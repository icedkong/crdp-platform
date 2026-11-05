package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E15-T01 / A-FU-003、A-FU-010 状态流转服务单测（验收「状态按规格流转，逾期完成单独标记」）：逾期刷新（标记 + TASK_OVERDUE 事件 +
 * 汇总审计、守卫落空不发事件、无候选不写）、进行中联动（只从待随访）、完成（完成方式三态、直接录入期门槛、 终态 40901、并发 40900）、补做 / 关闭（Q32 /
 * PLT-TEN-017）。完成 / 补走包内重载显式传业务日，不 mock 静态时钟。 全部虚构数据；BACKFILL 豁免逾期在 findOverdueCandidates 的 SQL 侧，由
 * TaskStatusFlowIT 端到端覆盖。
 */
class FollowupTaskStatusServiceTest {

    private static final long TASK_ID = 901L;
    private static final long ENROLLMENT_ID = 31L;
    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;
    private static final long SUBPLAN_ID = 100L;
    private static final long NODE_ID = 200L;
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 3, 8);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 3, 22);
    private static final long OPERATOR = 55L;

    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final FollowupPolicyService policy = mock(FollowupPolicyService.class);

    private final FollowupTaskStatusService service =
            new FollowupTaskStatusService(taskMapper, planMapper, outbox, audit, crypto, policy);

    {
        lenient().when(policy.policy()).thenReturn(new FollowupPolicyService.ContactPolicy(3, 1));
    }

    // ---------------------------------------------------------------- 装配

    /** 任务行（默认待随访；A 年度窗口 [2026-03-08, 2026-03-22]，第 3 次；第 4 次开启日 2027-03-08）。 */
    private FollowupTaskMapper.TaskRow taskRow(String status) {
        return taskRow(status, 0);
    }

    /** 任务行（followupSeq = 窗口内跟进序号，0 = 主任务；E15-T05）。 */
    private FollowupTaskMapper.TaskRow taskRow(String status, int followupSeq) {
        return new FollowupTaskMapper.TaskRow(
                TASK_ID,
                ENROLLMENT_ID,
                PATIENT_ID,
                PLAN_ID,
                NODE_ID,
                3,
                "NORMAL",
                status,
                followupSeq,
                WINDOW_START,
                WINDOW_END);
    }

    /** A 年度形态计划（+1 年、提前 7 天、窗口 ±7 天；起算 2023-03-15 → 第 4 次 2027-03-15、开启日 2027-03-08）。 */
    private void annualPlan() {
        lenient()
                .when(taskMapper.findEnrollmentStartDate(ENROLLMENT_ID))
                .thenReturn(LocalDate.of(2023, 3, 15));
        lenient()
                .when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "PUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                null,
                                null,
                                0,
                                null,
                                1L));
        lenient()
                .when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(subplanRow(), nodeRow()));
    }

    private static PlanMapper.NodeRow subplanRow() {
        return new PlanMapper.NodeRow(
                SUBPLAN_ID,
                null,
                "SUBPLAN",
                "年度随访（示例）",
                "CYCLE",
                null,
                1,
                "YEAR",
                null,
                0,
                "YEAR",
                0,
                "DAY",
                0,
                "DAY",
                0,
                "DAY",
                null,
                null,
                null,
                null,
                null,
                10);
    }

    private static PlanMapper.NodeRow nodeRow() {
        return new PlanMapper.NodeRow(
                NODE_ID,
                SUBPLAN_ID,
                "NODE",
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
    }

    private static ApiException assertConflict(java.util.function.Supplier<?> action, int code) {
        ApiException ex = assertThrows(ApiException.class, action::get);
        assertEquals(code, ex.code(), "业务码");
        return ex;
    }

    // ---------------------------------------------------------------- 逾期刷新（A-FU-010）

    @Test
    void refreshOverdueMarksCandidatesAndPublishesEvents() {
        when(taskMapper.findOverdueCandidates(LocalDate.of(2026, 3, 23)))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.OverdueCandidateRow(
                                        901L,
                                        PATIENT_ID,
                                        66L,
                                        5L,
                                        "cipher-a",
                                        "第 4 年随访（示例）",
                                        WINDOW_START,
                                        WINDOW_END)));
        when(taskMapper.markOverdue(901L)).thenReturn(1);
        when(crypto.decrypt("cipher-a")).thenReturn("张小明（示例）");

        int marked = service.refreshOverdue(LocalDate.of(2026, 3, 23));

        assertEquals(1, marked, "窗口结束日次日置逾期（A-FU-010）");
        verify(taskMapper).markOverdue(901L);
        // 负载契约 NotifyEventPayload：站内信密文名 / 责任人复核锚点 / 文案窗口齐全（A-NTF-003）
        verify(outbox)
                .publish(
                        eq("TASK_OVERDUE"),
                        contains(
                                "\"taskId\":901,\"ownerId\":66,\"wardId\":5,"
                                        + "\"patientId\":77,\"patientName\":\"张小明（示例）\""));
        verify(outbox).publish(eq("TASK_OVERDUE"), contains("\"nodeName\":\"第 4 年随访（示例）\""));
        verify(outbox)
                .publish(
                        eq("TASK_OVERDUE"),
                        contains(
                                "\"windowStartDate\":\"2026-03-08\",\"windowEndDate\":\"2026-03-22\""));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("TASK_OVERDUE_MARK", captor.getValue().action(), "批量作业写一条汇总审计");
    }

    @Test
    void refreshOverdueSkipsGuardMissWithoutEvent() {
        when(taskMapper.findOverdueCandidates(any()))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.OverdueCandidateRow(
                                        902L,
                                        PATIENT_ID,
                                        null,
                                        5L,
                                        null,
                                        null,
                                        WINDOW_START,
                                        WINDOW_END)));
        when(taskMapper.markOverdue(902L)).thenReturn(0); // 并发完成 / 重放已 OVERDUE

        assertEquals(0, service.refreshOverdue(LocalDate.of(2026, 3, 23)));

        verify(outbox, never()).publish(any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void refreshOverdueWithoutCandidatesWritesNothing() {
        when(taskMapper.findOverdueCandidates(any())).thenReturn(List.of());

        assertEquals(0, service.refreshOverdue(LocalDate.of(2026, 3, 23)));

        verify(outbox, never()).publish(any(), any());
        verify(audit, never()).append(any());
    }

    // ---------------------------------------------------------------- 进行中（第一次联系 / 暂存草稿）

    @Test
    void markInProgressMovesPendingOnlyAndAudits() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("PENDING"));
        when(taskMapper.startProgress(TASK_ID)).thenReturn(1);

        assertTrue(service.markInProgress(TASK_ID, OPERATOR, "DRAFT"));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("TASK_PROGRESS", captor.getValue().action());
        assertEquals("TASK", captor.getValue().objectType());
        assertEquals(String.valueOf(TASK_ID), captor.getValue().objectId());
    }

    @Test
    void markInProgressKeepsOverdueUntouched() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("OVERDUE"));
        when(taskMapper.startProgress(TASK_ID)).thenReturn(0); // 守卫 PENDING 落空

        assertFalse(service.markInProgress(TASK_ID, OPERATOR, "CONTACT"));

        verify(audit, never()).append(any());
    }

    @Test
    void markInProgressRejectsMissingTaskWith40400() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(null);

        assertConflict(() -> service.markInProgress(TASK_ID, OPERATOR, "CONTACT"), 40400);
    }

    // ---------------------------------------------------------------- 完成（提交联动）

    @Test
    void completeInsideWindowMarksOnTime() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS"));
        when(taskMapper.completeTask(eq(TASK_ID), eq("ON_TIME"), any(), eq(OPERATOR)))
                .thenReturn(1);

        FollowupTaskStatusService.CompletionView view =
                service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 15));

        assertEquals("COMPLETED", view.status());
        assertEquals("ON_TIME", view.completeKind());
        verify(audit).append(any());
    }

    @Test
    void completeAfterWindowEndMarksOverdueKindWithinDirectEntryPeriod() {
        // 窗口 2026-03-22 结束；完成日 2026-03-25（第 4 次开启日 2027-03-08 之前）→ 逾期完成（验收）
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("OVERDUE"));
        when(taskMapper.completeTask(eq(TASK_ID), eq("OVERDUE"), any(), eq(OPERATOR)))
                .thenReturn(1);
        annualPlan();

        assertEquals(
                "OVERDUE",
                service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 25)).completeKind(),
                "逾期完成单独标记（A-FU-010）");
    }

    @Test
    void completeOnLastDirectEntryDayIsStillAllowed() {
        // 完成日 = 窗口结束后、尚早于第 4 次开启日（2027-03-08）的最后一日
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("OVERDUE"));
        when(taskMapper.completeTask(eq(TASK_ID), eq("OVERDUE"), any(), eq(OPERATOR)))
                .thenReturn(1);
        annualPlan();

        assertEquals(
                "OVERDUE",
                service.complete(TASK_ID, OPERATOR, LocalDate.of(2027, 3, 7)).completeKind());
    }

    @Test
    void completeBeforeWindowStartMarksBeforeWindow() {
        // 开启日早于窗口开始日的形态（提醒提前量大于窗口前宽）：开启日当天提交 → 窗口前完成
        FollowupTaskMapper.TaskRow row =
                new FollowupTaskMapper.TaskRow(
                        TASK_ID,
                        ENROLLMENT_ID,
                        PATIENT_ID,
                        PLAN_ID,
                        NODE_ID,
                        3,
                        "NORMAL",
                        "PENDING",
                        0,
                        LocalDate.of(2026, 3, 25),
                        LocalDate.of(2026, 3, 25));
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(row);
        when(taskMapper.completeTask(eq(TASK_ID), eq("BEFORE_WINDOW"), any(), eq(OPERATOR)))
                .thenReturn(1);

        assertEquals(
                "BEFORE_WINDOW",
                service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 15)).completeKind());
    }

    @Test
    void completeRejectsStatesPerSpec() {
        for (String status : List.of("COMPLETED", "LOST", "TERMINATED", "SKIPPED_SUSPENDED")) {
            when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow(status));
            assertConflict(
                    () -> service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 15)), 40901);
        }
        verify(taskMapper, never()).completeTask(anyLong(), any(), any(), any());
    }

    @Test
    void completeRejectsWhenNextOccurrenceAlreadyOpened() {
        // 完成日 2027-03-08 = 第 4 次开启日 → 已过直接录入期，只能历史补录（A-FU-010 / Q09）
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("OVERDUE"));
        annualPlan();

        ApiException ex =
                assertConflict(
                        () -> service.complete(TASK_ID, OPERATOR, LocalDate.of(2027, 3, 8)), 40901);

        assertTrue(ex.getMessage().contains("历史补录"), "口径：之后只能历史补录");
        verify(taskMapper, never()).completeTask(anyLong(), any(), any(), any());
    }

    @Test
    void completeAllowsLateCompletionWhenNoNextOccurrence() {
        // 起算快照缺失（配置防御）/ FIXED 排完：无下一次可开启 → 直接录入期不设界（OQ-82③）
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("OVERDUE"));
        when(taskMapper.findEnrollmentStartDate(ENROLLMENT_ID)).thenReturn(null);
        when(taskMapper.completeTask(eq(TASK_ID), eq("OVERDUE"), any(), eq(OPERATOR)))
                .thenReturn(1);

        assertEquals(
                "OVERDUE",
                service.complete(TASK_ID, OPERATOR, LocalDate.of(2027, 6, 1)).completeKind());
    }

    @Test
    void completeSurfacesConcurrentStateChangeAs40900() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("PENDING"));
        when(taskMapper.completeTask(eq(TASK_ID), any(), any(), any())).thenReturn(0);

        assertConflict(() -> service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 15)), 40900);
    }

    // ---------------------------------------------------------------- 期间未执行：补做 /
    // 关闭（Q32、PLT-TEN-017）

    @Test
    void redoSkippedReopensEndedWindowAsOverdue() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("SKIPPED_SUSPENDED"));
        when(taskMapper.redoSkippedTask(TASK_ID, "OVERDUE")).thenReturn(1);

        assertEquals(
                "OVERDUE",
                service.redoSkipped(TASK_ID, OPERATOR, LocalDate.of(2026, 11, 5)).status(),
                "窗口已结束 → 逾期态待补做");
        verify(audit).append(any());
    }

    @Test
    void redoSkippedReopensOpenWindowAsPending() {
        FollowupTaskMapper.TaskRow row =
                new FollowupTaskMapper.TaskRow(
                        TASK_ID,
                        ENROLLMENT_ID,
                        PATIENT_ID,
                        PLAN_ID,
                        NODE_ID,
                        3,
                        "NORMAL",
                        "SKIPPED_SUSPENDED",
                        0,
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 30));
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(row);
        when(taskMapper.redoSkippedTask(TASK_ID, "PENDING")).thenReturn(1);

        assertEquals(
                "PENDING",
                service.redoSkipped(TASK_ID, OPERATOR, LocalDate.of(2026, 11, 5)).status());
    }

    @Test
    void closeSkippedTerminatesWithReason() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("SKIPPED_SUSPENDED"));
        when(taskMapper.closeSkippedTask(eq(TASK_ID), eq("已随访外院（示例）"), any(), eq(OPERATOR)))
                .thenReturn(1);

        FollowupTaskStatusService.SkippedActionView view =
                service.closeSkipped(TASK_ID, OPERATOR, "已随访外院（示例）");

        assertEquals("TERMINATED", view.status());
        assertEquals("已随访外院（示例）", view.reason());
        verify(audit).append(any());
    }

    @Test
    void closeSkippedRequiresReason() {
        assertConflict(() -> service.closeSkipped(TASK_ID, OPERATOR, " "), 40001);
        assertConflict(() -> service.closeSkipped(TASK_ID, OPERATOR, null), 40001);
        verify(taskMapper, never()).closeSkippedTask(anyLong(), any(), any(), any());
    }

    @Test
    void skippedActionsRejectOtherStates() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("PENDING"));
        assertConflict(
                () -> service.redoSkipped(TASK_ID, OPERATOR, LocalDate.of(2026, 11, 5)), 40901);
        assertConflict(() -> service.closeSkipped(TASK_ID, OPERATOR, "其他（示例）"), 40901);
    }

    // ---------------------------------------------------------------- 失访候选刷新（A-FU-012，E15-T05）

    /** 候选行（窗口 [WINDOW_START, WINDOW_END]，第 3 次主任务）。 */
    private FollowupTaskMapper.OverdueCandidateRow lostCandidate(long taskId) {
        return new FollowupTaskMapper.OverdueCandidateRow(
                taskId, PATIENT_ID, 66L, 5L, "cipher-a", "第 3 年随访（示例）", WINDOW_START, WINDOW_END);
    }

    /** 窗口内联系行（新→旧由 SQL 保证，测试按传入顺序走连续段）。 */
    private static FollowupTaskMapper.ContactAttemptRow attempt(
            String result, LocalDateTime contactTime) {
        return new FollowupTaskMapper.ContactAttemptRow(1L, result, contactTime);
    }

    @Test
    void refreshLostCandidatesMarksOnDistinctDateStreak() {
        when(taskMapper.findLostCandidates(LocalDate.of(2026, 3, 23)))
                .thenReturn(List.of(lostCandidate(901L)));
        // 窗口内不同日期连续 3 次「未联系到 / 号码错误」（新→旧）
        when(taskMapper.contactsOfPatientInWindow(PATIENT_ID, WINDOW_START, WINDOW_END))
                .thenReturn(
                        List.of(
                                attempt("WRONG_NUMBER", LocalDateTime.of(2026, 3, 20, 10, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 19, 9, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 18, 15, 0))));
        when(taskMapper.markLostCandidate(901L)).thenReturn(1);
        when(crypto.decrypt("cipher-a")).thenReturn("张小明（示例）");

        assertEquals(
                1, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)), "连续 3 次不同日期 → 失访候选");

        verify(taskMapper).markLostCandidate(901L);
        // 负载契约 NotifyEventPayload（同 TASK_OVERDUE 口径）：类型 = NotifyEventType.LOST_CANDIDATE.name()
        verify(outbox).publish(eq("LOST_CANDIDATE"), contains("\"taskId\":901,\"ownerId\":66"));
        verify(outbox).publish(eq("LOST_CANDIDATE"), contains("\"windowEndDate\":\"2026-03-22\""));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("TASK_LOST_CANDIDATE_MARK", captor.getValue().action(), "批量作业写一条汇总审计");
    }

    @Test
    void refreshLostCandidatesCountsDistinctDatesOnly() {
        when(taskMapper.findLostCandidates(any())).thenReturn(List.of(lostCandidate(901L)));
        // 同日多次尝试只计一日：3 条未接通分布在 2 个日期 → 不足 3（Q02「不同日期连续 3 次」）
        when(taskMapper.contactsOfPatientInWindow(PATIENT_ID, WINDOW_START, WINDOW_END))
                .thenReturn(
                        List.of(
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 20, 10, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 20, 9, 0)),
                                attempt("WRONG_NUMBER", LocalDateTime.of(2026, 3, 19, 15, 0))));

        assertEquals(0, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)));

        verify(taskMapper, never()).markLostCandidate(anyLong());
        verify(outbox, never()).publish(any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void refreshLostCandidatesStopsStreakAtReachedOrRefused() {
        when(taskMapper.findLostCandidates(any())).thenReturn(List.of(lostCandidate(901L)));
        // 最新一条是「拒访」：拒访不计入失访（改走移出计划），连续段从它断开
        when(taskMapper.contactsOfPatientInWindow(PATIENT_ID, WINDOW_START, WINDOW_END))
                .thenReturn(
                        List.of(
                                attempt("REFUSED", LocalDateTime.of(2026, 3, 20, 10, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 19, 9, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 18, 15, 0))));

        assertEquals(
                0, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)), "「拒访」断开连续段（A-FU-012）");

        // 「已联系」同样断开
        when(taskMapper.contactsOfPatientInWindow(PATIENT_ID, WINDOW_START, WINDOW_END))
                .thenReturn(
                        List.of(
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 20, 10, 0)),
                                attempt("REACHED", LocalDateTime.of(2026, 3, 19, 9, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 18, 15, 0))));
        assertEquals(0, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)), "「已联系」断开连续段");
        verify(taskMapper, never()).markLostCandidate(anyLong());
    }

    @Test
    void refreshLostCandidatesUsesConfiguredThreshold() {
        when(policy.policy()).thenReturn(new FollowupPolicyService.ContactPolicy(2, 1));
        when(taskMapper.findLostCandidates(any())).thenReturn(List.of(lostCandidate(901L)));
        // 2 个日期连续未接通即达阈值 2（次数可配置，A-FU-012）
        when(taskMapper.contactsOfPatientInWindow(PATIENT_ID, WINDOW_START, WINDOW_END))
                .thenReturn(
                        List.of(
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 20, 10, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 19, 9, 0))));
        when(taskMapper.markLostCandidate(901L)).thenReturn(1);

        assertEquals(1, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)), "阈值取租户生效参数");
    }

    @Test
    void refreshLostCandidatesSkipsGuardMissAndEmptyCandidates() {
        when(taskMapper.findLostCandidates(any())).thenReturn(List.of());
        assertEquals(0, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)));
        verify(audit, never()).append(any());

        when(taskMapper.findLostCandidates(any())).thenReturn(List.of(lostCandidate(902L)));
        when(taskMapper.contactsOfPatientInWindow(PATIENT_ID, WINDOW_START, WINDOW_END))
                .thenReturn(
                        List.of(
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 20, 10, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 19, 9, 0)),
                                attempt("NOT_REACHED", LocalDateTime.of(2026, 3, 18, 15, 0))));
        when(taskMapper.markLostCandidate(902L)).thenReturn(0); // 并发完成 / 终止 / 重放
        assertEquals(0, service.refreshLostCandidates(LocalDate.of(2026, 3, 23)), "守卫落空不标不发");
        verify(outbox, never()).publish(any(), any());
    }

    // ---------------------------------------------------------------- 确认失访（A-FU-012，E15-T05）

    @Test
    void confirmLostMarksLostClosesFollowupsAndAudits() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("LOST_CANDIDATE"));
        when(taskMapper.confirmLostTask(TASK_ID)).thenReturn(1);
        when(taskMapper.terminateWindowFollowups(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        any(),
                        any(),
                        eq(OPERATOR)))
                .thenReturn(2);
        when(taskMapper.findWindowStatuses(ENROLLMENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.WindowStatusRow(WINDOW_START, "LOST"),
                                new FollowupTaskMapper.WindowStatusRow(
                                        LocalDate.of(2025, 3, 8), "COMPLETED")));

        FollowupTaskStatusService.LostConfirmView view = service.confirmLost(TASK_ID, OPERATOR);

        assertEquals("LOST", view.status(), "失访候选 → 失访（医生确认）");
        assertEquals(2, view.terminatedFollowups(), "同窗口未完成跟进任务关闭");
        assertFalse(view.patientLostPrompt(), "仅 1 个窗口失访，不提示患者级失访");
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("TASK_LOST_CONFIRM", captor.getValue().action());
    }

    @Test
    void confirmLostPromptsPatientLostOnTwoConsecutiveWindows() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("LOST_CANDIDATE"));
        when(taskMapper.confirmLostTask(TASK_ID)).thenReturn(1);
        when(taskMapper.findWindowStatuses(ENROLLMENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.WindowStatusRow(WINDOW_START, "LOST"),
                                new FollowupTaskMapper.WindowStatusRow(
                                        LocalDate.of(2025, 3, 8), "LOST")));

        assertTrue(
                service.confirmLost(TASK_ID, OPERATOR).patientLostPrompt(),
                "连续 2 个窗口失访 → 提示把患者状态改为失访（A-FU-012，不自动改）");
    }

    @Test
    void confirmLostPromptBreaksAtNonLostWindow() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("LOST_CANDIDATE"));
        when(taskMapper.confirmLostTask(TASK_ID)).thenReturn(1);
        // 新→旧：LOST、LOST、COMPLETED、LOST → 最新连续段 = 2（第三年前已完成，段断开）
        when(taskMapper.findWindowStatuses(ENROLLMENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.WindowStatusRow(WINDOW_START, "LOST"),
                                new FollowupTaskMapper.WindowStatusRow(
                                        LocalDate.of(2025, 3, 8), "LOST"),
                                new FollowupTaskMapper.WindowStatusRow(
                                        LocalDate.of(2024, 3, 8), "COMPLETED"),
                                new FollowupTaskMapper.WindowStatusRow(
                                        LocalDate.of(2023, 3, 8), "LOST")));

        assertTrue(service.confirmLost(TASK_ID, OPERATOR).patientLostPrompt(), "连续段仍达 2");
    }

    @Test
    void confirmLostRejectsNonCandidateStates() {
        for (String status :
                List.of("PENDING", "IN_PROGRESS", "OVERDUE", "COMPLETED", "LOST", "TERMINATED")) {
            when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow(status));
            assertConflict(() -> service.confirmLost(TASK_ID, OPERATOR), 40901);
        }
        verify(taskMapper, never()).confirmLostTask(anyLong());
    }

    @Test
    void confirmLostSurfacesConcurrentChangeAs40900() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("LOST_CANDIDATE"));
        when(taskMapper.confirmLostTask(TASK_ID)).thenReturn(0);

        assertConflict(() -> service.confirmLost(TASK_ID, OPERATOR), 40900);
    }

    // ---------------------------------------------------------------- 完成传播（A-FU-024 / OQ-86⑤）

    @Test
    void completingFollowupTaskCompletesWindowMainTask() {
        // 跟进任务（followupSeq=1）完成 → 主任务同完成方式一并完成（窗口口径完成传播）
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS", 1));
        when(taskMapper.completeTask(eq(TASK_ID), eq("ON_TIME"), any(), eq(OPERATOR)))
                .thenReturn(1);
        when(taskMapper.completeWindowMainTask(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        eq("ON_TIME"),
                        any(),
                        eq(OPERATOR)))
                .thenReturn(1);
        when(taskMapper.terminateWindowFollowups(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        any(),
                        any(),
                        eq(OPERATOR)))
                .thenReturn(0);

        assertEquals(
                "ON_TIME",
                service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 15)).completeKind());

        verify(taskMapper)
                .completeWindowMainTask(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        eq("ON_TIME"),
                        any(),
                        eq(OPERATOR));
    }

    @Test
    void completingMainTaskClosesOpenFollowups() {
        // 主任务（followupSeq=0）完成 → 同窗口其余未完成跟进任务终止
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("PENDING"));
        when(taskMapper.completeTask(eq(TASK_ID), any(), any(), eq(OPERATOR))).thenReturn(1);
        when(taskMapper.completeWindowMainTask(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        any(),
                        any(),
                        eq(OPERATOR)))
                .thenReturn(0);
        when(taskMapper.terminateWindowFollowups(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        any(),
                        any(),
                        eq(OPERATOR)))
                .thenReturn(1);

        service.complete(TASK_ID, OPERATOR, LocalDate.of(2026, 3, 15));

        verify(taskMapper)
                .terminateWindowFollowups(
                        eq(ENROLLMENT_ID),
                        eq(NODE_ID),
                        eq(3),
                        eq(TASK_ID),
                        contains("窗口任务已完成"),
                        any(),
                        eq(OPERATOR));
    }
}
