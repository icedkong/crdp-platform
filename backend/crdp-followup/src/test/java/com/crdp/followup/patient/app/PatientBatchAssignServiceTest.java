package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientBatchMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E13-T07 / A-PAT-017、C-PAT-016 批量指定责任医生单测（tasks.csv 验收「批量操作结果逐条可查」）。
 *
 * <p>「只能选该病区的医生」：医生须启用（整体 40001）且负责患者当前病区（sys_user_ward）——跨病区患者逐条 DOCTOR_NOT_IN_WARD 跳过； 越范围 /
 * 不存在患者 NOT_FOUND（不带摘要，不暴露存在）；并发转病区（ward_id 守卫 0 行）WARD_CHANGED。逐条结果含脱敏摘要、成功 / 跳过计数，顺序与请求一致；一次批量一条
 * PATIENT_BATCH_ASSIGN 汇总审计（不含姓名明文）。数据范围由 Mapper 的 @DataScope 承担（集成测试覆盖）。全部虚构数据。
 */
class PatientBatchAssignServiceTest {

    private static final long DOCTOR_ID = 5L;
    private static final long WARD_A = 3L;
    private static final long WARD_B = 4L;

    private final PatientBatchMapper mapper = mock(PatientBatchMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private final PatientBatchAssignService service =
            new PatientBatchAssignService(mapper, audit, crypto);

    private static PatientBatchMapper.BatchPatientRow patient(long id, long wardId, String status) {
        return new PatientBatchMapper.BatchPatientRow(
                id, "name-c-" + id, "mrn-c-" + id, wardId, "虚构病区一", status);
    }

    private void doctorEnabledManaging(long... wardIds) {
        when(mapper.countEnabledDoctor(DOCTOR_ID)).thenReturn(1);
        when(mapper.findDoctorWardIds(DOCTOR_ID))
                .thenReturn(java.util.Arrays.stream(wardIds).boxed().toList());
    }

    @Test
    void assignUpdatesEachPatientAndReturnsPerItemResults() {
        doctorEnabledManaging(WARD_A);
        when(mapper.findPatientsInScope(anyList()))
                .thenReturn(List.of(patient(77L, WARD_A, "ACTIVE"), patient(78L, WARD_A, "DEAD")));
        when(mapper.updateResponsibleUser(eq(77L), eq(WARD_A), eq(DOCTOR_ID), any())).thenReturn(1);
        when(mapper.updateResponsibleUser(eq(78L), eq(WARD_A), eq(DOCTOR_ID), any())).thenReturn(1);
        when(crypto.decrypt("name-c-77")).thenReturn("赵小同（示例）");
        when(crypto.decrypt("mrn-c-77")).thenReturn("IT-E13T07-1");

        PatientBatchAssignService.BatchAssignResult result =
                service.assign(List.of(77L, 78L), DOCTOR_ID);

        assertEquals(2, result.succeeded());
        assertEquals(0, result.skipped());
        assertEquals(2, result.items().size());
        assertEquals("SUCCESS", result.items().get(0).outcome());
        assertEquals("赵*）", result.items().get(0).name(), "逐条结果姓名只含脱敏值");
        assertEquals("IT*******-1", result.items().get(0).patientNo());
        verify(mapper).updateResponsibleUser(eq(77L), eq(WARD_A), eq(DOCTOR_ID), any());
        ArgumentCaptor<com.crdp.framework.audit.AuditEntry> captor =
                ArgumentCaptor.forClass(com.crdp.framework.audit.AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("PATIENT_BATCH_ASSIGN", captor.getValue().action(), "一次批量一条汇总审计");
        assertTrue(
                captor.getValue().afterJson().contains("\"succeeded\":2"),
                "审计含成功计数：" + captor.getValue().afterJson());
    }

    @Test
    void assignSkipsDoctorNotInWardAndOutOfRangeAndWardChanged() {
        doctorEnabledManaging(WARD_A);
        // 77 病区 A（医生负责）→ 成功；79 病区 B（医生不负责）→ DOCTOR_NOT_IN_WARD；
        // 78 越范围（findPatientsInScope 不返回）；80 守卫 0 行（并发转病区）
        PatientBatchMapper.BatchPatientRow inWardA = patient(77L, WARD_A, "ACTIVE");
        PatientBatchMapper.BatchPatientRow otherWard = patient(79L, WARD_B, "ACTIVE");
        PatientBatchMapper.BatchPatientRow guarded = patient(80L, WARD_A, "ACTIVE");
        when(mapper.findPatientsInScope(anyList()))
                .thenReturn(List.of(inWardA, otherWard, guarded));
        when(mapper.updateResponsibleUser(eq(77L), eq(WARD_A), eq(DOCTOR_ID), any())).thenReturn(1);
        when(mapper.updateResponsibleUser(eq(80L), eq(WARD_A), eq(DOCTOR_ID), any())).thenReturn(0);

        PatientBatchAssignService.BatchAssignResult result =
                service.assign(List.of(77L, 78L, 79L, 80L), DOCTOR_ID);

        assertEquals(1, result.succeeded());
        assertEquals(3, result.skipped());
        assertEquals("SUCCESS", result.items().get(0).outcome());
        assertEquals("NOT_FOUND", result.items().get(1).reason(), "越范围患者逐条列明且不带摘要");
        assertNull(result.items().get(1).name(), "不暴露对象存在");
        assertEquals("DOCTOR_NOT_IN_WARD", result.items().get(2).reason(), "A-PAT-017「只能选该病区的医生」");
        assertEquals("WARD_CHANGED", result.items().get(3).reason(), "并发转病区守卫 0 行");
        verify(mapper, never()).updateResponsibleUser(eq(79L), anyLong(), anyLong(), any());
    }

    @Test
    void assignValidatesDoctorAndIds() {
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.assign(List.of(1L), null)).code(),
                "未选医生 40001");

        when(mapper.countEnabledDoctor(DOCTOR_ID)).thenReturn(0);
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.assign(List.of(1L), DOCTOR_ID))
                        .code(),
                "医生不存在或已停用 40001");

        doctorEnabledManaging(WARD_A);
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.assign(List.of(), DOCTOR_ID)).code(),
                "patientIds 为空 40001");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.assign(
                                                java.util.stream.LongStream.rangeClosed(
                                                                1,
                                                                PatientBatchAssignService
                                                                                .MAX_BATCH_SIZE
                                                                        + 1)
                                                        .boxed()
                                                        .toList(),
                                                DOCTOR_ID))
                        .code(),
                "单次最多 500");
        // 去重：同一 id 只处理一次
        when(mapper.findPatientsInScope(anyList()))
                .thenReturn(List.of(patient(77L, WARD_A, "ACTIVE")));
        when(mapper.updateResponsibleUser(eq(77L), eq(WARD_A), eq(DOCTOR_ID), any())).thenReturn(1);
        PatientBatchAssignService.BatchAssignResult deduped =
                service.assign(List.of(77L, 77L), DOCTOR_ID);
        assertEquals(1, deduped.items().size(), "重复 id 去重");
    }

    @Test
    void doctorOptionsListsEnabledUsersOfEnabledWards() {
        when(mapper.findDoctorOptions(anyList()))
                .thenReturn(
                        List.of(
                                new PatientBatchMapper.DoctorOptionRow(
                                        5L, "doctor.demo", "演示医生（示例）", 3L, "虚构病区一"),
                                new PatientBatchMapper.DoctorOptionRow(
                                        6L, "nurse.demo", "演示护士（示例）", 3L, "虚构病区一")));

        PatientBatchAssignService.DoctorOptionsView options = service.doctorOptions(List.of(3L));

        assertEquals(2, options.doctors().size());
        assertEquals("5", options.doctors().get(0).id(), "超过 2^53 的 ID 序列化为字符串同口径");
        assertEquals("演示医生（示例）", options.doctors().get(0).displayName());
        assertEquals("3", options.doctors().get(0).wardId());
        assertEquals("虚构病区一", options.doctors().get(0).wardName());
    }

    @Test
    void assignDoctorToPatientIsReusableDomainEntry() {
        doctorEnabledManaging(WARD_A);
        when(mapper.updateResponsibleUser(77L, WARD_A, DOCTOR_ID, null)).thenReturn(1);
        assertNull(service.assignDoctorToPatient(77L, DOCTOR_ID, WARD_A, null), "null = 已指定");

        assertEquals(
                PatientBatchAssignService.REASON_DOCTOR_NOT_IN_WARD,
                service.assignDoctorToPatient(77L, DOCTOR_ID, WARD_B, null),
                "医生不负责该病区返回原因码");
    }
}
