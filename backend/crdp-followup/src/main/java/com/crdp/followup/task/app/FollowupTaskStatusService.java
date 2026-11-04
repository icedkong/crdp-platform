package com.crdp.followup.task.app;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.domain.TaskStatusFlow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务状态流转写入口（E15-T01 / A-FU-003「任务状态流转」、A-FU-010「逾期与逾期完成」；tasks.csv 验收「状态按规格流转， 逾期完成单独标记」）。
 *
 * <p>流转规则见 {@link TaskStatusFlow}（纯函数，合法方向与完成方式判定）。HTTP 触发与 E14-T06 / E14-T07 同口径：
 * 本任务落服务层领域入口，接线随后续任务——完成与进行中联动随随访记录提交流程（E15-T02，fu_record 落地后 RecordDraftService / submit
 * 调用，OQ-77②）、第一次联系随沟通记录（E15-T04）、补做 / 关闭的操作面随任务列表（E15-T02 / T03）；调用方先做任务对应患者的 数据范围校验（40400，不暴露存在）。
 *
 * <ul>
 *   <li>{@link #refreshOverdue}：每日 00:10 调度内的逾期刷新（tech-plan §8 步骤 W「刷新状态：逾期」； 由 {@code
 *       FollowupTaskCreationService} 在创建循环后调用）——窗口结束日次日（asOf 晚于 window_end）仍未完成（待随访 / 进行中）且非补录
 *       （BACKFILL 豁免，E14-T05 口径）的任务置 OVERDUE，同事务写 TASK_OVERDUE 事件（通知规则引擎 E09-T04 消费：
 *       站内信即时、短信并入每日汇总）与一条汇总审计；补跑 / 重放幂等（状态守卫，已 OVERDUE 落空不再发事件）；
 *   <li>{@link #markInProgress}：第一次联系或暂存草稿 → 进行中（只从待随访进，逾期不退回，OQ-82①）；
 *   <li>{@link #complete}：随访记录提交 → 已完成，按完成日与窗口的相对关系落完成方式（窗口前 / 窗口内 / 逾期完成，V53）；
 *       逾期后的「直接录入期」到下一节点任务开启日为止（A-FU-010 / Q09，A 两者同日），之后只能历史补录（E15-T07）；
 *   <li>{@link #redoSkipped} / {@link #closeSkipped}：「停用 / 暂停期间未执行」（PLT-TEN-017、A 的
 *       Q32）由人工选择补做或关闭—— 补做按窗口是否已结束重回 PENDING / OVERDUE（OQ-82②），关闭置 TERMINATED 并落终止原因（须填）。
 * </ul>
 *
 * <p>用户触发的流转逐条写审计（状态变更审计，AGENTS §5.4）；逾期刷新是批量作业，按 E14-T04 的 TASK_CREATE 口径写一条汇总审计。 TASK_OVERDUE
 * 事件负载契约 {@code NotifyEventPayload}：患者姓名密文经 {@link CryptoService} 解密后只进站内信密文列， 标题 / 摘要 /
 * 台账不落患者明文（A-NTF-003）。
 */
@Service
public class FollowupTaskStatusService {

    /** 已逾期 outbox 事件类型（= NotifyEventType.TASK_OVERDUE.name()，通知规则引擎按它订阅；窗口结束次日）。 */
    static final String EVENT_TASK_OVERDUE = "TASK_OVERDUE";

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskStatusService.class);

    private final FollowupTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final CryptoService crypto;

    public FollowupTaskStatusService(
            FollowupTaskMapper taskMapper,
            PlanMapper planMapper,
            OutboxPublisher outbox,
            AuditLogger audit,
            CryptoService crypto) {
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.outbox = outbox;
        this.audit = audit;
        this.crypto = crypto;
    }

    /** 完成结果（complete_kind 为完成方式：窗口前 / 窗口内 / 逾期完成，followup V53）。 */
    public record CompletionView(String taskId, String status, String completeKind) {}

    /** 补做 / 关闭结果（targetStatus = 补做后的新状态或 TERMINATED）。 */
    public record SkippedActionView(String taskId, String status, String reason) {}

    // ---------------------------------------------------------------- 逾期刷新（每日 00:10 调度内）

    /**
     * 逾期刷新（A-FU-010「窗口结束仍未完成即逾期」，进入时间 = 窗口结束日次日）：在租户上下文内由每日任务作业调用， asOf =
     * 本次运行目标逻辑日（补跑时逐日推进后按最终日一次刷新，晚标记同样只发一次事件）。 同一事务：逐条置 OVERDUE（状态守卫）+ 写 TASK_OVERDUE 事件 +
     * 一条汇总审计；无候选时幂等返回 0（不写审计）。
     */
    @Transactional
    public int refreshOverdue(LocalDate asOf) {
        List<FollowupTaskMapper.OverdueCandidateRow> candidates =
                taskMapper.findOverdueCandidates(asOf);
        int marked = 0;
        for (FollowupTaskMapper.OverdueCandidateRow candidate : candidates) {
            if (taskMapper.markOverdue(candidate.id()) == 0) {
                continue; // 并发完成 / 终止 / 已逾期（重放）：不覆盖、不重发事件
            }
            marked++;
            publishTaskOverdue(candidate);
        }
        if (marked == 0) {
            return 0;
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_OVERDUE_MARK")
                        .objectType("TASK")
                        .description(
                                "每日逾期刷新：窗口已结束仍未完成的任务置逾期 "
                                        + marked
                                        + " 条（asOf "
                                        + asOf
                                        + "，补录任务豁免，A-FU-010）")
                        .afterJson("{\"marked\":" + marked + ",\"asOf\":\"" + asOf + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("逾期刷新完成 asOf={} marked={}", asOf, marked);
        return marked;
    }

    /** TASK_OVERDUE 事件（负载契约 NotifyEventPayload；患者姓名解密后只进站内信密文列）。 */
    private void publishTaskOverdue(FollowupTaskMapper.OverdueCandidateRow candidate) {
        String patientName =
                candidate.patientNameCipher() == null
                        ? null
                        : crypto.decrypt(candidate.patientNameCipher());
        outbox.publish(
                EVENT_TASK_OVERDUE,
                "{\"taskId\":"
                        + candidate.id()
                        + ",\"ownerId\":"
                        + candidate.assigneeId()
                        + ",\"wardId\":"
                        + candidate.wardId()
                        + ",\"patientId\":"
                        + candidate.patientId()
                        + ",\"patientName\":"
                        + jsonText(patientName)
                        + ",\"nodeName\":"
                        + jsonText(candidate.nodeName())
                        + ",\"windowStartDate\":\""
                        + candidate.windowStart()
                        + "\",\"windowEndDate\":\""
                        + candidate.windowEnd()
                        + "\"}");
    }

    // ---------------------------------------------------------------- 进行中（第一次联系 / 暂存草稿）

    /**
     * 待随访 → 进行中（A 规格任务状态表「记录第一次联系或暂存」）：只从 PENDING 进——逾期不退回进行中（窗口事实，OQ-82①）、 终态不动。返回是否发生流转（false =
     * 本就非待随访，幂等不写审计）；{@code trigger} 只进审计描述（CONTACT 联系 / DRAFT 暂存草稿）。
     */
    @Transactional
    public boolean markInProgress(long taskId, Long operatorId, String trigger) {
        FollowupTaskMapper.TaskRow task = requireTask(taskId);
        if (taskMapper.startProgress(taskId) == 0) {
            log.debug("任务进入进行中被跳过（非待随访） taskId={} status={}", taskId, task.status());
            return false;
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_PROGRESS")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description(
                                "任务进入进行中（" + (trigger == null ? "联系或暂存" : trigger) + "，A-FU-003）")
                        .beforeJson("{\"status\":\"" + task.status() + "\"}")
                        .afterJson("{\"status\":\"" + TaskStatusFlow.IN_PROGRESS + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("任务进入进行中 taskId={} trigger={} operator={}", taskId, trigger, operatorId);
        return true;
    }

    // ---------------------------------------------------------------- 完成（随访记录提交联动）

    /**
     * → 已完成（A 规格任务状态表「随访记录已提交」；调用方 = 提交流程，E15-T02 接入）：
     *
     * <ul>
     *   <li>完成方式按业务日与窗口的相对关系落 complete_kind（窗口前 / 窗口内 / 逾期完成，A-FU-010 验收「逾期完成单独标记」）；
     *   <li>直接录入期（A-FU-010 / Q09）：窗口结束后到下一节点任务开启日（本节点第 N+1 次的开启日，A 医院 = 下一次窗口开始日） 之前可完成；到点后 40901
     *       只能历史补录（E15-T07）。无下一次（FIXED 排完 / YEARS 周期到界）不设界—— 最后一期补做只能走本入口， 历史补录仍可（OQ-82③）；
     *   <li>并发冲突（守卫落空）40900；已完成 / 失访 / 已终止 / 期间未执行 40901（期间未执行先补做）。
     * </ul>
     */
    @Transactional
    public CompletionView complete(long taskId, Long operatorId) {
        return complete(taskId, operatorId, PlanDateCalculator.businessToday(Instant.now()));
    }

    /** 完成入口（completedOn = 完成业务日，公开入口按北京时间今天；测试与提交流程可显式传日）。 完成方式与直接录入期判定都用它，与窗口列同口径 LocalDate。 */
    CompletionView complete(long taskId, Long operatorId, LocalDate completedOn) {
        FollowupTaskMapper.TaskRow task = requireTask(taskId);
        requireCompletable(task);
        if (completedOn.isAfter(task.windowEnd()) && !withinDirectEntryPeriod(task, completedOn)) {
            throw new ApiException(409, 40901, "已过直接录入期（下一节点任务已开启），只能历史补录（A-FU-010）");
        }
        String completeKind =
                TaskStatusFlow.completeKindOf(task.windowStart(), task.windowEnd(), completedOn);
        LocalDateTime completedAt = LocalDateTime.now();
        if (taskMapper.completeTask(taskId, completeKind, completedAt, operatorId) == 0) {
            throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_COMPLETE")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description("任务完成（完成方式 " + completeKindLabel(completeKind) + "）")
                        .beforeJson(
                                "{\"status\":\""
                                        + task.status()
                                        + "\",\"windowStart\":\""
                                        + task.windowStart()
                                        + "\",\"windowEnd\":\""
                                        + task.windowEnd()
                                        + "\"}")
                        .afterJson(
                                "{\"status\":\""
                                        + TaskStatusFlow.COMPLETED
                                        + "\",\"completeKind\":\""
                                        + completeKind
                                        + "\",\"completedOn\":\""
                                        + completedOn
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "任务完成 taskId={} kind={} operator={} window=[{},{}]",
                taskId,
                completeKind,
                operatorId,
                task.windowStart(),
                task.windowEnd());
        return new CompletionView(String.valueOf(taskId), TaskStatusFlow.COMPLETED, completeKind);
    }

    /** 完成前置校验：终态与「期间未执行」给出口径明确的 40901（不悄悄改写历史事实）。 */
    private static void requireCompletable(FollowupTaskMapper.TaskRow task) {
        switch (task.status()) {
            case TaskStatusFlow.COMPLETED -> throw new ApiException(409, 40901, "任务已完成");
            case TaskStatusFlow.LOST -> throw new ApiException(409, 40901, "任务已失访，不能完成");
            case TaskStatusFlow.TERMINATED -> throw new ApiException(409, 40901, "任务已终止，不能完成");
            case TaskStatusFlow.SKIPPED_SUSPENDED ->
                    throw new ApiException(
                            409, 40901, "「停用或暂停期间未执行」的任务须先补做（redo）才能完成（Q32 / PLT-TEN-017）");
            default -> {
                // PENDING / IN_PROGRESS / OVERDUE：可完成
            }
        }
    }

    /**
     * 直接录入期判定（A-FU-010「逾期后到下一节点任务开启前仍可直接录入」）：completedOn 早于本节点第 N+1 次的开启日即在期内。 第 N+1
     * 次按入组起算快照与计划版本实时计算（未来次数未预生成，A-FU-001）；无下一次或计划配置缺失不设界（防御放行，OQ-82③）。
     */
    private boolean withinDirectEntryPeriod(
            FollowupTaskMapper.TaskRow task, LocalDate completedOn) {
        LocalDate startDate = taskMapper.findEnrollmentStartDate(task.enrollmentId());
        PlanMapper.PlanRow plan = planMapper.findById(task.planId());
        List<PlanMapper.NodeRow> rows =
                plan == null ? List.of() : planMapper.findNodesByPlanId(task.planId());
        if (startDate == null || rows.isEmpty()) {
            return true; // 配置防御：查不到排期依据时不拦完成（历史补录仍可兜底）
        }
        PlanDateCalculator.NodeSchedule next =
                TaskCreationPlanner.occurrenceScheduleOf(
                        FollowupTaskCreationService.nodeDefs(rows),
                        task.nodeId(),
                        task.occurrence() + 1,
                        startDate);
        if (next == null) {
            return true; // FIXED 排完 / 滚动基准只排第 1 次：无下一节点可开启，直接录入期不设界
        }
        LocalDate planEnd =
                "YEARS".equals(plan.cycleType()) && plan.cycleYears() != null
                        ? startDate.plusYears(plan.cycleYears())
                        : null;
        if (planEnd != null && next.planDate().isAfter(planEnd)) {
            return true; // 管理周期边界外不会再开任务（同 E14 调度口径）
        }
        return completedOn.isBefore(next.openDate());
    }

    // ---------------------------------------------------------------- 期间未执行：补做 /
    // 关闭（Q32、PLT-TEN-017）

    /**
     * 补做（PLT-TEN-017「恢复后由人工选择补做或关闭」）：SKIPPED_SUSPENDED → 窗口未结束 PENDING / 已结束 OVERDUE （补做后照常走联系 →
     * 提交，完成时窗口已过自然落「逾期完成」，OQ-82②）；非「期间未执行」40901。
     */
    @Transactional
    public SkippedActionView redoSkipped(long taskId, Long operatorId) {
        return redoSkipped(taskId, operatorId, PlanDateCalculator.businessToday(Instant.now()));
    }

    /** 补做入口（today = 补做业务日，公开入口按北京时间今天）。 */
    SkippedActionView redoSkipped(long taskId, Long operatorId, LocalDate today) {
        FollowupTaskMapper.TaskRow task = requireTask(taskId);
        requireSkipped(task, "补做");
        String target =
                today.isAfter(task.windowEnd()) ? TaskStatusFlow.OVERDUE : TaskStatusFlow.PENDING;
        if (taskMapper.redoSkippedTask(taskId, target) == 0) {
            throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_REDO")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description(
                                "「停用或暂停期间未执行」任务补做：重回 "
                                        + (TaskStatusFlow.OVERDUE.equals(target) ? "逾期" : "待随访")
                                        + "（Q32 / PLT-TEN-017）")
                        .beforeJson("{\"status\":\"" + task.status() + "\"}")
                        .afterJson("{\"status\":\"" + target + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("期间未执行任务补做 taskId={} -> {} operator={}", taskId, target, operatorId);
        return new SkippedActionView(String.valueOf(taskId), target, null);
    }

    /**
     * 关闭（PLT-TEN-017 / Q32「由医生选择补做或关闭」）：SKIPPED_SUSPENDED → TERMINATED，原因必填（≤255，同移出口径）；
     * 非「期间未执行」40901。关闭即人工停止该次随访，复用 E14-T06 终止三列留痕。
     */
    @Transactional
    public SkippedActionView closeSkipped(long taskId, Long operatorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "关闭「期间未执行」任务须填写原因");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, "原因不能超过 255 字");
        }
        FollowupTaskMapper.TaskRow task = requireTask(taskId);
        requireSkipped(task, "关闭");
        if (taskMapper.closeSkippedTask(taskId, trimmed, LocalDateTime.now(), operatorId) == 0) {
            throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_CLOSE")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description("「停用或暂停期间未执行」任务关闭：" + trimmed)
                        .beforeJson("{\"status\":\"" + task.status() + "\"}")
                        .afterJson(
                                "{\"status\":\""
                                        + TaskStatusFlow.TERMINATED
                                        + "\",\"reason\":"
                                        + jsonText(trimmed)
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("期间未执行任务关闭 taskId={} operator={}", taskId, operatorId);
        return new SkippedActionView(String.valueOf(taskId), TaskStatusFlow.TERMINATED, trimmed);
    }

    // ---------------------------------------------------------------- 内部

    /** 任务存在性（40400 口径：越范围与不存在同码，调用方完成数据范围校验后本层兜底存在性）。 */
    private FollowupTaskMapper.TaskRow requireTask(long taskId) {
        FollowupTaskMapper.TaskRow task = taskMapper.findTaskById(taskId);
        if (task == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        return task;
    }

    private static void requireSkipped(FollowupTaskMapper.TaskRow task, String action) {
        if (!TaskStatusFlow.SKIPPED_SUSPENDED.equals(task.status())) {
            throw new ApiException(
                    409, 40901, "只有「停用或暂停期间未执行」的任务能" + action + "（Q32 / PLT-TEN-017）");
        }
    }

    /** 完成方式中文名（审计描述用）。 */
    private static String completeKindLabel(String kind) {
        return switch (kind) {
            case TaskStatusFlow.KIND_BEFORE_WINDOW -> "窗口前完成";
            case TaskStatusFlow.KIND_OVERDUE -> "逾期完成";
            default -> "窗口内完成";
        };
    }

    /** JSON 字符串值（null 输出 null 字面量，不落明文之外的转义歧义）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
