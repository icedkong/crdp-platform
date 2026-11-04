package com.crdp.followup.plan.app;

import com.crdp.followup.patient.app.PatientBatchAssignService;
import com.crdp.followup.patient.infra.mapper.PatientBatchMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 批量入组（E13-T07 / A-PAT-016；docs/api/followup.md §1 enrollments/batch）。
 *
 * <p>「按筛选条件（含高级检索）选出患者，批量加入一个已发布的随访计划」——筛选面由患者检索接口承担（E13-T04 / T06）， 本服务接收勾选后的
 * patientIds：预检（batch-preview）逐条判「将加入 / 将跳过」（确认前显示将加入人数与将跳过人数，A-PAT-016）， 执行（batch）逐条入组并返回成功 /
 * 跳过明细——「批量操作结果逐条可查」（tasks.csv 验收；规格验收「20 名患者加入计划，其中 2 名已在组，结果显示成功 18、跳过 2」）。
 *
 * <p>跳过口径（A-PAT-016「已在该计划中、已死亡、已从该计划移除的患者跳过并在结果中列明」+ 数据范围）：
 *
 * <ul>
 *   <li>{@code ALREADY_ENROLLED} 已在该计划族在组（ACTIVE / PAUSED，uk 唯一性口径）；
 *   <li>{@code DEAD} 患者已死亡；{@code TERMINATED} 患者已终止随访（单条 {@code PlanEnrollmentService.enroll} 同口径）；
 *   <li>{@code REMOVED} 曾从该计划移除（REMOVED 行 active_key 为生成列 NULL，按 plan_code 查）；
 *   <li>{@code NOT_FOUND} 患者不存在或不在操作者数据范围内（不暴露存在，AGENTS §5.2）；
 *   <li>{@code DOCTOR_NOT_IN_WARD} 带责任医生时医生不负责该患者病区（A-PAT-017「只能选该病区的医生」）。
 * </ul>
 *
 * <p>执行逐条经 {@link PlanEnrollmentService#enroll}（各自事务、入组即建补录 / 当期任务，E14-T05）： 单条并发状态变化（预检后恰好被他人入组）按
 * {@code CONFLICT} 跳过不中断整批（与自动入组 autoEnrollOnRegistration 的逐条容错同口径）；系统级异常照常抛出（不吞库故障）。
 * 带责任医生时逐条先指定（复用 PatientBatchAssignService.assignDoctorToPatient，跨子域走 app
 * 入口），指定成功后再入组——指定本身是独立合法操作，入组失败不回退指定（PR 注明口径）。
 *
 * <p>审计：预检写 BATCH_QUERY 数据访问审计（批量查询患者）；执行写一条 PLAN_ENROLL_BATCH 汇总（逐条成功另有单条 PLAN_ENROLL 审计）。
 */
@Service
public class BatchEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(BatchEnrollmentService.class);

    /** 预检结果码：将加入 / 将跳过。 */
    public static final String PREVIEW_ENROLL = "ENROLL";

    public static final String PREVIEW_SKIP = "SKIP";

    /** 执行结果码：成功 / 跳过。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    public static final String OUTCOME_SKIP = "SKIP";

    /** 跳过原因：已在该计划族在组。 */
    public static final String REASON_ALREADY_ENROLLED = "ALREADY_ENROLLED";

    /** 跳过原因：患者已死亡（A-PAT-016 批量口径）。 */
    public static final String REASON_DEAD = "DEAD";

    /** 跳过原因：已从该计划移除（A-PAT-016 批量口径；REMOVED 行不占唯一键，单条入口可重新入组）。 */
    public static final String REASON_REMOVED = "REMOVED";

    /** 跳过原因：患者已终止随访（单条 enroll 同口径）。 */
    public static final String REASON_TERMINATED = "TERMINATED";

    /** 跳过原因：患者不存在或不在操作者数据范围内。 */
    public static final String REASON_NOT_FOUND = "NOT_FOUND";

    /** 跳过原因：带责任医生时医生不负责该患者病区。 */
    public static final String REASON_DOCTOR_NOT_IN_WARD = "DOCTOR_NOT_IN_WARD";

    /** 跳过原因：预检后并发状态变化（恰被他人入组等，enroll 40901 兜底）。 */
    public static final String REASON_CONFLICT = "CONFLICT";

    private final PlanMapper planMapper;
    private final PlanEnrollmentMapper enrollmentMapper;
    private final PlanEnrollmentService enrollments;
    private final PatientBatchAssignService doctorAssignments;
    private final AuditLogger audit;
    private final CryptoService crypto;

    public BatchEnrollmentService(
            PlanMapper planMapper,
            PlanEnrollmentMapper enrollmentMapper,
            PlanEnrollmentService enrollments,
            PatientBatchAssignService doctorAssignments,
            AuditLogger audit,
            CryptoService crypto) {
        this.planMapper = planMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.enrollments = enrollments;
        this.doctorAssignments = doctorAssignments;
        this.audit = audit;
        this.crypto = crypto;
    }

    /** 逐条结果（name / patientNo 只含脱敏值）。 */
    public record BatchItemView(
            String patientId,
            String name,
            String patientNo,
            String wardName,
            String outcome,
            String reason) {}

    /** 批量结果（items 与请求顺序一致，逐条可查）。 */
    public record BatchEnrollResult(List<BatchItemView> items, int succeeded, int skipped) {}

    /** 批量入组预检（A-PAT-016「确认前显示将加入人数与将跳过人数」）：逐条 ENROLL / SKIP + 原因，不写任何数据。 */
    public BatchEnrollResult preview(long planId, List<Long> patientIds, Long doctorId) {
        PlanMapper.PlanRow plan = requireEnrollablePlan(planId);
        List<Long> ids = PatientBatchAssignService.normalizeIds(patientIds);
        Map<Long, PatientBatchMapper.BatchPatientRow> patients =
                doctorAssignments.patientsInScope(ids);
        Map<Long, Set<String>> enrollmentStatuses = enrollmentStatusesOf(ids, plan.code());

        List<BatchItemView> items = new ArrayList<>(ids.size());
        int enrollable = 0;
        int skipped = 0;
        for (Long id : ids) {
            PatientBatchMapper.BatchPatientRow patient = patients.get(id);
            String reason = skipReasonOf(patient, enrollmentStatuses.get(id), doctorId);
            if (reason == null) {
                items.add(itemOf(id, patient, PREVIEW_ENROLL, null));
                enrollable++;
            } else {
                items.add(itemOf(id, patient, PREVIEW_SKIP, reason));
                skipped++;
            }
        }
        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("PATIENT")
                        .description(
                                "批量入组预检（A-PAT-016）：计划 "
                                        + plan.name()
                                        + " v"
                                        + plan.version()
                                        + "，将加入 "
                                        + enrollable
                                        + "、将跳过 "
                                        + skipped)
                        .recordCount((long) items.size())
                        .queryDigest("{\"planCode\":\"" + plan.code() + "\"}")
                        .build());
        return new BatchEnrollResult(items, enrollable, skipped);
    }

    /**
     * 批量入组执行：对预检可入组的患者逐条指定责任医生（可选）→ {@link PlanEnrollmentService#enroll}（入组即建补录 / 当期任务，E14-T05）。
     * 单条业务性失败（并发状态变化）按 CONFLICT 跳过，不中断整批。
     */
    public BatchEnrollResult enroll(long planId, List<Long> patientIds, Long doctorId) {
        PlanMapper.PlanRow plan = requireEnrollablePlan(planId);
        List<Long> ids = PatientBatchAssignService.normalizeIds(patientIds);
        Map<Long, PatientBatchMapper.BatchPatientRow> patients =
                doctorAssignments.patientsInScope(ids);
        Map<Long, Set<String>> enrollmentStatuses = enrollmentStatusesOf(ids, plan.code());
        Long operatorId =
                CurrentSession.current().map(principal -> principal.userId()).orElse(null);

        List<BatchItemView> items = new ArrayList<>(ids.size());
        int succeeded = 0;
        int skipped = 0;
        for (Long id : ids) {
            PatientBatchMapper.BatchPatientRow patient = patients.get(id);
            String reason = skipReasonOf(patient, enrollmentStatuses.get(id), doctorId);
            if (reason == null && doctorId != null && patient.wardId() != null) {
                // 先指定责任医生（独立合法操作），失败按病区口径跳过不入组
                reason =
                        doctorAssignments.assignDoctorToPatient(
                                id, doctorId, patient.wardId(), operatorId);
            }
            // 越范围患者在上方 skipReasonOf 已按 NOT_FOUND 跳过，此处 patient 不会为 null
            if (reason == null) {
                try {
                    enrollments.enroll(id, planId, null);
                } catch (ApiException race) {
                    // 预检后并发状态变化（恰被他人入组 / 患者状态流转）：逐条跳过不中断整批
                    log.debug("批量入组单条跳过（患者 {} 计划 {}）：{}", id, plan.code(), race.getMessage());
                    reason = REASON_CONFLICT;
                }
            }
            if (reason == null) {
                items.add(itemOf(id, patient, OUTCOME_SUCCESS, null));
                succeeded++;
            } else {
                items.add(itemOf(id, patient, OUTCOME_SKIP, reason));
                skipped++;
            }
        }
        audit.append(
                AuditEntry.operation("followup", "PLAN_ENROLL_BATCH")
                        .objectType("PLAN")
                        .objectId(String.valueOf(planId))
                        .description(
                                "批量入组（A-PAT-016）：计划 "
                                        + plan.name()
                                        + " v"
                                        + plan.version()
                                        + "，成功 "
                                        + succeeded
                                        + "、跳过 "
                                        + skipped
                                        + (doctorId == null ? "" : "，同时指定责任医生 " + doctorId))
                        .afterJson(summaryJson(items, succeeded, skipped))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new BatchEnrollResult(items, succeeded, skipped);
    }

    // ---------------------------------------------------------------- 内部

    /** 计划存在且允许入组（PUBLISHED；未发布 / 已暂停 / 已停用 40901，与单条 enroll 同口径）。 */
    private PlanMapper.PlanRow requireEnrollablePlan(long planId) {
        PlanMapper.PlanRow plan = planMapper.findById(planId);
        if (plan == null) {
            throw new ApiException(404, 40400, "计划不存在");
        }
        switch (plan.status()) {
            case PlanService.UNPUBLISHED -> throw new ApiException(409, 40901, "计划尚未发布，不能入组");
            case PlanService.PAUSED ->
                    throw new ApiException(409, 40901, "计划已暂停（不开启新任务、不发通知），不能入组");
            case PlanService.DISABLED ->
                    throw new ApiException(409, 40901, "计划已停用（不再允许入组）；如需继续入组请使用新版本");
            default -> {
                // PUBLISHED（含派生「随访中」）允许入组
            }
        }
        return plan;
    }

    /** 各患者在该计划族的入组状态集合（含 REMOVED；按 plan_code 查，REMOVED 行 active_key 为生成列 NULL）。 */
    private Map<Long, Set<String>> enrollmentStatusesOf(List<Long> ids, String planCode) {
        Map<Long, Set<String>> statuses = new HashMap<>();
        for (PlanEnrollmentMapper.PatientEnrollmentStatusRow row :
                enrollmentMapper.findStatusByPatientIdsAndPlanCode(planCode, ids)) {
            statuses.computeIfAbsent(row.patientId(), key -> new HashSet<>()).add(row.status());
        }
        return statuses;
    }

    /**
     * 该患者入组前的跳过原因（null = 可入组）：越范围 → 已在该计划族在组 → 已从该计划移除 → 已死亡 → 已终止（患者级，如从其他计划移除）→ 责任医生病区。
     * 「已移除」先于患者级「已终止」：从本计划移除的患者（患者级通常随之 TERMINATED，E14-T06）对用户口径是「已从该计划移除」。
     */
    private String skipReasonOf(
            PatientBatchMapper.BatchPatientRow patient,
            Set<String> enrollmentStatuses,
            Long doctorId) {
        if (patient == null) {
            return REASON_NOT_FOUND;
        }
        if (enrollmentStatuses != null
                && (enrollmentStatuses.contains("ACTIVE")
                        || enrollmentStatuses.contains("PAUSED"))) {
            return REASON_ALREADY_ENROLLED;
        }
        if (enrollmentStatuses != null && enrollmentStatuses.contains("REMOVED")) {
            return REASON_REMOVED;
        }
        if ("DEAD".equals(patient.followupStatus())) {
            return REASON_DEAD;
        }
        if ("TERMINATED".equals(patient.followupStatus())) {
            return REASON_TERMINATED;
        }
        if (doctorId != null
                && (patient.wardId() == null
                        || !doctorAssignments.doctorManagesWard(doctorId, patient.wardId()))) {
            return REASON_DOCTOR_NOT_IN_WARD;
        }
        return null;
    }

    private static String summaryJson(List<BatchItemView> items, int succeeded, int skipped) {
        StringBuilder json =
                new StringBuilder("{\"succeeded\":")
                        .append(succeeded)
                        .append(",\"skipped\":")
                        .append(skipped)
                        .append(",\"items\":[");
        Map<String, Integer> reasonCount = new HashMap<>();
        int listed = 0;
        for (BatchItemView item : items) {
            if (item.reason() != null) {
                reasonCount.merge(item.reason(), 1, Integer::sum);
            }
            if (listed >= 20) {
                continue;
            }
            if (listed > 0) {
                json.append(',');
            }
            json.append("{\"patientId\":")
                    .append(item.patientId())
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

    /** 逐条结果构造（name / patientNo 脱敏；patient 为 null = 越范围 / 不存在，不带摘要不暴露存在）。 */
    private BatchItemView itemOf(
            long id, PatientBatchMapper.BatchPatientRow patient, String outcome, String reason) {
        if (patient == null) {
            return new BatchItemView(String.valueOf(id), null, null, null, outcome, reason);
        }
        return new BatchItemView(
                String.valueOf(id),
                SensitiveValueMasker.mask("name", decrypt(patient.nameCipher())),
                SensitiveValueMasker.mask("medicalRecordNo", decrypt(patient.mrnCipher())),
                patient.wardName(),
                outcome,
                reason);
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }
}
