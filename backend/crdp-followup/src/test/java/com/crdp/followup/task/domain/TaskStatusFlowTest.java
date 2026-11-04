package com.crdp.followup.task.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * E15-T01 / A-FU-003 状态机纯函数单测（验收「状态按规格流转」）：A 规格「关键字段 · 任务状态」表的 进入条件逐行 → 合法流转方向；A-FU-010 →
 * 完成方式判定（窗口前 / 窗口内 / 逾期完成，验收「逾期完成单独标记」）。全部虚构数据。
 */
class TaskStatusFlowTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 8);
    private static final LocalDate END = LocalDate.of(2026, 3, 22);

    // ---------------------------------------------------------------- 流转方向（A 规格任务状态表）

    @Test
    void pendingTransitionsToProgressOverdueCompleteLostOrTerminated() {
        assertTrue(TaskStatusFlow.canTransition("PENDING", "IN_PROGRESS"), "第一次联系或暂存 → 进行中");
        assertTrue(TaskStatusFlow.canTransition("PENDING", "OVERDUE"), "窗口结束日次日 → 逾期");
        assertTrue(TaskStatusFlow.canTransition("PENDING", "COMPLETED"), "提交 → 已完成");
        assertTrue(TaskStatusFlow.canTransition("PENDING", "LOST"), "医生确认 → 失访（E15-T05）");
        assertTrue(TaskStatusFlow.canTransition("PENDING", "TERMINATED"), "死亡 / 人工停止 → 已终止");
    }

    @Test
    void inProgressTransitionsToOverdueCompleteLostOrTerminated() {
        assertTrue(TaskStatusFlow.canTransition("IN_PROGRESS", "OVERDUE"), "已联系仍可逾期");
        assertTrue(TaskStatusFlow.canTransition("IN_PROGRESS", "COMPLETED"));
        assertTrue(TaskStatusFlow.canTransition("IN_PROGRESS", "LOST"));
        assertTrue(TaskStatusFlow.canTransition("IN_PROGRESS", "TERMINATED"));
        assertFalse(TaskStatusFlow.canTransition("IN_PROGRESS", "PENDING"), "不退回待随访");
    }

    @Test
    void overdueCompletesButNeverFallsBackToInProgress() {
        assertTrue(TaskStatusFlow.canTransition("OVERDUE", "COMPLETED"), "逾期后完成 = 逾期完成（A-FU-010）");
        assertTrue(TaskStatusFlow.canTransition("OVERDUE", "LOST"));
        assertTrue(TaskStatusFlow.canTransition("OVERDUE", "TERMINATED"));
        assertFalse(
                TaskStatusFlow.canTransition("OVERDUE", "IN_PROGRESS"),
                "逾期是窗口事实，联系 / 录入不退回进行中（OQ-82①）");
        assertFalse(TaskStatusFlow.canTransition("OVERDUE", "PENDING"));
    }

    @Test
    void skippedSuspendedRedoesToPendingOrOverdueAndClosesToTerminated() {
        assertTrue(TaskStatusFlow.canTransition("SKIPPED_SUSPENDED", "PENDING"), "补做（窗口未结束）");
        assertTrue(TaskStatusFlow.canTransition("SKIPPED_SUSPENDED", "OVERDUE"), "补做（窗口已结束，Q32）");
        assertTrue(TaskStatusFlow.canTransition("SKIPPED_SUSPENDED", "TERMINATED"), "关闭（人工停止）");
        assertFalse(TaskStatusFlow.canTransition("SKIPPED_SUSPENDED", "COMPLETED"), "须先补做再完成");
        assertFalse(TaskStatusFlow.canTransition("SKIPPED_SUSPENDED", "LOST"), "失访判定不针对未执行补偿任务");
    }

    @Test
    void completedLostTerminatedAreTerminal() {
        assertTrue(TaskStatusFlow.isTerminal("COMPLETED"), "已完成任务不变（历史事实）");
        assertTrue(TaskStatusFlow.isTerminal("LOST"));
        assertTrue(TaskStatusFlow.isTerminal("TERMINATED"));
        assertFalse(TaskStatusFlow.canTransition("COMPLETED", "OVERDUE"));
        assertFalse(TaskStatusFlow.canTransition("COMPLETED", "IN_PROGRESS"));
        assertFalse(TaskStatusFlow.canTransition("TERMINATED", "COMPLETED"));
        assertFalse(TaskStatusFlow.canTransition("LOST", "COMPLETED"));
    }

    @Test
    void unknownStatusIsNotKnownNorTerminal() {
        assertFalse(TaskStatusFlow.known("PLANNED"), "「待开始 / 计划中」只是时间轴显示态，不落库（A-FU-003）");
        assertFalse(TaskStatusFlow.known("DONE_LATE"), "逾期完成是完成方式（complete_kind），不是独立状态");
        assertFalse(TaskStatusFlow.isTerminal("PENDING"));
        assertFalse(TaskStatusFlow.canTransition("PENDING", "PENDING"), "自流转不算流转");
    }

    // ---------------------------------------------------------------- 完成方式（A-FU-010）

    @Test
    void completingInsideWindowIsOnTime() {
        assertEquals("ON_TIME", TaskStatusFlow.completeKindOf(START, END, START), "窗口首日完成 = 窗口内");
        assertEquals("ON_TIME", TaskStatusFlow.completeKindOf(START, END, END), "窗口末日完成 = 窗口内");
        assertEquals(
                "ON_TIME", TaskStatusFlow.completeKindOf(START, END, LocalDate.of(2026, 3, 15)));
    }

    @Test
    void completingBeforeWindowStartIsBeforeWindow() {
        assertEquals(
                "BEFORE_WINDOW",
                TaskStatusFlow.completeKindOf(START, END, START.minusDays(1)),
                "窗口开启前提交 → 窗口前完成（A 规格任务状态表）");
    }

    @Test
    void completingAfterWindowEndIsOverdue() {
        assertEquals(
                "OVERDUE",
                TaskStatusFlow.completeKindOf(START, END, END.plusDays(1)),
                "窗口结束后提交 → 逾期完成（A-FU-010 验收「逾期完成单独标记」）");
        assertEquals("OVERDUE", TaskStatusFlow.completeKindOf(START, END, END.plusYears(1)));
    }
}
