package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientBatchAssignService;
import com.crdp.followup.patient.infra.mapper.PatientBatchMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E13-T07 / A-PAT-016 批量入组单测（tasks.csv 验收「批量操作结果逐条可查」、规格验收「20 名患者其中 2 名已在组 → 成功 18、跳过 2」）。
 *
 * <p>跳过口径：已在组（ACTIVE / PAUSED）/ 已死亡 / 已终止 / 已从该计划移除 / 越范围（NOT_FOUND，不暴露存在）/ 带责任医生时医生不负责该病区；
 * 预检不写数据只写 BATCH_QUERY 审计；执行逐条经 PlanEnrollmentService.enroll（各自事务），并发冲突按 CONFLICT 跳过不中断整批。数据范围由
 * Mapper 的 @DataScope 承担（集成测试覆盖）。全部虚构数据。
 */
class BatchEnrollmentServiceTest {

    private static final long PLAN_ID = 11L;
    private static final long PATIENT_ID = 77L;
    private static final long DOCTOR_ID = 5L;

    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentMapper enrollmentMapper = mock(PlanEnrollmentMapper.class);
    private final PlanEnrollmentService enrollments = mock(PlanEnrollmentService.class);
    private final PatientBatchAssignService doctorAssignments =
            mock(PatientBatchAssignService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private final BatchEnrollmentService service =
            new BatchEnrollmentService(
                    planMapper, enrollmentMapper, enrollments, doctorAssignments, audit, crypto);

    private static PlanMapper.PlanRow publishedPlan() {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                "PLAN-IT-1",
                "示例 IT 年度随访计划",
                2,
                "PUBLISHED",
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                false,
                null,
                0,
                null,
                0L);
    }

    private static PatientBatchMapper.BatchPatientRow patient(long id, String status, Long wardId) {
        return new PatientBatchMapper.BatchPatientRow(
                id, "name-c-" + id, "mrn-c-" + id, wardId, "虚构病区一", status);
    }

    private void patientVisible(String followupStatus) {
        when(doctorAssignments.patientsInScope(anyList()))
                .thenReturn(Map.of(PATIENT_ID, patient(PATIENT_ID, followupStatus, 3L)));
    }

    // ----------------------------------------------------------------
    // 预检（A-PAT-016「确认前显示将加入人数与将跳过人数」）

    @Test
    void previewClassifiesEnrollableAndSkippedPerPatient() {
        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        when(doctorAssignments.patientsInScope(anyList()))
                .thenReturn(
                        Map.of(
                                1L, patient(1L, "ACTIVE", 3L),
                                2L, patient(2L, "DEAD", 3L),
                                3L, patient(3L, "ACTIVE", 3L)));
        when(enrollmentMapper.findStatusByPatientIdsAndPlanCode(anyString(), anyList()))
                .thenReturn(
                        List.of(
                                // 2：已死亡且曾在组（死亡不动入组行状态，OQ-79②）→ 在组判定在前
                                new PlanEnrollmentMapper.PatientEnrollmentStatusRow(2L, "ACTIVE"),
                                new PlanEnrollmentMapper.PatientEnrollmentStatusRow(
                                        3L, "REMOVED")));
        // 4 = 越范围（patientsInScope 不返回该行）

        BatchEnrollmentService.BatchEnrollResult result =
                service.preview(PLAN_ID, List.of(1L, 2L, 3L, 4L), null);

        assertEquals(1, result.succeeded(), "将加入 1（预检口径与执行一致：可入组数）");
        assertEquals(3, result.skipped(), "将跳过 3");
        assertEquals(4, result.items().size(), "逐条结果与请求顺序一致");
        assertEquals("ENROLL", result.items().get(0).outcome());
        assertNull(result.items().get(0).reason());
        assertEquals("SKIP", result.items().get(1).outcome());
        assertEquals(
                "ALREADY_ENROLLED", result.items().get(1).reason(), "已在组跳过（在组判定优先于死亡——患者本就在组里）");
        assertEquals("SKIP", result.items().get(2).outcome());
        assertEquals("REMOVED", result.items().get(2).reason(), "已从该计划移除跳过");
        assertEquals("SKIP", result.items().get(3).outcome());
        assertEquals("NOT_FOUND", result.items().get(3).reason(), "越范围按 NOT_FOUND 列明");
        assertNull(result.items().get(3).name(), "越范围患者不带摘要（不暴露存在）");
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString());
    }

    @Test
    void previewSkipsRemovedFromThisPlanAndDoctorNotInWard() {
        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        when(doctorAssignments.patientsInScope(anyList()))
                .thenReturn(
                        Map.of(
                                1L, patient(1L, "ACTIVE", 3L),
                                2L, patient(2L, "ACTIVE", 3L),
                                9L, patient(9L, "DEAD", 3L),
                                8L, patient(8L, "TERMINATED", 3L)));
        when(enrollmentMapper.findStatusByPatientIdsAndPlanCode(anyString(), anyList()))
                .thenReturn(
                        List.of(
                                new PlanEnrollmentMapper.PatientEnrollmentStatusRow(
                                        1L, "REMOVED")));
        when(doctorAssignments.doctorManagesWard(DOCTOR_ID, 3L)).thenReturn(false);

        BatchEnrollmentService.BatchEnrollResult removed =
                service.preview(PLAN_ID, List.of(1L), null);
        assertEquals("REMOVED", removed.items().get(0).reason(), "已从该计划移除的患者跳过（A-PAT-016）");

        BatchEnrollmentService.BatchEnrollResult doctorBlocked =
                service.preview(PLAN_ID, List.of(2L), DOCTOR_ID);
        assertEquals(
                "DOCTOR_NOT_IN_WARD",
                doctorBlocked.items().get(0).reason(),
                "带责任医生时医生不负责该病区跳过（A-PAT-017）");

        BatchEnrollmentService.BatchEnrollResult dead = service.preview(PLAN_ID, List.of(9L), null);
        assertEquals("DEAD", dead.items().get(0).reason(), "已死亡跳过（A-PAT-016）");

        BatchEnrollmentService.BatchEnrollResult terminated =
                service.preview(PLAN_ID, List.of(8L), null);
        assertEquals("TERMINATED", terminated.items().get(0).reason(), "已终止随访跳过");
    }

    @Test
    void previewWritesBatchQueryAuditOnly() {
        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        patientVisible("ACTIVE");

        service.preview(PLAN_ID, List.of(PATIENT_ID), null);

        ArgumentCaptor<com.crdp.framework.audit.AuditEntry> captor =
                ArgumentCaptor.forClass(com.crdp.framework.audit.AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("BATCH_QUERY", captor.getValue().action(), "批量查询写数据访问审计");
    }

    // ---------------------------------------------------------------- 执行（验收「成功 18、跳过 2」同构）

    @Test
    void enrollReturnsPerPatientResultsAndSucceedsSkippable() {
        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        when(doctorAssignments.patientsInScope(anyList()))
                .thenReturn(Map.of(PATIENT_ID, patient(PATIENT_ID, "ACTIVE", 3L)));
        when(enrollmentMapper.findStatusByPatientIdsAndPlanCode(anyString(), anyList()))
                .thenReturn(List.of());
        when(crypto.decrypt("name-c-77")).thenReturn("赵小同（示例）");
        when(crypto.decrypt("mrn-c-77")).thenReturn("IT-E13T07-1");
        when(enrollments.enroll(PATIENT_ID, PLAN_ID, null))
                .thenReturn(new PlanEnrollmentService.EnrollmentView("31", "77", "11", "NORMAL"));

        BatchEnrollmentService.BatchEnrollResult result =
                service.enroll(PLAN_ID, List.of(PATIENT_ID), null);

        assertEquals(1, result.succeeded());
        assertEquals(0, result.skipped());
        assertEquals("SUCCESS", result.items().get(0).outcome());
        assertEquals("赵*）", result.items().get(0).name(), "逐条结果姓名只含脱敏值");
        assertEquals("IT*******-1", result.items().get(0).patientNo(), "病案号前 2 后 2 掩码");
        assertEquals("虚构病区一", result.items().get(0).wardName());
        verify(enrollments).enroll(PATIENT_ID, PLAN_ID, null);
        verify(audit).append(any(com.crdp.framework.audit.AuditEntry.class));
    }

    @Test
    void enrollSkipsSingleConflictWithoutBreakingTheBatch() {
        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        when(doctorAssignments.patientsInScope(anyList()))
                .thenReturn(
                        Map.of(
                                1L, patient(1L, "ACTIVE", 3L),
                                2L, patient(2L, "ACTIVE", 3L)));
        when(enrollmentMapper.findStatusByPatientIdsAndPlanCode(anyString(), anyList()))
                .thenReturn(List.of());
        // 第一条：预检后恰被他人入组（并发）→ CONFLICT 跳过；第二条成功
        when(enrollments.enroll(1L, PLAN_ID, null))
                .thenThrow(new ApiException(409, 40901, "该患者已在该计划中（在组）"));
        when(enrollments.enroll(2L, PLAN_ID, null))
                .thenReturn(new PlanEnrollmentService.EnrollmentView("32", "2", "11", "NORMAL"));

        BatchEnrollmentService.BatchEnrollResult result =
                service.enroll(PLAN_ID, List.of(1L, 2L), null);

        assertEquals(1, result.succeeded(), "单条并发冲突不中断整批");
        assertEquals(1, result.skipped());
        assertEquals("CONFLICT", result.items().get(0).reason());
        assertEquals("SUCCESS", result.items().get(1).outcome());
    }

    @Test
    void enrollAssignsDoctorBeforeEnrollingAndSkipsWhenDoctorNotInWard() {
        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        when(doctorAssignments.patientsInScope(anyList()))
                .thenReturn(Map.of(PATIENT_ID, patient(PATIENT_ID, "ACTIVE", 3L)));
        when(enrollmentMapper.findStatusByPatientIdsAndPlanCode(anyString(), anyList()))
                .thenReturn(List.of());
        when(doctorAssignments.doctorManagesWard(DOCTOR_ID, 3L)).thenReturn(true);
        when(doctorAssignments.assignDoctorToPatient(PATIENT_ID, DOCTOR_ID, 3L, null))
                .thenReturn(PatientBatchAssignService.REASON_DOCTOR_NOT_IN_WARD);

        BatchEnrollmentService.BatchEnrollResult blocked =
                service.enroll(PLAN_ID, List.of(PATIENT_ID), DOCTOR_ID);
        assertEquals(0, blocked.succeeded());
        assertEquals("DOCTOR_NOT_IN_WARD", blocked.items().get(0).reason());
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString());

        when(doctorAssignments.assignDoctorToPatient(PATIENT_ID, DOCTOR_ID, 3L, null))
                .thenReturn(null);
        when(enrollments.enroll(PATIENT_ID, PLAN_ID, null))
                .thenReturn(new PlanEnrollmentService.EnrollmentView("31", "77", "11", "NORMAL"));
        BatchEnrollmentService.BatchEnrollResult assigned =
                service.enroll(PLAN_ID, List.of(PATIENT_ID), DOCTOR_ID);
        assertEquals(1, assigned.succeeded(), "医生属于病区 → 先指定后入组");
        verify(enrollments).enroll(PATIENT_ID, PLAN_ID, null);
    }

    // ---------------------------------------------------------------- 整体校验

    @Test
    void rejectsMissingPlanEmptyIdsAndNonPublishedPlan() {
        when(planMapper.findById(PLAN_ID)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.preview(PLAN_ID, List.of(1L), null))
                        .code(),
                "计划不存在 40400");

        when(planMapper.findById(PLAN_ID)).thenReturn(publishedPlan());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.enroll(PLAN_ID, List.of(), null))
                        .code(),
                "patientIds 为空 40001");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.enroll(
                                                PLAN_ID,
                                                java.util.stream.LongStream.rangeClosed(
                                                                1,
                                                                PatientBatchAssignService
                                                                                .MAX_BATCH_SIZE
                                                                        + 1)
                                                        .boxed()
                                                        .toList(),
                                                null))
                        .code(),
                "超过单次上限 500 40001");

        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "UNPUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                false,
                                null,
                                0,
                                null,
                                0L));
        ApiException unpublished =
                assertThrows(ApiException.class, () -> service.enroll(PLAN_ID, List.of(1L), null));
        assertEquals(40901, unpublished.code(), "未发布计划不能入组（与单条 enroll 同口径）");
        assertTrue(unpublished.getMessage().contains("尚未发布"));
    }
}
