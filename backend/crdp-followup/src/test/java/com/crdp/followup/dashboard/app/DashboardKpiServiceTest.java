package com.crdp.followup.dashboard.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.app.DashboardKpiService.DrillView;
import com.crdp.followup.dashboard.app.DashboardKpiService.KpiCardView;
import com.crdp.followup.dashboard.app.DashboardKpiService.MetricView;
import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.followup.stats.app.EriListService;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E19-T02 / A-DASH-001、C-DASH-001 指标卡服务单测（验收「数字与列表钻取一致」的取数与钻取条件）：
 *
 * <ul>
 *   <li>A 指标集七项（implant 关 / 无行）：状态计数来自同一 GROUP BY、总数含已死亡已终止（Q01）、 本月应随访用当月闭区间、钻取 query 与列表页筛选参数一致；
 *   <li>C 指标集七项（implant 开）：在随访 / 本月应随访 / 当前待随访 / 当前逾期可算， 近 30 天标红按窗口取数； 接近 ERI 与待录入扫描件随 E21
 *       占位（available = false、无钻取）；
 *   <li>wardId 非法 40001。
 * </ul>
 *
 * <p>「数字与列表一致」的端到端（同库同条件树对列表接口总数）在 DashboardCardsIT 覆盖； 本测锁定条件装配与响应结构。全部虚构数据。
 */
class DashboardKpiServiceTest {

    private final DashboardStatsMapper stats = mock(DashboardStatsMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final EriListService eriList = mock(EriListService.class);
    private final DashboardKpiService service = new DashboardKpiService(stats, features, eriList);

    {
        lenient().when(features.findEnabled("implant")).thenReturn(false);
        lenient()
                .when(stats.countPatientsByStatus(any()))
                .thenReturn(
                        List.of(
                                new DashboardStatsMapper.PatientStatusCountRow("ACTIVE", 12L),
                                new DashboardStatsMapper.PatientStatusCountRow("DEAD", 2L),
                                new DashboardStatsMapper.PatientStatusCountRow("TERMINATED", 1L),
                                new DashboardStatsMapper.PatientStatusCountRow("LOST", 1L)));
        lenient().when(stats.countMonthDue(any(), any(), any())).thenReturn(8L);
        lenient().when(stats.countTasksByStatus(eq("OVERDUE"), any())).thenReturn(5L);
        lenient().when(stats.countTasksByStatus(eq("PENDING"), any())).thenReturn(20L);
        lenient().when(stats.countRecentHighlightRecords(any(), any())).thenReturn(3L);
    }

    // ---------------------------------------------------------------- 医院 A 指标集

    @Test
    void aStyleReturnsSevenMetricsWithListConsistentDrills() {
        KpiCardView view = service.kpi(null);

        assertEquals("A", view.style());
        assertEquals(7, view.metrics().size());
        Map<String, MetricView> byCode = new java.util.LinkedHashMap<>();
        for (MetricView metric : view.metrics()) {
            byCode.put(metric.code(), metric);
        }
        // 患者总数 = 各状态之和（含已死亡 / 已终止，Q01），钻取进无筛选患者列表
        assertEquals(16L, byCode.get("total").value());
        assertEquals(new DrillView("PATIENT_LIST", Map.of()), byCode.get("total").drill());
        assertEquals(12L, byCode.get("active").value());
        assertEquals(Map.of("status", "ACTIVE"), byCode.get("active").drill().query());
        assertEquals(2L, byCode.get("dead").value());
        assertEquals(Map.of("status", "DEAD"), byCode.get("dead").drill().query());
        assertEquals(1L, byCode.get("terminated").value());
        assertEquals(Map.of("status", "TERMINATED"), byCode.get("terminated").drill().query());
        assertEquals(1L, byCode.get("lost").value());
        assertEquals(Map.of("status", "LOST"), byCode.get("lost").drill().query());
        // 当前逾期钻取任务列表（status 筛选与列表检索同参数）
        assertEquals(5L, byCode.get("overdue").value());
        assertEquals(
                Map.of("tab", "all", "status", "OVERDUE"), byCode.get("overdue").drill().query());
        // 本月应随访钻取带当月计划日期窗口
        assertEquals(8L, byCode.get("monthDue").value());
        assertEquals("TASK_LIST", byCode.get("monthDue").drill().target());
        assertEquals("all", byCode.get("monthDue").drill().query().get("tab"));
        for (MetricView metric : view.metrics()) {
            assertTrue(metric.available(), metric.code() + " 应可用");
        }
    }

    @Test
    void monthDueUsesCurrentBusinessMonthBounds() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        service.kpi(null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(stats).countMonthDue(from.capture(), to.capture(), any());
        assertEquals(today.withDayOfMonth(1), from.getValue());
        assertEquals(today.withDayOfMonth(today.lengthOfMonth()), to.getValue());
        assertEquals(
                today.withDayOfMonth(1).toString(),
                service.kpi(null).metrics().stream()
                        .filter(metric -> "monthDue".equals(metric.code()))
                        .findFirst()
                        .orElseThrow()
                        .drill()
                        .query()
                        .get("dueFrom"));
    }

    // ---------------------------------------------------------------- 医院 C 指标集

    @Test
    void cStyleReturnsSevenMetricsWithE21Placeholders() {
        when(features.findEnabled("implant")).thenReturn(true);

        KpiCardView view = service.kpi(null);

        assertEquals("C", view.style());
        Map<String, MetricView> byCode = new java.util.LinkedHashMap<>();
        for (MetricView metric : view.metrics()) {
            byCode.put(metric.code(), metric);
        }
        assertEquals(7, view.metrics().size());
        assertEquals(
                List.of(
                        "active",
                        "monthDue",
                        "pending",
                        "overdue",
                        "eriNear",
                        "highlightRecent",
                        "scanPending"),
                view.metrics().stream().map(MetricView::code).toList());
        assertEquals("在随访患者数", byCode.get("active").title());
        assertEquals(20L, byCode.get("pending").value());
        assertEquals(
                Map.of("tab", "all", "status", "PENDING"), byCode.get("pending").drill().query());
        // ERI 数据源已随 E21-T19 落地，但 eri_cycle 关闭（本测默认 null）时仍占位
        // （出数见 cStyleEriNearWithFeatureOn）
        assertNull(byCode.get("eriNear").value());
        assertNull(byCode.get("eriNear").drill());
        assertNull(byCode.get("scanPending").value());
        assertNull(byCode.get("scanPending").drill());
        // 近 30 天标红可算（fu_record.highlight，E17-T04）；列表即 abnormal_records 卡片，不单设钻取
        assertEquals(3L, byCode.get("highlightRecent").value());
        assertNull(byCode.get("highlightRecent").drill());
    }

    @Test
    void cStyleEriNearWithFeatureOnCountsFromEriListCardQuery() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(features.findEnabled("eri_cycle")).thenReturn(true);
        when(eriList.nearEriCount(null)).thenReturn(4L);

        KpiCardView view = service.kpi(null);

        MetricView eriNear =
                view.metrics().stream()
                        .filter(metric -> "eriNear".equals(metric.code()))
                        .findFirst()
                        .orElseThrow();
        // E21-T19：接近 ERI 器械数与 eri_list 卡片 total 同一查询（一个口径一个查询）；无列表页钻取
        assertTrue(eriNear.available());
        assertEquals(4L, eriNear.value());
        assertNull(eriNear.drill());
        verify(eriList).nearEriCount(null);
    }

    @Test
    void cStyleScanPendingWithFeatureOnDrillsToReportList() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(stats.countReportsByDigitizeStatus("PENDING", null)).thenReturn(46L);

        KpiCardView view = service.kpi(null);

        MetricView scanPending =
                view.metrics().stream()
                        .filter(metric -> "scanPending".equals(metric.code()))
                        .findFirst()
                        .orElseThrow();
        // E21-T15：待录入扫描件数出数，钻取全院报告单列表「电子化 = 待录入」（检索同条件树，C-SCAN-014）
        assertTrue(scanPending.available());
        assertEquals(46L, scanPending.value());
        assertEquals("REPORT_LIST", scanPending.drill().target());
        assertEquals(Map.of("digitizeStatus", "PENDING"), scanPending.drill().query());
    }

    @Test
    void cStyleScanPendingWardFilterPassedThrough() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(features.findEnabled("scan_progress")).thenReturn(true);

        service.kpi(7L);

        verify(stats).countReportsByDigitizeStatus("PENDING", 7L);
    }

    @Test
    void highlightWindowIsLast30BusinessDays() {
        when(features.findEnabled("implant")).thenReturn(true);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));

        service.kpi(null);

        var since = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(stats).countRecentHighlightRecords(since.capture(), any());
        assertEquals(
                today.minusDays(DashboardKpiService.HIGHLIGHT_WINDOW_DAYS - 1L).atStartOfDay(),
                since.getValue());
    }

    @Test
    void cStyleHighlightStillComputedWhenCompareHighlightOff() {
        // compare_highlight 关闭时新记录不再落 highlight，计数自然为 0——指标仍在（C-DASH-001 固定七项）
        when(features.findEnabled("implant")).thenReturn(true);
        when(stats.countRecentHighlightRecords(any(), any())).thenReturn(0L);

        KpiCardView view = service.kpi(null);

        assertEquals(
                0L,
                view.metrics().stream()
                        .filter(metric -> "highlightRecent".equals(metric.code()))
                        .findFirst()
                        .orElseThrow()
                        .value());
    }

    // ---------------------------------------------------------------- 参数清洗

    @Test
    void invalidWardRejected() {
        ApiException zero = assertThrows(ApiException.class, () -> service.kpi(0L));
        assertEquals(40001, zero.code());
        ApiException negative = assertThrows(ApiException.class, () -> service.kpi(-1L));
        assertEquals(40001, negative.code());
    }

    @Test
    void wardIdPassedThroughToCounts() {
        service.kpi(7L);
        verify(stats).countPatientsByStatus(7L);
        verify(stats).countTasksByStatus(eq("OVERDUE"), eq(7L));
        verify(stats).countMonthDue(any(), any(), eq(7L));
    }
}
