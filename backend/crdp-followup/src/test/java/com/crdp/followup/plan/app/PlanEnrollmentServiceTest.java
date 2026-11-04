package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.infra.mapper.PlanEnrollmentInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * E14-T01 / A-FU-019 入组单测（验收「新入组用新版本」）：入组只允许已发布（或派生随访中） 版本——旧版本发布新版本时已自动停用，入组必然落在新版本； 未发布 / 已暂停 /
 * 已停用 40901； 患者死亡 / 终止 40901；同族已在组 40901（唯一键兜底同口径）。全部虚构数据。
 */
class PlanEnrollmentServiceTest {

    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;

    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentMapper mapper = mock(PlanEnrollmentMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PlanEnrollmentService service =
            new PlanEnrollmentService(planMapper, mapper, audit);

    private static PlanMapper.PlanRow plan(String status, int version) {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                "PLAN-IT-1",
                "示例 IT 年度随访计划",
                version,
                status,
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                null,
                0,
                null,
                "ACTIVE".equals(status) ? 5L : 0L);
    }

    private void patientAlive() {
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "ACTIVE"));
    }

    @Test
    void enrollPinsPublishedPlanVersion() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 2));
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(null);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanEnrollmentInsertRow.class).setId(31L);
                            return null;
                        })
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));

        PlanEnrollmentService.EnrollmentView view = service.enroll(PATIENT_ID, PLAN_ID, " normal ");

        assertEquals(String.valueOf(PLAN_ID), view.planId(), "入组行固定在当前发布版本 v2（A-FU-019）");
        assertEquals("NORMAL", view.mode());
        ArgumentCaptor<PlanEnrollmentInsertRow> captor =
                ArgumentCaptor.forClass(PlanEnrollmentInsertRow.class);
        verify(mapper).insertEnrollment(captor.capture());
        assertEquals("PLAN-IT-1", captor.getValue().getPlanCode());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        verify(audit).append(any(com.crdp.framework.audit.AuditEntry.class));
    }

    @Test
    void enrollRejectedForNonPublishedOrDeadOrDuplicate() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("UNPUBLISHED", 1));
        ApiException unpublished =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, unpublished.code());

        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PAUSED", 1));
        ApiException paused =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, paused.code());
        assertTrue(paused.getMessage().contains("暂停"));

        when(planMapper.findById(PLAN_ID)).thenReturn(plan("DISABLED", 1));
        ApiException disabled =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, disabled.code());
        assertTrue(disabled.getMessage().contains("新版本"), "已停用不再允许入组 → 引导用新版本");

        // 患者死亡（A-PAT-016 批量入组「已死亡跳过」同口径）
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "DEAD"));
        ApiException dead =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, dead.code());

        // 同族已在组
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(31L);
        ApiException dup =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, dup.code());
        assertTrue(dup.getMessage().contains("已在"));

        // 唯一键兜底（并发重复入组）
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_fu_enrollment_active"))
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));
        ApiException race =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, race.code());
    }

    @Test
    void enrollValidatesModeAndMissingPatient() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        when(mapper.findPatientState(anyLong())).thenReturn(null);
        ApiException missing =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40400, missing.code());

        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(anyLong(), anyString())).thenReturn(null);
        ApiException badMode =
                assertThrows(
                        ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, "monthly"));
        assertEquals(40001, badMode.code());
    }
}
