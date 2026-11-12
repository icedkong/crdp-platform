package com.crdp.followup.task.app;

import com.crdp.followup.contact.domain.ContactResult;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *       跟进任务（FOLLOWUP，A-FU-024）完成时 同窗口主任务一并完成、其余跟进终止（窗口口径的完成传播，OQ-86⑤）；
 *   <li>{@link #refreshLostCandidates}（E15-T05 / A-FU-012）：每日 00:10 调度在逾期刷新后判定—— 窗口内不同日期连续 N 次「未联系到
 *       / 号码错误」（N = 平台参数 + 租户覆盖，默认 3；「拒访」等其它结果断开连续段，不计入） 且窗口已结束 → 失访候选 + LOST_CANDIDATE 事件；
 *   <li>{@link #confirmLost}（E15-T05 / A-FU-012）：医生确认失访候选 → 失访，同窗口未完成跟进任务终止； 连续 2 个窗口（A
 *       语境「年度」）失访时返回提示位，由前端提示把患者状态改为失访；
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

    /** 失访候选 outbox 事件类型（= NotifyEventType.LOST_CANDIDATE.name()；窗口结束次日随每日刷新）。 */
    static final String EVENT_LOST_CANDIDATE = "LOST_CANDIDATE";

    /** 确认失访后同窗口未完成跟进任务的终止原因（窗口工作已结束，A-FU-024）。 */
    static final String FOLLOWUP_CLOSE_ON_LOST = "窗口任务已确认失访，跟进任务关闭";

    /** 主任务完成后同窗口未完成跟进任务的终止原因（A-FU-024：跟进是同一窗口的继续）。 */
    static final String FOLLOWUP_CLOSE_ON_COMPLETE = "窗口任务已完成，跟进任务关闭";

    /** 「连续 2 个年度失访」提示口径（A-FU-012 / tech-plan §9）：同入组主任务窗口序连续 LOST ≥ 2。 */
    static final int CONSECUTIVE_LOST_YEARS_PROMPT = 2;

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskStatusService.class);

    private final FollowupTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final CryptoService crypto;
    private final FollowupPolicyService policy;
    private final com.crdp.followup.patient.app.PatientContactGateway patientScope;

    public FollowupTaskStatusService(
            FollowupTaskMapper taskMapper,
            PlanMapper planMapper,
            OutboxPublisher outbox,
            AuditLogger audit,
            CryptoService crypto,
            FollowupPolicyService policy,
            com.crdp.followup.patient.app.PatientContactGateway patientScope) {
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.outbox = outbox;
        this.audit = audit;
        this.crypto = crypto;
        this.policy = policy;
        this.patientScope = patientScope;
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

    // ---------------------------------------------------------------- 失访候选刷新（每日 00:10 调度内；
    // A-FU-012，E15-T05）

    /**
     * 失访候选刷新（A-FU-012「窗口内不同日期连续 N 次『未联系到 / 号码错误』且窗口已结束」；tech-plan §9「每日状态刷新时判定」）：
     * 在租户上下文内由每日任务作业于逾期刷新后调用，asOf = 本次运行目标逻辑日。同一事务：逐条置 LOST_CANDIDATE（状态守卫）+ 写 LOST_CANDIDATE 事件 +
     * 一条汇总审计；无命中时幂等返回 0（不写审计）。候选集合由 {@code findLostCandidates} 先行筛出
     * （窗口已结束、未完成、非补录、主任务、窗口内有未接通联系），这里再按连续段计数复核。
     */
    @Transactional
    public int refreshLostCandidates(LocalDate asOf) {
        int threshold = policy.policy().lostCandidateAttempts();
        List<FollowupTaskMapper.OverdueCandidateRow> candidates =
                taskMapper.findLostCandidates(asOf);
        int marked = 0;
        for (FollowupTaskMapper.OverdueCandidateRow candidate : candidates) {
            if (!unreachedStreakReached(candidate, threshold)) {
                continue; // 连续段不足（含「拒访」等结果断开、同日多次只计一日）：不标不发
            }
            if (taskMapper.markLostCandidate(candidate.id()) == 0) {
                continue; // 并发完成 / 终止 / 确认失访 / 重放：不覆盖、不重发事件
            }
            marked++;
            publishLostCandidate(candidate);
        }
        if (marked == 0) {
            return 0;
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_LOST_CANDIDATE_MARK")
                        .objectType("TASK")
                        .description(
                                "每日失访候选刷新：窗口已结束且连续未联系到的任务置失访候选 "
                                        + marked
                                        + " 条（asOf "
                                        + asOf
                                        + "，连续 "
                                        + threshold
                                        + " 次「未联系到 / 号码错误」，拒访不计，A-FU-012）")
                        .afterJson(
                                "{\"marked\":"
                                        + marked
                                        + ",\"asOf\":\""
                                        + asOf
                                        + "\",\"attempts\":"
                                        + threshold
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("失访候选刷新完成 asOf={} attempts={} marked={}", asOf, threshold, marked);
        return marked;
    }

    /**
     * 窗口内「未联系到 / 号码错误」连续段是否达到阈值：从最新一条往前走，遇其他结果（已联系 / 家属代答 / 拒访〔不计入失访〕/ 告知已故）
     * 即断；按<b>不同日期</b>计数（同日多次尝试只计一日，Q02「不同日期连续 3 次」）。
     */
    private boolean unreachedStreakReached(
            FollowupTaskMapper.OverdueCandidateRow candidate, int threshold) {
        List<FollowupTaskMapper.ContactAttemptRow> attempts =
                taskMapper.contactsOfPatientInWindow(
                        candidate.patientId(), candidate.windowStart(), candidate.windowEnd());
        Set<LocalDate> distinctDates = new HashSet<>();
        for (FollowupTaskMapper.ContactAttemptRow attempt : attempts) {
            boolean unreached =
                    ContactResult.of(attempt.result())
                            .map(ContactResult::countsTowardLost)
                            .orElse(false);
            if (!unreached) {
                break;
            }
            distinctDates.add(attempt.contactTime().toLocalDate());
            if (distinctDates.size() >= threshold) {
                return true;
            }
        }
        return false;
    }

    /** LOST_CANDIDATE 事件（负载契约 NotifyEventPayload；患者姓名解密后只进站内信密文列，同 TASK_OVERDUE 口径）。 */
    private void publishLostCandidate(FollowupTaskMapper.OverdueCandidateRow candidate) {
        String patientName =
                candidate.patientNameCipher() == null
                        ? null
                        : crypto.decrypt(candidate.patientNameCipher());
        outbox.publish(
                EVENT_LOST_CANDIDATE,
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

    // ---------------------------------------------------------------- 确认失访（医生确认，A-FU-012；E15-T05）

    /** 确认失访结果（terminatedFollowups = 同窗口关闭的未完成跟进任务数；patientLostPrompt = 连续 2 个窗口失访，提示把患者状态改为失访）。 */
    public record LostConfirmView(
            String taskId, String status, int terminatedFollowups, boolean patientLostPrompt) {}

    /**
     * 失访候选 → 失访（A-FU-012「医生确认后标为失访」；POST /tasks/{id}/confirm-lost，E15-T05）：
     *
     * <ul>
     *   <li>只接受失访候选（其它状态 40901，含已失访重复确认）；并发已被处理 40900；
     *   <li>同窗口未完成跟进任务（A-FU-024）终止留痕（窗口工作已结束）；
     *   <li>连续 2 个窗口（A 语境「年度」，同入组主任务窗口序连续 LOST）失访 → patientLostPrompt = true， 由前端提示医生把患者状态改为失访（A 规格
     *       §390 行「系统提示后由医生改为失访」，不自动改）。
     * </ul>
     */
    @Transactional
    public LostConfirmView confirmLost(long taskId, Long operatorId) {
        FollowupTaskMapper.TaskRow task = requireTask(taskId);
        if (!TaskStatusFlow.LOST_CANDIDATE.equals(task.status())) {
            throw new ApiException(409, 40901, "只有「失访候选」的任务能确认失访（A-FU-012，窗口结束且连续未联系到后自动标记）");
        }
        LocalDateTime lostAt = LocalDateTime.now();
        if (taskMapper.confirmLostTask(taskId) == 0) {
            throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
        }
        int terminatedFollowups =
                taskMapper.terminateWindowFollowups(
                        task.enrollmentId(),
                        task.nodeId(),
                        task.occurrence(),
                        taskId,
                        FOLLOWUP_CLOSE_ON_LOST,
                        lostAt,
                        operatorId);
        boolean patientLostPrompt =
                consecutiveLostWindows(task.enrollmentId()) >= CONSECUTIVE_LOST_YEARS_PROMPT;
        audit.append(
                AuditEntry.operation("followup", "TASK_LOST_CONFIRM")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description(
                                "确认失访（失访候选 → 失访"
                                        + (terminatedFollowups > 0
                                                ? "；同窗口跟进任务关闭 " + terminatedFollowups + " 条"
                                                : "")
                                        + (patientLostPrompt ? "；连续 " : "")
                                        + (patientLostPrompt
                                                ? CONSECUTIVE_LOST_YEARS_PROMPT + " 个窗口失访，提示修改患者状态"
                                                : "")
                                        + "，A-FU-012）")
                        .beforeJson(
                                "{\"status\":\""
                                        + TaskStatusFlow.LOST_CANDIDATE
                                        + "\",\"windowStart\":\""
                                        + task.windowStart()
                                        + "\",\"windowEnd\":\""
                                        + task.windowEnd()
                                        + "\"}")
                        .afterJson(
                                "{\"status\":\""
                                        + TaskStatusFlow.LOST
                                        + "\",\"terminatedFollowups\":"
                                        + terminatedFollowups
                                        + ",\"patientLostPrompt\":"
                                        + patientLostPrompt
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "确认失访 taskId={} operator={} closedFollowups={} patientLostPrompt={}",
                taskId,
                operatorId,
                terminatedFollowups,
                patientLostPrompt);
        return new LostConfirmView(
                String.valueOf(taskId),
                TaskStatusFlow.LOST,
                terminatedFollowups,
                patientLostPrompt);
    }

    /** 同入组主任务窗口序（新→旧）从最新一条起的连续失访窗口数（刚确认的任务已是 LOST，含在计数内）； A 语境「连续 2 个年度失访」按窗口序推广，不按日历年。 */
    private int consecutiveLostWindows(long enrollmentId) {
        int consecutive = 0;
        for (FollowupTaskMapper.WindowStatusRow row : taskMapper.findWindowStatuses(enrollmentId)) {
            if (!TaskStatusFlow.LOST.equals(row.status())) {
                break;
            }
            consecutive++;
        }
        return consecutive;
    }

    // ---------------------------------------------------------------- 完成提醒（REMINDER 无需填表，
    // A-FU-026 / E15-T06）

    /** 完成提醒结果（与 {@link CompletionView} 同形：状态 COMPLETED + 完成方式按窗口派生）。 */
    public record AcknowledgeView(String taskId, String status, String completeKind) {}

    /**
     * 站内提醒类任务完成（A-FU-026「到点只提醒责任人、无需填表」；POST /tasks/{id}/acknowledge，E15-T06； tasks.csv E15-T06
     * 验收「站内提醒类任务无需填表」）：责任人确认「已处理」即完成——不创建记录、不走提交。
     *
     * <ul>
     *   <li>只接受 REMINDER 任务：FORM 任务须提交随访记录完成（40901，验收「无需填表」只对提醒类）；
     *   <li>数据范围前置校验（越范围患者 40400，不暴露任务存在，与开始录入 / 认领同口径）；
     *   <li>完成方式仍按业务日与窗口的相对关系落（窗口前 / 窗口内 / 逾期完成）；不受直接录入期约束（无「下一次」可拦， 窗口结束后补点确认同样允许——提醒语义是「知道了」）；
     *   <li>终态（已完成 / 失访 / 已终止 / 期间未执行）40901、并发已被处理 40900；写 TASK_ACKNOWLEDGE 审计。
     * </ul>
     */
    @Transactional
    public AcknowledgeView acknowledgeReminder(long taskId, Long operatorId) {
        FollowupTaskMapper.StartTaskRow task = taskMapper.findStartTaskById(taskId);
        if (task == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        // 数据范围前置校验（与 E15-T02 / E15-T03 写入口同口径）：越范围患者不暴露任务存在
        if (patientScope.patientIdInScope(task.patientId()) == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        if (!"REMINDER".equals(task.taskType())) {
            throw new ApiException(409, 40901, "填表任务须提交随访记录完成（站内提醒类任务才可「完成提醒」，A-FU-026）");
        }
        switch (task.status()) {
            case TaskStatusFlow.COMPLETED -> throw new ApiException(409, 40901, "任务已完成");
            case TaskStatusFlow.LOST, TaskStatusFlow.LOST_CANDIDATE ->
                    throw new ApiException(409, 40901, "任务已失访，不能完成");
            case TaskStatusFlow.TERMINATED -> throw new ApiException(409, 40901, "任务已终止，不能完成");
            case TaskStatusFlow.SKIPPED_SUSPENDED ->
                    throw new ApiException(
                            409, 40901, "「停用或暂停期间未执行」的任务须先补做（redo）才能完成（Q32 / PLT-TEN-017）");
            default -> {
                // PENDING / IN_PROGRESS / OVERDUE：可确认完成
            }
        }
        // 完成方式判定用的窗口列（此时任务必然开放态，行必在）
        FollowupTaskMapper.TaskRow window = requireTask(taskId);
        LocalDate completedOn = PlanDateCalculator.businessToday(Instant.now());
        String completeKind =
                TaskStatusFlow.completeKindOf(
                        window.windowStart(), window.windowEnd(), completedOn);
        java.time.LocalDateTime completedAt = java.time.LocalDateTime.now();
        if (taskMapper.completeTask(taskId, completeKind, completedAt, operatorId) == 0) {
            throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_ACKNOWLEDGE")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description(
                                "完成提醒（站内提醒类任务无需填表，A-FU-026）：完成方式 "
                                        + completeKindLabel(completeKind))
                        .beforeJson("{\"status\":\"" + task.status() + "\"}")
                        .afterJson(
                                "{\"status\":\""
                                        + TaskStatusFlow.COMPLETED
                                        + "\",\"completeKind\":\""
                                        + completeKind
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("完成提醒 taskId={} kind={} operator={}", taskId, completeKind, operatorId);
        return new AcknowledgeView(String.valueOf(taskId), TaskStatusFlow.COMPLETED, completeKind);
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
        // 临时任务（E15-T06 / A-FU-013）豁免直接录入期：occurrence 为负数计数器，无「本节点第 N+1 次」可排，
        // 医生手工加的加访到窗口结束前始终可提交（更晚仍可走历史补录，A-FU-014）
        if (completedOn.isAfter(task.windowEnd())
                && !TaskCreationPlanner.CREATE_TEMP.equals(task.createType())
                && !withinDirectEntryPeriod(task, completedOn)) {
            throw new ApiException(409, 40901, "已过直接录入期（下一节点任务已开启），只能历史补录（A-FU-010）");
        }
        String completeKind =
                TaskStatusFlow.completeKindOf(task.windowStart(), task.windowEnd(), completedOn);
        LocalDateTime completedAt = LocalDateTime.now();
        if (taskMapper.completeTask(taskId, completeKind, completedAt, operatorId) == 0) {
            throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
        }
        // 窗口口径的完成传播（A-FU-024 / OQ-86⑤）：跟进任务完成 ⇒ 主任务一并完成（同完成方式）；
        // 主任务或跟进任务完成 ⇒ 同窗口其余未完成跟进任务终止——窗口工作已结束，不留悬挂待办。
        // 常规窗口无兄弟任务（主任务恒一条、无跟进），两条 UPDATE 天然 0 行。
        taskMapper.completeWindowMainTask(
                task.enrollmentId(),
                task.nodeId(),
                task.occurrence(),
                taskId,
                completeKind,
                completedAt,
                operatorId);
        int closedFollowups =
                taskMapper.terminateWindowFollowups(
                        task.enrollmentId(),
                        task.nodeId(),
                        task.occurrence(),
                        taskId,
                        FOLLOWUP_CLOSE_ON_COMPLETE,
                        completedAt,
                        operatorId);
        audit.append(
                AuditEntry.operation("followup", "TASK_COMPLETE")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description(
                                "任务完成（完成方式 "
                                        + completeKindLabel(completeKind)
                                        + (closedFollowups > 0
                                                ? "；同窗口跟进任务关闭 " + closedFollowups + " 条（A-FU-024）"
                                                : "")
                                        + "）")
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
                                        + "\",\"closedFollowups\":"
                                        + closedFollowups
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "任务完成 taskId={} kind={} operator={} window=[{},{}] closedFollowups={}",
                taskId,
                completeKind,
                operatorId,
                task.windowStart(),
                task.windowEnd(),
                closedFollowups);
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
                // PENDING / IN_PROGRESS / OVERDUE / LOST_CANDIDATE：可完成（失访候选在直接录入期内
                // 联系上患者照常完成，A-FU-010 / A-FU-012，OQ-86①）
            }
        }
    }

    /**
     * 直接录入期判定（A-FU-010「逾期后到下一节点任务开启前仍可直接录入」）：completedOn 早于本节点第 N+1 次的开启日即在期内。 第 N+1
     * 次按入组起算快照与计划版本实时计算（未来次数未预生成，A-FU-001）；滚动循环节点（E21-T07）的下一次由 随访滚动派生（max（上次随访快照，已排最新一次计划日期）+ 偏移）；
     * 无下一次或计划配置缺失不设界（防御放行，OQ-82③）。
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
        List<TaskCreationPlanner.NodeDef> defs = FollowupTaskCreationService.nodeDefs(rows);
        if (defs.stream()
                .anyMatch(
                        node ->
                                node.id() == task.nodeId()
                                        && "LAST_VISIT".equalsIgnoreCase(node.basis()))) {
            return completedOn.isBefore(rollingNextOpenDate(task));
        }
        PlanDateCalculator.NodeSchedule next =
                TaskCreationPlanner.occurrenceScheduleOf(
                        defs, task.nodeId(), task.occurrence() + 1, startDate);
        if (next == null) {
            return true; // FIXED 排完：无下一节点可开启，直接录入期不设界
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

    /**
     * 滚动循环节点的下一次开启日（E21-T07）：max（上次实际随访日期快照，该节点已排最新一次计划日期）+ 节点偏移 后按节点提醒 / 窗口前取较早一天；
     * 无快照也无已排次数（防御）返回极大值——不设界放行（与配置缺失同口径，OQ-82③）。
     */
    private LocalDate rollingNextOpenDate(FollowupTaskMapper.TaskRow task) {
        LocalDate lastVisit = taskMapper.findLastVisitDate(task.enrollmentId());
        LocalDate latestPlanDate = null;
        int latestOccurrence = 0;
        for (FollowupTaskMapper.TaskOccurrenceRow row :
                taskMapper.findTaskOccurrences(task.enrollmentId())) {
            if (row.nodeId() == task.nodeId() && row.occurrence() > latestOccurrence) {
                latestOccurrence = row.occurrence();
                latestPlanDate = row.planDate();
            }
        }
        List<PlanMapper.NodeRow> rows = planMapper.findNodesByPlanId(task.planId());
        PlanDateCalculator.NodeSchedule next =
                TaskCreationPlanner.rollingNextSchedule(
                        FollowupTaskCreationService.nodeDefs(rows),
                        task.nodeId(),
                        lastVisit,
                        latestPlanDate);
        return next != null ? next.openDate() : LocalDate.MAX;
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
