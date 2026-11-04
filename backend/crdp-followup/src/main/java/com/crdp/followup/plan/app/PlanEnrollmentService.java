package com.crdp.followup.plan.app;

import com.crdp.followup.plan.infra.mapper.PlanEnrollmentInsertRow;
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
import java.util.List;
import java.util.Map;
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
 * <p>E14-T08 / A-FU-022 计划患者列表：该计划版本的全部入组（含已暂停 / 已移除）——加入时间、随访完成次数、状态（已启动 / 已暂停 / 已移除）。患者面数据按
 * {@code @DataScope(resource = "patient")} 出行（fu_enrollment 自 E14-T01 起登记 ControlledTables）；姓名 /
 * 病案号只输出脱敏值（AGENTS §5.3，明文只走 E07-T05 专用接口）；随访完成次数为 fu_task 管理口径聚合计数（E15-T01 状态流转后开始累计）。单个患者的暂停 / 恢复
 * / 移出（A-FU-008）随 E14-T06；批量入组（A-PAT-016）随 E13-T07、自动入组随 E14-T05 复用 {@link #enroll} 入口。
 */
@Service
public class PlanEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(PlanEnrollmentService.class);

    /** 入组模式（C：常规 / ERI 期）。 */
    public static final String MODE_NORMAL = "NORMAL";

    /** 默认分页（AGENTS §6：pageNo 从 1 起、pageSize 默认 20、上限 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    /** 同一患者同一计划族（active_key）只允许一条在组入组：uk_fu_enrollment_active 兜底。 */
    private final PlanMapper planMapper;

    private final PlanEnrollmentMapper mapper;

    private final AuditLogger audit;

    private final CryptoService crypto;

    public PlanEnrollmentService(
            PlanMapper planMapper,
            PlanEnrollmentMapper mapper,
            AuditLogger audit,
            CryptoService crypto) {
        this.planMapper = planMapper;
        this.mapper = mapper;
        this.audit = audit;
        this.crypto = crypto;
    }

    /** 入组结果（planId = 固定的计划版本行 id，A-FU-019）。 */
    public record EnrollmentView(String id, String patientId, String planId, String mode) {}

    /** 计划患者列表行（A-FU-022：加入时间、随访完成次数、状态；name / mrn 只含脱敏值）。 */
    public record EnrollmentPatientView(
            String id,
            String patientId,
            String name,
            String mrn,
            String legacyNo,
            String gender,
            String wardName,
            String mode,
            String status,
            String pauseReason,
            String removeReason,
            String enrolledAt,
            long completedCount) {}

    /** 分页响应（AGENTS §6：{ items, total, pageNo, pageSize }）。 */
    public record EnrollmentPatientPage(
            List<EnrollmentPatientView> items, long total, int pageNo, int pageSize) {}

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

    // ---------------------------------------------------------------- 计划患者列表（E14-T08 / A-FU-022）

    /**
     * 计划患者列表：该计划版本的全部入组（含已暂停 / 已移除），按加入时间倒序分页。 姓名与病案号只输出脱敏值（解密后 SensitiveValueMasker，AGENTS
     * §5.3）；数据范围由 Mapper 的 {@code @DataScope(resource = "patient")} 出行（越范围患者直接不出现在列表，§5.2）。 计划不存在
     * 40400。
     */
    public EnrollmentPatientPage patients(long planId, Integer pageNo, Integer pageSize) {
        if (planMapper.findById(planId) == null) {
            throw new ApiException(404, 40400, "计划不存在");
        }
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size =
                pageSize == null || pageSize < 1
                        ? DEFAULT_PAGE_SIZE
                        : Math.min(pageSize, MAX_PAGE_SIZE);
        long total = mapper.countPatientsByPlanId(planId);
        List<PlanEnrollmentMapper.EnrollmentPatientRow> rows =
                total == 0
                        ? List.of()
                        : mapper.findPatientsByPlanId(planId, (page - 1) * size, size);
        Map<Long, Long> completedByEnrollment = completedCountsOf(rows);
        List<EnrollmentPatientView> items = new ArrayList<>(rows.size());
        for (PlanEnrollmentMapper.EnrollmentPatientRow row : rows) {
            Long completed = completedByEnrollment.get(row.id());
            items.add(
                    new EnrollmentPatientView(
                            String.valueOf(row.id()),
                            String.valueOf(row.patientId()),
                            mask(decrypt(row.nameCipher()), "name"),
                            mask(decrypt(row.mrnCipher()), "medicalRecordNo"),
                            row.legacyNo(),
                            row.gender(),
                            row.wardName(),
                            row.mode(),
                            row.status(),
                            row.pauseReason(),
                            row.removeReason(),
                            PlanService.Time.format(row.enrolledAt()),
                            completed == null ? 0 : completed));
        }
        return new EnrollmentPatientPage(items, total, page, size);
    }

    /** 随访完成次数（fu_task COMPLETED 聚合计数；本页各入组一次批量查询）。 */
    private Map<Long, Long> completedCountsOf(
            List<PlanEnrollmentMapper.EnrollmentPatientRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = rows.stream().map(PlanEnrollmentMapper.EnrollmentPatientRow::id).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (PlanEnrollmentMapper.CompletedCountRow row :
                mapper.countCompletedByEnrollmentIds(ids)) {
            counts.put(row.enrollmentId(), row.completed());
        }
        return counts;
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }

    private static String mask(String plain, String field) {
        return plain == null ? null : SensitiveValueMasker.mask(field, plain);
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
