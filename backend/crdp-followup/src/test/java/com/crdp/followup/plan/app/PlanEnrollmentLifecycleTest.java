package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.patient.app.PatientFollowupStatusService;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.app.FollowupTaskTerminator;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E14-T06 / A-FU-008、A-PAT-011 入组生命周期单测（暂停 / 恢复 / 移出须填原因；状态守卫 40901；越范围患者 40400；
 * 患者级状态只在无其他在组入组时同步；恢复触发暂停补偿）。全部虚构数据。
 */
class PlanEnrollmentLifecycleTest {

    private static final long PLAN_ID = 11L;
    private static final long ENROLLMENT_ID = 31L;
    private static final long PATIENT_ID = 77L;
    private static final LocalDate PAUSED_FROM = LocalDate.of(2026, 10, 5);

    private final com.crdp.followup.plan.infra.mapper.PlanMapper planMapper =
            mock(com.crdp.followup.plan.infra.mapper.PlanMapper.class);
    private final PlanEnrollmentMapper mapper = mock(PlanEnrollmentMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final FollowupTaskCreationService taskCreation =
            mock(FollowupTaskCreationService.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final PatientFollowupStatusService patientStatus =
            mock(PatientFollowupStatusService.class);
    private final FollowupTaskTerminator taskTerminations = mock(FollowupTaskTerminator.class);

    private final PlanEnrollmentService service =
            new PlanEnrollmentService(
                    planMapper,
                    mapper,
                    audit,
                    crypto,
                    taskCreation,
                    patientScope,
                    patientStatus,
                    taskTerminations);

    // ---------------------------------------------------------------- 装配

    /** 患者在操作者数据范围内（默认口径；越范围用例单独覆盖）。 */
    private void patientInScope() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
    }

    private PlanEnrollmentMapper.EnrollmentStateRow state(
            String status, String patientStatusValue) {
        return new PlanEnrollmentMapper.EnrollmentStateRow(
                ENROLLMENT_ID,
                PATIENT_ID,
                PLAN_ID,
                "PLAN-IT-1",
                "NORMAL",
                status,
                "PAUSED".equals(status) ? PAUSED_FROM : null,
                null,
                patientStatusValue);
    }

    // ---------------------------------------------------------------- 暂停（A-FU-008 / A-PAT-011）

    @Test
    void pauseRequiresReasonAndActiveEnrollment() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        ApiException blank =
                assertThrows(ApiException.class, () -> service.pause(PLAN_ID, ENROLLMENT_ID, "  "));
        assertEquals(40001, blank.code(), "暂停须填原因（A-PAT-011）");

        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () -> service.pause(PLAN_ID, ENROLLMENT_ID, "长".repeat(256)));
        assertEquals(40001, tooLong.code());

        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("PAUSED", "PAUSED"));
        ApiException already =
                assertThrows(
                        ApiException.class, () -> service.pause(PLAN_ID, ENROLLMENT_ID, "外地随访"));
        assertEquals(40901, already.code(), "已暂停的入组不能再暂停");

        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("REMOVED", "TERMINATED"));
        ApiException removed =
                assertThrows(
                        ApiException.class, () -> service.pause(PLAN_ID, ENROLLMENT_ID, "拒绝随访"));
        assertEquals(40901, removed.code());
        verify(mapper, never()).pauseEnrollment(anyLong(), anyString());
    }

    @Test
    void pauseMarksEnrollmentAndSyncsPatientWhenLastEnrolled() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        when(mapper.countOtherEnrolled(PATIENT_ID, ENROLLMENT_ID)).thenReturn(0);
        when(mapper.pauseEnrollment(ENROLLMENT_ID, "外地随访")).thenReturn(1);

        PlanEnrollmentService.EnrollmentActionView view =
                service.pause(PLAN_ID, ENROLLMENT_ID, " 外地随访 ");

        assertEquals("PAUSED", view.status());
        verify(mapper).pauseEnrollment(ENROLLMENT_ID, "外地随访");
        verify(patientStatus)
                .transition(
                        eq(PATIENT_ID), eq("PAUSED"), eq("外地随访"), eq("PLAN"), eq("ACTIVE"), any());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("ENROLLMENT_PAUSE", captor.getValue().action());
        assertTrue(captor.getValue().afterJson().contains("\"status\":\"PAUSED\""));
    }

    @Test
    void pauseKeepsPatientActiveWhenOtherEnrollmentRemains() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        when(mapper.countOtherEnrolled(PATIENT_ID, ENROLLMENT_ID)).thenReturn(1);
        when(mapper.pauseEnrollment(ENROLLMENT_ID, "拒绝随访")).thenReturn(1);

        service.pause(PLAN_ID, ENROLLMENT_ID, "拒绝随访");

        verify(mapper).pauseEnrollment(ENROLLMENT_ID, "拒绝随访");
        verify(patientStatus, never())
                .transition(anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    void pauseRejectsForeignPlanOrOutOfScopePatient() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        ApiException foreign =
                assertThrows(
                        ApiException.class, () -> service.pause(PLAN_ID + 1, ENROLLMENT_ID, "其他"));
        assertEquals(40400, foreign.code(), "入组不属于该计划按不存在处理");

        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(null);
        ApiException outOfScope =
                assertThrows(ApiException.class, () -> service.pause(PLAN_ID, ENROLLMENT_ID, "其他"));
        assertEquals(40400, outOfScope.code(), "越范围患者 40400（不暴露存在）");
    }

    // ---------------------------------------------------------------- 恢复（A-FU-008「从下一个窗口起继续」）

    @Test
    void resumeRejectsNotPausedOrDeadPatient() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        ApiException active =
                assertThrows(ApiException.class, () -> service.resume(PLAN_ID, ENROLLMENT_ID));
        assertEquals(40901, active.code());

        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("PAUSED", "DEAD"));
        ApiException dead =
                assertThrows(ApiException.class, () -> service.resume(PLAN_ID, ENROLLMENT_ID));
        assertEquals(40901, dead.code(), "已死亡患者不能恢复（A-FU-007 之后不再随访）");
        assertTrue(dead.getMessage().contains("死亡"));
        verify(mapper, never()).resumeEnrollment(anyLong());
    }

    @Test
    void resumeRestoresEnrollmentAndRunsPauseCompensation() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("PAUSED", "PAUSED"));
        when(mapper.countOtherPaused(PATIENT_ID, ENROLLMENT_ID)).thenReturn(0);
        when(mapper.resumeEnrollment(ENROLLMENT_ID)).thenReturn(1);
        when(taskCreation.initializeForResumedEnrollment(
                        ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL", PAUSED_FROM))
                .thenReturn(new FollowupTaskCreationService.ResumeInitSummary(2, 1));

        PlanEnrollmentService.EnrollmentActionView view = service.resume(PLAN_ID, ENROLLMENT_ID);

        assertEquals("ACTIVE", view.status());
        assertEquals(2, view.pausedSkipped(), "暂停期间未执行补建数并入响应");
        assertEquals(1, view.normal());
        verify(mapper).resumeEnrollment(ENROLLMENT_ID);
        verify(taskCreation)
                .initializeForResumedEnrollment(
                        ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL", PAUSED_FROM);
        verify(patientStatus)
                .transition(
                        eq(PATIENT_ID), eq("ACTIVE"), anyString(), eq("PLAN"), eq("PAUSED"), any());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("ENROLLMENT_RESUME", captor.getValue().action());
        assertTrue(captor.getValue().afterJson().contains("\"pausedSkippedTasks\":2"));
    }

    @Test
    void resumeKeepsPatientPausedWhileOtherEnrollmentPaused() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("PAUSED", "PAUSED"));
        when(mapper.countOtherPaused(PATIENT_ID, ENROLLMENT_ID)).thenReturn(1);
        when(mapper.resumeEnrollment(ENROLLMENT_ID)).thenReturn(1);
        when(taskCreation.initializeForResumedEnrollment(
                        anyLong(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new FollowupTaskCreationService.ResumeInitSummary(0, 0));

        service.resume(PLAN_ID, ENROLLMENT_ID);

        verify(patientStatus, never())
                .transition(anyLong(), anyString(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 移出（A-FU-008 / A-PAT-011）

    @Test
    void removeRequiresReasonAndEnrolledStatus() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        ApiException blank =
                assertThrows(
                        ApiException.class, () -> service.remove(PLAN_ID, ENROLLMENT_ID, null));
        assertEquals(40001, blank.code(), "移出须填原因（A-PAT-011）");

        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("REMOVED", "TERMINATED"));
        ApiException again =
                assertThrows(
                        ApiException.class, () -> service.remove(PLAN_ID, ENROLLMENT_ID, "拒绝随访"));
        assertEquals(40901, again.code(), "已移出的入组不能重复移出");
        verify(mapper, never()).removeEnrollment(anyLong(), anyString());
    }

    @Test
    void removeTerminatesUnfinishedTasksAndSyncsPatient() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("ACTIVE", "ACTIVE"));
        when(mapper.countOtherEnrolled(PATIENT_ID, ENROLLMENT_ID)).thenReturn(0);
        when(mapper.removeEnrollment(ENROLLMENT_ID, "拒绝随访")).thenReturn(1);
        when(taskTerminations.terminateUnfinishedByEnrollment(eq(ENROLLMENT_ID), eq("拒绝随访"), any()))
                .thenReturn(4);

        PlanEnrollmentService.EnrollmentActionView view =
                service.remove(PLAN_ID, ENROLLMENT_ID, "拒绝随访");

        assertEquals("REMOVED", view.status());
        assertEquals(4, view.terminated(), "终止任务数并入响应");
        verify(mapper).removeEnrollment(ENROLLMENT_ID, "拒绝随访");
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(eq(ENROLLMENT_ID), eq("拒绝随访"), any());
        verify(patientStatus)
                .transition(
                        eq(PATIENT_ID),
                        eq("TERMINATED"),
                        eq("拒绝随访"),
                        eq("PLAN"),
                        eq("ACTIVE"),
                        any());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("ENROLLMENT_REMOVE", captor.getValue().action());
        assertTrue(captor.getValue().afterJson().contains("\"terminatedTasks\":4"));
    }

    @Test
    void removeFromPausedEnrollmentWorksButKeepsDeadPatientStatus() {
        patientInScope();
        when(mapper.findEnrollmentState(ENROLLMENT_ID)).thenReturn(state("PAUSED", "DEAD"));
        when(mapper.countOtherEnrolled(PATIENT_ID, ENROLLMENT_ID)).thenReturn(0);
        when(mapper.removeEnrollment(ENROLLMENT_ID, "其他")).thenReturn(1);
        when(taskTerminations.terminateUnfinishedByEnrollment(anyLong(), anyString(), any()))
                .thenReturn(0);

        service.remove(PLAN_ID, ENROLLMENT_ID, "其他");

        verify(mapper).removeEnrollment(ENROLLMENT_ID, "其他");
        verify(patientStatus, never())
                .transition(anyLong(), anyString(), any(), any(), any(), any());
    }
}
