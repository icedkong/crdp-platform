package com.crdp.followup.plan.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * E14-T03 / A-FU-002、A-NFR-006 日期计算库标准用例：偏移（天 / 周 / 月 / 年）、提醒开始日、窗口前后与 任务开启日 = min（提醒开始日，窗口开始日）。
 * 与前端 packages/rules/plan-date（E14-T02）共享用例——A 年度、B 3 个月、B 1 年、B 术后第 1 / 3 / 5 天四个验算例 （pages.md
 * §8、tech-plan §8「验算」表），另含验收要求的跨年、闰年、月末与周期 N 次用例。全部虚构数据。
 */
class PlanDateCalculatorTest {

    /** A 医院年度随访（A-FU-002 / A-FU-017）：+1 年、提前 7 天、窗口前后 7 天。 */
    private static final PlanDateCalculator.NodeTiming PLAN_A_ANNUAL =
            new PlanDateCalculator.NodeTiming(1, "YEAR", 7, "DAY", 7, "DAY", 7, "DAY");

    /** 医院 B 3 个月节点（hospital-a-spec §14）：+3 个月、提前 2 周、前后 14 天。 */
    private static final PlanDateCalculator.NodeTiming PLAN_B_3M =
            new PlanDateCalculator.NodeTiming(3, "MONTH", 2, "WEEK", 14, "DAY", 14, "DAY");

    /** 医院 B 1 年节点（§14）：+1 年、提前 1 个月、前后 1 个月。 */
    private static final PlanDateCalculator.NodeTiming PLAN_B_1Y =
            new PlanDateCalculator.NodeTiming(1, "YEAR", 1, "MONTH", 1, "MONTH", 1, "MONTH");

    private static PlanDateCalculator.NodeTiming postOpDay(int days) {
        return new PlanDateCalculator.NodeTiming(days, "DAY", 0, "DAY", 0, "DAY", 1, "DAY");
    }

    @Test
    void addDateUnitSupportsDayAndWeek() {
        assertEquals(
                LocalDate.of(2026, 3, 2),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 1), 1, "DAY"));
        assertEquals(
                LocalDate.of(2026, 3, 4),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 1), 3, "DAY"));
        assertEquals(
                LocalDate.of(2026, 3, 6),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 1), 5, "DAY"));
        assertEquals(
                LocalDate.of(2026, 3, 15),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 1), 2, "WEEK"));
    }

    @Test
    void addMonthClampsToMonthEndAndCrossesYear() {
        // V46 / A-FU-016：按月 / 年加遇目标月没有该日取当月最后一天
        assertEquals(
                LocalDate.of(2026, 6, 1),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 1), 3, "MONTH"));
        assertEquals(
                LocalDate.of(2027, 1, 20),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 12, 20), 1, "MONTH"));
        assertEquals(
                LocalDate.of(2025, 2, 28),
                PlanDateCalculator.addDateUnit(LocalDate.of(2025, 1, 31), 1, "MONTH"));
        assertEquals(
                LocalDate.of(2026, 2, 28),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 1, 31), 1, "MONTH"));
    }

    @Test
    void addYearHandlesLeapDayAndCrossesYear() {
        // A 规格关键字段口径：2 月 29 日起算的平年取 2 月 28 日
        assertEquals(
                LocalDate.of(2026, 3, 15),
                PlanDateCalculator.addDateUnit(LocalDate.of(2025, 3, 15), 1, "YEAR"));
        assertEquals(
                LocalDate.of(2025, 2, 28),
                PlanDateCalculator.addDateUnit(LocalDate.of(2024, 2, 29), 1, "YEAR"));
        assertEquals(
                LocalDate.of(2026, 2, 28),
                PlanDateCalculator.addDateUnit(LocalDate.of(2025, 2, 28), 1, "YEAR"));
        assertEquals(
                LocalDate.of(2028, 2, 29),
                PlanDateCalculator.addDateUnit(LocalDate.of(2024, 2, 29), 4, "YEAR"));
        assertEquals(
                LocalDate.of(2036, 11, 5),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 11, 5), 10, "YEAR"));
    }

    @Test
    void subtractUsesSameClampingForRemindAndWindowBefore() {
        assertEquals(
                LocalDate.of(2026, 5, 18),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 6, 1), -2, "WEEK"));
        assertEquals(
                LocalDate.of(2027, 2, 1),
                PlanDateCalculator.addDateUnit(LocalDate.of(2027, 3, 1), -1, "MONTH"));
        assertEquals(
                LocalDate.of(2026, 2, 28),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 31), -1, "MONTH"));
        assertEquals(
                LocalDate.of(2026, 3, 15),
                PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 15), 0, "DAY"));
    }

    @Test
    void addDateUnitRejectsUnknownUnit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanDateCalculator.addDateUnit(LocalDate.of(2026, 3, 1), 1, "HOUR"));
    }

    @Test
    void hospitalAAnnualCaseMatchesSharedSpec() {
        // 出院 2025-03-15 → 计划日期 2026-03-15、开启日 2026-03-08（tech-plan §8 验算表原句）
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(PLAN_A_ANNUAL, LocalDate.of(2025, 3, 15));
        assertEquals(LocalDate.of(2026, 3, 15), schedule.planDate());
        assertEquals(LocalDate.of(2026, 3, 8), schedule.remindStart());
        assertEquals(LocalDate.of(2026, 3, 8), schedule.windowStart());
        assertEquals(LocalDate.of(2026, 3, 22), schedule.windowEnd());
        assertEquals(LocalDate.of(2026, 3, 8), schedule.openDate());
    }

    @Test
    void hospitalBThreeMonthCaseMatchesSharedSpec() {
        // 手术 2026-03-01 → 计划 2026-06-01、2026-05-18 开启、窗口至 06-15（§14 示例）
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(PLAN_B_3M, LocalDate.of(2026, 3, 1));
        assertEquals(LocalDate.of(2026, 6, 1), schedule.planDate());
        assertEquals(LocalDate.of(2026, 5, 18), schedule.remindStart());
        assertEquals(LocalDate.of(2026, 5, 18), schedule.windowStart());
        assertEquals(LocalDate.of(2026, 6, 15), schedule.windowEnd());
        assertEquals(LocalDate.of(2026, 5, 18), schedule.openDate());
    }

    @Test
    void hospitalBOneYearCaseMatchesSharedSpec() {
        // 手术 2026-03-01 → 计划 2027-03-01、2027-02-01 开启、窗口至 04-01（§14 示例；提醒 / 窗口按月减）
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(PLAN_B_1Y, LocalDate.of(2026, 3, 1));
        assertEquals(LocalDate.of(2027, 3, 1), schedule.planDate());
        assertEquals(LocalDate.of(2027, 2, 1), schedule.remindStart());
        assertEquals(LocalDate.of(2027, 2, 1), schedule.windowStart());
        assertEquals(LocalDate.of(2027, 4, 1), schedule.windowEnd());
        assertEquals(LocalDate.of(2027, 2, 1), schedule.openDate());
    }

    @Test
    void hospitalBPostOpDayCasesMatchSharedSpec() {
        // 手术 2026-03-01、出院 2026-03-08 建档：第 1 / 3 / 5 天计划日期 03-02 / 03-04 / 03-06，窗口当日开启、次日出窗
        PlanDateCalculator.NodeSchedule day1 =
                PlanDateCalculator.computeNodeSchedule(postOpDay(1), LocalDate.of(2026, 3, 1));
        assertEquals(LocalDate.of(2026, 3, 2), day1.planDate());
        assertEquals(LocalDate.of(2026, 3, 2), day1.remindStart());
        assertEquals(LocalDate.of(2026, 3, 2), day1.windowStart());
        assertEquals(LocalDate.of(2026, 3, 3), day1.windowEnd());
        assertEquals(LocalDate.of(2026, 3, 2), day1.openDate());
        assertEquals(
                LocalDate.of(2026, 3, 4),
                PlanDateCalculator.computeNodeSchedule(postOpDay(3), LocalDate.of(2026, 3, 1))
                        .planDate());
        assertEquals(
                LocalDate.of(2026, 3, 6),
                PlanDateCalculator.computeNodeSchedule(postOpDay(5), LocalDate.of(2026, 3, 1))
                        .planDate());
    }

    @Test
    void hospitalCAsymmetricWindowCase() {
        // C 术后 72 小时（hospital-c-spec §5）：计划日期 = 植入 + 3 天，窗口 = 植入日至植入后 7 天（前 3 后 4）
        PlanDateCalculator.NodeTiming timing =
                new PlanDateCalculator.NodeTiming(3, "DAY", 0, "DAY", 3, "DAY", 4, "DAY");
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(timing, LocalDate.of(2026, 5, 10));
        assertEquals(LocalDate.of(2026, 5, 13), schedule.planDate());
        assertEquals(LocalDate.of(2026, 5, 10), schedule.windowStart());
        assertEquals(LocalDate.of(2026, 5, 17), schedule.windowEnd());
    }

    @Test
    void hospitalCRollingBasisCase() {
        // C 常规随访按上次实际随访日期滚动：+12 个月、前后 30 天（基准 LAST_VISIT 只是调用方换传基准日期）
        PlanDateCalculator.NodeTiming timing =
                new PlanDateCalculator.NodeTiming(12, "MONTH", 0, "DAY", 30, "DAY", 30, "DAY");
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(timing, LocalDate.of(2026, 6, 20));
        assertEquals(LocalDate.of(2027, 6, 20), schedule.planDate());
        assertEquals(LocalDate.of(2027, 5, 21), schedule.windowStart());
        assertEquals(LocalDate.of(2027, 7, 20), schedule.windowEnd());
        assertEquals(LocalDate.of(2027, 5, 21), schedule.openDate());
    }

    @Test
    void openDateIsEarlierOfRemindStartAndWindowStart() {
        // 提醒提前量 0（当天提醒）时开启日 = 窗口开始日（A-FU-016）
        PlanDateCalculator.NodeTiming timing =
                new PlanDateCalculator.NodeTiming(1, "YEAR", 0, "DAY", 7, "DAY", 7, "DAY");
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(timing, LocalDate.of(2025, 3, 15));
        assertEquals(LocalDate.of(2026, 3, 15), schedule.remindStart());
        assertEquals(LocalDate.of(2026, 3, 8), schedule.windowStart());
        assertEquals(LocalDate.of(2026, 3, 8), schedule.openDate());
    }

    @Test
    void remindUnitCanBeWeekOrMonth() {
        assertEquals(
                LocalDate.of(2026, 3, 8),
                PlanDateCalculator.computeNodeSchedule(
                                new PlanDateCalculator.NodeTiming(
                                        1, "YEAR", 1, "WEEK", 7, "DAY", 7, "DAY"),
                                LocalDate.of(2025, 3, 15))
                        .remindStart());
        assertEquals(
                LocalDate.of(2026, 2, 15),
                PlanDateCalculator.computeNodeSchedule(
                                new PlanDateCalculator.NodeTiming(
                                        1, "YEAR", 1, "MONTH", 7, "DAY", 7, "DAY"),
                                LocalDate.of(2025, 3, 15))
                        .remindStart());
    }

    @Test
    void occurrencePlanDateForAnnualCycleIsBasisPlusNYears() {
        // A-FU-001：计划日期 = 出院日期 + N 年，N 从 1 起逐年递增（偏移 1 年 + 周期 1 年：第 1 次 = 基准 + 1 年）
        assertEquals(
                LocalDate.of(2026, 3, 15),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2025, 3, 15), PLAN_A_ANNUAL, 1, "YEAR", 1));
        assertEquals(
                LocalDate.of(2028, 3, 15),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2025, 3, 15), PLAN_A_ANNUAL, 1, "YEAR", 3));
        assertEquals(
                LocalDate.of(2036, 3, 15),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2025, 3, 15), PLAN_A_ANNUAL, 1, "YEAR", 11));
    }

    @Test
    void occurrencePlanDateHandlesLeapYearBasisAcrossYears() {
        // 闰日出院 + N 年：平年取 2 月 28 日、闰年回到 2 月 29 日（跨年 + 闰年验收用例）
        assertEquals(
                LocalDate.of(2025, 2, 28),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2024, 2, 29), PLAN_A_ANNUAL, 1, "YEAR", 1));
        assertEquals(
                LocalDate.of(2026, 2, 28),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2024, 2, 29), PLAN_A_ANNUAL, 1, "YEAR", 2));
        assertEquals(
                LocalDate.of(2028, 2, 29),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2024, 2, 29), PLAN_A_ANNUAL, 1, "YEAR", 4));
        assertEquals(
                LocalDate.of(2034, 2, 28),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2024, 2, 29), PLAN_A_ANNUAL, 1, "YEAR", 10));
    }

    @Test
    void occurrencePlanDateAddsCalendarUnitsInOneStepForMonthEnd() {
        // 计划日期 = 基准 + 偏移 + (N−1)×周期 一次性相加：1-31 偏移 1 月周期 1 月的第 2 次是 3-31，不是逐次链加的 3-28
        assertEquals(
                LocalDate.of(2026, 3, 31),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2026, 1, 31),
                        new PlanDateCalculator.NodeTiming(1, "MONTH", 7, "DAY", 7, "DAY", 7, "DAY"),
                        1,
                        "MONTH",
                        2));
    }

    @Test
    void occurrencePlanDateSupportsWeekAndMixedCycleUnits() {
        // B「术后第 N 天」族（FIXED）：+3 天节点第 1 次
        assertEquals(
                LocalDate.of(2026, 3, 4),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2026, 3, 1), postOpDay(3), 1, "WEEK", 1));
        // 混合单位（偏移天 + 周期年）：先加偏移再整体加 (N−1)×周期
        assertEquals(
                LocalDate.of(2029, 3, 4),
                PlanDateCalculator.occurrencePlanDate(
                        LocalDate.of(2026, 3, 1), postOpDay(3), 1, "YEAR", 4));
    }

    @Test
    void occurrencePlanDateRejectsNonPositiveOccurrence() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlanDateCalculator.occurrencePlanDate(
                                LocalDate.of(2026, 3, 1), PLAN_A_ANNUAL, 1, "YEAR", 0));
    }

    @Test
    void nodeTimingCanBeBuiltFromNormalizedNode() {
        // E14-T01 的 NormalizedNode → 日期计算参数（fu_plan_node 列名同口径）
        PlanNodeRule.NormalizedNode node =
                new PlanNodeRule.NormalizedNode(
                        "NODE",
                        "s1",
                        "电话随访（示例）",
                        null,
                        null,
                        null,
                        null,
                        "PLAN_START",
                        1,
                        "YEAR",
                        7,
                        "DAY",
                        7,
                        "DAY",
                        7,
                        "DAY",
                        "FORM",
                        "WARD_DEFAULT",
                        "fu_annual_v1",
                        null,
                        null,
                        20);
        assertEquals(PLAN_A_ANNUAL, PlanDateCalculator.NodeTiming.from(node));
    }

    @Test
    void businessTodayUsesBeijingZone() {
        // A-NFR-006：所有日期按北京时间计算窗口与通知（UTC 16:30 已是上海次日 00:30）
        assertEquals(
                LocalDate.of(2026, 11, 4),
                PlanDateCalculator.businessToday(
                        ZonedDateTime.of(2026, 11, 3, 16, 30, 0, 0, ZoneOffset.UTC).toInstant()));
        assertEquals(
                LocalDate.of(2026, 11, 3),
                PlanDateCalculator.businessToday(
                        ZonedDateTime.of(2026, 11, 3, 15, 30, 0, 0, ZoneOffset.UTC).toInstant()));
        assertEquals(
                ZoneOffset.ofHours(8),
                PlanDateCalculator.BUSINESS_ZONE.getRules().getOffset(java.time.Instant.EPOCH));
    }
}
