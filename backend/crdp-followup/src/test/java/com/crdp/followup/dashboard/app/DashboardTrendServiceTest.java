package com.crdp.followup.dashboard.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.app.DashboardTrendService.TrendBucketView;
import com.crdp.followup.dashboard.app.DashboardTrendService.TrendCardView;
import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E19-T02 / A-DASH-002 趋势图服务单测（验收「各周期合计与按同一日期筛选的患者列表一致」的取数装配）： 默认参数（手术日期 · 月 · 不分组 · 最近 24
 * 个月）、可选项白名单与 40001、 周期序列连续补零、分组标签（性别 / 出院状态 / 年龄段）与图例排序、 SQL 表达式由枚举白名单拼装（维度列 / 分桶 / 分组片段只出自服务端枚举）。
 * 端到端与列表总数一致在 DashboardCardsIT 覆盖。全部虚构数据。
 */
class DashboardTrendServiceTest {

    private final DashboardStatsMapper stats = mock(DashboardStatsMapper.class);
    private final DashboardTrendService service = new DashboardTrendService(stats);

    {
        lenient()
                .when(stats.countTrend(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    // ---------------------------------------------------------------- 默认参数与回显

    @Test
    void defaultsToSurgeryMonthNoneLast24Months() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));

        TrendCardView view = service.trend(null, null, null, null, null, null);

        assertEquals("SURGERY", view.timeDimension());
        assertEquals("MONTH", view.granularity());
        assertEquals("NONE", view.groupBy());
        assertEquals(today.withDayOfMonth(1).minusMonths(23), view.from());
        assertEquals(today.withDayOfMonth(today.lengthOfMonth()), view.to());
        assertEquals(24, view.buckets().size(), "最近 24 个月逐月一桶");
        assertEquals(
                view.from().getYear() + "-" + String.format("%02d", view.from().getMonthValue()),
                view.buckets().get(0).bucket(),
                "首桶 = 默认起点所在月");
    }

    @Test
    void whitelistExpressionsPassedToMapper() {
        service.trend(
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "discharge",
                "quarter",
                "ward");

        verify(stats)
                .countTrend(
                        contains("p.discharge_date"),
                        contains("w.name"),
                        eq("p.discharge_date"),
                        contains("LEFT JOIN ward"),
                        eq(LocalDate.of(2026, 1, 1)),
                        eq(LocalDate.of(2026, 3, 31)),
                        isNull());
        // 分桶表达式按维度列拼季度键（白名单生成，不透传请求原文）
        verify(stats)
                .countTrend(
                        eq("CONCAT(YEAR(p.discharge_date), '-Q', QUARTER(p.discharge_date))"),
                        eq("COALESCE(w.name, '__UNKNOWN__')"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    // ---------------------------------------------------------------- 周期序列与归并

    @Test
    void quarterBucketsContinuousWithZeroFillAndWardLegend() {
        when(stats.countTrend(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new DashboardStatsMapper.TrendCountRow("2026-Q1", "心外一病区（示例）", 3L),
                                new DashboardStatsMapper.TrendCountRow("2026-Q1", "心外二病区（示例）", 2L),
                                new DashboardStatsMapper.TrendCountRow(
                                        "2026-Q3", "心外一病区（示例）", 4L)));

        TrendCardView view =
                service.trend(
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        null,
                        "quarter",
                        "ward");

        assertEquals(4, view.buckets().size(), "四个季度各一桶（空季度补零）");
        assertEquals(
                List.of("2026-Q1", "2026-Q2", "2026-Q3", "2026-Q4"),
                view.buckets().stream().map(TrendBucketView::bucket).toList());
        TrendBucketView q1 = view.buckets().get(0);
        assertEquals(5L, q1.total());
        assertEquals(2, q1.groups().size());
        assertEquals(3L, q1.groups().get(0).count());
        assertEquals(2L, q1.groups().get(1).count());
        // Q2 空季度：两组都为 0
        TrendBucketView q2 = view.buckets().get(1);
        assertEquals(0L, q2.total());
        assertEquals(0L, q2.groups().get(0).count());
        // Q3 只有病区一有数，病区二补 0
        TrendBucketView q3 = view.buckets().get(2);
        assertEquals(4L, q3.total());
        assertEquals(0L, q3.groups().get(1).count());
        assertEquals("心外一病区（示例）", view.legend().get(0).label());
    }

    @Test
    void genderGroupLabelsWithFixedOrder() {
        when(stats.countTrend(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new DashboardStatsMapper.TrendCountRow("2026-01", "F", 5L),
                                new DashboardStatsMapper.TrendCountRow("2026-01", "M", 6L),
                                new DashboardStatsMapper.TrendCountRow(
                                        "2026-01", "__UNKNOWN__", 1L)));

        TrendCardView view =
                service.trend(
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31),
                        null,
                        null,
                        "gender");

        assertEquals(3, view.legend().size());
        assertEquals("M", view.legend().get(0).key());
        assertEquals("男", view.legend().get(0).label());
        assertEquals("女", view.legend().get(1).label());
        assertEquals("未知", view.legend().get(2).label());
        assertEquals(12L, view.buckets().get(0).total());
    }

    @Test
    void dischargeStatusLabelsMapped() {
        when(stats.countTrend(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new DashboardStatsMapper.TrendCountRow("2026", "1", 4L),
                                new DashboardStatsMapper.TrendCountRow("2026", "2", 1L),
                                new DashboardStatsMapper.TrendCountRow("2026", "3", 1L)));

        TrendCardView view =
                service.trend(
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        null,
                        "year",
                        "dischargeStatus");

        assertEquals(1, view.buckets().size());
        assertEquals(
                List.of("存活", "病重自动出院", "死亡"),
                view.legend().stream().map(item -> item.label()).toList());
        assertEquals(6L, view.buckets().get(0).total());
    }

    @Test
    void ageBandExpressionBandsBySurgeryAge() {
        service.trend(
                null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, "ageBand");

        ArgumentCaptor<String> groupExpr = ArgumentCaptor.forClass(String.class);
        verify(stats).countTrend(any(), groupExpr.capture(), any(), any(), any(), any(), any());
        assertTrue(
                groupExpr.getValue().contains("TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date)"));
        assertTrue(groupExpr.getValue().contains("< 50"));
        assertTrue(groupExpr.getValue().contains("'GE80'"));
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    void invalidOptionsRejected() {
        assert40001(() -> service.trend(null, null, null, "birth", null, null));
        assert40001(() -> service.trend(null, null, null, null, "week", null));
        assert40001(() -> service.trend(null, null, null, null, null, "surgeon"));
        assert40001(() -> service.trend(0L, null, null, null, null, null));
    }

    @Test
    void invertedOrOverlongRangeRejected() {
        assert40001(
                () ->
                        service.trend(
                                null,
                                LocalDate.of(2026, 6, 1),
                                LocalDate.of(2026, 1, 1),
                                null,
                                null,
                                null));
        // 30 年按月分桶超出上限（防超大区间）
        assert40001(
                () ->
                        service.trend(
                                null,
                                LocalDate.of(1990, 1, 1),
                                LocalDate.of(2026, 1, 1),
                                null,
                                "month",
                                null));
    }

    private static void assert40001(Runnable call) {
        ApiException error = assertThrows(ApiException.class, call::run);
        assertEquals(40001, error.code());
    }
}
