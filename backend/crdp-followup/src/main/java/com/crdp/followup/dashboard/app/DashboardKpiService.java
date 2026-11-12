package com.crdp.followup.dashboard.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 首页指标卡（E19-T02 / A-DASH-001、C-DASH-001；docs/api/stats.md §3 cardCode = kpi）。
 *
 * <p>验收「数字与列表钻取一致」：每项指标的取数条件与钻取目标列表的检索条件同一棵条件树——患者状态计数 = 患者列表 status 筛选总数（deleted = 0；患者总数含已死亡 /
 * 已终止，Q01）、任务状态计数 = 任务列表 status 筛选总数、 本月应随访 = 任务列表计划日期窗口筛选（跟进 / 临时任务不在「年度随访数」口径内，OQ-107）； 数据范围由
 * Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行，范围内无患者时全部为 0（stats.md §1，不报错）。
 *
 * <p>指标集按租户差异用功能开关判定（stats.md §3「A 与 C 指标不同，按租户配置」）： {@code implant} 开 → 医院 C 指标集 （C-DASH-001
 * 七项，其中「接近 ERI 器械数」的数据源随 E21-T07 / T19 落地、先行返回 available = false 占位——与任务列表 C 三列占位同做法； 「待录入扫描件数」已随
 * E21-T15 落地（rpt_report.digitize_status，随 {@code scan_progress} 开关出数、关闭仍占位））；关 → 医院 A
 * 指标集（A-DASH-001 七项）。随 E19-T06 卡片目录按角色配置时，指标集配置随目录 落库（OQ-107）。
 */
@Service
public class DashboardKpiService {

    /** C 指标「近 30 天含标红参数的记录数」的窗口天数（C-DASH-001 固定 30；N 可配置随 E21）。 */
    static final int HIGHLIGHT_WINDOW_DAYS = 30;

    /** 电子化状态 / 补录进度的功能开关（E21-T15：关闭时「待录入扫描件数」保持占位，AGENTS §5.7）。 */
    static final String SCAN_PROGRESS_FEATURE = "scan_progress";

    private final DashboardStatsMapper stats;
    private final DashboardFeatureMapper features;

    public DashboardKpiService(DashboardStatsMapper stats, DashboardFeatureMapper features) {
        this.stats = stats;
        this.features = features;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * 钻取目标（点指标卡进入对应列表，A-DASH-001「点击进入对应列表」）： target = PATIENT_LIST 患者列表 / TASK_LIST 我的任务 /
     * REPORT_LIST 全院报告单（E21-T15「待录入扫描件数」→ 电子化筛选）；query 为列表页路由 query 参数（前端直接拼进
     * /followup/patient、/followup/task、/followup/report）。
     */
    public record DrillView(String target, Map<String, Object> query) {}

    /**
     * 一项指标：value 为 null 且 available = false 表示数据源尚未落地（C 的 ERI / 扫描件随 E21）， 前端隐藏；drill 为 null
     * 表示暂无对应列表（C 的 ERI / 异常标红列表卡片随 E21-T19；「待录入扫描件数」已随 E21-T15 钻取全院报告单列表）。
     */
    public record MetricView(
            String code, String title, Long value, boolean available, DrillView drill) {}

    /** 指标卡数据：style = A / C（按 implant 开关），asOfMonth 标明「本月」口径（北京时间业务月）。 */
    public record KpiCardView(String style, String asOfMonth, List<MetricView> metrics) {}

    // ---------------------------------------------------------------- 取数

    /** 指标卡（病区筛选 A-DASH-005 随 E19-T03 页面接入，接口先行支持）。 */
    public KpiCardView kpi(Long wardId) {
        Long ward = sanitizeWard(wardId);
        LocalDate today = PlanDateCalculator.businessToday(java.time.Instant.now());
        boolean hospitalC = Boolean.TRUE.equals(features.findEnabled("implant"));
        List<MetricView> metrics = hospitalC ? cMetrics(ward, today) : aMetrics(ward, today);
        return new KpiCardView(
                hospitalC ? "C" : "A",
                today.getYear() + "-" + String.format("%02d", today.getMonthValue()),
                metrics);
    }

    // ---------------------------------------------------------------- 医院 A 指标集（A-DASH-001）

    /** A 七项：患者总数、在随访、已死亡、已终止、失访、本月应随访、当前逾期（口径见类注释与 OQ-107）。 */
    private List<MetricView> aMetrics(Long ward, LocalDate today) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (DashboardStatsMapper.PatientStatusCountRow row : stats.countPatientsByStatus(ward)) {
            byStatus.put(row.status(), row.total());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        long monthDue = stats.countMonthDue(monthStart, monthEnd, ward);
        long overdue = stats.countTasksByStatus("OVERDUE", ward);
        List<MetricView> metrics = new ArrayList<>();
        metrics.add(
                new MetricView(
                        "total", "患者总数", total, true, new DrillView("PATIENT_LIST", Map.of())));
        metrics.add(
                new MetricView(
                        "active",
                        "在随访",
                        byStatus.getOrDefault("ACTIVE", 0L),
                        true,
                        patientDrill("ACTIVE")));
        metrics.add(
                new MetricView(
                        "dead",
                        "已死亡",
                        byStatus.getOrDefault("DEAD", 0L),
                        true,
                        patientDrill("DEAD")));
        metrics.add(
                new MetricView(
                        "terminated",
                        "已终止",
                        byStatus.getOrDefault("TERMINATED", 0L),
                        true,
                        patientDrill("TERMINATED")));
        metrics.add(
                new MetricView(
                        "lost",
                        "失访",
                        byStatus.getOrDefault("LOST", 0L),
                        true,
                        patientDrill("LOST")));
        metrics.add(
                new MetricView(
                        "monthDue",
                        "本月应随访",
                        monthDue,
                        true,
                        new DrillView(
                                "TASK_LIST",
                                Map.of(
                                        "tab", "all",
                                        "dueFrom", monthStart.toString(),
                                        "dueTo", monthEnd.toString()))));
        metrics.add(
                new MetricView(
                        "overdue",
                        "当前逾期",
                        overdue,
                        true,
                        new DrillView("TASK_LIST", Map.of("tab", "all", "status", "OVERDUE"))));
        return metrics;
    }

    // ---------------------------------------------------------------- 医院 C 指标集（C-DASH-001）

    /**
     * C 七项：在随访患者数、本月应随访、当前待随访、当前逾期、接近 ERI 器械数（随 E21）、 近 30 天含标红参数的记录数
     * （fu_record.highlight，E17-T04）、待录入扫描件数（随 E21-T15）。
     */
    private List<MetricView> cMetrics(Long ward, LocalDate today) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (DashboardStatsMapper.PatientStatusCountRow row : stats.countPatientsByStatus(ward)) {
            byStatus.put(row.status(), row.total());
        }
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        LocalDateTime highlightSince = today.minusDays(HIGHLIGHT_WINDOW_DAYS - 1L).atStartOfDay();
        List<MetricView> metrics = new ArrayList<>();
        metrics.add(
                new MetricView(
                        "active",
                        "在随访患者数",
                        byStatus.getOrDefault("ACTIVE", 0L),
                        true,
                        patientDrill("ACTIVE")));
        metrics.add(
                new MetricView(
                        "monthDue",
                        "本月应随访",
                        stats.countMonthDue(monthStart, monthEnd, ward),
                        true,
                        new DrillView(
                                "TASK_LIST",
                                Map.of(
                                        "tab", "all",
                                        "dueFrom", monthStart.toString(),
                                        "dueTo", monthEnd.toString()))));
        metrics.add(
                new MetricView(
                        "pending",
                        "当前待随访",
                        stats.countTasksByStatus("PENDING", ward),
                        true,
                        new DrillView("TASK_LIST", Map.of("tab", "all", "status", "PENDING"))));
        metrics.add(
                new MetricView(
                        "overdue",
                        "当前逾期",
                        stats.countTasksByStatus("OVERDUE", ward),
                        true,
                        new DrillView("TASK_LIST", Map.of("tab", "all", "status", "OVERDUE"))));
        // 接近 ERI 器械数：植入 / 电极数据源随 E21（eri_cycle 循环随 E21-T07），先行返回占位（available = false）
        metrics.add(new MetricView("eriNear", "接近 ERI 器械数", null, false, null));
        metrics.add(
                new MetricView(
                        "highlightRecent",
                        "近 30 天含标红参数的记录数",
                        stats.countRecentHighlightRecords(highlightSince, ward),
                        true,
                        null));
        // 待录入扫描件数（C-SCAN-011 / C-DASH-007）：E21-T15 起随 scan_progress 开关出数——
        // 关闭（含无行）仍占位（available = false，AGENTS §5.7；医院 A / B 不启用，A-ATT-018）
        if (Boolean.TRUE.equals(features.findEnabled(SCAN_PROGRESS_FEATURE))) {
            metrics.add(
                    new MetricView(
                            "scanPending",
                            "待录入扫描件数",
                            stats.countReportsByDigitizeStatus("PENDING", ward),
                            true,
                            // 钻取 = 全院报告单列表「电子化 = 待录入」筛选（检索同条件树，C-SCAN-014）
                            new DrillView("REPORT_LIST", Map.of("digitizeStatus", "PENDING"))));
        } else {
            metrics.add(new MetricView("scanPending", "待录入扫描件数", null, false, null));
        }
        return metrics;
    }

    /** 患者列表钻取（列表页按 status 筛选；总数口径进无筛选列表）。 */
    private static DrillView patientDrill(String status) {
        return new DrillView("PATIENT_LIST", Map.of("status", status));
    }

    private static Long sanitizeWard(Long wardId) {
        if (wardId == null) {
            return null;
        }
        if (wardId <= 0) {
            throw new ApiException(400, 40001, "wardId（须为正整数）不合法");
        }
        return wardId;
    }
}
