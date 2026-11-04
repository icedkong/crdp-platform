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

import com.crdp.followup.patient.app.PatientFollowupStatusService;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.task.app.FollowupTaskTerminator;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E14-T06 / A-FU-007、C-FU-021 死亡自动终止单测（tasks.csv 验收「告知已故后未开始任务全部终止」）： 患者 → 已死亡 + 状态时间线一行、全部未开始 /
 * 未完成任务置 TERMINATED（原因「死亡」）、幂等与越范围 40400。全部虚构数据。
 */
class FollowupTerminationServiceTest {

    private static final long PATIENT_ID = 77L;
    private static final long OPERATOR_ID = 55L;

    private final PlanEnrollmentMapper enrollmentMapper = mock(PlanEnrollmentMapper.class);
    private final PatientFollowupStatusService patientStatus =
            mock(PatientFollowupStatusService.class);
    private final FollowupTaskTerminator terminator = mock(FollowupTaskTerminator.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final FollowupTerminationService service =
            new FollowupTerminationService(enrollmentMapper, patientStatus, terminator, audit);

    @Test
    void informedDeceasedTerminatesAllUnstartedTasksAndMarksPatientDead() {
        when(enrollmentMapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "ACTIVE"));
        when(terminator.terminateUnfinishedByPatient(eq(PATIENT_ID), eq("死亡"), any()))
                .thenReturn(6);

        FollowupTerminationService.DeathTerminationView view =
                service.terminateOnDeath(PATIENT_ID, "告知已故", OPERATOR_ID);

        assertEquals("DEAD", view.status());
        assertEquals(6, view.terminatedTasks(), "未开始任务全部终止（验收）");
        verify(patientStatus)
                .transition(
                        eq(PATIENT_ID),
                        eq("DEAD"),
                        eq("告知已故"),
                        eq("SYSTEM"),
                        eq((String) null),
                        eq(OPERATOR_ID));
        verify(terminator).terminateUnfinishedByPatient(eq(PATIENT_ID), eq("死亡"), eq(OPERATOR_ID));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("PATIENT_DEATH_TERMINATE", captor.getValue().action());
        assertTrue(captor.getValue().afterJson().contains("\"terminatedTasks\":6"));
        assertTrue(captor.getValue().description().contains("之后不再创建任务"));
    }

    @Test
    void deathTerminationIsIdempotentForAlreadyDeadPatient() {
        when(enrollmentMapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "DEAD"));
        when(terminator.terminateUnfinishedByPatient(anyLong(), anyString(), any())).thenReturn(0);

        FollowupTerminationService.DeathTerminationView view =
                service.terminateOnDeath(PATIENT_ID, "随访记录提交「是否死亡=是」", OPERATOR_ID);

        assertEquals(0, view.terminatedTasks());
        verify(patientStatus, never())
                .transition(anyLong(), anyString(), any(), any(), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void missingPatientIsRejectedWith40400() {
        when(enrollmentMapper.findPatientState(PATIENT_ID)).thenReturn(null);
        ApiException rejected =
                assertThrows(
                        ApiException.class,
                        () -> service.terminateOnDeath(PATIENT_ID, "告知已故", OPERATOR_ID));
        assertEquals(40400, rejected.code(), "患者不存在 40400（不暴露存在）");
        verify(terminator, never()).terminateUnfinishedByPatient(anyLong(), anyString(), any());
    }
}
