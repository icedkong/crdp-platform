package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E19-T04 卡片服务层单测（A-DASH-006 每年随访率 / A-DASH-012、C-DASH-004 节点随访率，口径 Q38）： 每年随访率 =
 * 年度节点按随访年度聚合（患者级去重——多计划并行时同一年度不重复计分母）； 节点随访率 按计划 × 节点 × 第 N 次聚合（行 = 入组），两卡共用同一事实表查询； 逾期完成 /
 * 补录计入已随访由刷新侧口径（OQ-106）保证， 服务层只做比率换算、行标签与开关 / 参数校验。
 */
@ExtendWith(MockitoExtension.class)
class NodeOccurrenceStatsServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    @Mock private NodeOccurrenceMapper occurrenceMapper;
    @Mock private DashboardFeatureMapper features;

    private NodeOccurrenceStatsService service() {
        return new NodeOccurrenceStatsService(occurrenceMapper, features);
    }

    // ---------------------------------------------------------------- 每年随访率（A-DASH-006）

    @Test
    void yearlyRateAggregatesAnnualYearsWithPatientLevelCounts() {
        when(occurrenceMapper.aggregateByYear(isNull(), isNull()))
                .thenReturn(
                        List.of(
                                new NodeOccurrenceMapper.YearCountRow(1, 4, 3, 1, 1, 0, AS_OF),
                                new NodeOccurrenceMapper.YearCountRow(
                                        2, 3, 2, 2, 0, 1, AS_OF.minusDays(1))));
        when(occurrenceMapper.findCohortYears(isNull())).thenReturn(List.of(2025, 2024));

        var view = service().yearlyRate(null, null);

        assertEquals(AS_OF, view.asOf(), "asOf = 事实表最近刷新日");
        assertNull(view.cohortYear());
        assertEquals(List.of(2025, 2024), view.cohortYears());
        assertEquals(2, view.rows().size());
        var first = view.rows().get(0);
        assertEquals("第1年", first.label(), "年度行标签 = 第 N 年");
        assertEquals(1, first.year());
        assertEquals(4, first.dueCount());
        assertEquals(3, first.followedCount(), "已随访含逾期完成 / 补录（口径 Q38，计数来自事实表）");
        assertEquals(75.0, first.followRatePct(), "随访率 = 已随访 ÷ 应随访（主指标）");
        assertEquals(25.0, first.inWindowRatePct(), "窗口内完成率 = 1/4（参考）");
        assertEquals(25.0, first.lostRatePct());
        var second = view.rows().get(1);
        assertEquals(66.7, second.followRatePct(), "比率保留一位小数（2/3）");
        assertEquals(1, second.deathCount());
    }

    @Test
    void yearlyRatePassesWardAndCohortFiltersAndHandlesEmptyTable() {
        when(occurrenceMapper.aggregateByYear(eq(5L), eq(2024))).thenReturn(List.of());
        when(occurrenceMapper.findCohortYears(isNull())).thenReturn(List.of());

        var view = service().yearlyRate(5L, 2024);

        assertNull(view.asOf(), "事实表无行时 asOf 为空");
        assertEquals(List.of(), view.rows());
        verify(occurrenceMapper).aggregateByYear(eq(5L), eq(2024));
    }

    // ---------------------------------------------------------------- 节点随访率（A-DASH-012 /
    // C-DASH-004）

    @Test
    void nodeRateLabelsFixedCycleAndAnnualNodes() {
        when(features.findEnabled("node_follow_rate")).thenReturn(true);
        when(occurrenceMapper.findPublishedPlans())
                .thenReturn(
                        List.of(
                                new NodeOccurrenceMapper.PlanOptionRow(11L, "冠心病术后年度随访（示例）"),
                                new NodeOccurrenceMapper.PlanOptionRow(12L, "起搏器术后随访（示例）")));
        when(occurrenceMapper.aggregateNodeRates(eq(11L), isNull(), isNull()))
                .thenReturn(
                        List.of(
                                // 固定节点（FIXED）：行标签 = 节点名
                                new NodeOccurrenceMapper.NodeRateRow(
                                        201L,
                                        "术后第 1 天（示例）",
                                        "FIXED",
                                        1,
                                        null,
                                        10,
                                        9,
                                        8,
                                        0,
                                        0,
                                        AS_OF),
                                // 年度循环节点：行标签 = 第 N 年（与每年随访率同一口径）
                                new NodeOccurrenceMapper.NodeRateRow(
                                        202L, "电话随访（示例）", "CYCLE", 1, 1, 4, 3, 1, 1, 0, AS_OF),
                                new NodeOccurrenceMapper.NodeRateRow(
                                        202L, "电话随访（示例）", "CYCLE", 2, 2, 3, 2, 2, 0, 1, AS_OF),
                                // 非年度循环节点（如 C 常规随访滚动）：行标签 = 第 N 次
                                new NodeOccurrenceMapper.NodeRateRow(
                                        203L, "常规随访（示例）", "CYCLE", 1, null, 7, 7, 7, 0, 0, AS_OF)));
        when(occurrenceMapper.findCohortYears(eq(11L))).thenReturn(List.of(2025));

        var view = service().nodeRate(null, null, null);

        assertEquals(11L, view.planId(), "缺省计划 = 预置计划（id 最小的已发布版本）");
        assertEquals("冠心病术后年度随访（示例）", view.planName());
        assertEquals(2, view.plans().size());
        assertEquals(4, view.rows().size());
        assertEquals("术后第 1 天（示例）", view.rows().get(0).label(), "固定节点标签 = 节点名");
        assertEquals("第1年", view.rows().get(1).label(), "年度循环节点标签 = 第 N 年");
        assertEquals("第2年", view.rows().get(2).label());
        assertEquals("第1次", view.rows().get(3).label(), "非年度循环节点标签 = 第 N 次");
        assertEquals(90.0, view.rows().get(0).followRatePct());
        assertEquals(100.0, view.rows().get(3).followRatePct());
        verify(occurrenceMapper).aggregateNodeRates(eq(11L), isNull(), isNull());
    }

    @Test
    void nodeRateRequiresFeatureSwitch() {
        when(features.findEnabled("node_follow_rate")).thenReturn(false);

        ApiException error =
                assertThrows(ApiException.class, () -> service().nodeRate(null, null, null));

        assertEquals(40301, error.code(), "开关关闭返回 40301（AGENTS §5.7）");
        verify(occurrenceMapper, never()).aggregateNodeRates(anyLong(), any(), any());
    }

    @Test
    void nodeRateWithoutPublishedPlansReturnsEmptyRows() {
        when(features.findEnabled("node_follow_rate")).thenReturn(true);
        when(occurrenceMapper.findPublishedPlans()).thenReturn(List.of());
        when(occurrenceMapper.findCohortYears(isNull())).thenReturn(List.of());

        var view = service().nodeRate(null, null, null);

        assertEquals(0L, view.planId());
        assertNull(view.planName());
        assertEquals(List.of(), view.rows());
        verify(occurrenceMapper, never()).aggregateNodeRates(anyLong(), any(), any());
    }

    @Test
    void nodeRateUsesGivenPlanEvenIfNotInPublishedOptions() {
        when(features.findEnabled("node_follow_rate")).thenReturn(true);
        when(occurrenceMapper.findPublishedPlans())
                .thenReturn(List.of(new NodeOccurrenceMapper.PlanOptionRow(11L, "年度随访（示例）")));
        when(occurrenceMapper.aggregateNodeRates(eq(30L), isNull(), isNull()))
                .thenReturn(List.of());
        when(occurrenceMapper.findCohortYears(eq(30L))).thenReturn(List.of());

        var view = service().nodeRate(30L, null, null);

        assertEquals(30L, view.planId(), "指定计划直接生效（未发布 / 无数据时行为 = 空行集）");
        assertNull(view.planName(), "计划不在已发布候选里时名称为空");
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    void rejectsInvalidWardPlanAndCohortYear() {
        ApiException ward = assertThrows(ApiException.class, () -> service().yearlyRate(0L, null));
        assertEquals(40001, ward.code());

        // 参数校验在开关判定之后：开关开启的前提下校验 planId / cohortYear
        lenient().when(features.findEnabled("node_follow_rate")).thenReturn(true);
        ApiException plan =
                assertThrows(ApiException.class, () -> service().nodeRate(-1L, null, null));
        assertEquals(40001, plan.code());

        ApiException cohort =
                assertThrows(ApiException.class, () -> service().nodeRate(null, null, 1700));
        assertEquals(40001, cohort.code());
    }
}
