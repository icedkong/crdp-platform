package com.crdp.followup.stats.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.EnrollmentInput;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.FactRow;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.FloatingRecord;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.PlanVersion;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.RecordInput;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.StatusPoint;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.TaskInput;
import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E19-T01 / A-DASH-012 节点随访事实表纯函数单测（口径 = hospital-a-spec「节点随访率口径（与 Q38 相同）」）： 应随访 =
 * 窗口已结束且窗口开始日（入组更晚取入组日）仍符合条件（存活、未停止、未失访；暂停仍计入）； 已随访 = 该次随访记录已提交（窗口内 / 逾期 / 补录 /
 * 历史导入都算；随访中确认死亡并提交算已随访）。 全部虚构数据（年度计划：起算 2025-03-15，±7 天窗口，第 1 年窗口 2026-03-08 ~ 2026-03-22）。
 */
class NodeOccurrenceFactCalculatorTest {

    private static final long ENROLLMENT_ID = 31L;
    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;
    private static final long SUBPLAN_ID = 100L;
    private static final long NODE_ID = 200L;
    private static final LocalDate BASIS = LocalDate.of(2025, 3, 15);
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    /** 年度循环节点（同医院 A 预置形态：偏移 1 年、提醒 / 窗口各 7 天）。 */
    private static List<NodeDef> annualNodes() {
        return List.of(
                new NodeDef(
                        SUBPLAN_ID,
                        null,
                        "SUBPLAN",
                        "CYCLE",
                        null,
                        1,
                        "YEAR",
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
                        "DAY"),
                new NodeDef(
                        NODE_ID,
                        SUBPLAN_ID,
                        "NODE",
                        null,
                        null,
                        null,
                        null,
                        "PLAN_START",
                        null,
                        "BACKFILL",
                        1,
                        "YEAR",
                        7,
                        "DAY",
                        7,
                        "DAY",
                        7,
                        "DAY"));
    }

    private static EnrollmentInput enrollment(LocalDate enrolledOn) {
        return new EnrollmentInput(ENROLLMENT_ID, PATIENT_ID, PLAN_ID, BASIS, "NORMAL", enrolledOn);
    }

    private static TaskInput task(int occurrence, String status, String completeKind) {
        return new TaskInput(
                900L + occurrence,
                null,
                NODE_ID,
                occurrence,
                BASIS.plusYears(occurrence),
                BASIS.plusYears(occurrence).minusDays(7),
                BASIS.plusYears(occurrence).plusDays(7),
                "NORMAL",
                status,
                completeKind);
    }

    private static FactRow soleRow(List<FactRow> rows) {
        assertEquals(1, rows.size(), "应只产生第 1 次一行：" + rows);
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 应随访与已随访（验收：按节点出应随访、已随访数）

    @Test
    void completedAnnualTaskIsDueAndFollowedWithYearAndCohort() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 3),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(task(1, "COMPLETED", "ON_TIME")),
                                List.of(),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertEquals(NODE_ID, row.nodeId());
        assertEquals(1, row.occurrence());
        assertEquals(1, row.followupYear(), "年度节点的随访年度 = 第 N 次");
        assertEquals(LocalDate.of(2026, 3, 15), row.planDate());
        assertEquals(LocalDate.of(2026, 3, 8), row.windowStart());
        assertEquals(LocalDate.of(2026, 3, 22), row.windowEnd());
        assertEquals(2025, row.cohortYear(), "队列年份 = 起算日期年份");
        assertTrue(row.due(), "窗口已结束且窗口开始日在组 → 应随访");
        assertEquals("DUE", row.dueReason());
        assertTrue(row.followed(), "已提交 → 已随访");
        assertEquals("ON_TIME", row.completeKind());
        assertTrue(row.inWindow());
        assertEquals(900L + 1, row.taskId());
    }

    @Test
    void windowStillOpenTaskIsKeptButNotDue() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(task(1, "PENDING", null)),
                                List.of(),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                LocalDate.of(2026, 3, 20)));

        assertTrue(!row.due(), "窗口未结束（统计日在窗口内）不计应随访");
        assertEquals("WINDOW_OPEN", row.dueReason());
        assertTrue(!row.followed());
    }

    @Test
    void deadOrTerminatedBeforeWindowStartIsNotDue() {
        for (String status : new String[] {"DEAD", "TERMINATED", "LOST"}) {
            FactRow row =
                    soleRow(
                            NodeOccurrenceFactCalculator.compute(
                                    annualNodes(),
                                    new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                    enrollment(LocalDate.of(2025, 3, 20)),
                                    List.of(task(1, "OVERDUE", null)),
                                    List.of(),
                                    List.of(),
                                    List.of(
                                            new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20)),
                                            new StatusPoint(status, LocalDate.of(2026, 3, 1))),
                                    null,
                                    AS_OF));

            assertTrue(!row.due(), status + " 早于窗口开始日 → 不在应随访");
            assertEquals("NOT_QUALIFIED", row.dueReason());
        }
    }

    @Test
    void deathConfirmedInsideWindowStaysDueAndMarksDeath() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(task(1, "COMPLETED", "OVERDUE")),
                                List.of(),
                                List.of(),
                                List.of(
                                        new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20)),
                                        new StatusPoint("DEAD", LocalDate.of(2026, 3, 10))),
                                null,
                                AS_OF));

        assertTrue(row.due(), "窗口开始日（03-08）仍存活 → 应随访（死亡发生在窗口内）");
        assertTrue(row.followed(), "随访中确认死亡并提交的记录算已随访（Q38）");
        assertTrue(row.deathConfirmed(), "死亡生效日在窗口内 → 该节点死亡人数 +1");
    }

    @Test
    void pausedAtWindowStartStillCountsAsDue() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(task(1, "PENDING", null)),
                                List.of(),
                                List.of(),
                                List.of(
                                        new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20)),
                                        new StatusPoint("PAUSED", LocalDate.of(2026, 3, 1))),
                                null,
                                AS_OF));

        assertTrue(row.due(), "暂停中的患者仍计入应随访（Q38）");
    }

    @Test
    void lostTaskMarksLostButNotFollowed() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(task(1, "LOST", null)),
                                List.of(),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertTrue(row.due());
        assertTrue(row.lost(), "任务确认失访 → 该节点失访人数 +1");
        assertTrue(!row.followed());
    }

    // ---------------------------------------------------------------- 记录来源（历史导入 / 历史未随访 / 补录）

    @Test
    void importedSubmittedRecordCountsAsFollowedEvenOutsideWindow() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(),
                                List.of(
                                        new RecordInput(
                                                700L,
                                                NODE_ID,
                                                1,
                                                "HIST_IMPORT",
                                                "SUBMITTED",
                                                LocalDate.of(2026, 4, 1))),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertTrue(row.due(), "历史导入年度计入应随访（Q06：历史未随访也计入）");
        assertTrue(row.followed(), "窗口外日期的历史导入照常算已随访");
        assertEquals("HIST_IMPORT", row.completeKind());
        assertTrue(!row.inWindow(), "实际随访日期在窗口外 → 不计入窗口内完成");
        assertEquals(700L, row.recordId());
    }

    @Test
    void recordVisitOnWindowBoundaryDaysCountsAsInWindow() {
        // Q38「窗口内完成率：实际随访日期在窗口内（含首尾两天）」——记录路径（历史导入 / 补录）的首末日边界（E19-T08 口径核对）
        LocalDate[] boundaryDays = {LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 22)};
        for (LocalDate visit : boundaryDays) {
            FactRow row =
                    soleRow(
                            NodeOccurrenceFactCalculator.compute(
                                    annualNodes(),
                                    new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                    enrollment(LocalDate.of(2025, 3, 20)),
                                    List.of(),
                                    List.of(
                                            new RecordInput(
                                                    702L,
                                                    NODE_ID,
                                                    1,
                                                    "HIST_IMPORT",
                                                    "SUBMITTED",
                                                    visit)),
                                    List.of(),
                                    List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                    null,
                                    AS_OF));

            assertTrue(row.followed(), visit + " 已提交 → 已随访");
            assertTrue(row.inWindow(), visit + " = 窗口首 / 末日 → 计入窗口内完成（含首尾两天）");
        }
        FactRow dayAfter =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(),
                                List.of(
                                        new RecordInput(
                                                703L,
                                                NODE_ID,
                                                1,
                                                "HIST_IMPORT",
                                                "SUBMITTED",
                                                LocalDate.of(2026, 3, 23))),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertTrue(dayAfter.followed(), "窗口外日期照常算已随访");
        assertTrue(!dayAfter.inWindow(), "窗口结束次日 → 不计入窗口内完成");
    }

    @Test
    void histMissedDraftIsDueButNotFollowed() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(),
                                List.of(
                                        new RecordInput(
                                                701L, NODE_ID, 1, "HIST_MISSED", "DRAFT", null)),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertTrue(row.due(), "历史未随访计入应随访（Q06）");
        assertTrue(!row.followed(), "未补录前不计入已随访");
    }

    @Test
    void histMissedSubmittedUpgradesToBackfillFollowed() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(),
                                List.of(
                                        new RecordInput(
                                                701L,
                                                NODE_ID,
                                                1,
                                                "HIST_MISSED",
                                                "SUBMITTED",
                                                LocalDate.of(2026, 5, 10))),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertTrue(row.followed(), "历史未随访年度补录提交后计入已随访（Q06）");
        assertEquals("BACKFILL", row.completeKind());
        assertTrue(!row.inWindow(), "补录随访日期（05-10）在窗口外");
    }

    @Test
    void floatingBackfillRecordAttributedToNearestOccurrence() {
        // 无锚点补录（A-FU-014 手工历史补录，source = BACKFILL）：按与计划日期最近的次数归属
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        annualNodes(),
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        enrollment(LocalDate.of(2025, 3, 20)),
                        List.of(),
                        List.of(),
                        List.of(new FloatingRecord(710L, LocalDate.of(2026, 6, 1))),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                        null,
                        AS_OF);

        FactRow row = soleRow(rows);
        assertTrue(row.followed(), "补录记录已提交 → 该节点已随访");
        assertEquals("BACKFILL", row.completeKind());
        assertEquals(710L, row.recordId());
        assertTrue(!row.inWindow(), "补录日期在窗口外（ nearer 第 1 年而非第 2 年）");
    }

    @Test
    void backfillCreateTypeTaskKeepsBackfillKind() {
        FactRow row =
                soleRow(
                        NodeOccurrenceFactCalculator.compute(
                                annualNodes(),
                                new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                                enrollment(LocalDate.of(2025, 3, 20)),
                                List.of(
                                        new TaskInput(
                                                910L,
                                                null,
                                                NODE_ID,
                                                1,
                                                BASIS.plusYears(1),
                                                BASIS.plusYears(1).minusDays(7),
                                                BASIS.plusYears(1).plusDays(7),
                                                "BACKFILL",
                                                "COMPLETED",
                                                "OVERDUE")),
                                List.of(),
                                List.of(),
                                List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                                null,
                                AS_OF));

        assertTrue(row.followed());
        assertEquals("BACKFILL", row.completeKind(), "补录任务完成方式记补录（E14-T05 口径）");
    }

    // ---------------------------------------------------------------- 入组与边界

    @Test
    void preEnrollmentWindowWithoutTaskOrRecordIsExcluded() {
        // 医院 C 的 Q14：入组前已过窗口的节点不建任务、不计入（无任务无记录 → 无事实行）
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        annualNodes(),
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        enrollment(LocalDate.of(2026, 5, 1)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2026, 5, 1))),
                        null,
                        AS_OF);

        assertTrue(rows.isEmpty(), "第 1 年窗口在入组（2026-05-01）前结束且无任务 / 记录 → 不计");
    }

    @Test
    void preEnrollmentWindowWithBackfillTaskCountsDueAtEnrollmentDay() {
        // A、B 的 Q44：入组前已过窗口照常补建补录任务；条件判定基准日 = max(窗口开始日, 入组日)
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        annualNodes(),
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        enrollment(LocalDate.of(2026, 5, 1)),
                        List.of(
                                new TaskInput(
                                        911L,
                                        null,
                                        NODE_ID,
                                        1,
                                        BASIS.plusYears(1),
                                        BASIS.plusYears(1).minusDays(7),
                                        BASIS.plusYears(1).plusDays(7),
                                        "BACKFILL",
                                        "PENDING",
                                        null)),
                        List.of(),
                        List.of(),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2026, 5, 1))),
                        null,
                        AS_OF);

        FactRow row = soleRow(rows);
        assertTrue(row.due(), "入组日（更晚）仍符合条件的补录任务计入应随访（Q44）");
    }

    @Test
    void unresolvableStartDateYieldsNothing() {
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        annualNodes(),
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        new EnrollmentInput(
                                ENROLLMENT_ID,
                                PATIENT_ID,
                                PLAN_ID,
                                null,
                                "NORMAL",
                                LocalDate.of(2025, 3, 20)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                        null,
                        AS_OF);

        assertTrue(rows.isEmpty(), "起算日期不可解析 → 不产事实行（与任务调度同口径）");
    }

    @Test
    void planCycleYearsBoundaryLimitsOccurrences() {
        // cycle_type = YEARS 2：只排第 1、2 次（第 3 次计划日期超出起算 + 2 年边界）
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        annualNodes(),
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        enrollment(LocalDate.of(2025, 3, 20)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                        BASIS.plusYears(2),
                        LocalDate.of(2028, 6, 1));

        assertEquals(2, rows.size(), "管理周期 2 年 → 至多第 1、2 次两行");
        assertEquals(1, rows.get(0).occurrence());
        assertEquals(2, rows.get(1).occurrence());
    }

    @Test
    void fixedYearPlanAlsoMarksFollowupYear() {
        // 固定次数年度子计划（FIXED × 2、周期 1 年，周期回落口径 OQ-78①）：随访年度照常 = 第 N 次
        List<NodeDef> fixedYearlyNodes =
                List.of(
                        new NodeDef(
                                SUBPLAN_ID,
                                null,
                                "SUBPLAN",
                                "FIXED",
                                2,
                                1,
                                "YEAR",
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
                                "DAY"),
                        new NodeDef(
                                NODE_ID,
                                SUBPLAN_ID,
                                "NODE",
                                null,
                                null,
                                null,
                                null,
                                "PLAN_START",
                                null,
                                "BACKFILL",
                                1,
                                "YEAR",
                                7,
                                "DAY",
                                7,
                                "DAY",
                                7,
                                "DAY"));
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        fixedYearlyNodes,
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        enrollment(LocalDate.of(2025, 3, 20)),
                        List.of(task(1, "COMPLETED", "ON_TIME"), task(2, "PENDING", null)),
                        List.of(),
                        List.of(),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                        null,
                        LocalDate.of(2027, 6, 1));

        assertEquals(2, rows.size(), "固定 2 次 → 两行，不因固定次数丢年度语义");
        assertEquals(1, rows.get(0).followupYear());
        assertEquals(2, rows.get(1).followupYear());
    }

    @Test
    void laterOccurrencesAccumulateWhenAllWindowsEnded() {
        List<FactRow> rows =
                NodeOccurrenceFactCalculator.compute(
                        annualNodes(),
                        new PlanVersion(PLAN_ID, "PLAN-IT-1", 1),
                        enrollment(LocalDate.of(2025, 3, 20)),
                        List.of(task(1, "COMPLETED", "ON_TIME"), task(2, "PENDING", null)),
                        List.of(),
                        List.of(),
                        List.of(new StatusPoint("ACTIVE", LocalDate.of(2025, 3, 20))),
                        null,
                        LocalDate.of(2027, 6, 1));

        assertEquals(2, rows.size(), "两次窗口都已结束 → 按第 N 次分行（第 3 次窗口未结束不排）");
        assertTrue(rows.get(0).due() && rows.get(0).followed());
        assertTrue(rows.get(1).due() && !rows.get(1).followed(), "第 2 次未完成 → 应随访但未随访");
    }
}
