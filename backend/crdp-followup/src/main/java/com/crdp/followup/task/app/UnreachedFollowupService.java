package com.crdp.followup.task.app;

import com.crdp.followup.task.domain.TaskStatusFlow;
import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.outbox.OutboxPublisher;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未联系到自动跟进（E15-T05 / A-FU-024「沟通结果为『未联系到』时，次日自动生成一项跟进任务（间隔天数可配置）， 每个窗口最多 5 次，窗口结束后不再生成」；验收「每窗口最多 5
 * 次跟进，窗口结束不再生成」）。
 *
 * <p>tech-plan §9「写沟通记录时创建，计数存任务上」：跟进任务在 {@code ContactLogService.create} 的同一事务内同步创建（沟通域 →
 * 任务域的既有依赖方向），不开每日调度：
 *
 * <ul>
 *   <li>触发：挂任务的沟通且结果为「未接通」（未联系到 / 号码错误，与 E15-T04 前端提示 contactFollowupHint 同口径，OQ-86②）；
 *       不挂任务的联系（患者页签直记）没有窗口锚点，不生成（OQ-86⑧）；
 *   <li>开启日 = 沟通业务日（北京时间）+ 间隔天数（平台参数 + 租户覆盖，默认 1 = 次日，A-FU-024 / Q28）；
 *       开启日晚于窗口结束日（窗口已结束）不创建——「窗口结束后不再生成」；
 *   <li>窗口与负责人继承源任务（跟进是同一窗口随访的继续，窗口结束口径与逾期刷新一致，OQ-82④）； plan_date = remind_start = open_date =
 *       开启日（当天开启当天提醒）；
 *   <li>「每窗口最多 5 次」：窗口（入组 + 节点 + 第 N 次）已建跟进数（MAX(followup_seq)，followup V57）达到 {@link
 *       FollowupPolicyService#MAX_FOLLOWUPS_PER_WINDOW} 不再创建；uk 扩 followup_seq 兜底并发；
 *   <li>源任务须未完成（待随访 / 进行中 / 逾期）——完成 / 失访候选〔窗口已结束，前一条守卫必然拦下〕/ 终态不再跟进；
 *   <li>同事务写 UNREACHED_FOLLOWUP 事件（= NotifyEventType.UNREACHED_FOLLOWUP，负载契约 NotifyEventPayload；
 *       站内信即时，「次日 08:30」为目录默认时机元数据，OQ-86④）与 TASK_FOLLOWUP_CREATE 审计。
 * </ul>
 *
 * <p>创建失败（窗口结束 / 已满 5 次 / 源任务终态 / 并发撞 uk）一律返回 null 静默跳过——跟进是便利性自动化， 不是沟通记录写入口的业务前置（不抛错打断记录）。
 */
@Service
public class UnreachedFollowupService {

    /** 未联系到跟进 outbox 事件类型（= NotifyEventType.UNREACHED_FOLLOWUP.name()，通知规则引擎按它订阅）。 */
    static final String EVENT_UNREACHED_FOLLOWUP = "UNREACHED_FOLLOWUP";

    /** 创建方式：未联系到自动跟进（fu_task.create_type，V48 枚举预留、本任务落地）。 */
    static final String CREATE_FOLLOWUP = "FOLLOWUP";

    private static final Logger log = LoggerFactory.getLogger(UnreachedFollowupService.class);

    private final FollowupTaskMapper taskMapper;
    private final FollowupPolicyService policy;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final CryptoService crypto;

    public UnreachedFollowupService(
            FollowupTaskMapper taskMapper,
            FollowupPolicyService policy,
            OutboxPublisher outbox,
            AuditLogger audit,
            CryptoService crypto) {
        this.taskMapper = taskMapper;
        this.policy = policy;
        this.outbox = outbox;
        this.audit = audit;
        this.crypto = crypto;
    }

    /**
     * 为一条「未接通」沟通生成跟进任务（在沟通记录创建的同一事务内调用）。
     *
     * @param sourceTaskId 沟通挂的任务（主任务或上一条跟进任务）
     * @param contactTime 沟通时间（业务日按北京时间取 {@link LocalDateTime#toLocalDate()}）
     * @param operatorId 记录人（跟进任务 created_by）
     * @return 新建任务 id；不满足条件（窗口结束 / 已满 5 次 / 源任务终态 / 并发撞 uk）返回 null
     */
    @Transactional
    public Long createForUnreachedContact(
            long sourceTaskId, LocalDateTime contactTime, Long operatorId) {
        FollowupTaskMapper.TaskRow source = taskMapper.findTaskById(sourceTaskId);
        if (source == null || !sourceOpen(source.status())) {
            return null;
        }
        LocalDate contactDate = contactTime.toLocalDate();
        LocalDate openDate = contactDate.plusDays(policy.policy().unreachedFollowupIntervalDays());
        if (openDate.isAfter(source.windowEnd())) {
            log.debug(
                    "跟进任务不生成：窗口已结束（开启日 {} 晚于窗口结束日 {}，A-FU-024）task={}",
                    openDate,
                    source.windowEnd(),
                    sourceTaskId);
            return null;
        }
        int existing =
                taskMapper.findMaxFollowupSeq(
                        source.enrollmentId(), source.nodeId(), source.occurrence());
        if (existing >= FollowupPolicyService.MAX_FOLLOWUPS_PER_WINDOW) {
            log.debug(
                    "跟进任务不生成：每窗口最多 {} 次（A-FU-024）task={} seq={}",
                    FollowupPolicyService.MAX_FOLLOWUPS_PER_WINDOW,
                    sourceTaskId,
                    existing);
            return null;
        }
        int seq = existing + 1;
        // 任务形态与负责人继承源任务（跟进是同一窗口随访的继续；TaskRow 快照不含这几列，按 id 补取一次）
        FollowupTaskMapper.FollowupTemplateRow template =
                taskMapper.findFollowupTemplate(sourceTaskId);
        String taskType = template == null ? "FORM" : template.taskType();
        String formCode = template == null ? null : template.formCode();
        String ownerType = template == null ? "WARD_DEFAULT" : template.ownerType();
        Long assigneeId = template == null ? null : template.assigneeId();
        FollowupTaskInsertRow row =
                new FollowupTaskInsertRow(
                        source.enrollmentId(),
                        source.patientId(),
                        source.planId(),
                        source.nodeId(),
                        source.occurrence(),
                        taskType,
                        formCode,
                        ownerType,
                        assigneeId,
                        openDate,
                        openDate,
                        source.windowStart(),
                        source.windowEnd(),
                        openDate,
                        CREATE_FOLLOWUP,
                        TaskStatusFlow.PENDING,
                        seq,
                        contactDate);
        try {
            taskMapper.insertTask(row);
        } catch (DuplicateKeyException race) {
            // 并发未接通沟通同时算出同一序号：uk 兜底，按已存在处理（该窗口跟进数已 +1，语义不受影响）
            log.debug("跟进任务并发重复创建，按已存在处理 task={} seq={}", sourceTaskId, seq);
            return null;
        }
        publishFollowupOpened(row.getId());
        audit.append(
                AuditEntry.operation("followup", "TASK_FOLLOWUP_CREATE")
                        .objectType("TASK")
                        .objectId(String.valueOf(row.getId()))
                        .description(
                                "未联系到自动生成跟进任务（第 "
                                        + seq
                                        + "/"
                                        + FollowupPolicyService.MAX_FOLLOWUPS_PER_WINDOW
                                        + " 次，开启日 "
                                        + openDate
                                        + "，A-FU-024）")
                        .afterJson(
                                "{\"sourceTaskId\":"
                                        + sourceTaskId
                                        + ",\"followupSeq\":"
                                        + seq
                                        + ",\"openDate\":\""
                                        + openDate
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "未联系到跟进任务已创建 id={} source={} seq={} openDate={}",
                row.getId(),
                sourceTaskId,
                seq,
                openDate);
        return row.getId();
    }

    /** 源任务是否还开着（未完成且未到失访候选）。 */
    private static boolean sourceOpen(String status) {
        return TaskStatusFlow.PENDING.equals(status)
                || TaskStatusFlow.IN_PROGRESS.equals(status)
                || TaskStatusFlow.OVERDUE.equals(status);
    }

    /** UNREACHED_FOLLOWUP 事件（负载契约 NotifyEventPayload；患者姓名解密后只进站内信密文列）。 */
    private void publishFollowupOpened(long followupTaskId) {
        FollowupTaskMapper.OverdueCandidateRow notify =
                taskMapper.findTaskNotifyRow(followupTaskId);
        if (notify == null) {
            return; // 防御：行刚插入即被删（同事务不会发生）
        }
        String patientName =
                notify.patientNameCipher() == null
                        ? null
                        : crypto.decrypt(notify.patientNameCipher());
        outbox.publish(
                EVENT_UNREACHED_FOLLOWUP,
                "{\"taskId\":"
                        + notify.id()
                        + ",\"ownerId\":"
                        + notify.assigneeId()
                        + ",\"wardId\":"
                        + notify.wardId()
                        + ",\"patientId\":"
                        + notify.patientId()
                        + ",\"patientName\":"
                        + jsonText(patientName)
                        + ",\"nodeName\":"
                        + jsonText(notify.nodeName())
                        + ",\"windowStartDate\":\""
                        + notify.windowStart()
                        + "\",\"windowEndDate\":\""
                        + notify.windowEnd()
                        + "\"}");
    }

    /** JSON 字符串值（null 输出 null 字面量；与 FollowupTaskStatusService 同口径的小工具）。 */
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
                    if (ch < ' ') {
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
