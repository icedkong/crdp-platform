package com.crdp.followup.plan.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.patient.app.PatientFollowupStatusService;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.app.FollowupTaskTerminator;
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
 * <p>E14-T05 / Q44 建档补录：{@link #enroll} 在同一事务内调 {@code
 * FollowupTaskCreationService.initializeForNewEnrollment}——窗口在入组日前已结束的次数按节点 passed_window_action
 * 补建「补录」任务（BACKFILL，不发通知）或跳过，窗口已开启未结束的次数当天正常创建；任务建不出来整个入组回滚。
 *
 * <p>E14-T05 / Q35 自动入组：{@link #autoEnrollOnRegistration}（出院登记后调用）把患者加入全部 auto_enroll = 1
 * 的已发布计划；出院状态为死亡（A 规格字段 191 取值 3）不加入。单个计划的业务性失败（如计划恰好被暂停）只跳过不中断。
 *
 * <p>E14-T08 / A-FU-022 计划患者列表：该计划版本的全部入组（含已暂停 / 已移除）——加入时间、随访完成次数、状态（已启动 / 已暂停 / 已移除）。患者面数据按
 * {@code @DataScope(resource = "patient")} 出行（fu_enrollment 自 E14-T01 起登记 ControlledTables）；姓名 /
 * 病案号只输出脱敏值（AGENTS §5.3，明文只走 E07-T05 专用接口）；随访完成次数为 fu_task 管理口径聚合计数（E15-T01 状态流转后开始累计）。单个患者的暂停 / 恢复
 * / 移出（A-FU-008）随 E14-T06；批量入组（A-PAT-016）随 E13-T07、自动入组复用 {@link #enroll} 入口。
 */
@Service
public class PlanEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(PlanEnrollmentService.class);

    /** 入组模式（C：常规 / ERI 期）。 */
    public static final String MODE_NORMAL = "NORMAL";

    /** 出院状态「死亡」（A 规格字段 191：1 存活 / 2 病重自动出院 / 3 死亡）——Q35 不自动入组。 */
    private static final int DISCHARGE_STATUS_DEAD = 3;

    /** 患者级随访状态（components.md §2 patient 域；E14-T06 暂停 / 恢复 / 移出同步流转）。 */
    static final String PATIENT_ACTIVE = "ACTIVE";

    static final String PATIENT_PAUSED = "PAUSED";

    static final String PATIENT_DEAD = "DEAD";

    static final String PATIENT_TERMINATED = "TERMINATED";

    /** 状态时间线来源：计划域联动（V39 source 枚举 REGISTRATION / MANUAL / PLAN / SYSTEM）。 */
    static final String STATUS_SOURCE_PLAN = "PLAN";

    /** 默认分页（AGENTS §6：pageNo 从 1 起、pageSize 默认 20、上限 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    /** 同一患者同一计划族（active_key）只允许一条在组入组：uk_fu_enrollment_active 兜底。 */
    private final PlanMapper planMapper;

    private final PlanEnrollmentMapper mapper;

    private final AuditLogger audit;

    private final CryptoService crypto;

    private final FollowupTaskCreationService taskCreation;

    private final PatientContactGateway patientScope;

    private final PatientFollowupStatusService patientStatus;

    private final FollowupTaskTerminator taskTerminations;

    public PlanEnrollmentService(
            PlanMapper planMapper,
            PlanEnrollmentMapper mapper,
            AuditLogger audit,
            CryptoService crypto,
            FollowupTaskCreationService taskCreation,
            PatientContactGateway patientScope,
            PatientFollowupStatusService patientStatus,
            FollowupTaskTerminator taskTerminations) {
        this.planMapper = planMapper;
        this.mapper = mapper;
        this.audit = audit;
        this.crypto = crypto;
        this.taskCreation = taskCreation;
        this.patientScope = patientScope;
        this.patientStatus = patientStatus;
        this.taskTerminations = taskTerminations;
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
        // E14-T05 / Q44 建档补录：入组同一事务内建任务（已结束窗口标补录、窗口内当期任务）；
        // 建不出来（如配置异常）整个入组回滚——每日调度不补入组前已过窗口，不能留在组无任务的半状态
        FollowupTaskCreationService.EnrollmentInitSummary initialized =
                taskCreation.initializeForNewEnrollment(
                        row.getId(), patientId, planId, normalizedMode);
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
                                        + "，A-FU-019；入组即建补录 "
                                        + initialized.backfill()
                                        + " 条、当期 "
                                        + initialized.normal()
                                        + " 条）")
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
                                        + "\",\"status\":\"ACTIVE\",\"backfillTasks\":"
                                        + initialized.backfill()
                                        + ",\"currentTasks\":"
                                        + initialized.normal()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new EnrollmentView(
                String.valueOf(row.getId()),
                String.valueOf(patientId),
                String.valueOf(planId),
                normalizedMode);
    }

    // ---------------------------------------------------------------- 自动入组（E14-T05 / Q35）

    /**
     * 出院登记后的自动入组（Q35）：加入全部 auto_enroll = 1 且 PUBLISHED 的计划；出院状态为死亡不加入。
     *
     * <p>在登记事务内调用（patient.app → plan.app 跨子域入口）；单个计划的业务性失败（计划被暂停、患者不可入组等 ApiException）只记 DEBUG
     * 跳过、不中断登记；登记侧重故障（如库不可达）由调用方兜底记 FAIL 审计。
     *
     * @return 实际入组的计划数
     */
    public int autoEnrollOnRegistration(long patientId, Integer dischargeStatus) {
        if (dischargeStatus != null && dischargeStatus == DISCHARGE_STATUS_DEAD) {
            log.debug("出院状态为死亡，不自动入组（Q35）patient={}", patientId);
            return 0;
        }
        int enrolled = 0;
        for (PlanMapper.PlanRow plan : planMapper.findAutoEnrollPlans()) {
            try {
                enroll(patientId, plan.id(), MODE_NORMAL);
                enrolled++;
            } catch (ApiException skip) {
                // 单个计划不可入组（状态变化、并发已在组等）：跳过继续其余计划，不打断登记
                log.debug("自动入组跳过计划 {}（{}），原因：{}", plan.id(), plan.code(), skip.getMessage());
            }
        }
        if (enrolled > 0) {
            log.info("出院登记自动入组完成 patient={} plans={}", patientId, enrolled);
        }
        return enrolled;
    }

    // ---------------------------------------------------------------- 历史导入入组（E18-T05）

    /**
     * 历史导入的入组（E18-T05 / tech-plan §12⑦「按规则自动入组」）：与 {@link #enroll} 的差别——
     *
     * <ul>
     *   <li><b>不建任务</b>：历史导入按自身规则生成历史记录（有数据年度 HIST_IMPORT、窗口已结束无数据年度
     *       HIST_MISSED，Q06），不为过去年度建补录任务（E14-T04 已定「导入不走入组即建」）；当前窗口的当年任务由每日 定时任务创建（A-PAT-018 流程
     *       6「窗口正在开启中的年度由每日定时任务创建当年任务」）——每日调度对「开启日不晚于 逻辑日且窗口未在入组日前结束」的次数照常排期，无需此处预建；
     *   <li>起算日期快照由调用方按计划起算字段解析（导入行出院日期；解析不到传 null，由每日调度沿用解析回填路径）；
     *   <li>审计不在此写：导入作业级审计（E18-T05 confirm / rollback）覆盖，患者 × 计划逐条不重复写。
     * </ul>
     *
     * <p>并发 / 重复导入撞 uk_fu_enrollment_active 视为已在组，返回既有入组 id（幂等）。
     *
     * @return 入组 id（计划在导入间隙被删返回 null——记录照常生成，无入组锚点）
     */
    public Long enrollForImport(
            long patientId, long planId, java.time.LocalDate startDate, Long operatorId) {
        PlanMapper.PlanRow plan = planMapper.findById(planId);
        if (plan == null) {
            return null;
        }
        Long existing = mapper.findActiveIdByPatientAndPlanCode(patientId, plan.code());
        if (existing != null) {
            return existing;
        }
        PlanEnrollmentInsertRow row =
                new PlanEnrollmentInsertRow(
                        patientId,
                        planId,
                        plan.code(),
                        startDate,
                        MODE_NORMAL,
                        "ACTIVE",
                        operatorId);
        try {
            mapper.insertEnrollment(row);
            return row.getId();
        } catch (DuplicateKeyException race) {
            Long concurrent = mapper.findActiveIdByPatientAndPlanCode(patientId, plan.code());
            return concurrent != null ? concurrent : row.getId();
        }
    }

    // ---------------------------------------------------------------- 暂停 / 恢复 / 移出（E14-T06 /
    // A-FU-008、A-PAT-011）

    /** 暂停 / 恢复 / 移出操作结果（counts：恢复的暂停补偿与当期任务、移出终止的任务数）。 */
    public record EnrollmentActionView(
            String id,
            String patientId,
            String planId,
            String status,
            int pausedSkipped,
            int normal,
            int terminated) {}

    /**
     * 暂停单个患者（A-FU-008 / A-PAT-011）：在组（ACTIVE）→ 已暂停，须填原因；暂停期间不创建任务、不发通知 （每日调度只遍历 ACTIVE
     * 入组），已开启的任务保留原状（统计「暂停中的患者仍计入应随访」）。 无其他在组入组时患者级状态同步「已暂停」。
     */
    @Transactional
    public EnrollmentActionView pause(long planId, long enrollmentId, String reason) {
        PlanEnrollmentMapper.EnrollmentStateRow enrollment =
                requireOwnEnrollment(planId, enrollmentId);
        String normalized = requireReason(reason, "暂停");
        if (!"ACTIVE".equals(enrollment.status())) {
            denied(
                    "ENROLLMENT_PAUSE",
                    enrollment,
                    "暂停",
                    "ENROLLMENT_STATUS_" + enrollment.status());
            throw new ApiException(409, 40901, "只有已启动（在组）的入组才能暂停；已暂停的可直接恢复，已移出的需重新入组");
        }
        if (mapper.pauseEnrollment(enrollmentId, normalized) == 0) {
            throw new ApiException(409, 40901, "入组状态已变化，请刷新后重试");
        }
        // 患者级「已暂停」：多计划并存时只在该患者无其他在组入组时落（A 医院单计划场景即恒落）
        if (mapper.countOtherEnrolled(enrollment.patientId(), enrollmentId) == 0
                && "ACTIVE".equals(enrollment.patientFollowupStatus())) {
            patientStatus.transition(
                    enrollment.patientId(),
                    PATIENT_PAUSED,
                    normalized,
                    STATUS_SOURCE_PLAN,
                    PATIENT_ACTIVE,
                    currentUserId());
        }
        audit.append(
                AuditEntry.operation("followup", "ENROLLMENT_PAUSE")
                        .objectType("ENROLLMENT")
                        .objectId(String.valueOf(enrollmentId))
                        .description(
                                "暂停计划内患者（A-FU-008）：计划 "
                                        + enrollment.planCode()
                                        + "，原因 "
                                        + normalized
                                        + "（暂停期间不创建任务、不发通知）")
                        .afterJson(
                                "{\"enrollmentId\":"
                                        + enrollmentId
                                        + ",\"planCode\":\""
                                        + enrollment.planCode()
                                        + "\",\"status\":\"PAUSED\",\"reason\":\""
                                        + normalized
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new EnrollmentActionView(
                String.valueOf(enrollmentId),
                String.valueOf(enrollment.patientId()),
                String.valueOf(planId),
                "PAUSED",
                0,
                0,
                0);
    }

    /**
     * 恢复单个患者（A-FU-008 / A-PAT-011「恢复后从下一个窗口起继续按需创建任务」）：已暂停 → 在组，落暂停区间止点；
     * 同一事务内补建暂停期间的次数——窗口已结束的补为「暂停期间未执行」（PAUSED_SKIPPED，由医生选择补做或关闭，A 的 Q32），窗口未结束的当期任务正常创建。患者已死亡 /
     * 已终止时不能恢复（A-FU-007 之后不再随访）。
     */
    @Transactional
    public EnrollmentActionView resume(long planId, long enrollmentId) {
        PlanEnrollmentMapper.EnrollmentStateRow enrollment =
                requireOwnEnrollment(planId, enrollmentId);
        if (!"PAUSED".equals(enrollment.status())) {
            denied(
                    "ENROLLMENT_RESUME",
                    enrollment,
                    "恢复",
                    "ENROLLMENT_STATUS_" + enrollment.status());
            throw new ApiException(409, 40901, "只有已暂停的入组才能恢复");
        }
        if (PATIENT_DEAD.equals(enrollment.patientFollowupStatus())
                || PATIENT_TERMINATED.equals(enrollment.patientFollowupStatus())) {
            denied(
                    "ENROLLMENT_RESUME",
                    enrollment,
                    "恢复",
                    "PATIENT_" + enrollment.patientFollowupStatus());
            throw new ApiException(409, 40901, "患者已死亡或已终止随访，不能恢复（A-FU-007 之后不再创建任务）");
        }
        if (mapper.resumeEnrollment(enrollmentId) == 0) {
            throw new ApiException(409, 40901, "入组状态已变化，请刷新后重试");
        }
        FollowupTaskCreationService.ResumeInitSummary compensation =
                enrollment.pauseFrom() == null
                        ? new FollowupTaskCreationService.ResumeInitSummary(0, 0)
                        : taskCreation.initializeForResumedEnrollment(
                                enrollmentId,
                                enrollment.patientId(),
                                enrollment.planId(),
                                enrollment.mode(),
                                enrollment.pauseFrom());
        // 患者级回「随访中」：该患者已无其他已暂停入组且当前为「已暂停」时解除
        if (mapper.countOtherPaused(enrollment.patientId(), enrollmentId) == 0
                && PATIENT_PAUSED.equals(enrollment.patientFollowupStatus())) {
            patientStatus.transition(
                    enrollment.patientId(),
                    PATIENT_ACTIVE,
                    "恢复随访（计划 " + enrollment.planCode() + "）",
                    STATUS_SOURCE_PLAN,
                    PATIENT_PAUSED,
                    currentUserId());
        }
        audit.append(
                AuditEntry.operation("followup", "ENROLLMENT_RESUME")
                        .objectType("ENROLLMENT")
                        .objectId(String.valueOf(enrollmentId))
                        .description(
                                "恢复计划内患者（A-FU-008）：计划 "
                                        + enrollment.planCode()
                                        + "，从下一个窗口起继续按需创建任务；补建暂停期间未执行 "
                                        + compensation.pausedSkipped()
                                        + " 条、当期 "
                                        + compensation.normal()
                                        + " 条")
                        .afterJson(
                                "{\"enrollmentId\":"
                                        + enrollmentId
                                        + ",\"planCode\":\""
                                        + enrollment.planCode()
                                        + "\",\"status\":\"ACTIVE\",\"pausedSkippedTasks\":"
                                        + compensation.pausedSkipped()
                                        + ",\"currentTasks\":"
                                        + compensation.normal()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new EnrollmentActionView(
                String.valueOf(enrollmentId),
                String.valueOf(enrollment.patientId()),
                String.valueOf(planId),
                "ACTIVE",
                compensation.pausedSkipped(),
                compensation.normal(),
                0);
    }

    /**
     * 移出单个患者（A-FU-008 / A-PAT-011，须填原因：拒绝随访 / 外地随访 / 其他）：在组（含已暂停）→ 已移除； 该入组全部未完成任务自动终止（终止原因 =
     * 移除原因），之后不再创建任务（调度只遍历在组入组）。 无其他在组入组时患者级状态同步「已终止」。
     */
    @Transactional
    public EnrollmentActionView remove(long planId, long enrollmentId, String reason) {
        PlanEnrollmentMapper.EnrollmentStateRow enrollment =
                requireOwnEnrollment(planId, enrollmentId);
        String normalized = requireReason(reason, "移出");
        if (!"ACTIVE".equals(enrollment.status()) && !"PAUSED".equals(enrollment.status())) {
            denied(
                    "ENROLLMENT_REMOVE",
                    enrollment,
                    "移出",
                    "ENROLLMENT_STATUS_" + enrollment.status());
            throw new ApiException(409, 40901, "该入组已移出，无需重复操作");
        }
        if (mapper.removeEnrollment(enrollmentId, normalized) == 0) {
            throw new ApiException(409, 40901, "入组状态已变化，请刷新后重试");
        }
        int terminated =
                taskTerminations.terminateUnfinishedByEnrollment(
                        enrollmentId, normalized, currentUserId());
        // 患者级「已终止」：无其他在组入组且患者仍在随访中 / 已暂停时落（已死亡 / 失访等更强状态不覆盖）
        if (mapper.countOtherEnrolled(enrollment.patientId(), enrollmentId) == 0
                && (PATIENT_ACTIVE.equals(enrollment.patientFollowupStatus())
                        || PATIENT_PAUSED.equals(enrollment.patientFollowupStatus()))) {
            patientStatus.transition(
                    enrollment.patientId(),
                    PATIENT_TERMINATED,
                    normalized,
                    STATUS_SOURCE_PLAN,
                    enrollment.patientFollowupStatus(),
                    currentUserId());
        }
        audit.append(
                AuditEntry.operation("followup", "ENROLLMENT_REMOVE")
                        .objectType("ENROLLMENT")
                        .objectId(String.valueOf(enrollmentId))
                        .description(
                                "移出计划内患者（A-FU-008 / A-PAT-011）：计划 "
                                        + enrollment.planCode()
                                        + "，原因 "
                                        + normalized
                                        + "，终止未完成任务 "
                                        + terminated
                                        + " 条")
                        .afterJson(
                                "{\"enrollmentId\":"
                                        + enrollmentId
                                        + ",\"planCode\":\""
                                        + enrollment.planCode()
                                        + "\",\"status\":\"REMOVED\",\"reason\":\""
                                        + normalized
                                        + "\",\"terminatedTasks\":"
                                        + terminated
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new EnrollmentActionView(
                String.valueOf(enrollmentId),
                String.valueOf(enrollment.patientId()),
                String.valueOf(planId),
                "REMOVED",
                0,
                0,
                terminated);
    }

    /** 操作对象定位：入组存在、属于该计划，且患者在操作者数据范围内（越范围 40400，不暴露存在）。 */
    private PlanEnrollmentMapper.EnrollmentStateRow requireOwnEnrollment(
            long planId, long enrollmentId) {
        PlanEnrollmentMapper.EnrollmentStateRow enrollment =
                mapper.findEnrollmentState(enrollmentId);
        if (enrollment == null || enrollment.planId() != planId) {
            throw new ApiException(404, 40400, "入组不存在或不属于该计划");
        }
        if (patientScope.patientIdInScope(enrollment.patientId()) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        return enrollment;
    }

    /** 原因必填（A-PAT-011：暂停 / 移出须选原因），≤255 字符。 */
    private static String requireReason(String reason, String verb) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "请填写" + verb + "原因（A-PAT-011）");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, verb + "原因最多 255 个字符");
        }
        return trimmed;
    }

    /** 被拒的状态流转只写 DENIED 审计（没有发生改动，不落计划变更记录）。 */
    private void denied(
            String action,
            PlanEnrollmentMapper.EnrollmentStateRow enrollment,
            String verb,
            String reasonCode) {
        audit.append(
                AuditEntry.operation("followup", action)
                        .objectType("ENROLLMENT")
                        .objectId(String.valueOf(enrollment.id()))
                        .description(
                                verb
                                        + "计划内患者被阻止：计划 "
                                        + enrollment.planCode()
                                        + "（当前入组状态 "
                                        + enrollment.status()
                                        + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40901)
                        .reason(reasonCode)
                        .build());
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

    private static Long currentUserId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
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
