package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.patient.infra.mapper.PatientWardHistoryMapper;
import com.crdp.followup.patient.infra.mapper.WardHistoryInsert;
import com.crdp.followup.ward.infra.mapper.WardMapper;
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
 * E13-T08 / A-WARD-005 单元测试（tasks.csv 验收：转病区后原病区医生不可见——隔离由数据范围改写承担， 服务只改 pt_patient.ward_id
 * 一行）。锁定口径：范围内校验 40400（DENIED 审计）、目标病区须启用、同病区拦截、原因必填、 历史只追加（from / to / 原因 / 操作人）。全部虚构数据。
 */
class PatientTransferServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long WARD_A = 3L;
    private static final long WARD_B = 4L;
    private static final long OPERATOR_ID = 9L;

    private final PatientTransferGateway gateway = mock(PatientTransferGateway.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final PatientMapper patientMapper = mock(PatientMapper.class);
    private final PatientWardHistoryMapper historyMapper = mock(PatientWardHistoryMapper.class);
    private final WardMapper wardMapper = mock(WardMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PatientTransferService service =
            new PatientTransferService(
                    gateway, patientScope, patientMapper, historyMapper, wardMapper, audit);

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

    // ---------------------------------------------------------------- 转病区（A-WARD-005）

    @Test
    void transferUpdatesWardAndAppendsHistoryInOneAction() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A));
        when(wardMapper.findById(WARD_B))
                .thenReturn(
                        new WardMapper.WardRow(
                                WARD_B, "W-IT-B", "示例二病区", "WARD", null, null, null, 0, "ENABLED",
                                0L, 0L));

        PatientTransferService.TransferredView view =
                service.transfer(
                        PATIENT_ID,
                        new PatientTransferService.TransferRequest(WARD_B, "患者术后转入本病区（示例）"));

        assertEquals(String.valueOf(WARD_B), view.wardId());
        assertEquals("示例二病区", view.wardName());

        // 只改患者一行（任务 / 附件经 patient_id 关联随患者迁移，子表不搬）
        verify(patientMapper).updateWard(PATIENT_ID, WARD_B, OPERATOR_ID);

        ArgumentCaptor<WardHistoryInsert> history =
                ArgumentCaptor.forClass(WardHistoryInsert.class);
        verify(historyMapper).insertHistory(history.capture());
        assertEquals(WARD_A, history.getValue().getFromWardId());
        assertEquals(WARD_B, history.getValue().getToWardId());
        assertEquals("患者术后转入本病区（示例）", history.getValue().getReason());
        assertEquals(OPERATOR_ID, history.getValue().getTransferredBy());

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_TRANSFER", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
    }

    @Test
    void transferOutOfScopeReturns40400WithDeniedAudit() {
        when(gateway.patientWardInScope(PATIENT_ID)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.transfer(
                                        PATIENT_ID,
                                        new PatientTransferService.TransferRequest(
                                                WARD_B, "越范围（示例）")));

        assertEquals(40400, error.code(), "越范围不暴露对象存在");
        verify(patientMapper, never()).updateWard(anyLong(), anyLong(), anyLong());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals(AuditResult.DENIED, entry.getValue().result());
        assertEquals(40400, entry.getValue().errorCode());
    }

    @Test
    void transferValidatesTargetAndReason() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A));
        when(wardMapper.findById(WARD_B))
                .thenReturn(
                        new WardMapper.WardRow(
                                WARD_B,
                                "W-IT-B",
                                "示例二病区",
                                "WARD",
                                null,
                                null,
                                null,
                                0,
                                "DISABLED",
                                0L,
                                0L));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        null, "原因（示例）")))
                        .code(),
                "目标病区必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        999L, "原因（示例）")))
                        .code(),
                "目标病区不存在");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_B, "原因（示例）")))
                        .code(),
                "目标病区已停用不能转入");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_A, " ")))
                        .code(),
                "原因必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_A, "原因（示例）")))
                        .code(),
                "同病区无需转病区");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_B, "长".repeat(256))))
                        .code(),
                "原因长度上限 255");
        verify(patientMapper, never()).updateWard(anyLong(), anyLong(), anyLong());
    }

    // ---------------------------------------------------------------- 历史（A-WARD-005「保留转病区历史」）

    @Test
    void historyOfRequiresScopeAndEnrichesWardNames() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(wardMapper.findAll())
                .thenReturn(
                        List.of(
                                new WardMapper.WardRow(
                                        WARD_A, "W-A", "示例一病区", "WARD", null, null, null, 0,
                                        "ENABLED", 0L, 0L),
                                new WardMapper.WardRow(
                                        WARD_B, "W-B", "示例二病区", "WARD", null, null, null, 0,
                                        "ENABLED", 0L, 0L)));
        when(gateway.wardHistoryOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new PatientWardHistoryMapper.WardHistoryRow(
                                        1L,
                                        WARD_A,
                                        WARD_B,
                                        "转入二病区（示例）",
                                        OPERATOR_ID,
                                        "演示医生",
                                        null)));

        PatientTransferService.WardHistoryView view = service.historyOf(PATIENT_ID);

        assertEquals(1, view.items().size());
        assertEquals("示例一病区", view.items().get(0).fromWardName());
        assertEquals("示例二病区", view.items().get(0).toWardName());
        assertEquals("演示医生", view.items().get(0).operatorName());

        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.historyOf(PATIENT_ID)).code());
    }

    @Test
    void historyOfDeletedWardShowsPlaceholder() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(wardMapper.findAll()).thenReturn(List.of());
        when(gateway.wardHistoryOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new PatientWardHistoryMapper.WardHistoryRow(
                                        1L, WARD_A, WARD_B, "原因（示例）", OPERATOR_ID, null, null)));

        PatientTransferService.WardHistoryItemView item =
                service.historyOf(PATIENT_ID).items().get(0);

        assertEquals("（已删除）", item.fromWardName());
        assertNull(item.operatorName(), "操作人缺失显示空，不报错");
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
                List.of("followup:patient:transfer"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                null);
    }
}
