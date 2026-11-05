package com.crdp.followup.task.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.task.infra.mapper.FollowupTaskAssignMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认领与改派（E15-T03 / A-FU-009「支持单个与批量改派、从公共池认领」；docs/api/followup.md §2 claim / batch-assign，页面
 * /followup/task，pages.md §7）。
 *
 * <p>验收口径：改派后新责任人收到通知（tasks.csv E15-T03）——每次实际改派（负责人发生变化）同一事务写一条 {@code TASK_REASSIGNED} outbox
 * 事件（事件类型 = {@code NotifyEventType.TASK_REASSIGNED}，负载契约 {@code NotifyEventPayload}：接收人 =
 * 新负责人、患者姓名解密后只进站内信密文列）， 由 E09-T04 通知规则引擎投站内信（A 规格
 * §7「任务改派：改派后即时，新负责人，站内信」）；负责人未变（TASK_UNCHANGED）与被跳过的行不发。
 *
 * <ul>
 *   <li>{@link #batchAssign}：单个与批量改派共用一个入口（单个 = 批量 1 条）。新负责人须启用，且负责患者当前病区（sys_user_ward）或持全院范围角色——与
 *       通知接收人病区复核（A-NTF-004「已不负责该病区的医生不再收到」）同口径，保证改派成功 ⇒ 通知可达；跨病区行按 DOCTOR_NOT_IN_WARD
 *       跳过（A-PAT-017「只能选该病区的医生」同码）。只有未完成任务（PENDING / IN_PROGRESS / OVERDUE /
 *       SKIPPED_SUSPENDED）可改派，终态行 TASK_NOT_OPEN；越范围 / 不存在 TASK_NOT_FOUND（不暴露存在）；守卫落空
 *       TASK_CONFLICT。写一条 TASK_ASSIGN 汇总审计（A-NFR-004「改派记入 审计」；逐条前 20 条 + 原因计数，与响应可对账）；
 *   <li>{@link #claim}：从病区公共池认领——只对「待认领（assignee NULL）且未完成」的任务开放，认领人 = 当前登录用户； 已有负责人
 *       40901（请走改派）、并发被抢 40900、越范围 40400（不暴露存在）；写 TASK_CLAIM 审计。 认领是本人自取，不发通知（A 规格 §7 事件目录只列「任务改派 →
 *       新负责人」）。
 * </ul>
 *
 * <p>owner_type 是创建时节点模板的快照（V48），人工改派不改写；改派 / 认领不动任务状态与患者责任医生（pt_patient.responsible_user_id
 * 走患者批量指定，E13-T07）。
 */
@Service
public class FollowupTaskAssignService {

    /** 任务改派 outbox 事件类型（= NotifyEventType.TASK_REASSIGNED.name()，通知规则引擎按它订阅；改派后即时）。 */
    static final String EVENT_TASK_REASSIGNED = "TASK_REASSIGNED";

    /** 单次批量上限（与患者批量操作同口径，docs/api/followup.md §2）。 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 改派原因上限（与移出计划等原因字段同口径）。 */
    private static final int REASON_MAX_LENGTH = 255;

    /** 未完成五态（可改派 / 可认领；E15-T01 状态机「我的待办」同集合——失访候选仍需医生确认，E15-T05 / OQ-86①）。 */
    private static final List<String> OPEN_STATUSES =
            List.of("PENDING", "IN_PROGRESS", "OVERDUE", "LOST_CANDIDATE", "SKIPPED_SUSPENDED");

    /** 逐条结果码：成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    /** 逐条结果码：跳过（原因见 reason；批量语义下跳过是正常结果，逐条可查）。 */
    public static final String OUTCOME_SKIP = "SKIP";

    /** 跳过原因：任务不存在或不在操作者数据范围内（不暴露存在）。 */
    public static final String REASON_TASK_NOT_FOUND = "TASK_NOT_FOUND";

    /** 跳过原因：任务已完成 / 失访 / 已终止，无可再执行的随访。 */
    public static final String REASON_TASK_NOT_OPEN = "TASK_NOT_OPEN";

    /** 跳过原因：负责人已是所选用户（不重复改派、不发通知）。 */
    public static final String REASON_TASK_UNCHANGED = "TASK_UNCHANGED";

    /** 跳过原因：新负责人不负责患者当前病区（A-PAT-017「只能选该病区的医生」同码）。 */
    public static final String REASON_DOCTOR_NOT_IN_WARD = "DOCTOR_NOT_IN_WARD";

    /** 跳过原因：守卫落空（并发完成 / 终止 / 他人先改派）。 */
    public static final String REASON_TASK_CONFLICT = "TASK_CONFLICT";

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskAssignService.class);

    private final FollowupTaskAssignMapper mapper;
    private final PatientContactGateway patientScope;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final CryptoService crypto;
    private final ObjectMapper json = new ObjectMapper();

    public FollowupTaskAssignService(
            FollowupTaskAssignMapper mapper,
            PatientContactGateway patientScope,
            OutboxPublisher outbox,
            AuditLogger audit,
            CryptoService crypto) {
        this.mapper = mapper;
        this.patientScope = patientScope;
        this.outbox = outbox;
        this.audit = audit;
        this.crypto = crypto;
    }

    // ---------------------------------------------------------------- 改派（单个与批量共用）

    /** 逐条结果（patientName / mrn 只含脱敏值；TASK_NOT_FOUND 行不带摘要，不暴露存在）。 */
    public record AssignItemView(
            String taskId,
            String patientId,
            String patientName,
            String mrn,
            String wardName,
            String nodeName,
            String outcome,
            String reason) {}

    /** 批量结果（items 与请求顺序一致，逐条可查；id 为字符串口径，超过 2^53 安全）。 */
    public record AssignResultView(List<AssignItemView> items, int succeeded, int skipped) {}

    /**
     * 批量改派（单个改派 = 传一条）：新负责人须启用（40001）；taskIds 非空、去重、≤500（40001）； reason 可选、≤255（40001）。
     * 逐条结果按请求顺序返回；只有实际改派成功的行写 TASK_REASSIGNED 事件（验收「改派后新责任人收到通知」）。
     */
    @Transactional
    public AssignResultView batchAssign(List<Long> taskIds, Long assigneeId, String reason) {
        if (assigneeId == null || assigneeId <= 0) {
            throw new ApiException(400, 40001, "请选择新负责人");
        }
        String trimmedReason = normalizeReason(reason);
        if (mapper.countEnabledAssignee(assigneeId) == 0) {
            throw new ApiException(400, 40001, "新负责人不存在或已停用");
        }
        List<Long> ids = normalizeIds(taskIds);
        boolean hospitalWide = mapper.countHospitalWideScope(assigneeId) > 0;
        Map<Long, FollowupTaskAssignMapper.AssignTaskRow> rows = tasksInScope(ids);
        Long operatorId =
                CurrentSession.current().map(principal -> principal.userId()).orElse(null);

        List<AssignItemView> items = new ArrayList<>(ids.size());
        int succeeded = 0;
        int skipped = 0;
        for (long id : ids) {
            FollowupTaskAssignMapper.AssignTaskRow row = rows.get(id);
            if (row == null) {
                items.add(notFoundItem(id));
                skipped++;
                continue;
            }
            String blocked = whyBlocked(row, assigneeId, hospitalWide);
            if (blocked == null && mapper.reassignTask(id, row.assigneeId(), assigneeId) == 0) {
                blocked = REASON_TASK_CONFLICT;
            }
            if (blocked != null) {
                items.add(itemOf(row, OUTCOME_SKIP, blocked));
                skipped++;
                continue;
            }
            items.add(itemOf(row, OUTCOME_SUCCESS, null));
            succeeded++;
            publishReassigned(row, assigneeId, trimmedReason);
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_ASSIGN")
                        .objectType("TASK")
                        .description(
                                "任务改派（A-FU-009）：新负责人 "
                                        + assigneeId
                                        + "，成功 "
                                        + succeeded
                                        + "、跳过 "
                                        + skipped)
                        .afterJson(resultJson(items, succeeded, skipped))
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "任务改派 assignee={} succeeded={} skipped={} operator={}",
                assigneeId,
                succeeded,
                skipped,
                operatorId);
        return new AssignResultView(items, succeeded, skipped);
    }

    /** 该任务被改派给该负责人时的跳过原因（null = 可以改派）：终态不可改派；负责人未变不重复发通知； 新负责人须负责患者病区或持全院范围（保证通知可达）。 */
    private String whyBlocked(
            FollowupTaskAssignMapper.AssignTaskRow row, long assigneeId, boolean hospitalWide) {
        if (!OPEN_STATUSES.contains(row.status())) {
            return REASON_TASK_NOT_OPEN;
        }
        if (row.assigneeId() != null && row.assigneeId() == assigneeId) {
            return REASON_TASK_UNCHANGED;
        }
        if (row.wardId() != null
                && !hospitalWide
                && mapper.countWardMembership(assigneeId, row.wardId()) == 0) {
            return REASON_DOCTOR_NOT_IN_WARD;
        }
        return null;
    }

    /** TASK_REASSIGNED 事件（负载契约 NotifyEventPayload；患者姓名解密后只进站内信密文列，A-NTF-003）。 */
    private void publishReassigned(
            FollowupTaskAssignMapper.AssignTaskRow row, long newAssigneeId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", row.id());
        payload.put("ownerId", newAssigneeId);
        payload.put("wardId", row.wardId());
        payload.put("patientId", row.patientId());
        payload.put(
                "patientName", row.nameCipher() == null ? null : crypto.decrypt(row.nameCipher()));
        payload.put("nodeName", row.nodeName());
        payload.put(
                "windowStartDate", row.windowStart() == null ? null : row.windowStart().toString());
        payload.put("windowEndDate", row.windowEnd() == null ? null : row.windowEnd().toString());
        payload.put("reason", reason);
        try {
            outbox.publish(EVENT_TASK_REASSIGNED, json.writeValueAsString(payload));
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("任务改派事件负载序列化失败", broken);
        }
    }

    // ---------------------------------------------------------------- 认领（公共池）

    /** 认领结果。 */
    public record ClaimView(String taskId, String status) {}

    /**
     * 从病区公共池认领（A-FU-009）：认领人 = 当前登录用户。待认领（assignee NULL）且未完成才可认领—— 已有负责人 40901（走改派）、终态 40901、越范围 /
     * 不存在 40400（不暴露存在）、并发被抢 40900；写 TASK_CLAIM 审计（A-NFR-004「认领记入审计」）。
     */
    @Transactional
    public ClaimView claim(long taskId) {
        FollowupTaskAssignMapper.ClaimTaskRow task = mapper.findClaimTaskById(taskId);
        if (task == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        // 数据范围前置校验（与 E15-T01 / E15-T02 写入口同口径）：越范围患者不暴露任务存在
        if (patientScope.patientIdInScope(task.patientId()) == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        if (!OPEN_STATUSES.contains(task.status())) {
            throw new ApiException(409, 40901, "任务已完成或已终止，无需认领");
        }
        if (task.assigneeId() != null) {
            throw new ApiException(409, 40901, "任务已有负责人，不能认领（可请有权限的人改派）");
        }
        long operatorId =
                CurrentSession.current()
                        .orElseThrow(() -> new ApiException(401, 40100, "未登录或登录已失效"))
                        .userId();
        if (mapper.claimTask(taskId, operatorId) == 0) {
            throw new ApiException(409, 40900, "任务刚被他人认领，请刷新后重试");
        }
        audit.append(
                AuditEntry.operation("followup", "TASK_CLAIM")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description("从公共池认领任务（A-FU-009）")
                        .beforeJson("{\"assigneeId\":null}")
                        .afterJson("{\"assigneeId\":" + operatorId + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("公共池认领 taskId={} operator={}", taskId, operatorId);
        return new ClaimView(String.valueOf(taskId), task.status());
    }

    // ---------------------------------------------------------------- 内部

    /** 批量取操作者数据范围内的任务行（按 id 索引；范围外不返回）。 */
    private Map<Long, FollowupTaskAssignMapper.AssignTaskRow> tasksInScope(List<Long> ids) {
        Map<Long, FollowupTaskAssignMapper.AssignTaskRow> byId = new LinkedHashMap<>();
        for (FollowupTaskAssignMapper.AssignTaskRow row : mapper.findAssignTasksInScope(ids)) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    /** taskIds 规整：非空（40001）、去重保序、≤500（40001）。 */
    private static List<Long> normalizeIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new ApiException(400, 40001, "请先选择任务");
        }
        List<Long> ids = taskIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) {
            throw new ApiException(400, 40001, "请先选择任务");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new ApiException(400, 40001, "单次批量最多 " + MAX_BATCH_SIZE + " 条任务");
        }
        return ids;
    }

    /** 改派原因：可空；去空白后 ≤255。 */
    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX_LENGTH) {
            throw new ApiException(400, 40001, "改派原因不能超过 " + REASON_MAX_LENGTH + " 字");
        }
        return trimmed;
    }

    /** 越范围 / 不存在行（不带患者摘要，不暴露存在）。 */
    private static AssignItemView notFoundItem(long taskId) {
        return new AssignItemView(
                String.valueOf(taskId),
                null,
                null,
                null,
                null,
                null,
                OUTCOME_SKIP,
                REASON_TASK_NOT_FOUND);
    }

    /** 逐条结果构造（patientName / mrn 脱敏值；病区与节点辅助定位）。 */
    private AssignItemView itemOf(
            FollowupTaskAssignMapper.AssignTaskRow row, String outcome, String reason) {
        return new AssignItemView(
                String.valueOf(row.id()),
                String.valueOf(row.patientId()),
                SensitiveValueMasker.mask("name", decrypt(row.nameCipher())),
                SensitiveValueMasker.mask("medicalRecordNo", decrypt(row.mrnCipher())),
                row.wardName(),
                row.nodeName(),
                outcome,
                reason);
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }

    /** 审计摘要（只含 taskId / outcome / 原因码，不落患者明文；逐条前 20 条 + 全量原因计数，与响应可对账）。 */
    private static String resultJson(List<AssignItemView> items, int succeeded, int skipped) {
        StringBuilder json =
                new StringBuilder("{\"succeeded\":")
                        .append(succeeded)
                        .append(",\"skipped\":")
                        .append(skipped)
                        .append(",\"items\":[");
        Map<String, Integer> reasonCount = new HashMap<>();
        int listed = 0;
        for (AssignItemView item : items) {
            if (item.reason() != null) {
                reasonCount.merge(item.reason(), 1, Integer::sum);
            }
            if (listed >= 20) {
                continue;
            }
            if (listed > 0) {
                json.append(',');
            }
            json.append("{\"taskId\":")
                    .append(item.taskId())
                    .append(",\"outcome\":\"")
                    .append(item.outcome())
                    .append('"');
            if (item.reason() != null) {
                json.append(",\"reason\":\"").append(item.reason()).append('"');
            }
            json.append('}');
            listed++;
        }
        json.append("],\"reasonCount\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : reasonCount.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        json.append("}}");
        return json.toString();
    }
}
