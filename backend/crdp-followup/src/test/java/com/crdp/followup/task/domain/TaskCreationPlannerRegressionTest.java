package com.crdp.followup.task.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import com.crdp.followup.task.domain.TaskCreationPlanner.PlanResult;
import com.crdp.followup.task.domain.TaskCreationPlanner.PlannedTask;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E14-T09 计划引擎回归用例（多计划、长期年度；tasks.csv 验收「10 年以上年度随访用例通过」）。
 *
 * <p>覆盖 E14-T01..T07 落地后的长期形态回归：
 *
 * <ul>
 *   <li>长期年度（A-FU-001「N 从 1 起逐年递增、不设上限」、A-FU-017 年度预置、Q21 随访不设 10 年上限）： 起算 2016 年的患者随访 12 年
 *       ——每年一次照排、无上限，闰日起算跨 12 年平年 02-28 / 闰年回 02-29（A 规格第 5 章）；
 *   <li>管理周期 N 年（A-FU-002）边界：第 10 年恰在边界内（含），第 11 年不排；
 *   <li>入组已过多年（E14-T05 补录）：已结束窗口的全部年度照常建任务并标补录（Q44）；
 *   <li>起算变更重算（E14-T07 / A-FU-011）在高次数下与调度同一套推进口径；
 *   <li>多条子计划流并行（A-FU-016 计划 > 子计划 > 任务三层；多计划同患者并行的端到端见 PlanEngineRegressionIT）。
 * </ul>
 *
 * <p>全部虚构数据；期望值按 PlanDateCalculator 的月末钳制口径（E14-T03）手算。
 */
class TaskCreationPlannerRegressionTest {

    private static final long ANNUAL_SUBPLAN_ID = 100L;
    private static final long ANNUAL_NODE_ID = 200L;
    private static final long BIENNIAL_SUBPLAN_ID = 101L;
    private static final long BIENNIAL_NODE_ID = 201L;

    /** A 年度形态（A-FU-017 预置同款参数：+1 年、提前 7 天、窗口 ±7 天，子计划持续循环）。 */
    private static NodeDef annualNode() {
        return cycleNode(ANNUAL_NODE_ID, ANNUAL_SUBPLAN_ID, 1, "YEAR", 7, 7, 7, null);
    }

    private static NodeDef annualSubplan() {
        return subplan(ANNUAL_SUBPLAN_ID, "CYCLE", null, 1, "YEAR");
    }

    /** 持续循环节点（parent 子计划 + 偏移 unit×offsetValue、提醒 remind 天、窗口 before/after 天）。 */
    private static NodeDef cycleNode(
            long nodeId,
            long parentId,
            int offsetValue,
            String offsetUnit,
            int remind,
            int before,
            int after,
            String passedWindowAction) {
        return new NodeDef(
                nodeId,
                parentId,
                "NODE",
                "CYCLE",
                null,
                null,
                null,
                "PLAN_START",
                null,
                passedWindowAction,
                offsetValue,
                offsetUnit,
                remind,
                "DAY",
                before,
                "DAY",
                after,
                "DAY");
    }

    private static NodeDef subplan(
            long id, String repeatMode, Integer repeatCount, Integer cycleValue, String cycleUnit) {
        return new NodeDef(
                id,
                null,
                "SUBPLAN",
                repeatMode,
                repeatCount,
                cycleValue,
                cycleUnit,
                null,
                null,
                null,
                0,
                "YEAR",
                0,
                "DAY",
                0,
                "DAY",
                0,
                "DAY");
    }

    // ---------------------------------------------------------------- 长期年度：10 年以上逐年照排、不设上限

    @Test
    void annualPlanSchedulesTwelveYearsOfOccurrencesWithNoUpperBound() {
        // 起算（出院）2016-03-15、入组 2016-04-01、逻辑日 2028-06-01：第 1..12 次全部应建（12 年 > 验收的 10 年）
        PlanResult result =
                TaskCreationPlanner.plan(
                        List.of(annualSubplan(), annualNode()),
                        "NORMAL",
                        LocalDate.of(2016, 3, 15),
                        LocalDate.of(2028, 6, 1),
                        LocalDate.of(2016, 4, 1),
                        null);
        assertEquals(12, result.due().size(), "12 个年度次数全部应建（A-FU-001 不设上限）");
        for (int occurrence = 1; occurrence <= 12; occurrence++) {
            PlannedTask task = result.due().get(occurrence - 1);
            LocalDate planDate = LocalDate.of(2016, 3, 15).plusYears(occurrence);
            assertEquals(occurrence, task.occurrence());
            assertEquals(planDate, task.planDate(), "第 " + occurrence + " 次计划日期 = 起算 + N 年");
            assertEquals(planDate.minusDays(7), task.remindStart(), "提前 7 天提醒");
            assertEquals(planDate.minusDays(7), task.windowStart());
            assertEquals(planDate.plusDays(7), task.windowEnd());
            assertEquals(planDate.minusDays(7), task.openDate(), "开启日 = 提醒开始日与窗口开始日较早一天（同日）");
            assertEquals("NORMAL", task.createType(), "入组后逐日调度创建方式恒为正常");
        }
        // 下次（第 13 次）缓存：长期随访不因年数多而停止
        assertEquals(13, result.next().occurrence());
        assertEquals(LocalDate.of(2029, 3, 15), result.next().planDate());
        assertEquals(LocalDate.of(2029, 3, 8), result.next().openDate());
    }

    @Test
    void leapDayBasisKeepsFebEndInCommonYearsAndReturnsOnLeapYearsAcrossTwelveYears() {
        // 闰日出院（A 规格第 5 章：2 月 29 日起算的平年取 2 月 28 日）跨 3 个闰年回归：
        // 平年 02-28、闰年（2020 / 2024 / 2028）回到 02-29，窗口 ±7 不漂移
        PlanResult result =
                TaskCreationPlanner.plan(
                        List.of(annualSubplan(), annualNode()),
                        "NORMAL",
                        LocalDate.of(2016, 2, 29),
                        LocalDate.of(2028, 6, 1),
                        LocalDate.of(2016, 3, 1),
                        null);
        assertEquals(12, result.due().size());
        assertEquals(LocalDate.of(2017, 2, 28), result.due().get(0).planDate(), "平年钳 02-28");
        assertEquals(LocalDate.of(2020, 2, 29), result.due().get(3).planDate(), "闰年回 02-29");
        assertEquals(LocalDate.of(2021, 2, 28), result.due().get(4).planDate(), "闰年后的平年再钳 02-28");
        assertEquals(LocalDate.of(2024, 2, 29), result.due().get(7).planDate());
        assertEquals(LocalDate.of(2028, 2, 29), result.due().get(11).planDate());
        PlannedTask twelfth = result.due().get(11);
        assertEquals(LocalDate.of(2028, 2, 22), twelfth.remindStart());
        assertEquals(LocalDate.of(2028, 2, 22), twelfth.openDate());
        assertEquals(LocalDate.of(2028, 3, 7), twelfth.windowEnd(), "含 02-29 的窗口宽度不漂移");
        assertEquals(LocalDate.of(2029, 2, 28), result.next().planDate(), "下次又回到平年口径");
    }

    @Test
    void tenYearManagementCycleIncludesBoundaryYearAndStopsAfterIt() {
        // 计划管理周期 10 年（A-FU-002 cycle_type=YEARS）：第 10 次恰在边界内（含边界年），第 11 年不排、无下次
        PlanResult result =
                TaskCreationPlanner.plan(
                        List.of(annualSubplan(), annualNode()),
                        "NORMAL",
                        LocalDate.of(2016, 3, 15),
                        LocalDate.of(2030, 6, 1),
                        LocalDate.of(2016, 4, 1),
                        LocalDate.of(2026, 3, 15));
        assertEquals(10, result.due().size(), "边界年内（含第 10 年）排完即止");
        assertEquals(LocalDate.of(2026, 3, 15), result.due().get(9).planDate());
        assertNull(result.next(), "管理周期外无下次（固定周期完成的「随访结束」由展示层派生）");
    }

    // ---------------------------------------------------------------- 入组已过多年（E14-T05 补录回归）

    @Test
    void enrollmentAfterTenYearsBackfillsAllPassedAnnualWindows() {
        // 起算 2016-03-15、十年后才建档入组（2026-11-05）：第 1..10 次窗口全部已结束 → 照常建任务并标补录（Q44）
        NodeDef backfillAnnual =
                cycleNode(ANNUAL_NODE_ID, ANNUAL_SUBPLAN_ID, 1, "YEAR", 7, 7, 7, "BACKFILL");
        PlanResult result =
                TaskCreationPlanner.planForEnrollment(
                        List.of(annualSubplan(), backfillAnnual),
                        "NORMAL",
                        LocalDate.of(2016, 3, 15),
                        LocalDate.of(2026, 11, 5),
                        null);
        assertEquals(10, result.due().size(), "十年已结束窗口全部补建（长期年度不因年数多漏排）");
        for (int occurrence = 1; occurrence <= 10; occurrence++) {
            PlannedTask task = result.due().get(occurrence - 1);
            assertEquals(occurrence, task.occurrence());
            assertEquals("BACKFILL", task.createType(), "第 " + occurrence + " 次标补录");
            assertEquals(LocalDate.of(2016, 3, 15).plusYears(occurrence), task.planDate());
        }
        assertEquals(11, result.next().occurrence(), "第 11 次未开启 → 只进缓存不预生成");
        assertEquals(LocalDate.of(2027, 3, 15), result.next().planDate());
        assertEquals(LocalDate.of(2027, 3, 8), result.next().openDate());
    }

    // ---------------------------------------------------------------- 起算变更重算在高次数下的口径回归（E14-T07）

    @Test
    void recalculationAtEleventhOccurrenceMatchesDailyCycleMath() {
        // 第 11 次（10 年以上）重算：与 plan() 同一套「起算 + N 年」推进，窗口 / 提醒随计划日期
        PlanDateCalculator.NodeSchedule schedule =
                TaskCreationPlanner.occurrenceScheduleOf(
                        List.of(annualSubplan(), annualNode()),
                        ANNUAL_NODE_ID,
                        11,
                        LocalDate.of(2016, 3, 15));
        assertEquals(LocalDate.of(2027, 3, 15), schedule.planDate(), "第 11 次 = 起算 + 11 年");
        assertEquals(LocalDate.of(2027, 3, 8), schedule.remindStart());
        assertEquals(LocalDate.of(2027, 3, 8), schedule.openDate());
        assertEquals(LocalDate.of(2027, 3, 22), schedule.windowEnd());
        // 闰日起算的高次数：第 12 次落在闰年回 02-29
        PlanDateCalculator.NodeSchedule leap =
                TaskCreationPlanner.occurrenceScheduleOf(
                        List.of(annualSubplan(), annualNode()),
                        ANNUAL_NODE_ID,
                        12,
                        LocalDate.of(2016, 2, 29));
        assertEquals(LocalDate.of(2028, 2, 29), leap.planDate());
        assertEquals(LocalDate.of(2028, 3, 7), leap.windowEnd());
    }

    // ---------------------------------------------------------------- 多条子计划流并行（多计划的排期面）

    @Test
    void annualAndBiennialSubplansScheduleBothStreamsTogether() {
        // 同一计划下年度 + 两年一次（如「以后增加的专病计划」的节点面，A 规格第 5 章）并行：
        // 两条流各自按自己的偏移 / 周期 / 窗口推进，due 按开启日排序，下次缓存取两流中最早
        NodeDef biennial =
                new NodeDef(
                        BIENNIAL_NODE_ID,
                        BIENNIAL_SUBPLAN_ID,
                        "NODE",
                        "CYCLE",
                        null,
                        null,
                        null,
                        "PLAN_START",
                        null,
                        null,
                        2,
                        "YEAR",
                        14,
                        "DAY",
                        14,
                        "DAY",
                        30,
                        "DAY");
        List<NodeDef> nodes =
                List.of(
                        annualSubplan(),
                        subplan(BIENNIAL_SUBPLAN_ID, "CYCLE", null, 2, "YEAR"),
                        annualNode(),
                        biennial);
        PlanResult result =
                TaskCreationPlanner.plan(
                        nodes,
                        "NORMAL",
                        LocalDate.of(2016, 3, 15),
                        LocalDate.of(2020, 6, 1),
                        LocalDate.of(2016, 4, 1),
                        null);
        // 年度第 1..4 次（开启 2017/2018/2019/2020-03-08）+ 两年一次第 1..2 次（开启 2018/2020-03-01）
        assertEquals(6, result.due().size(), "两条子计划流的次数互不干扰、全部应建");
        List<Long> dueNodes = result.due().stream().map(PlannedTask::nodeId).toList();
        assertEquals(
                List.of(
                        ANNUAL_NODE_ID, // 2017-03-08
                        BIENNIAL_NODE_ID, // 2018-03-01
                        ANNUAL_NODE_ID, // 2018-03-08
                        ANNUAL_NODE_ID, // 2019-03-08
                        BIENNIAL_NODE_ID, // 2020-03-01
                        ANNUAL_NODE_ID), // 2020-03-08
                dueNodes,
                "due 按开启日稳定排序（年度与两年一次交错）");
        PlannedTask biennialSecond =
                result.due().stream()
                        .filter(task -> task.nodeId() == BIENNIAL_NODE_ID && task.occurrence() == 2)
                        .findFirst()
                        .orElseThrow();
        assertEquals(LocalDate.of(2020, 3, 15), biennialSecond.planDate(), "两年一次第 2 次 = 起算 + 4 年");
        assertEquals(LocalDate.of(2020, 3, 1), biennialSecond.remindStart(), "提前 14 天提醒");
        assertEquals(LocalDate.of(2020, 3, 1), biennialSecond.openDate());
        assertEquals(LocalDate.of(2020, 4, 14), biennialSecond.windowEnd(), "窗口后 30 天（不对称窗口）");
        assertEquals(LocalDate.of(2021, 3, 15), result.next().planDate(), "下次取两流中最早（年度第 5 次）");
        assertEquals(LocalDate.of(2021, 3, 8), result.next().openDate());
        assertEquals(ANNUAL_NODE_ID, result.next().nodeId());
    }

    /** 起算日期与各次计划日期的推进辅助断言（plusYears 与月末钳制口径下 12 年逐年对照）。 */
    @Test
    void monthEndBasisKeepsMonthDayAcrossTwelveYears() {
        // 8-31 出院（大月末）：+N 年恒为 8-31，不受月末钳制影响；避免跨 12 年链加漂移
        List<LocalDate> planDates = new ArrayList<>();
        for (int occurrence = 1; occurrence <= 12; occurrence++) {
            planDates.add(
                    PlanDateCalculator.occurrencePlanDate(
                            LocalDate.of(2016, 8, 31),
                            new PlanDateCalculator.NodeTiming(
                                    1, "YEAR", 7, "DAY", 7, "DAY", 7, "DAY"),
                            1,
                            "YEAR",
                            occurrence));
        }
        for (int occurrence = 1; occurrence <= 12; occurrence++) {
            assertEquals(
                    LocalDate.of(2016, 8, 31).plusYears(occurrence),
                    planDates.get(occurrence - 1),
                    "第 " + occurrence + " 次 8-31 不漂移");
        }
    }
}
