package com.crdp.followup.plan.app;

import com.crdp.followup.implant.domain.ImplantCodes;
import com.crdp.followup.plan.infra.mapper.DevicePlanMappingMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper.SwitchableEnrollmentRow;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.app.FollowupTaskTerminator;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划切换规则与触发事件（E21-T06 / C-FU-007、Q01、Q03；tasks.csv 验收「切换表每行一条用例通过」——C 规格第 5 章「计划切换触发事件」表十行，逐行用例
 * SW-1…SW-10 见 docs/reviews/hospital-c-acceptance-review.md §3.3.1）。
 *
 * <p>切换的统一落法（换计划的行：首次植入换算路径除外）——<b>原入组结束 + 未完成任务自动终止并记录原因 + 按新器械类型映射重新入组</b>：
 *
 * <ul>
 *   <li>原入组结束 = {@code fu_enrollment.status} → SWITCHED（V88 终态，active_key 置空释放 uk，同族可重新入组）， {@code
 *       switch_reason} 记原因；不落患者级「已终止」（A-FU-008 移出才落——切换后患者仍在随访，取出后再植入须能重新入组）；
 *   <li>未完成任务（待随访 / 进行中 / 逾期 / 期间未执行）置 TERMINATED，终止原因 = 切换原因（C-FU-007「未完成的旧任务自动终止并记录原因」）；
 *       已完成与已失访保留（历史事实，与 E14-T06 终止保留同口径）；
 *   <li>重新入组经 {@link PlanEnrollmentService#enroll(long, long, String, LocalDate)}：起算快照 = 新植入日期（SW-2
 *       / SW-3「从升级日期起算」、SW-4 「按更换日期重新走术后节点」；SW-6 器械类型更正例外——仍按原植入日期），入组即建任务沿用 E14-T05（C 计划
 *       passed_window_action = SKIP， 只为今天之后开始的窗口建任务，Q14）；
 *   <li>触发事件与计划变化（十行摘要）：首次植入 → 按器械类型入组（SW-1，E21-T05 自动入组路径，本服务不参与）；升级为 ICD / CRT-D → 转入 ICD/CRT-D
 *       计划（SW-2）；升级为 CRT-P / 普通类型 → 转入或留在普通计划（SW-3，CL-02 假设三：CRT-P 归普通计划）；电池更换（同类型）→ 计划类型不变、ERI
 *       期循环结束、从更换日期重走术后节点（SW-4，CL-02 假设二）；电极修正 → 不切换（SW-5，E21-T02 既有口径）；器械类型更正（录入纠错）→
 *       按新类型重新入组、未开始任务按新计划重算、起算仍按原植入日期（SW-6）；取出且未再植入 → 结束入组、不再创建任务（SW-9）。
 * </ul>
 *
 * <p><b>ERI 期切换不换入组行</b>（切换表「接近 ERI / ERI 标记解除」两行，C-FU-006）：随访记录提交（或修改已提交记录）时 ERI 状态（C_PROGRAM_FU
 * 稳定键 f323，OQ-130① 契约：0 正常 / 1 接近ERI / 2 已达ERI(EOI)）为接近 / 已达，或预计电池寿命（f324）≤ 1 年 → 在组入组 {@code mode}
 * 翻 ERI、{@code eri_since} = 该次随访日期（「否，从该次随访日期起算」），常规未完成任务终止（循环接管）；后续 ERI 状态更正为正常且电池寿命不再偏低 → {@code
 * mode} 回 NORMAL、{@code eri_since} 清空，恢复原计划的常规间隔（SW-7 / SW-8）。ERI 期循环任务的创建（每 1–3 个月、从 eri_since
 * 起算、更换器械后结束）已随 <b>E21-T07</b> 落地：TaskCreationPlanner 滚动排期（LoopBasis，基准 = max（{@code
 * last_visit_date} 快照，已排最新一次计划日期）+ 节点偏移）与每日调度消费；「同时满足多项时按 医生指定 → ERI 期循环 → 计划默认 取值」中医生指定的窗口展开随
 * E21-T08。
 *
 * <p><b>医生指定下次随访时间</b>（切换表末行，C-FU-005 / Q05）：只登记 {@code
 * doctor_next_date}（每次提交覆盖，只影响下一次任务），<b>不改变计划类型</b>（SW-10）； 早于今天的日期忽略（补录的历史记录「只保存，不再生成任务」，C 规格 §5
 * 业务规则）；窗口围绕展开与任务创建消费随 E21-T08。
 *
 * <p>「其他（OTHER）」植入类型：C 规格关键字段表「由医生选择是否重新起算」——选择载体未定，本服务不切换（沿用 E21-T05 行为），见 OQ-137。
 * 触发入口（ImplantService 保存 / 编辑 / 取出、RecordEntryService 提交 / 修改）已做患者数据范围校验；本服务写 PLAN_SWITCH
 * 操作审计（只追加）。
 */
@Service
public class PlanSwitchService {

    private static final Logger log = LoggerFactory.getLogger(PlanSwitchService.class);

    /** C_PROGRAM_FU 稳定键契约（OQ-130①，附录 A 随访组 #n → f3nn）：ERI 状态（单选 0/1/2）。 */
    static final String KEY_ERI_STATUS = "f323";

    /** C_PROGRAM_FU 稳定键：预计电池寿命（年，≤ 1 视同接近 ERI，切换表「接近 ERI」行）。 */
    static final String KEY_BATTERY_LIFE = "f324";

    /** C_PROGRAM_FU 稳定键：下次随访时间（日期，C-FU-005 医生指定，切换表末行）。 */
    static final String KEY_NEXT_VISIT_DATE = "f333";

    /** ERI 状态选项编码（字典 c_eri_status，V82 / OQ-130①）：0 正常 / 1 接近ERI / 2 已达ERI(EOI)。 */
    static final String ERI_NORMAL = "0";

    static final String ERI_NEAR = "1";

    static final String ERI_EOI = "2";

    /** 预计电池寿命的接近 ERI 阈值（年，切换表「预计电池寿命 ≤ 1 年」）。 */
    private static final double BATTERY_LIFE_THRESHOLD = 1d;

    private final PlanEnrollmentMapper enrollmentMapper;

    private final DevicePlanMappingMapper mappingMapper;

    private final PlanMapper planMapper;

    private final PlanEnrollmentService enrollments;

    private final FollowupTaskTerminator taskTerminations;

    private final AuditLogger audit;

    public PlanSwitchService(
            PlanEnrollmentMapper enrollmentMapper,
            DevicePlanMappingMapper mappingMapper,
            PlanMapper planMapper,
            PlanEnrollmentService enrollments,
            FollowupTaskTerminator taskTerminations,
            AuditLogger audit) {
        this.enrollmentMapper = enrollmentMapper;
        this.mappingMapper = mappingMapper;
        this.planMapper = planMapper;
        this.enrollments = enrollments;
        this.taskTerminations = taskTerminations;
        this.audit = audit;
    }

    /** 植入侧切换结果（switched = false 时调用方回落 E21-T05 自动入组路径：SW-1 / 无在组入组 / 不触发类型）。 */
    public record ImplantSwitchView(
            long patientId,
            boolean switched,
            int endedEnrollments,
            int terminatedTasks,
            boolean enrolled,
            String toPlanCode) {}

    /** 记录侧切换结果（ERI 翻模式 / 医生指定日期登记；两者可同次发生）。 */
    public record RecordSwitchView(
            long patientId, int eriSwitched, int eriRestored, boolean doctorNextDateSet) {}

    // ---------------------------------------------------------------- 植入记录新增（SW-2 / SW-3 /
    // SW-4；SW-5 不触发）

    /**
     * 保存（新植 / 接替在用）植入记录后的切换判定：植入类型为升级 / 更换且患者有在组入组时—— 结束原入组（SWITCHED + 终止未完成任务）并按新器械类型映射 重新入组（起算快照
     * = 新植入日期）；首次植入（SW-1）、电极修正（SW-5）、其他（OQ-137）与无在组入组时返回 switched = false， 由调用方回落 E21-T05
     * 自动入组路径。新类型未映射 / 映射族无已发布版本时不结束原入组（旧随访不中断，配置补齐后再次保存触发）。
     */
    @Transactional
    public ImplantSwitchView onActiveImplantAdded(
            long patientId,
            long implantId,
            String implantType,
            String deviceType,
            LocalDate implantDate) {
        if (!ImplantCodes.TYPE_UPGRADE.equals(implantType)
                && !ImplantCodes.TYPE_REPLACE.equals(implantType)) {
            // SW-1 首次植入走 E21-T05 自动入组；SW-5 电极修正不切换；OTHER 由医生选择（OQ-137）
            return notSwitched(patientId);
        }
        List<SwitchableEnrollmentRow> active =
                enrollmentMapper.findSwitchableEnrollments(patientId);
        if (active.isEmpty()) {
            return notSwitched(patientId); // 无在组入组：直接按新类型入组（E21-T05 路径）
        }
        TargetPlan target = resolveTarget(deviceType);
        if (target == null) {
            log.info(
                    "计划切换跳过：新器械类型 {} 未映射或映射族无已发布版本，保留原入组（C-FU-007）patient={}",
                    deviceType,
                    patientId);
            return notSwitched(patientId);
        }
        String reason =
                "计划切换："
                        + ImplantCodes.IMPLANT_TYPES.get(implantType)
                        + "（"
                        + deviceType
                        + "，植入记录 "
                        + implantId
                        + "，"
                        + implantDate
                        + "）";
        return endAndReenroll(patientId, active, reason, target, implantDate);
    }

    // ---------------------------------------------------------------- 器械类型更正（SW-6）

    /**
     * 编辑植入记录更正起搏器类型（录入纠错）：按新类型重新入组，起算快照 <b>仍按原植入日期</b>（切换表「否，仍按原植入日期」）；
     * 未开始任务随重新入组按新计划重算（原入组终止）、已完成记录不变。新类型未映射 / 族未发布时不结束原入组（同 {@link #onActiveImplantAdded} 防御口径）。
     */
    @Transactional
    public ImplantSwitchView onDeviceTypeCorrected(
            long patientId,
            long implantId,
            String oldDeviceType,
            String newDeviceType,
            LocalDate implantDate) {
        List<SwitchableEnrollmentRow> active =
                enrollmentMapper.findSwitchableEnrollments(patientId);
        if (active.isEmpty()) {
            return notSwitched(patientId);
        }
        TargetPlan target = resolveTarget(newDeviceType);
        if (target == null) {
            log.info(
                    "计划切换跳过：更正类型 {} 未映射或映射族无已发布版本，保留原入组（C-FU-007 / SW-6）patient={}",
                    newDeviceType,
                    patientId);
            return notSwitched(patientId);
        }
        String reason =
                "计划切换：器械类型更正（"
                        + oldDeviceType
                        + " → "
                        + newDeviceType
                        + "，植入记录 "
                        + implantId
                        + "；起算仍按原植入日期 "
                        + implantDate
                        + "）";
        return endAndReenroll(patientId, active, reason, target, implantDate);
    }

    // ---------------------------------------------------------------- 取出（SW-9）

    /**
     * 器械取出且未再植入：结束全部在组（含已暂停）入组并终止未完成任务，不再创建任务（调度只遍历 ACTIVE）； 不重新入组（再植入走登记 /
     * 新建，届时按类型自动入组）。不落患者级「已终止」——患者仍在册，再植入须能重新入组。
     */
    @Transactional
    public ImplantSwitchView onImplantExplanted(long patientId, long implantId, LocalDate date) {
        List<SwitchableEnrollmentRow> active =
                enrollmentMapper.findSwitchableEnrollments(patientId);
        if (active.isEmpty()) {
            return notSwitched(patientId);
        }
        String reason = "器械取出（" + date + "，植入记录 " + implantId + "）：结束随访计划，不再创建任务";
        int ended = 0;
        int terminated = 0;
        for (SwitchableEnrollmentRow enrollment : active) {
            if (enrollmentMapper.switchEndEnrollment(enrollment.id(), reason) == 0) {
                continue; // 并发已结束：跳过
            }
            ended++;
            terminated +=
                    taskTerminations.terminateUnfinishedByEnrollment(
                            enrollment.id(), reason, currentUserId());
        }
        audit.append(
                AuditEntry.operation("followup", "PLAN_SWITCH")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "计划切换（取出，SW-9）：结束入组 "
                                        + ended
                                        + " 条、终止未完成任务 "
                                        + terminated
                                        + " 条，不再创建任务（C-FU-007）")
                        .afterJson(
                                "{\"patientId\":"
                                        + patientId
                                        + ",\"trigger\":\"EXPLANT\",\"ended\":"
                                        + ended
                                        + ",\"terminatedTasks\":"
                                        + terminated
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("计划切换（取出）完成 patient={} ended={} terminated={}", patientId, ended, terminated);
        return new ImplantSwitchView(patientId, true, ended, terminated, false, null);
    }

    // ---------------------------------------------------------------- 随访记录提交 / 修改（SW-7 / SW-8 /
    // SW-10）

    /**
     * 随访记录提交（或修改已提交记录）后的 ERI 与医生指定判定：
     *
     * <ul>
     *   <li>SW-7 接近 ERI：f323 ∈ {接近, 已达} 或 f324 ≤ 1 → 在组且 mode = NORMAL 的入组翻 ERI（eri_since =
     *       visitDate）并终止未完成任务；
     *   <li>SW-8 ERI 解除：f323 = 正常 且电池寿命不再偏低（f324 缺失或 &gt; 1，OQ-137）→ mode = ERI 的入组回
     *       NORMAL（eri_since 清空）、 终止 ERI 期未完成任务（恢复原计划的常规间隔由调度按原起算重建）；
     *   <li>SW-10 医生指定：f333 为今天或未来日期时登记 doctor_next_date（计划类型不变）；过去日期忽略（补录只保存）。
     * </ul>
     *
     * @param visitDate 该次随访的有效随访日期（fu_record.visit_date ?? 表单随访日期，入口侧解析）
     */
    @Transactional
    public RecordSwitchView onRecordSubmitted(
            long patientId, long recordId, LocalDate visitDate, Map<String, Object> data) {
        List<SwitchableEnrollmentRow> active =
                enrollmentMapper.findSwitchableEnrollments(patientId);
        if (active.isEmpty()) {
            return new RecordSwitchView(patientId, 0, 0, false);
        }
        LocalDate effectiveVisit = visitDate != null ? visitDate : LocalDate.now();
        int switched = 0;
        int restored = 0;
        if (eriMarked(data)) {
            String reason =
                    "切换 ERI 期循环（C-FU-006）：ERI 状态为接近 / 已达或预计电池寿命 ≤ "
                            + (int) BATTERY_LIFE_THRESHOLD
                            + " 年（记录 "
                            + recordId
                            + "，随访日期 "
                            + effectiveVisit
                            + "）";
            for (SwitchableEnrollmentRow enrollment : active) {
                if (!PlanEnrollmentService.MODE_NORMAL.equals(enrollment.mode())
                        || enrollmentMapper.switchEnrollmentMode(
                                        enrollment.id(), "ERI", effectiveVisit)
                                == 0) {
                    continue; // 已在 ERI 期（幂等）或并发已结束
                }
                switched++;
                taskTerminations.terminateUnfinishedByEnrollment(
                        enrollment.id(), reason, currentUserId());
            }
            if (switched > 0) {
                auditEri(patientId, recordId, "ERI", reason, switched);
            }
        } else if (eriCleared(data)) {
            String reason = "ERI 标记解除（记录 " + recordId + "，随访日期 " + effectiveVisit + "）：恢复原计划的常规间隔";
            for (SwitchableEnrollmentRow enrollment : active) {
                if (!"ERI".equals(enrollment.mode())
                        || enrollmentMapper.switchEnrollmentMode(
                                        enrollment.id(), PlanEnrollmentService.MODE_NORMAL, null)
                                == 0) {
                    continue; // 常规期（幂等）或并发已结束
                }
                restored++;
                taskTerminations.terminateUnfinishedByEnrollment(
                        enrollment.id(), reason, currentUserId());
            }
            if (restored > 0) {
                auditEri(patientId, recordId, "NORMAL", reason, restored);
            }
        }
        boolean doctorSet = false;
        LocalDate doctorDate = doctorNextDate(data);
        if (doctorDate != null && !doctorDate.isBefore(LocalDate.now())) {
            enrollmentMapper.setDoctorNextDate(patientId, doctorDate);
            doctorSet = true;
        }
        // 上次实际随访日期快照单调推进（E21-T07 / C-FU-006）：滚动循环节点（常规 / ERI 期）的排期基准
        // fu_enrollment.last_visit_date（V93）——ERI 期第一次循环 = eri_since 那次随访 + 间隔，此后每次随访滚动一次
        enrollmentMapper.advanceLastVisitDate(patientId, effectiveVisit);
        if (switched == 0 && restored == 0 && doctorSet) {
            log.info(
                    "医生指定下次随访日期（SW-10，计划类型不变）：patient={} date={}（窗口展开随 E21-T08）",
                    patientId,
                    doctorDate);
        }
        return new RecordSwitchView(patientId, switched, restored, doctorSet);
    }

    // ---------------------------------------------------------------- 内部

    /** 重新入组目标（映射行解析到的该族当前 PUBLISHED 版本；SWITCHED 结束后按它 enroll）。 */
    private record TargetPlan(long planId, String planCode) {}

    /** 未发生切换（调用方回落 E21-T05 自动入组路径）。 */
    private static ImplantSwitchView notSwitched(long patientId) {
        return new ImplantSwitchView(patientId, false, 0, 0, false, null);
    }

    private TargetPlan resolveTarget(String deviceType) {
        DevicePlanMappingMapper.MappingRow mapping = mappingMapper.findByDeviceType(deviceType);
        if (mapping == null || !mapping.enabled()) {
            return null;
        }
        PlanMapper.PlanRow plan = planMapper.findPublishedByCode(mapping.planCode());
        return plan == null ? null : new TargetPlan(plan.id(), plan.code());
    }

    /** 换计划各行共用落法：结束全部在组入组（SWITCHED + 终止未完成任务）→ 按目标族重新入组（起算快照 = startDate）。 */
    private ImplantSwitchView endAndReenroll(
            long patientId,
            List<SwitchableEnrollmentRow> active,
            String reason,
            TargetPlan target,
            LocalDate startDate) {
        int ended = 0;
        int terminated = 0;
        String fromPlans = plansOf(active);
        for (SwitchableEnrollmentRow enrollment : active) {
            if (enrollmentMapper.switchEndEnrollment(enrollment.id(), reason) == 0) {
                continue; // 并发已结束：跳过
            }
            ended++;
            terminated +=
                    taskTerminations.terminateUnfinishedByEnrollment(
                            enrollment.id(), reason, currentUserId());
        }
        if (ended == 0) {
            // 并发下全部已结束：无切换事实，回落自动入组路径（可能 ALREADY_IN_PLAN 跳过）
            return notSwitched(patientId);
        }
        boolean enrolled = false;
        try {
            enrollments.enroll(
                    patientId, target.planId(), PlanEnrollmentService.MODE_NORMAL, startDate);
            enrolled = true;
        } catch (ApiException skip) {
            // 患者已死亡 / 已终止、并发已在组等业务性不可入组：原入组已结束的事实保留，不回滚植入保存（与 E21-T05 跳过口径同向）
            log.info(
                    "计划切换后重新入组跳过：{}（patient={} plan={}）",
                    skip.getMessage(),
                    patientId,
                    target.planCode());
        }
        audit.append(
                AuditEntry.operation("followup", "PLAN_SWITCH")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "计划切换（C-FU-007）："
                                        + fromPlans
                                        + " → "
                                        + (enrolled ? target.planCode() : "（重新入组跳过）")
                                        + "，结束入组 "
                                        + ended
                                        + " 条、终止未完成任务 "
                                        + terminated
                                        + " 条，起算日 "
                                        + startDate)
                        .afterJson(
                                "{\"patientId\":"
                                        + patientId
                                        + ",\"from\":\""
                                        + fromPlans
                                        + "\",\"to\":\""
                                        + target.planCode()
                                        + "\",\"enrolled\":"
                                        + enrolled
                                        + ",\"startDate\":\""
                                        + startDate
                                        + "\",\"ended\":"
                                        + ended
                                        + ",\"terminatedTasks\":"
                                        + terminated
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "计划切换完成 patient={} {} -> {} ended={} terminated={} enrolled={}",
                patientId,
                fromPlans,
                target.planCode(),
                ended,
                terminated,
                enrolled);
        return new ImplantSwitchView(
                patientId, true, ended, terminated, enrolled, target.planCode());
    }

    private void auditEri(long patientId, long recordId, String toMode, String reason, int count) {
        audit.append(
                AuditEntry.operation("followup", "PLAN_SWITCH")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "计划切换（ERI，mode → "
                                        + toMode
                                        + "）：入组 "
                                        + count
                                        + " 条，终止未完成任务并记录原因（C-FU-006）——"
                                        + reason)
                        .afterJson(
                                "{\"patientId\":"
                                        + patientId
                                        + ",\"trigger\":\"ERI\",\"toMode\":\""
                                        + toMode
                                        + "\",\"recordId\":"
                                        + recordId
                                        + ",\"switched\":"
                                        + count
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
    }

    /** SW-7 命中：ERI 状态为接近 / 已达，或预计电池寿命 ≤ 1 年（值缺失不命中）。 */
    private static boolean eriMarked(Map<String, Object> data) {
        String eriStatus = codeOf(data.get(KEY_ERI_STATUS));
        if (ERI_NEAR.equals(eriStatus) || ERI_EOI.equals(eriStatus)) {
            return true;
        }
        Double batteryLife = numberOrNull(data.get(KEY_BATTERY_LIFE));
        return batteryLife != null && batteryLife <= BATTERY_LIFE_THRESHOLD;
    }

    /** SW-8 解除：ERI 状态更正为正常且预计电池寿命不再偏低（缺失视同不偏低，OQ-137）。 */
    private static boolean eriCleared(Map<String, Object> data) {
        if (!ERI_NORMAL.equals(codeOf(data.get(KEY_ERI_STATUS)))) {
            return false;
        }
        Double batteryLife = numberOrNull(data.get(KEY_BATTERY_LIFE));
        return batteryLife == null || batteryLife > BATTERY_LIFE_THRESHOLD;
    }

    /** SW-10：下次随访时间（日期字符串；缺失 / 不成日期为 null，过去日期由调用侧忽略）。 */
    private static LocalDate doctorNextDate(Map<String, Object> data) {
        Object value = data.get(KEY_NEXT_VISIT_DATE);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String codeOf(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Double numberOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return null;
    }

    private static String plansOf(List<SwitchableEnrollmentRow> enrollments) {
        StringBuilder codes = new StringBuilder();
        for (SwitchableEnrollmentRow enrollment : enrollments) {
            if (codes.length() > 0) {
                codes.append(',');
            }
            codes.append(enrollment.planCode());
        }
        return codes.toString();
    }

    private static Long currentUserId() {
        return com.crdp.framework.security.CurrentSession.current()
                .map(principal -> principal.userId())
                .orElse(null);
    }
}
