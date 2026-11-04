package com.crdp.followup.task.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import com.crdp.followup.task.domain.TaskCreationPlanner.PlanResult;
import com.crdp.followup.task.domain.TaskCreationPlanner.PlannedTask;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** E14-T04 / A-FU-001、C-FU-004、A-NFR-006 排期纯函数单测（验收：任务在提醒开始日与窗口开始日较早一天创建）。 全部虚构数据。 */
class TaskCreationPlannerTest {

    private static final long SUBPLAN_ID = 100L;
    private static final long NODE_ID = 200L;

    /** A 医院年度形态（A-FU-017）：持续循环每年一次、节点 +1 年、提前 7 天、窗口 ±7 天。 */
    private static NodeDef annualNode() {
        return node("CYCLE", null, 1, "YEAR", "PLAN_START", 1, "YEAR", 7, 7, 7, null, null);
    }

    private static NodeDef node(
            String repeatMode,
            Integer repeatCount,
            Integer cycleValue,
            String cycleUnit,
            String basis,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            int windowBefore,
            int windowAfter,
            String enrollMode,
            String passedWindowAction) {
        return new NodeDef(
                NODE_ID,
                SUBPLAN_ID,
                "NODE",
                repeatMode,
                repeatCount,
                cycleValue,
                cycleUnit,
                basis,
                enrollMode,
                passedWindowAction,
                offsetValue,
                offsetUnit,
                remindValue,
                "DAY",
                windowBefore,
                "DAY",
                windowAfter,
                "DAY");
    }

    private static NodeDef subplan(
            String repeatMode, Integer repeatCount, Integer cycleValue, String cycleUnit) {
        return new NodeDef(
                SUBPLAN_ID,
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

    /** 医院 B 里程碑节点（第 14 章）：术后第 N 天、当天提醒、窗口前 0 后 1、入组前已过窗口策略可配。 */
    private static NodeDef milestoneNode(long nodeId, int dayOffset, String passedWindowAction) {
        return new NodeDef(
                nodeId,
                SUBPLAN_ID,
                "NODE",
                null,
                null,
                null,
                null,
                "PLAN_START",
                null,
                passedWindowAction,
                dayOffset,
                "DAY",
                0,
                "DAY",
                0,
                "DAY",
                1,
                "DAY");
    }

    private static List<NodeDef> planOf(NodeDef node, NodeDef subplan) {
        return List.of(subplan, node);
    }

    // ---------------------------------------------------------------- 开启日 = 提醒开始日与窗口开始日较早一天

    @Test
    void opensOnEarlierOfRemindStartAndWindowStart() {
        NodeDef remindEarlier =
                node("CYCLE", null, 1, "YEAR", "PLAN_START", 1, "YEAR", 14, 3, 7, null, null);
        // 计划日期 2026-03-15：提醒开始日 2026-03-01（−14 天）、窗口开始日 2026-03-12（−3 天）→ 开启日 03-01
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(remindEarlier, subplan("CYCLE", null, 1, "YEAR")),
                        "NORMAL",
                        LocalDate.of(2025, 3, 15),
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2025, 4, 1),
                        null);
        assertEquals(1, result.due().size(), "开启日当天即创建（提醒开始日较早）");
        PlannedTask task = result.due().get(0);
        assertEquals(LocalDate.of(2026, 3, 1), task.openDate(), "开启日 = 提醒开始日（较早一天）");
        assertEquals(LocalDate.of(2026, 3, 12), task.windowStart());
        assertEquals(LocalDate.of(2026, 3, 1), task.remindStart());
        assertEquals(
                LocalDate.of(2027, 3, 1), result.next().openDate(), "第 2 次开启日在 2027-03-01，作为下次缓存");
    }

    @Test
    void opensOnWindowStartWhenWindowOpensEarlier() {
        NodeDef windowEarlier =
                node("CYCLE", null, 1, "YEAR", "PLAN_START", 1, "YEAR", 3, 14, 7, null, null);
        // 计划日期 2026-03-15：提醒开始日 2026-03-12（−3 天）、窗口开始日 2026-03-01（−14 天）→ 开启日 03-01
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(windowEarlier, subplan("CYCLE", null, 1, "YEAR")),
                        "NORMAL",
                        LocalDate.of(2025, 3, 15),
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2025, 4, 1),
                        null);
        assertEquals(1, result.due().size(), "窗口开始日较早 → 开启日 = 窗口开始日");
        assertEquals(LocalDate.of(2026, 3, 1), result.due().get(0).openDate());
        assertEquals(LocalDate.of(2026, 3, 1), result.due().get(0).windowStart());
    }

    @Test
    void notCreatedBeforeOpenDateAndNextCacheTracksFirstUpcoming() {
        // 逻辑日 2026-03-07（开启日 03-08 之前）→ 不创建，next 缓存 = 第 1 次
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(annualNode(), subplan("CYCLE", null, 1, "YEAR")),
                        "NORMAL",
                        LocalDate.of(2025, 3, 15),
                        LocalDate.of(2026, 3, 7),
                        LocalDate.of(2025, 4, 1),
                        null);
        assertTrue(result.due().isEmpty(), "开启日未到不创建（未来节点只在时间轴展示）");
        assertEquals(LocalDate.of(2026, 3, 8), result.next().openDate(), "A 年度开启日 = 计划日期 −7 天");
        assertEquals(LocalDate.of(2026, 3, 15), result.next().planDate(), "下次计划日期缓存");
    }

    // ---------------------------------------------------------------- 多次与补跑

    @Test
    void catchUpCreatesAllOccurrencesOpenedByLogicalDate() {
        // 月度循环：第 1/2/3 次开启日均不晚于逻辑日 → 一次补跑全部创建；next = 第 4 次
        NodeDef monthly =
                node("CYCLE", null, 1, "MONTH", "PLAN_START", 1, "MONTH", 7, 7, 7, null, null);
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(monthly, subplan("CYCLE", null, 1, "MONTH")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 15),
                        LocalDate.of(2026, 4, 10),
                        LocalDate.of(2026, 1, 20),
                        null);
        assertEquals(3, result.due().size(), "第 1（02-08 开启）/2（03-08）/3（04-08）次均已开启");
        assertEquals(1, result.due().get(0).occurrence());
        assertEquals(LocalDate.of(2026, 2, 15), result.due().get(0).planDate());
        assertEquals(3, result.due().get(2).occurrence());
        assertEquals(LocalDate.of(2026, 4, 15), result.due().get(2).planDate());
        assertEquals(LocalDate.of(2026, 5, 8), result.next().openDate(), "第 4 次开启日缓存");
    }

    @Test
    void occurrencesWhoseWindowEndedBeforeEnrollmentAreLeftToBackfillPolicy() {
        // 迟入组：第 1 次窗口 02-08..02-22 在入组日（03-01）前已结束 → 留给 E14-T05 的 passed_window_action；
        // 第 2 次开启日 03-08 已到 → 正常创建
        NodeDef monthly =
                node("CYCLE", null, 1, "MONTH", "PLAN_START", 1, "MONTH", 7, 7, 7, null, null);
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(monthly, subplan("CYCLE", null, 1, "MONTH")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 15),
                        LocalDate.of(2026, 3, 10),
                        LocalDate.of(2026, 3, 1),
                        null);
        assertEquals(1, result.due().size(), "入组前已结束窗口的次数不属每日调度（E14-T05 补录 / 跳过）");
        assertEquals(2, result.due().get(0).occurrence());
    }

    // ---------------------------------------------------------------- FIXED 子计划

    @Test
    void fixedSubplanWithoutStoredCycleFallsBackToOffsetAsPeriod() {
        // 配置面 FIXED 只收次数不收周期（PlanNodeRule）：周期回落节点偏移 → 第 N 次 = 基准 + N × 偏移
        NodeDef yearly = node("FIXED", 3, null, null, "PLAN_START", 1, "YEAR", 7, 7, 7, null, null);
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(yearly, subplan("FIXED", 3, null, null)),
                        "NORMAL",
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2032, 1, 1),
                        LocalDate.of(2026, 3, 5),
                        null);
        assertEquals(3, result.due().size(), "固定 3 次全部排完");
        assertEquals(LocalDate.of(2027, 3, 1), result.due().get(0).planDate());
        assertEquals(LocalDate.of(2028, 3, 1), result.due().get(1).planDate());
        assertEquals(LocalDate.of(2029, 3, 1), result.due().get(2).planDate());
        assertNull(result.next(), "固定次数排完 → 无下次（缓存清空）");
    }

    @Test
    void fixedSubplanUsesStoredCycleWhenPresent() {
        NodeDef node = node("FIXED", 3, 6, "MONTH", "PLAN_START", 1, "MONTH", 0, 7, 7, null, null);
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(node, subplan("FIXED", 3, 6, "MONTH")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2027, 6, 1),
                        LocalDate.of(2026, 2, 1),
                        null);
        // 第 2 次 = 基准 + 偏移 1 月 + 周期 6 月，月 / 年总量一次相加保月末：2026-01-31 + 7 月 = 2026-08-31
        assertEquals(3, result.due().size());
        assertEquals(LocalDate.of(2026, 8, 31), result.due().get(1).planDate(), "月末一次相加不漂移");
    }

    // ---------------------------------------------------------------- 计划管理周期 / 基准 / 启用条件

    @Test
    void yearsCycleTypeBoundsOccurrences() {
        // 计划管理周期 = 2 年（cycle_type=YEARS）：第 3 次计划日期 2028-03-15 超出边界不排
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(annualNode(), subplan("CYCLE", null, 1, "YEAR")),
                        "NORMAL",
                        LocalDate.of(2026, 3, 15),
                        LocalDate.of(2030, 1, 1),
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2028, 3, 15));
        assertEquals(2, result.due().size(), "边界年内（含第 N 年）排完即止");
        assertNull(result.next());
    }

    @Test
    void lastVisitBasisSchedulesOnlyFirstOccurrence() {
        // C 常规随访按上次实际随访日期滚动：无已提交记录（fu_record 随 E15）时只排第 1 次（基准回落起算日期）
        NodeDef rolling =
                node("CYCLE", null, 1, "YEAR", "LAST_VISIT", 1, "YEAR", 7, 7, 7, null, null);
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(rolling, subplan("CYCLE", null, 1, "YEAR")),
                        "NORMAL",
                        LocalDate.of(2026, 3, 15),
                        LocalDate.of(2030, 1, 1),
                        LocalDate.of(2026, 4, 1),
                        null);
        assertEquals(1, result.due().size(), "滚动基准只排第 1 次（后续按实际随访日期，随 E15 接入）");
        assertEquals(1, result.due().get(0).occurrence());
    }

    @Test
    void enrollModeMismatchedNodesAreSkipped() {
        // C：ERI 期循环节点只在 ERI 模式生效；常规入组不排
        NodeDef eriOnly =
                node("CYCLE", null, 1, "MONTH", "PLAN_START", 1, "MONTH", 7, 7, 7, "ERI", null);
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(eriOnly, subplan("CYCLE", null, 1, "MONTH")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 15),
                        LocalDate.of(2026, 4, 10),
                        LocalDate.of(2026, 1, 20),
                        null);
        assertTrue(result.due().isEmpty(), "启用条件不匹配的节点不排（C-FU-001 ERI）");
        assertNull(result.next());
        // ERI 入组 → 排
        PlanResult eri =
                TaskCreationPlanner.plan(
                        planOf(eriOnly, subplan("CYCLE", null, 1, "MONTH")),
                        "ERI",
                        LocalDate.of(2026, 1, 15),
                        LocalDate.of(2026, 4, 10),
                        LocalDate.of(2026, 1, 20),
                        null);
        assertEquals(3, eri.due().size(), "ERI 模式下照排");
    }

    // ---------------------------------------------------------------- 多节点 / 跨年闰日

    @Test
    void nextCacheIsEarliestUpcomingAcrossNodes() {
        NodeDef early =
                node("CYCLE", null, 1, "YEAR", "PLAN_START", 1, "YEAR", 7, 7, 7, null, null);
        NodeDef later =
                new NodeDef(
                        201L,
                        SUBPLAN_ID,
                        "NODE",
                        "CYCLE",
                        null,
                        1,
                        "YEAR",
                        "PLAN_START",
                        null,
                        null,
                        2,
                        "YEAR",
                        7,
                        "DAY",
                        7,
                        "DAY",
                        7,
                        "DAY");
        PlanResult result =
                TaskCreationPlanner.plan(
                        List.of(subplan("CYCLE", null, 1, "YEAR"), early, later),
                        "NORMAL",
                        LocalDate.of(2025, 3, 15),
                        LocalDate.of(2025, 6, 1),
                        LocalDate.of(2025, 4, 1),
                        null);
        assertEquals(LocalDate.of(2026, 3, 8), result.next().openDate(), "多节点取最早开启的下次");
    }

    @Test
    void leapDayBasisKeepsFebruaryEndAcrossYears() {
        // 闰日出院 + N 年：平年 2-28、闰年回 2-29（E14-T03 口径在排期里同样成立）
        PlanResult result =
                TaskCreationPlanner.plan(
                        planOf(annualNode(), subplan("CYCLE", null, 1, "YEAR")),
                        "NORMAL",
                        LocalDate.of(2024, 2, 29),
                        LocalDate.of(2027, 1, 1),
                        LocalDate.of(2024, 3, 1),
                        null);
        assertEquals(2, result.due().size(), "2025、2026 两次已开启（各 −7 天）");
        assertEquals("NORMAL", result.due().get(0).createType(), "每日调度创建方式恒为正常（NORMAL）");
        assertEquals(LocalDate.of(2025, 2, 28), result.due().get(0).planDate());
        assertEquals(LocalDate.of(2026, 2, 28), result.due().get(1).planDate());
    }

    // ---------------------------------------------------------------- E14-T05 入组即建（补录 / 当期）

    @Test
    void enrollmentBackfillsHospitalBStyleMilestonesEndedBeforeEnrollmentDay() {
        // A 规格第 5 章验收形态（医院 B，Q44）：手术 2026-03-01、出院建档 2026-03-08——
        // 术后第 1/3/5 天窗口（03-02..03、03-04..05、03-06..07）均已结束 → 照常建任务并标补录
        NodeDef day1 = milestoneNode(201L, 1, "BACKFILL");
        NodeDef day3 = milestoneNode(202L, 3, "BACKFILL");
        NodeDef day5 = milestoneNode(203L, 5, "BACKFILL");
        NodeDef threeMonths =
                node("FIXED", null, null, null, "PLAN_START", 3, "MONTH", 14, 14, 14, null, null);
        PlanResult result =
                TaskCreationPlanner.planForEnrollment(
                        List.of(subplan("FIXED", 1, null, null), day1, day3, day5, threeMonths),
                        "NORMAL",
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 3, 8),
                        null);
        assertEquals(3, result.due().size(), "三个已结束窗口的里程碑照常建任务");
        assertEquals("BACKFILL", result.due().get(0).createType(), "已结束窗口 → 标补录");
        assertEquals("BACKFILL", result.due().get(1).createType());
        assertEquals("BACKFILL", result.due().get(2).createType());
        assertEquals(LocalDate.of(2026, 3, 2), result.due().get(0).planDate(), "计划日期 = 手术 + 1 天");
        assertEquals(LocalDate.of(2026, 3, 4), result.due().get(1).planDate());
        assertEquals(LocalDate.of(2026, 3, 6), result.due().get(2).planDate());
        assertEquals(LocalDate.of(2026, 3, 2), result.due().get(0).windowStart());
        assertEquals(LocalDate.of(2026, 3, 3), result.due().get(0).windowEnd());
        assertEquals(
                LocalDate.of(2026, 5, 18),
                result.next().openDate(),
                "3 个月节点（计划 06-01 −14 天）为下次开启日缓存");
    }

    @Test
    void enrollmentSkipsEndedWindowsWhenNodePolicyIsSkipOrUnset() {
        // 医院 C 口径（Q14）：节点策略 SKIP（或未配置）→ 入组前已结束窗口不建任务、不计入
        NodeDef skipNode = milestoneNode(201L, 1, "SKIP");
        NodeDef unsetNode = milestoneNode(202L, 3, null);
        PlanResult result =
                TaskCreationPlanner.planForEnrollment(
                        List.of(subplan("FIXED", 1, null, null), skipNode, unsetNode),
                        "NORMAL",
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 3, 8),
                        null);
        assertTrue(result.due().isEmpty(), "SKIP / 未配置的节点不建补录任务");
        assertNull(result.next());
    }

    @Test
    void enrollmentCreatesCurrentlyOpenWindowAsNormalAndBackfillsEarlierOnes() {
        // 迟入组（月度循环，基准 2026-01-15，入组日 03-10）：第 1 次窗口 02-08..02-22 已结束 → 补录；
        // 第 2 次窗口 03-08..03-22 已开启未结束 → 当天正常创建（A 规格第 5 章）
        NodeDef monthly =
                node(
                        "CYCLE",
                        null,
                        1,
                        "MONTH",
                        "PLAN_START",
                        1,
                        "MONTH",
                        7,
                        7,
                        7,
                        null,
                        "BACKFILL");
        PlanResult result =
                TaskCreationPlanner.planForEnrollment(
                        planOf(monthly, subplan("CYCLE", null, 1, "MONTH")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 15),
                        LocalDate.of(2026, 3, 10),
                        null);
        assertEquals(2, result.due().size());
        assertEquals("BACKFILL", result.due().get(0).createType(), "入组前已结束的窗口标补录");
        assertEquals(1, result.due().get(0).occurrence());
        assertEquals("NORMAL", result.due().get(1).createType(), "当前窗口内当天正常创建");
        assertEquals(2, result.due().get(1).occurrence());
        assertEquals(LocalDate.of(2026, 3, 22), result.due().get(1).windowEnd(), "窗口尚未结束");
        assertEquals(LocalDate.of(2026, 4, 8), result.next().openDate(), "第 3 次开启日缓存");
    }

    @Test
    void enrollmentWindowEndingTodayIsStillCurrent() {
        // 窗口结束日 = 入组日：不算「已结束」，按当期任务正常创建（已结束 = 窗口结束日严格早于入组日）
        NodeDef endsToday =
                node("FIXED", null, null, null, "PLAN_START", 5, "DAY", 0, 5, 5, null, "BACKFILL");
        PlanResult result =
                TaskCreationPlanner.planForEnrollment(
                        List.of(subplan("FIXED", 1, null, null), endsToday),
                        "NORMAL",
                        LocalDate.of(2026, 3, 5),
                        LocalDate.of(2026, 3, 10),
                        null);
        assertEquals(1, result.due().size());
        assertEquals("NORMAL", result.due().get(0).createType(), "窗口今天结束仍在窗口内");
    }

    // ---------------------------------------------------------------- E14-T06 恢复补偿（A 的
    // Q32、A-FU-008）

    /** 零宽窗口循环节点（周期 10 天、当天提醒、窗口前 0 后 0）：第 N 次计划日期 = 基准 + 10 +（N−1）×10。 */
    private static NodeDef zeroWindowCycleNode() {
        return node("CYCLE", null, 10, "DAY", "PLAN_START", 10, "DAY", 0, 0, 0, null, null);
    }

    @Test
    void planForResumeMarksInPauseEndedWindowAsPausedSkipped() {
        // 基准 2026-01-01（周期 10 天、偏移 10 天）：第 1 次 01-11、第 2 次 01-21（开启早于暂停起始 01-25）、
        // 第 3 次 01-31（暂停区间内开启、窗口已结束）、第 4 次 02-10（未开启 → 下次缓存）
        PlanResult result =
                TaskCreationPlanner.planForResume(
                        planOf(zeroWindowCycleNode(), subplan("CYCLE", null, 10, "DAY")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 2, 5),
                        LocalDate.of(2026, 1, 25),
                        null);
        assertEquals(3, result.due().size());
        assertEquals(1, result.due().get(0).occurrence());
        assertEquals(2, result.due().get(1).occurrence());
        assertEquals("NORMAL", result.due().get(0).createType(), "开启日早于暂停起始不属暂停补偿（由停用补偿口径兜底）");
        assertEquals("NORMAL", result.due().get(1).createType());
        assertEquals(3, result.due().get(2).occurrence());
        assertEquals("PAUSED_SKIPPED", result.due().get(2).createType(), "暂停区间内开启且窗口已结束 → 暂停期间未执行");
        assertEquals(LocalDate.of(2026, 2, 10), result.next().planDate());
        assertEquals(LocalDate.of(2026, 2, 10), result.next().openDate());
    }

    @Test
    void planForResumeKeepsOpenWindowAsNormal() {
        // 第 1 次计划 01-11、窗口 [01-11, 01-21]：恢复日 01-15 窗口未结束 → 当期正常创建（「恢复后剩余子计划重新开启」）
        NodeDef openWindow =
                node("CYCLE", null, 30, "DAY", "PLAN_START", 10, "DAY", 0, 0, 10, null, null);
        PlanResult result =
                TaskCreationPlanner.planForResume(
                        planOf(openWindow, subplan("CYCLE", null, 30, "DAY")),
                        "NORMAL",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 15),
                        LocalDate.of(2026, 1, 5),
                        null);
        assertEquals(1, result.due().size());
        assertEquals("NORMAL", result.due().get(0).createType());
        assertEquals(LocalDate.of(2026, 1, 21), result.due().get(0).windowEnd());
    }
}
