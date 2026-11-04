package com.crdp.followup.plan.app;

import com.crdp.followup.plan.infra.mapper.PlanEnrollmentInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者入组用例（E14-T01 / A-FU-019、A-PAT-016；表 followup V46）。
 *
 * <p>E14-T01 验收「新入组用新版本」的落点：入组只允许 PUBLISHED（或派生「随访中」）的计划版本—— 发布新版本时同族旧版本自动转
 * DISABLED（不再允许入组），因此入组必然固定在当前最新发布版本； 入组行把版本固定在
 * fu_enrollment.plan_id，之后按需创建的任务也按该版本（E14-T04），患者不随计划升版迁移（A-FU-020 延后）。
 *
 * <p>本任务先落「单患者入组」领域用例（校验、版本固定、判重、审计），批量入组（A-PAT-016，E13-T07）与自动入组（E14-T05）复用本入口； 计划患者列表与暂停 / 恢复 /
 * 移出（A-FU-022、A-FU-008）随 E14-T08 / T06。
 */
@Service
public class PlanEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(PlanEnrollmentService.class);

    /** 入组模式（C：常规 / ERI 期）。 */
    public static final String MODE_NORMAL = "NORMAL";

    /** 同一患者同一计划族（active_key）只允许一条在组入组：uk_fu_enrollment_active 兜底。 */
    private final PlanMapper planMapper;

    private final PlanEnrollmentMapper mapper;

    private final AuditLogger audit;

    public PlanEnrollmentService(
            PlanMapper planMapper, PlanEnrollmentMapper mapper, AuditLogger audit) {
        this.planMapper = planMapper;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** 入组结果（planId = 固定的计划版本行 id，A-FU-019）。 */
    public record EnrollmentView(String id, String patientId, String planId, String mode) {}

    /**
     * 单患者入组：计划须为已发布（未发布 40901、已暂停 40901「不开启新任务」、已停用 40901「不再允许入组」）； 患者在册且未死亡（A-PAT-016
     * 批量跳过「已死亡」同口径）；同族已在组 40901。
     */
    @Transactional
    public EnrollmentView enroll(long patientId, long planId, String mode) {
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
        PlanEnrollmentMapper.PatientStateRow patient = mapper.findPatientState(patientId);
        if (patient == null) {
            throw new ApiException(404, 40400, "患者不存在或已删除");
        }
        if ("DEAD".equals(patient.followupStatus())
                || "TERMINATED".equals(patient.followupStatus())) {
            throw new ApiException(409, 40901, "患者已死亡或已终止随访，不能入组");
        }
        String normalizedMode = normalizeMode(mode);
        if (mapper.findActiveIdByPatientAndPlanCode(patientId, plan.code()) != null) {
            throw duplicate(plan, patientId);
        }
        PlanEnrollmentInsertRow row =
                new PlanEnrollmentInsertRow(
                        patientId,
                        planId,
                        plan.code(),
                        null,
                        normalizedMode,
                        "ACTIVE",
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null));
        try {
            mapper.insertEnrollment(row);
        } catch (DuplicateKeyException race) {
            // uk_fu_enrollment_active 兜底：并发重复入组同一计划族
            log.warn("入组并发冲突（患者 {} 计划族 {}），按已在组处理（E14-T01）", patientId, plan.code());
            throw duplicate(plan, patientId);
        }
        audit.append(
                AuditEntry.operation("followup", "PLAN_ENROLL")
                        .objectType("ENROLLMENT")
                        .objectId(String.valueOf(row.getId()))
                        .description(
                                "患者入组：计划 "
                                        + plan.name()
                                        + " v"
                                        + plan.version()
                                        + "（版本固定在 v"
                                        + plan.version()
                                        + "，A-FU-019）")
                        .afterJson(
                                "{\"patientId\":"
                                        + patientId
                                        + ",\"planId\":"
                                        + planId
                                        + ",\"planCode\":\""
                                        + plan.code()
                                        + "\",\"version\":"
                                        + plan.version()
                                        + ",\"mode\":\""
                                        + normalizedMode
                                        + "\",\"status\":\"ACTIVE\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new EnrollmentView(
                String.valueOf(row.getId()),
                String.valueOf(patientId),
                String.valueOf(planId),
                normalizedMode);
    }

    private ApiException duplicate(PlanMapper.PlanRow plan, long patientId) {
        audit.append(
                AuditEntry.operation("followup", "PLAN_ENROLL")
                        .objectType("ENROLLMENT")
                        .description("入组被阻止：患者已在同一计划族（" + plan.code() + "）在组（患者 " + patientId + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40901)
                        .reason("PATIENT_ALREADY_ENROLLED")
                        .build());
        return new ApiException(409, 40901, "该患者已在该计划中（在组），不能重复入组；如需迁移版本请等待后续任务");
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_NORMAL;
        }
        String normalized = mode.trim().toUpperCase();
        if (!"NORMAL".equals(normalized) && !"ERI".equals(normalized)) {
            throw new ApiException(400, 40001, "入组模式只支持常规（NORMAL）或 ERI 期（ERI）");
        }
        return normalized;
    }
}
