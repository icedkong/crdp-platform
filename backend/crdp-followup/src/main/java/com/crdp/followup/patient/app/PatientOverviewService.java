package com.crdp.followup.patient.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.implant.app.ImplantGateway;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.patient.infra.mapper.PatientOverviewMapper;
import com.crdp.followup.plan.domain.EriEval;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 档案概览与患者时间轴用例（E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010；接口 docs/api/patient.md §2 GET summary、§7 GET
 * followup-timeline）。
 *
 * <p>概览口径（验收「概览显示基线、计划、最近随访」，OQ-21：规格「概览」页 = 顶部 PatientHeader）：
 *
 * <ul>
 *   <li>基线——基线资料完整度 + 登记实体列（最小必填集即基线数据，A-PAT-003）；分节补录、锁定与基线档案页签内容随 E13-T02；
 *   <li>计划——下次随访窗口（pt_patient_summary.next_*，E14 入组调度写入，写入前 null 由前端显示占位）；
 *   <li>最近随访——最近随访日期、生存状态、关键指标（EF 等）与终点事件（pt_patient_summary 派生列，E15 提交后重算写入）；
 *   <li>C 器械卡片与待录入扫描件数（E21-T22 / C-PAT-010）——当前器械、植入时长、ERI 标记（{@code implant} 开关； ERI 段另受 {@code
 *       eri_cycle}）与本患者待录入扫描件数（{@code scan_progress}）：三个开关关闭（A / B 租户）时 device 为 null、
 *       pendingScanCount 为 null，概览行为与 E21 前完全一致（AGENTS §5.7，验收「功能开关关闭时 A、B 不受影响」）。
 * </ul>
 *
 * <p>时间轴（A-PAT-013「按时间顺序展示出院、每年随访、计划外随访、事件、死亡」；A-FU-023 / C-FU-023）：数据源为出院登记（pt_patient）、状态变更事件
 * （pt_status_history，登记初始行不重复展示）与随访节点（E15-T07 起并入本入口：fu_task 任务节点按子计划次数聚合完成进度 x / y、末尾按入组追加
 * 「下次计划日期」计划中节点、无任务的历史补录记录单独标识）；计划外随访随 E15-T06、终点事件随 E17、C 器械事件随 E21。
 *
 * <p>姓名 / 病案号只输出脱敏值（AGENTS.md §5.3，明文走 E07-T05 专用接口）；越范围 / 不存在的患者一律 40400（§5.2，不暴露对象存在）。
 */
@Service
public class PatientOverviewService {

    /** 已终止口径（pages.md §4.10：死亡 / 停止 / 失访 → 头部「—（已终止）」，随访类操作不可用）。 */
    private static final Set<String> TERMINATED_STATUSES = Set.of("DEAD", "TERMINATED", "LOST");

    /** 头部操作与所需权限（patient.md §2 actions；停止随访 A/C 权限码差异见 OQ-15）。 */
    private static final String PERM_RECORD_EDIT = "followup:record:edit";

    private static final String PERM_SCAN_UPLOAD = "followup:scan:upload";

    private static final String PERM_PLAN_ENROLL = "followup:plan:enroll";

    private static final String PERM_PATIENT_EDIT = "followup:patient:edit";

    /** 转病区（A-WARD-005，E13-T08）：管理动作，不受随访状态限制（终止患者的病区归属仍可调整）。 */
    private static final String PERM_PATIENT_TRANSFER = "followup:patient:transfer";

    /** 植入记录（器械卡片）的功能开关（E21-T22 / C-PAT-010：关闭时概览无器械行，A / B 行为不变）。 */
    private static final String FEATURE_IMPLANT = "implant";

    /** ERI 期随访的功能开关（E21-T22：关闭时器械卡片不带 ERI 段，components.md §4）。 */
    private static final String FEATURE_ERI_CYCLE = "eri_cycle";

    /** 电子化状态 / 补录进度的功能开关（E21-T22：关闭时概览不下发待录入扫描件数）。 */
    private static final String FEATURE_SCAN_PROGRESS = "scan_progress";

    /**
     * 业务日期按北京时间（AGENTS.md §6）：时间轴「完成日期」从 completed_at（服务器墙钟）换算北京业务日， 与窗口 / 计划日期同口径——CI 容器是 UTC，直接
     * toLocalDate() 在北京时间跨日时段（每天 00:00–08:00 北京）会差一天。
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatientOverviewGateway gateway;
    private final PatientStatusGateway statusGateway;
    private final FollowupTaskQueryMapper taskQueryMapper;
    private final FollowupRecordMapper recordMapper;
    private final CryptoService crypto;
    private final TenantViewFieldGuard viewGuard;
    private final PatientOverviewMapper overviewMapper;
    private final ImplantGateway implantGateway;
    private final ImplantDictPort implantDicts;
    private final DashboardFeatureMapper features;

    public PatientOverviewService(
            PatientOverviewGateway gateway,
            PatientStatusGateway statusGateway,
            FollowupTaskQueryMapper taskQueryMapper,
            FollowupRecordMapper recordMapper,
            CryptoService crypto,
            TenantViewFieldGuard viewGuard,
            PatientOverviewMapper overviewMapper,
            ImplantGateway implantGateway,
            ImplantDictPort implantDicts,
            DashboardFeatureMapper features) {
        this.gateway = gateway;
        this.statusGateway = statusGateway;
        this.taskQueryMapper = taskQueryMapper;
        this.recordMapper = recordMapper;
        this.crypto = crypto;
        this.viewGuard = viewGuard;
        this.overviewMapper = overviewMapper;
        this.implantGateway = implantGateway;
        this.implantDicts = implantDicts;
        this.features = features;
    }

    // ---------------------------------------------------------------- DTO

    /** 下次随访（计划窗口；E14 写入前整体为 null）。label（如「第4年」）随计划版本数据由 E14 补充。 */
    public record NextVisitView(LocalDate planDate, LocalDate windowStart, LocalDate windowEnd) {}

    /**
     * 最近一次随访（A-PAT-004「最近一次随访关键指标：生存状态、EF、终点事件」；E15 写入前整体为 null）。
     *
     * @param keyIndicators A {"ef": 最近 EF, "medications": [当前用药]}；C 器械键随 E21
     * @param endpointEvents 键 = 终点事件字段编码（心梗、再住院等），值 = 最近发生日期
     */
    public record LatestFollowupView(
            LocalDate date,
            String survivalStatus,
            Map<String, Object> keyIndicators,
            Map<String, String> endpointEvents) {}

    /**
     * C 当前器械摘要（E21-T22 / C-PAT-010「顶部显示当前器械、植入时长、ERI 标记」，components.md §4 器械行）。
     *
     * <p>{@code implant} 开关关闭（A / B 租户）或无在用器械（取出后）时整体 null；{@code eriCycle} 关闭时 {@code eri} 为
     * null（前端只隐藏 ERI 段，器械行照常），否则为「正常 / 接近ERI / 已达ERI(EOI)」——取最近一条含 ERI 字段（f323 / f324）的 已提交随访记录按
     * {@link EriEval} 判定（与接近 ERI 卡片、计划切换 SW-7 / SW-8 同一谓词族；无此类记录视为正常）。
     */
    public record DeviceView(String typeLabel, String implantDuration, String eri) {}

    /** 概览（PatientHeader 数据，components.md §4 / patient.md §2）：name / patientNo 只含脱敏值。 */
    public record PatientOverviewView(
            String id,
            String name,
            String patientNo,
            String legacyNo,
            String visitNo,
            String gender,
            Integer age,
            String status,
            boolean terminated,
            Long wardId,
            String wardName,
            Long deptId,
            String deptName,
            String responsibleName,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            Integer dischargeStatus,
            String patientType,
            Integer baselineCompleteness,
            BigDecimal followupCompleteness,
            NextVisitView nextVisit,
            LatestFollowupView latestFollowup,
            DeviceView device,
            Integer pendingScanCount,
            List<String> actions) {}

    /**
     * 时间轴节点（按时间顺序，旧 → 新）。
     *
     * @param kind DISCHARGE 出院 / STATUS 状态变更（含死亡）/ FOLLOWUP 每年随访（任务节点，E15-T07；REMINDER 站内提醒任务 随
     *     E15-T06 同为任务节点、note 标「无需填表」）/ BACKFILL 历史补录（无任务 的补录记录，A-FU-014、C-FU-013）/ UNPLANNED
     *     计划外随访（无任务记录按 source 分流，E15-T06 / C-FU-022）/ PLANNED 下次计划日期（「计划中 / 待开始」，实时计算不落库，
     *     A-FU-001）；EVENT 终点事件与 C 器械事件随 E17 / E21
     * @param label 数据驱动的节点名（DISCHARGE「出院」；STATUS / PLANNED 为空，由前端映射文案；FOLLOWUP =
     *     节点名；BACKFILL「历史补录」、UNPLANNED「计划外随访」）
     * @param status STATUS = 患者随访状态码（components.md §2 patient 域）；FOLLOWUP = 任务状态（PENDING /
     *     IN_PROGRESS / COMPLETED / OVERDUE / LOST_CANDIDATE / LOST / TERMINATED /
     *     SKIPPED_SUSPENDED）；BACKFILL / UNPLANNED = COMPLETED（已提交） 或 IN_PROGRESS（草稿未提交）；PLANNED =
     *     PLANNED（前端映射「计划中」）
     * @param planDate FOLLOWUP / PLANNED 的计划日期；windowStart / windowEnd 窗口起止；doneDate
     *     完成日期；progressDone / progressTotal 该子计划次数的完成进度 x / y（A-FU-023「完成进度 x/y」——主任务口径，跟进任务不单列）
     * @param canOpen 节点可否进入录入 / 查看（计划中 false；任务节点 = FORM 任务未完成或已关联记录）
     */
    public record TimelineEntryView(
            String kind,
            LocalDate date,
            String label,
            String status,
            String note,
            String recordId,
            String taskId,
            String taskType,
            String completeKind,
            String createType,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate doneDate,
            Integer progressDone,
            Integer progressTotal,
            Boolean canOpen,
            Integer highlightCount) {

        /** E13-T05 兼容构造（出院 / 状态事件不带随访列）。 */
        TimelineEntryView(String kind, LocalDate date, String label, String status, String note) {
            this(
                    kind, date, label, status, note, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
    }

    /** 时间轴响应。 */
    public record TimelineView(String patientId, List<TimelineEntryView> items) {}

    // ---------------------------------------------------------------- 概览（A-PAT-004、C-PAT-010）

    /** 患者概览：越范围 / 不存在 40400（不暴露对象存在）。 */
    public PatientOverviewView summaryOf(long patientId) {
        PatientOverviewMapper.OverviewRow row = requireOverview(patientId);
        return toView(row);
    }

    // ----------------------------------------------------------------
    // 时间轴（A-PAT-013、A-FU-023、C-FU-023）

    /**
     * 患者时间轴：出院节点 + 状态变更事件 + 随访节点（任务 / 补录 / 下次计划日期），按时间顺序（旧 → 新，components.md §9 线框口径）。 E15-T07
     * 起在同一入口并入随访记录数据（patient.md §7）：任务节点按（入组 × 节点 × 第 N 次）聚合显示完成进度 x / y；末尾按入组追加
     * 「下次计划日期」节点（计划中，A-FU-001 实时计算——缓存由调度 / 起算日期重算维护）；无任务的历史补录记录单独成节点（补录标识）。
     */
    public TimelineView timelineOf(long patientId) {
        PatientOverviewMapper.OverviewRow row = requireOverview(patientId);
        List<TimelineEntryView> items = new ArrayList<>();
        if (row.dischargeDate() != null) {
            // C-PAT-001 最小必填集不含出院日期：无出院日期不出该节点
            items.add(new TimelineEntryView("DISCHARGE", row.dischargeDate(), "出院", null, null));
        }
        for (var history : statusGateway.historyOf(patientId)) {
            // 登记初始行（source=REGISTRATION，登记即随访中）与出院节点同源，不重复展示
            if ("REGISTRATION".equals(history.source())) {
                continue;
            }
            items.add(
                    new TimelineEntryView(
                            "STATUS",
                            history.effectiveDate(),
                            null,
                            history.status(),
                            history.reason()));
        }
        appendFollowupNodes(patientId, items);
        // 计划外随访（E15-T06）、终点事件（E17）与 C 器械事件（E21）在同一入口并入
        items.sort(
                Comparator.comparing(
                                TimelineEntryView::date,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(PatientOverviewService::kindRank));
        return new TimelineView(String.valueOf(patientId), List.copyOf(items));
    }

    /** 随访节点：任务（按子计划次数聚合进度）→ 无任务补录记录 → 各在组入组的「下次计划日期」。 */
    private void appendFollowupNodes(long patientId, List<TimelineEntryView> items) {
        List<FollowupTaskQueryMapper.TimelineTaskRow> tasks =
                taskQueryMapper.timelineTasks(patientId);
        // （入组 × 节点 × 第 N 次）一组 = 一个子计划次数（A-FU-023「完成进度 x/y」）：组内主任务数 = y、已完成数 = x
        Map<String, int[]> progress = new LinkedHashMap<>();
        for (FollowupTaskQueryMapper.TimelineTaskRow task : tasks) {
            int[] cell =
                    progress.computeIfAbsent(
                            task.enrollmentId() + ":" + task.nodeId() + ":" + task.occurrence(),
                            key -> new int[2]);
            cell[1]++;
            if ("COMPLETED".equals(task.status())) {
                cell[0]++;
            }
        }
        for (FollowupTaskQueryMapper.TimelineTaskRow task : tasks) {
            int[] cell =
                    progress.get(
                            task.enrollmentId() + ":" + task.nodeId() + ":" + task.occurrence());
            items.add(
                    new TimelineEntryView(
                            "FOLLOWUP",
                            task.planDate(),
                            task.nodeName(),
                            task.status(),
                            followupNote(task),
                            task.recordId() == null ? null : String.valueOf(task.recordId()),
                            String.valueOf(task.id()),
                            task.taskType(),
                            task.completeKind(),
                            task.createType(),
                            task.planDate(),
                            task.windowStart(),
                            task.windowEnd(),
                            beijingDoneDate(task.completedAt()),
                            cell[0],
                            cell[1],
                            canOpen(task),
                            null));
        }
        // 无任务记录（A-FU-014 / C-FU-013 历史补录；E15-T06 起含 C-FU-022 计划外随访——按 source 分流成节点）
        for (FollowupRecordMapper.RecordRowRaw record :
                recordMapper.findTasklessByPatient(patientId)) {
            boolean submitted = "SUBMITTED".equals(record.status());
            boolean unplanned = "UNPLANNED".equals(record.source());
            items.add(
                    new TimelineEntryView(
                            unplanned ? "UNPLANNED" : "BACKFILL",
                            record.visitDate(),
                            unplanned ? "计划外随访" : "历史补录",
                            submitted ? "COMPLETED" : "IN_PROGRESS",
                            unplanned ? unplannedNote(record) : backfillNote(record),
                            String.valueOf(record.id()),
                            null,
                            null,
                            null,
                            unplanned ? "UNPLANNED" : "BACKFILL",
                            null,
                            null,
                            null,
                            record.visitDate(),
                            submitted ? 1 : 0,
                            1,
                            true,
                            null));
        }
        // 各在组入组的「下次计划日期」（A-FU-001：未来年度只在时间轴上显示，计划中 / 待开始，不落库）
        for (FollowupTaskQueryMapper.TimelineEnrollmentRow enrollment :
                taskQueryMapper.timelineEnrollments(patientId)) {
            // 防御去重：缓存指向的次数已有任务（调度已建 / 起算重算后落库）时不重复出节点
            boolean alreadyCreated =
                    tasks.stream()
                            .anyMatch(
                                    task ->
                                            task.enrollmentId() == enrollment.id()
                                                    && task.planDate()
                                                            .equals(enrollment.nextPlanDate()));
            if (alreadyCreated) {
                continue;
            }
            items.add(
                    new TimelineEntryView(
                            "PLANNED",
                            enrollment.nextPlanDate(),
                            null,
                            "PLANNED",
                            "计划中，任务开启日才创建",
                            null,
                            null,
                            null,
                            null,
                            null,
                            enrollment.nextPlanDate(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null));
        }
    }

    /** 任务节点备注（components.md §9：补录 / 逾期完成 / 跟进 / 站内提醒等的展示口径由状态与标记派生）。 */
    private static String followupNote(FollowupTaskQueryMapper.TimelineTaskRow task) {
        if ("REMINDER".equals(task.taskType())) {
            return "站内提醒：无需填表，到点提醒责任人（A-FU-026）";
        }
        if ("COMPLETED".equals(task.status()) && "OVERDUE".equals(task.completeKind())) {
            return "窗口结束后完成，记逾期完成";
        }
        if ("COMPLETED".equals(task.status()) && "BEFORE_WINDOW".equals(task.completeKind())) {
            return "窗口开启前提交，记窗口前完成";
        }
        if ("SKIPPED_SUSPENDED".equals(task.status())) {
            return "期间未执行，待人工选择补做或关闭";
        }
        return null;
    }

    /** 计划外随访备注（E15-T06 / C-FU-022）：随访时间点为「其他」、不影响后续节点，不作对比对象（A-FU-027）。 */
    private static String unplannedNote(FollowupRecordMapper.RecordRowRaw record) {
        if (!"SUBMITTED".equals(record.status())) {
            return "计划外随访草稿（未提交），不影响后续节点";
        }
        return "计划外随访（随访时间点「其他」），不影响后续节点；不作对比对象";
    }

    /** 历史补录备注：「补录，随访日期 X，Y 录入」（C-FU-023 各次随访含补录、单独标识）。 */
    private static String backfillNote(FollowupRecordMapper.RecordRowRaw record) {
        String entered =
                record.createdAt() == null ? null : record.createdAt().toLocalDate().toString();
        if (!"SUBMITTED".equals(record.status())) {
            return "补录草稿（未提交）" + (entered == null ? "" : "，" + entered + " 录入");
        }
        return "补录"
                + (record.visitDate() == null ? "" : "，随访日期 " + record.visitDate())
                + (entered == null ? "" : "，" + entered + " 录入");
    }

    /** 任务节点可否进入录入 / 查看：FORM 任务未完成（可开始录入）或已关联记录（可查看）。 */
    private static boolean canOpen(FollowupTaskQueryMapper.TimelineTaskRow task) {
        if (task.recordId() != null) {
            return true;
        }
        return "FORM".equals(task.taskType())
                && ("PENDING".equals(task.status())
                        || "IN_PROGRESS".equals(task.status())
                        || "OVERDUE".equals(task.status())
                        || "LOST_CANDIDATE".equals(task.status())
                        || "SKIPPED_SUSPENDED".equals(task.status()));
    }

    // ---------------------------------------------------------------- 内部

    private PatientOverviewMapper.OverviewRow requireOverview(long patientId) {
        PatientOverviewMapper.OverviewRow row = gateway.findOverview(patientId);
        if (row == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        return row;
    }

    private PatientOverviewView toView(PatientOverviewMapper.OverviewRow row) {
        String name = decrypt(row.nameCipher());
        String mrn = decrypt(row.mrnCipher());
        boolean terminated = TERMINATED_STATUSES.contains(row.status());
        return new PatientOverviewView(
                String.valueOf(row.id()),
                viewGuard.apply(
                        "patient",
                        "name",
                        name == null ? "" : SensitiveValueMasker.mask("name", name)),
                viewGuard.apply(
                        "patient",
                        "medicalRecordNo",
                        mrn == null ? "" : SensitiveValueMasker.mask("medicalRecordNo", mrn)),
                row.legacyNo(),
                row.visitNo(),
                row.gender(),
                row.age(),
                row.status(),
                terminated,
                row.wardId(),
                row.wardName(),
                row.deptId(),
                row.deptName(),
                row.responsibleName(),
                row.surgeryDate(),
                row.admissionDate(),
                row.dischargeDate(),
                row.dischargeStatus(),
                row.patientType(),
                row.baselineCompleteness(),
                row.followupCompleteness(),
                nextVisitOf(row),
                latestFollowupOf(row),
                deviceOf(row.id()),
                pendingScanCountOf(row.id()),
                actionsOf(row.status(), terminated));
    }

    // ---------------------------------------------------------------- C 器械卡片与待录入扫描件数（E21-T22 /
    // C-PAT-010）

    /**
     * C 当前器械摘要（implant 开关关闭 → null，A / B 概览行为与 E21 前完全一致，AGENTS §5.7）。 器械行三段：类型显示名（业务字典
     * pacemaker_type，含停用项——历史值照常显示，C-FLD-009；未知编码回落原码）、植入时长（植入日 → 今天，北京业务日）、 ERI 标记 （eri_cycle
     * 开关关闭为 null）。无在用器械（取出后 / 未登记）不出器械行。
     */
    private DeviceView deviceOf(long patientId) {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_IMPLANT))) {
            return null;
        }
        ImplantRow active = implantGateway.activeImplantOf(patientId);
        if (active == null) {
            return null;
        }
        String eri =
                Boolean.TRUE.equals(features.findEnabled(FEATURE_ERI_CYCLE))
                        ? eriLabelOf(patientId)
                        : null;
        return new DeviceView(
                deviceTypeLabel(active.deviceType()),
                implantDurationText(active.implantDate(), LocalDate.now(BUSINESS_ZONE)),
                eri);
    }

    /** 患者待录入扫描件数（scan_progress 开关关闭 → null，概览不显示该段；A 租户行 digitize_status 恒 NULL 计数恒 0）。 */
    private Integer pendingScanCountOf(long patientId) {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_SCAN_PROGRESS))) {
            return null;
        }
        Long count = overviewMapper.countPendingDigitizeReports(patientId);
        return count == null ? 0 : count.intValue();
    }

    /**
     * ERI 标记（C-PAT-010）：最近一条含 ERI 字段（f323 / f324）的已提交随访记录按 {@link EriEval} 判定—— 状态 2 已达ERI(EOI)、1
     * 接近ERI、0 正常；状态正常 / 缺失但预计电池寿命 ≤ 1 年视同接近ERI（与 marked() 同一「视同」口径）； 无此类记录（含 A / B 表单）视为正常。
     */
    private String eriLabelOf(long patientId) {
        Map<String, Object> data = readData(overviewMapper.findLatestEriRecordData(patientId));
        String statusCode = EriEval.statusCode(data);
        if (EriEval.STATUS_EOI.equals(statusCode)) {
            return "已达ERI(EOI)";
        }
        if (EriEval.STATUS_NEAR.equals(statusCode)) {
            return "接近ERI";
        }
        Double batteryLife = EriEval.batteryLife(data);
        if (batteryLife != null && batteryLife <= EriEval.BATTERY_LIFE_THRESHOLD) {
            return "接近ERI";
        }
        return "正常";
    }

    /** 起搏器类型显示名（字典 pacemaker_type 含停用项，C-FLD-009；未知编码回落原码，不返回 null）。 */
    private String deviceTypeLabel(String deviceType) {
        if (deviceType == null) {
            return null;
        }
        return implantDicts.items(ImplantDictPort.DICT_DEVICE_TYPE).stream()
                .filter(item -> deviceType.equals(item.itemCode()))
                .map(ImplantDictPort.DictItem::label)
                .findFirst()
                .orElse(deviceType);
    }

    /**
     * 植入时长显示（components.md §4「植入 1 年 5 个月」；与前端 record/logic implantDurationText 同口径）： 按日历月计算，目标日
     * 未到植入日当月口径减一；不足 1 个月显示「不足 1 个月」；无植入日期 null（前端只显示类型）。
     */
    static String implantDurationText(LocalDate implantDate, LocalDate today) {
        if (implantDate == null || today == null) {
            return null;
        }
        long months =
                (today.getYear() - implantDate.getYear()) * 12L
                        + (today.getMonthValue() - implantDate.getMonthValue());
        if (today.getDayOfMonth() < implantDate.getDayOfMonth()) {
            months--;
        }
        if (months < 0) {
            return null;
        }
        if (months == 0) {
            return "不足 1 个月";
        }
        long years = months / 12;
        long rest = months % 12;
        if (years <= 0) {
            return rest + " 个月";
        }
        if (rest <= 0) {
            return years + " 年";
        }
        return years + " 年 " + rest + " 个月";
    }

    /** fu_record.data JSON 容错解析（与 EriListService.readData 同口径：脏数据视作无记录，不阻断概览）。 */
    private static Map<String, Object> readData(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception invalid) {
            return Map.of();
        }
    }

    private static NextVisitView nextVisitOf(PatientOverviewMapper.OverviewRow row) {
        if (row.nextPlanDate() == null
                && row.nextWindowStart() == null
                && row.nextWindowEnd() == null) {
            return null;
        }
        return new NextVisitView(row.nextPlanDate(), row.nextWindowStart(), row.nextWindowEnd());
    }

    private static LatestFollowupView latestFollowupOf(PatientOverviewMapper.OverviewRow row) {
        if (row.latestFollowupDate() == null) {
            return null;
        }
        return new LatestFollowupView(
                row.latestFollowupDate(),
                row.survivalStatus(),
                parseJson(
                        row.keyIndicators(), new TypeReference<LinkedHashMap<String, Object>>() {}),
                parseJson(
                        row.endpointEvents(),
                        new TypeReference<LinkedHashMap<String, String>>() {}));
    }

    /** 派生列 JSON 容错解析：脏数据不阻断概览（完整口径的重算入口在 PatientSummaryService）。 */
    private static <T extends Map<?, ?>> T parseJson(String text, TypeReference<T> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(text, type);
        } catch (Exception invalid) {
            return null;
        }
    }

    /**
     * 头部可用操作（后端按权限 + 状态计算，前端仍做按钮级权限，patient.md §2）： unplanned / contact 需患者在随访中（ACTIVE）， stop
     * 对未终止患者可用；upload 不受随访状态限制。lost（改为失访，S5 / A-FU-012）需未终止（随访中 / 已暂停皆可改判失访）+
     * followup:patient:edit。 各操作的实际入口随 E14（停止）/ E15（计划外、沟通）/ E16（上传）开放。
     */
    private static List<String> actionsOf(String status, boolean terminated) {
        SessionPrincipal principal = CurrentSession.current().orElse(null);
        if (principal == null) {
            return List.of();
        }
        Set<String> codes = Set.copyOf(principal.permissionCodes());
        boolean inFollowup = "ACTIVE".equals(status);
        List<String> actions = new ArrayList<>();
        if (inFollowup && codes.contains(PERM_RECORD_EDIT)) {
            actions.add("unplanned");
        }
        if (codes.contains(PERM_SCAN_UPLOAD)) {
            actions.add("upload");
        }
        if (inFollowup && codes.contains(PERM_RECORD_EDIT)) {
            actions.add("contact");
        }
        if (!terminated
                && (codes.contains(PERM_PLAN_ENROLL) || codes.contains(PERM_PATIENT_EDIT))) {
            actions.add("stop");
        }
        // 改为失访（S5 / A-FU-012）：未终止（含已暂停）+ followup:patient:edit；已失访患者不再下发
        if (!terminated && codes.contains(PERM_PATIENT_EDIT)) {
            actions.add("lost");
        }
        // 转病区（E13-T08 / A-WARD-005）：管理动作，已终止患者也可调整病区归属（统计与历史回看要用）
        if (codes.contains(PERM_PATIENT_TRANSFER)) {
            actions.add("transfer");
        }
        return List.copyOf(actions);
    }

    /** 时间轴同类节点稳定次序：出院在状态事件之前（登记日同时有状态变更时）。 */
    private static int kindRank(TimelineEntryView entry) {
        return "DISCHARGE".equals(entry.kind()) ? 0 : 1;
    }

    /**
     * 完成日期 = completed_at（服务器墙钟）对应的北京业务日（AGENTS.md §6 业务日期按 Asia/Shanghai）。 服务器在 UTC 容器里时（CI /
     * 部署不保证时区），直接 {@code toLocalDate()} 在北京 00:00–08:00 时段会早一天。
     */
    static LocalDate beijingDoneDate(LocalDateTime completedAt) {
        return completedAt == null
                ? null
                : LocalDate.ofInstant(
                        completedAt.atZone(ZoneId.systemDefault()).toInstant(), BUSINESS_ZONE);
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }
}
