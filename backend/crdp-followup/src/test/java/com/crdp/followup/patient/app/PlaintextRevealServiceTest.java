package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.domain.PatientPlaintextSource;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.audit.AuditType;
import com.crdp.framework.fieldpolicy.FieldPolicyOverride;
import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E07-T05 / A-PAT-010、C-PAT-012（验收：默认脱敏；查看明文需权限并记审计）：明文查看用例单元测试。 权限码 {@code followup:patient:plain}
 * 由 Controller 声明式校验（40300），这里锁定策略口径（只服务 MASKED 字段）、 40400 / 42900 / 40001
 * 与审计写入（字段码与场景，绝不记明文值）。全部虚构数据。
 */
class PlaintextRevealServiceTest {

    private static final long PATIENT_ID = 990_001L;
    private static final String PLAIN_NAME = "张小明（示例）";
    private static final String PLAIN_PHONE = "13800005678";

    private final PatientPlaintextSource source = mock(PatientPlaintextSource.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PlaintextRateLimiter rateLimiter = mock(PlaintextRateLimiter.class);

    private final PlaintextRevealService service =
            new PlaintextRevealService(source, audit, rateLimiter);

    @BeforeEach
    void login() {
        when(rateLimiter.tryAcquire(anyLong(), anyLong())).thenReturn(true);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(FieldPolicySnapshot.defaults()), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void revealsMaskedFieldsAndWritesDataAccessAudit() {
        when(source.plaintextOf(PATIENT_ID))
                .thenReturn(
                        Optional.of(
                                Map.of(
                                        "name", PLAIN_NAME,
                                        "phone", PLAIN_PHONE,
                                        "idCard", "11010219900101567X")));

        PlaintextRevealService.RevealView view =
                service.reveal(PATIENT_ID, List.of("name", "phone"), "detail");

        assertEquals(PLAIN_NAME, view.values().get("name"));
        assertEquals(PLAIN_PHONE, view.values().get("phone"));
        assertEquals(60, view.expiresInSeconds(), "前端 60 秒自动恢复脱敏（design tokens 同口径）");
        assertEquals(2, view.values().size(), "只返回请求的字段，不捎带其他敏感字段");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(AuditType.DATA_ACCESS, entry.type());
        assertEquals("VIEW_PLAIN", entry.accessMethod());
        assertEquals("PATIENT", entry.objectType());
        assertEquals(String.valueOf(PATIENT_ID), entry.objectId());
        assertEquals(AuditResult.SUCCESS, entry.result());
        assertEquals(2L, entry.recordCount());
        assertEquals("查看患者敏感字段明文：姓名、手机号（场景 detail）", entry.description());
        org.junit.jupiter.api.Assertions.assertNull(entry.beforeJson());
        org.junit.jupiter.api.Assertions.assertNull(entry.afterJson());
        org.junit.jupiter.api.Assertions.assertFalse(
                entry.description().contains(PLAIN_PHONE), "审计描述不得出现明文");
    }

    @Test
    void dedupesRequestedFields() {
        when(source.plaintextOf(PATIENT_ID)).thenReturn(Optional.of(Map.of("name", PLAIN_NAME)));

        PlaintextRevealService.RevealView view =
                service.reveal(PATIENT_ID, List.of("name", "name"), "form");

        assertEquals(1, view.values().size());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("查看患者敏感字段明文：姓名（场景 form）", captor.getValue().description());
    }

    @Test
    void nullSceneDefaultsToDetail() {
        when(source.plaintextOf(PATIENT_ID)).thenReturn(Optional.of(Map.of("name", PLAIN_NAME)));

        service.reveal(PATIENT_ID, List.of("name"), null);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("查看患者敏感字段明文：姓名（场景 detail）", captor.getValue().description());
    }

    @Test
    void rejectsBlankOrUnknownFieldWith40001() {
        ApiException blank =
                assertThrows(
                        ApiException.class, () -> service.reveal(PATIENT_ID, List.of(), "detail"));
        assertEquals(40001, blank.code());
        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("nickname"), "detail"));
        assertEquals(40001, unknown.code());
        ApiException badScene =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("name"), "export"));
        assertEquals(40001, badScene.code());
        verify(audit, never()).append(any());
    }

    @Test
    void rejectsVisibleFieldBecausePlainAlreadyServedByNormalEndpoints() {
        loginWith(
                FieldPolicySnapshot.merge(
                        List.of(new FieldPolicyOverride("patient", "name", "VISIBLE"))));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("name"), "detail"));

        assertEquals(40001, ex.code());
        verify(audit, never()).append(any());
    }

    @Test
    void rejectsHiddenFieldBecausePolicyForbidsAnyOutput() {
        loginWith(
                FieldPolicySnapshot.merge(
                        List.of(new FieldPolicyOverride("patient", "address", "HIDDEN"))));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("address"), "detail"));

        assertEquals(40001, ex.code());
        verify(audit, never()).append(any());
    }

    @Test
    void missingOrOutOfScopePatientReturns40400WithDeniedAudit() {
        when(source.plaintextOf(PATIENT_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("phone"), "detail"));

        assertEquals(40400, ex.code());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(AuditResult.DENIED, entry.result());
        assertEquals(40400, entry.errorCode());
        assertEquals("PATIENT", entry.objectType());
    }

    @Test
    void rateLimitedCallReturns42900WithDeniedAudit() {
        when(rateLimiter.tryAcquire(anyLong(), anyLong())).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("phone"), "detail"));

        assertEquals(42900, ex.code());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(AuditResult.DENIED, entry.result());
        assertEquals(42900, entry.errorCode());
        assertEquals(PlaintextRevealService.REASON_RATE_LIMITED, entry.reason());
        assertEquals("VIEW_PLAIN", entry.accessMethod());
    }

    @Test
    void platformSessionCannotReveal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new SessionPrincipal(
                                        "token",
                                        "PLATFORM",
                                        null,
                                        null,
                                        null,
                                        5L,
                                        "platform.admin",
                                        "平台管理员（示例）",
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        1L,
                                        null,
                                        false,
                                        null),
                                null,
                                List.of()));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reveal(PATIENT_ID, List.of("name"), "detail"));

        assertEquals(40300, ex.code());
        verify(audit, never()).append(any());
    }

    private void loginWith(FieldPolicySnapshot policies) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(policies), null, List.of()));
    }

    private static SessionPrincipal principal(FieldPolicySnapshot policies) {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                9L,
                "itr.plain.demo",
                "IT 明文用户（示例）",
                List.of("ITR-PLAIN"),
                List.of(),
                List.of("followup:patient:plain"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                policies);
    }
}
