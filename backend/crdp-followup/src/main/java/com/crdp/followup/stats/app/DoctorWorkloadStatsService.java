package com.crdp.followup.stats.app;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.stats.infra.mapper.DoctorWorkloadMapper;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 医护负责患者数统计（E19-T07 / A-DASH-009、C-DASH-010；验收「按责任人统计」，docs/api/stats.md §2 {@code GET
 * /api/hospital/followup/stats/doctor-workload}）。
 *
 * <p>单独统计页（A 规格 §9「医护负责患者数」）：按医生一行列出<b>负责患者数、在随访、本月应随访、逾期、每年随访率</b>， 可按病区筛选（A-DASH-005）。归口口径 =
 * <b>患者责任医生</b>（面板口径，OQ-118）：患者两列 = 患者列表 doctorId 筛选总数、本月应随访 = 指标卡「本月应随访」同条件树（常规任务、入组在组）、逾期 =
 * status OVERDUE、每年随访率 = 年度节点按患者 × 年度去重汇总（Q38，与每年随访率卡片同一事实表——各年度行之和）。
 *
 * <p>点击数字进入对应患者 / 任务列表（A 规格 §9）：每列内联钻取目标（target + 列表页路由 query）， 与首页指标卡同形态（E19-T02）；数据范围由 Mapper
 * 语句上的 {@code @DataScope(resource = "patient")} 出行，范围内无患者 → 空行集，不报错（stats.md §1）。
 */
@Service
public class DoctorWorkloadStatsService {

    private final DoctorWorkloadMapper mapper;

    public DoctorWorkloadStatsService(DoctorWorkloadMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * 钻取目标（点数字进入对应列表，与指标卡同形态）： target = PATIENT_LIST 患者列表 / TASK_LIST 任务列表； query 为列表页路由 query
     * 参数（doctorName 供列表页钻取标签展示）。
     */
    public record DrillView(String target, Map<String, Object> query) {}

    /** 一位责任医生一行：四个计数列 + 每年随访率（分母 / 分子随行，Q38 口径患者 × 年度去重）。 */
    public record DoctorWorkloadRow(
            String doctorId,
            String doctorName,
            long totalPatients,
            long activePatients,
            long monthDue,
            long overdue,
            long rateDueCount,
            long rateFollowedCount,
            double followRatePct,
            DrillView totalDrill,
            DrillView activeDrill,
            DrillView monthDueDrill,
            DrillView overdueDrill) {}

    /**
     * 响应：asOfMonth 标明「本月」口径（北京时间业务月）；asOf = 随访率数据时点（事实表最近刷新日， 无随访率数据为 null——患者 /
     * 任务列按当前数据实时计算，与指标卡同口径）。
     */
    public record DoctorWorkloadView(
            String asOfMonth, LocalDate asOf, List<DoctorWorkloadRow> rows) {}

    // ---------------------------------------------------------------- 取数

    /** 医护负责患者数（wardId 病区筛选，A-DASH-005；范围内再收窄）。 */
    public DoctorWorkloadView workload(Long wardId) {
        Long ward = sanitizeWard(wardId);
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        Map<Long, long[]> monthDue =
                byDoctor(mapper.countMonthDueByDoctor(monthStart, monthEnd, ward));
        Map<Long, long[]> overdue = byDoctor(mapper.countOverdueByDoctor(ward));
        Map<Long, DoctorWorkloadMapper.DoctorRateRow> rates = new HashMap<>();
        LocalDate asOf = null;
        for (DoctorWorkloadMapper.DoctorRateRow row : mapper.aggregateYearlyRateByDoctor(ward)) {
            rates.put(row.doctorId(), row);
            if (row.refreshedDate() != null
                    && (asOf == null || row.refreshedDate().isAfter(asOf))) {
                asOf = row.refreshedDate();
            }
        }

        // 行锚点 = 至少负责 1 名在册患者的医生（Mapper 已按负责患者数降序）；任务 / 随访率行只并入锚点行
        List<DoctorWorkloadRow> rows = new ArrayList<>();
        for (DoctorWorkloadMapper.DoctorPatientRow patient : mapper.countPatientsByDoctor(ward)) {
            long due = monthDue.getOrDefault(patient.doctorId(), new long[1])[0];
            long late = overdue.getOrDefault(patient.doctorId(), new long[1])[0];
            DoctorWorkloadMapper.DoctorRateRow rate = rates.get(patient.doctorId());
            long rateDue = rate == null ? 0 : rate.dueCount();
            long rateFollowed = rate == null ? 0 : rate.followedCount();
            Map<String, Object> doctorQuery =
                    Map.of("doctorId", String.valueOf(patient.doctorId()));
            Map<String, Object> doctorNameQuery =
                    Map.of(
                            "doctorId",
                            String.valueOf(patient.doctorId()),
                            "doctorName",
                            patient.doctorName());
            rows.add(
                    new DoctorWorkloadRow(
                            String.valueOf(patient.doctorId()),
                            patient.doctorName(),
                            patient.totalPatients(),
                            patient.activePatients(),
                            due,
                            late,
                            rateDue,
                            rateFollowed,
                            pct(rateFollowed, rateDue),
                            new DrillView("PATIENT_LIST", doctorNameQuery),
                            new DrillView(
                                    "PATIENT_LIST", merge(doctorQuery, Map.of("status", "ACTIVE"))),
                            new DrillView(
                                    "TASK_LIST",
                                    merge(
                                            doctorQuery,
                                            Map.of(
                                                    "tab",
                                                    "all",
                                                    "dueFrom",
                                                    monthStart.toString(),
                                                    "dueTo",
                                                    monthEnd.toString()))),
                            new DrillView(
                                    "TASK_LIST",
                                    merge(
                                            doctorQuery,
                                            Map.of("tab", "all", "status", "OVERDUE")))));
        }
        return new DoctorWorkloadView(
                today.getYear() + "-" + String.format("%02d", today.getMonthValue()), asOf, rows);
    }

    // ---------------------------------------------------------------- 内部

    private static Map<Long, long[]> byDoctor(List<DoctorWorkloadMapper.DoctorTaskRow> rows) {
        Map<Long, long[]> counts = new HashMap<>();
        for (DoctorWorkloadMapper.DoctorTaskRow row : rows) {
            counts.computeIfAbsent(row.doctorId(), key -> new long[1])[0] = row.total();
        }
        return counts;
    }

    /** 比率换算：百分数、一位小数；分母为 0 时为 0（与随访率卡片同口径）。 */
    private static double pct(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return Math.round(numerator * 1000d / denominator) / 10d;
    }

    private static Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new HashMap<>(left);
        merged.putAll(right);
        return Map.copyOf(merged);
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
