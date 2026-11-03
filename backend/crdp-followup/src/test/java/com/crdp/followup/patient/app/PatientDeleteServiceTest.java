package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T09 / A-PAT-012、A-NFR-008 单元测试（tasks.csv 验收：删除只软删）。锁定口径： 越范围 40400（DENIED 审计，不暴露存在）、原因必填 ≤
 * 255、只置删除标记并释放病案号（不物理删除、 不动子表）、恢复前病案号占位检查（被新患者占用 40902）、重复删除 / 恢复 40901、PATIENT_DELETE /
 * PATIENT_RESTORE 审计。全部虚构数据。
 */
class PatientDeleteServiceTest {

    private static final long PATIENT_ID = 321L;
    private static final long OPERATOR_ID = 9L;
    private static final String MRN_BLIND = "blind-mrn-it";

    private final PatientDeleteGateway gateway = mock(PatientDeleteGateway.class);
    private final PatientMapper patientMapper = mock(PatientMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PatientDeleteService service =
            new PatientDeleteService(gateway, patientMapper, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 删除（A-PAT-012：只软删）

    @Test
    void deleteMarksRowDeletedAndKeepsData() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(patientMapper.softDelete(PATIENT_ID, "录入错误（示例）", OPERATOR_ID)).thenReturn(1);

        PatientDeleteService.DeletedView view =
                service.delete(PATIENT_ID, new PatientDeleteService.DeleteRequest(" 录入错误（示例） "));

        assertEquals(String.valueOf(PATIENT_ID), view.patientId());
        assertEquals("DELETED", view.status());
        // 只软删：置标记 + 原因（去空白），由 SQL 承担（delete_token = id 释放病案号），无任何物理删除调用
        verify(patientMapper).softDelete(PATIENT_ID, "录入错误（示例）", OPERATOR_ID);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_DELETE", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
        assertEquals("{\"deleted\":true,\"reason\":\"录入错误（示例）\"}", entry.getValue().afterJson());
    }

    @Test
    void deleteOutOfScopeReturns40400WithDeniedAudit() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.delete(
                                        PATIENT_ID,
                                        new PatientDeleteService.DeleteRequest("越范围（示例）")));

        assertEquals(40400, error.code(), "越范围 / 已删除不暴露对象存在");
        verify(patientMapper, never()).softDelete(anyLong(), anyString(), anyLong());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals(AuditResult.DENIED, entry.getValue().result());
        assertEquals(40400, entry.getValue().errorCode());
    }

    @Test
    void deleteRequiresReasonAndRejectsConcurrentDoubleDelete() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.delete(
                                                PATIENT_ID,
                                                new PatientDeleteService.DeleteRequest(" ")))
                        .code(),
                "原因必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.delete(
                                                PATIENT_ID,
                                                new PatientDeleteService.DeleteRequest(
                                                        "长".repeat(256))))
                        .code(),
                "原因长度上限 255");

        // 校验与更新之间被他人删除：更新 0 行 → 40901，不重复写 SUCCESS 审计
        when(patientMapper.softDelete(anyLong(), anyString(), anyLong())).thenReturn(0);
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.delete(
                                                PATIENT_ID,
                                                new PatientDeleteService.DeleteRequest("并发（示例）")))
                        .code(),
                "已被删除无需重复操作");
        verify(audit, never()).append(org.mockito.ArgumentMatchers.any(AuditEntry.class));
    }

    // ---------------------------------------------------------------- 恢复（A-PAT-012「可由租户管理员恢复」）

    @Test
    void restoreClearsMarkersAfterMrnConflictCheck() {
        when(patientMapper.findDeletedById(PATIENT_ID))
                .thenReturn(new PatientMapper.DeletedRow(PATIENT_ID, MRN_BLIND, "录入错误（示例）"));
        when(patientMapper.findLiveIdByMrnBlind(MRN_BLIND)).thenReturn(null);
        when(patientMapper.restore(PATIENT_ID, OPERATOR_ID)).thenReturn(1);

        PatientDeleteService.RestoredView view = service.restore(PATIENT_ID);

        assertEquals(String.valueOf(PATIENT_ID), view.patientId());
        verify(patientMapper).restore(PATIENT_ID, OPERATOR_ID);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_RESTORE", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
    }

    @Test
    void restoreRejectsMissingRowOccupiedMrnAndDoubleRestore() {
        when(patientMapper.findDeletedById(PATIENT_ID)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.restore(PATIENT_ID)).code(),
                "没有可恢复的已删除患者");

        // 病案号已被新在册患者占用（软删后病案号可复用，V37 预留冲突流程）
        when(patientMapper.findDeletedById(PATIENT_ID))
                .thenReturn(new PatientMapper.DeletedRow(PATIENT_ID, MRN_BLIND, "原因（示例）"));
        when(patientMapper.findLiveIdByMrnBlind(MRN_BLIND)).thenReturn(999L);
        assertEquals(
                40902,
                assertThrows(ApiException.class, () -> service.restore(PATIENT_ID)).code(),
                "病案号被新患者占用不能恢复");
        verify(patientMapper, never()).restore(anyLong(), anyLong());

        when(patientMapper.findLiveIdByMrnBlind(MRN_BLIND)).thenReturn(null);
        when(patientMapper.restore(PATIENT_ID, OPERATOR_ID)).thenReturn(0);
        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.restore(PATIENT_ID)).code(),
                "已被恢复无需重复操作");
    }

    // ---------------------------------------------------------------- 辅助

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:patient:delete"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }
}
