package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E14-T07 / A-FU-011 起算日期变更重算单测（tasks.csv 验收「改出院日期后未开始任务重算」）： 未开始（PENDING、尚无联系记录）任务按新起算日期重排窗口 /
 * 新窗口未开启撤回为计划中、快照与下次计划日期缓存实时刷新、已完成与未变化幂等。全部虚构数据。
 */
class StartDateRecalculationServiceTest {

    private static final long PATIENT_ID = 77L;
    private static final long ENROLLMENT_ID = 31L;
    private static final long PLAN_ID = 11L;
    private static final long SUBPLAN_ID = 100L;
    private static final long NODE_ID = 200L;
    private static final long TASK_ID = 901L;
    private static final LocalDate OLD_START = LocalDate.of(2025, 3, 15);

    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentMapper enrollmentMapper = mock(PlanEnrollmentMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final StartDateRecalculationService service =
            new StartDateRecalculationService(taskMapper, planMapper, enrollmentMapper, audit);

    // ---------------------------------------------------------------- 装配（A 年度形态：+1 年、提前 7 天、窗口 ±7
    // 天）

    private void patientAlive() {
        when(enrollmentMapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "ACTIVE"));
    }

    private void annualEnrollment(LocalDate snapshotStart) {
        when(taskMapper.findInGroupEnrollmentsByPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.RecalcEnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        snapshotStart,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1))));
        when(planMapper.findById(PLAN_ID))
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
        when(planMapper.findNodesByPlanId(PLAN_ID)).thenReturn(List.of(subplanRow(), nodeRow()));
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

    private void pendingTask(int occurrence) {
        when(taskMapper.findUnstartedTasks(ENROLLMENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.UnstartedTaskRow(
                                        TASK_ID, NODE_ID, occurrence, "NORMAL")));
        when(taskMapper.rescheduleTask(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);
        when(taskMapper.withdrawTask(anyLong())).thenReturn(1);
    }

    // ---------------------------------------------------------------- 验收：未开始任务按新日期重排窗口

    @Test
    void pendingTaskGetsNewWindowAndCacheRefreshedInRealTime() {
        patientAlive();
        annualEnrollment(OLD_START);
        // 新出院日期 = 一年前：第 1 次计划日期落在今天，窗口已开启 → 重排窗口（不是撤回）
        LocalDate newStart = PlanDateCalculator.businessToday(Instant.now()).minusYears(1);
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(newStart);
        pendingTask(1);

        StartDateRecalculationService.RecalculationView view =
                service.recalculateOnBaseDateChange(PATIENT_ID, 55L);

        LocalDate planDate = newStart.plusYears(1);
        verify(taskMapper)
                .rescheduleTask(
                        TASK_ID,
                        planDate,
                        planDate.minusDays(7),
                        planDate.minusDays(7),
                        planDate.plusDays(7),
                        planDate.minusDays(7));
        verify(taskMapper, never()).withdrawTask(anyLong());
        verify(taskMapper).updateStartDate(ENROLLMENT_ID, newStart);
        // 下次计划日期缓存实时指向第 2 次（新起算 + 2 年）
        verify(taskMapper)
                .updateNextCache(
                        ENROLLMENT_ID, newStart.plusYears(2), newStart.plusYears(2).minusDays(7));
        assertEquals(1, view.enrollments(), "一条入组快照更新");
        assertEquals(1, view.rescheduled(), "一条未开始任务重排");
        assertEquals(0, view.withdrawn());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("PLAN_START_DATE_RECALC", captor.getValue().action());
        assertTrue(captor.getValue().beforeJson().contains(OLD_START.toString()));
        assertTrue(captor.getValue().afterJson().contains(newStart.toString()));
    }

    @Test
    void unopenedNewWindowWithdrawsTaskBackToPlanned() {
        patientAlive();
        annualEnrollment(OLD_START);
        // 新出院日期 = 今天：第 1 次开启日在未来 → 撤回为「计划中」（删任务行，调度到点按新日期重建）
        LocalDate newStart = PlanDateCalculator.businessToday(Instant.now());
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(newStart);
        pendingTask(1);

        StartDateRecalculationService.RecalculationView view =
                service.recalculateOnBaseDateChange(PATIENT_ID, 55L);

        verify(taskMapper).withdrawTask(TASK_ID);
        verify(taskMapper, never()).rescheduleTask(anyLong(), any(), any(), any(), any(), any());
        verify(taskMapper).updateStartDate(ENROLLMENT_ID, newStart);
        // 下次计划日期 = 第 1 次（尚无已创建任务，缓存直接指向它）
        verify(taskMapper)
                .updateNextCache(
                        ENROLLMENT_ID, newStart.plusYears(1), newStart.plusYears(1).minusDays(7));
        assertEquals(0, view.rescheduled());
        assertEquals(1, view.withdrawn(), "新窗口尚未开启 → 撤回为计划中");
    }

    // ---------------------------------------------------------------- 幂等 / 跳过 / 边界

    @Test
    void unchangedStartDateIsNoOp() {
        patientAlive();
        annualEnrollment(OLD_START);
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(OLD_START);

        StartDateRecalculationService.RecalculationView view =
                service.recalculateOnBaseDateChange(PATIENT_ID, 55L);

        verify(taskMapper, never()).findUnstartedTasks(anyLong());
        verify(taskMapper, never()).updateStartDate(anyLong(), any());
        verify(taskMapper, never()).updateNextCache(anyLong(), any(), any());
        verify(audit, never()).append(any());
        assertEquals(0, view.enrollments());
        assertEquals(0, view.rescheduled());
        assertEquals(0, view.withdrawn());
    }

    @Test
    void nullSnapshotIsFilledAsChange() {
        patientAlive();
        annualEnrollment(null);
        LocalDate newStart = PlanDateCalculator.businessToday(Instant.now()).minusYears(1);
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(newStart);
        pendingTask(1);

        service.recalculateOnBaseDateChange(PATIENT_ID, 55L);

        verify(taskMapper).updateStartDate(ENROLLMENT_ID, newStart);
        verify(taskMapper).rescheduleTask(eq(TASK_ID), any(), any(), any(), any(), any());
    }

    @Test
    void unresolvableStartDateSkipsEnrollment() {
        patientAlive();
        annualEnrollment(OLD_START);
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(null);

        StartDateRecalculationService.RecalculationView view =
                service.recalculateOnBaseDateChange(PATIENT_ID, 55L);

        verify(taskMapper, never()).updateStartDate(anyLong(), any());
        verify(taskMapper, never()).withdrawTask(anyLong());
        assertEquals(0, view.enrollments());
    }

    @Test
    void multipleEnrollmentsRecalculatedIndependently() {
        patientAlive();
        long planB = 12L;
        LocalDate newDischarge = PlanDateCalculator.businessToday(Instant.now()).minusYears(1);
        when(taskMapper.findInGroupEnrollmentsByPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.RecalcEnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        OLD_START,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1)),
                                new FollowupTaskMapper.RecalcEnrollmentRow(
                                        32L,
                                        PATIENT_ID,
                                        planB,
                                        OLD_START,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 2))));
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(newDischarge);
        // 手术起算的计划（医院 B 形态）：手术日期未变 → 该入组不动
        when(taskMapper.resolveStartDate(planB, PATIENT_ID)).thenReturn(OLD_START);
        when(planMapper.findById(PLAN_ID))
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
        when(planMapper.findNodesByPlanId(PLAN_ID)).thenReturn(List.of(subplanRow(), nodeRow()));
        pendingTask(1);

        StartDateRecalculationService.RecalculationView view =
                service.recalculateOnBaseDateChange(PATIENT_ID, 55L);

        verify(taskMapper).updateStartDate(ENROLLMENT_ID, newDischarge);
        verify(taskMapper, never()).updateStartDate(eq(32L), any());
        assertEquals(1, view.enrollments(), "只有起算字段变化了的入组重算");
    }

    @Test
    void missingPatientIsRejectedWith40400() {
        when(enrollmentMapper.findPatientState(PATIENT_ID)).thenReturn(null);

        ApiException rejected =
                assertThrows(
                        ApiException.class,
                        () -> service.recalculateOnBaseDateChange(PATIENT_ID, 55L));
        assertEquals(40400, rejected.code(), "患者不存在 40400（不暴露存在）");
        verify(taskMapper, never()).findInGroupEnrollmentsByPatient(anyLong());
    }
}
