package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.BaselineLockMapper;
import com.crdp.followup.patient.infra.mapper.BaselineLockMapper.LockRowRaw;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T02 单元测试（tasks.csv 验收「锁定后只读，解锁需权限并留痕」；A-PAT-006）。
 *
 * <p>锁定口径：解锁原因必填（空白 40001）；重复锁定 / 未锁定时解锁 40001（状态不变不产生新留痕）； 锁定与解锁各写一条操作审计（BASELINE_LOCK /
 * BASELINE_UNLOCK，留痕，只追加）；越范围患者 40400。 权限本身由控制器 @RequiresPermission(followup:patient:lock) 强制（见
 * PermissionAuthorizationAspect 用例），本层锁定后只读经 requireUnlocked 由分节用例覆盖。全部虚构数据。
 */
class BaselineLockServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;

    private final BaselineGateway gateway = mock(BaselineGateway.class);
    private final BaselineLockMapper mapper = mock(BaselineLockMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final BaselineLockService service = new BaselineLockService(gateway, mapper, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        lenient().when(gateway.lock(PATIENT_ID)).thenReturn(null);
        lenient().when(mapper.findDisplayName(anyLong())).thenAnswer(invocation -> "演示医生（示例）");
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 锁定（A-PAT-006 病区医生确认）

    @Test
    void lockWritesRowAndAuditTrail() {
        when(gateway.lock(PATIENT_ID)).thenReturn(null, lockedRow(true, null));

        BaselineLockService.LockView view =
                service.setLocked(
                        PATIENT_ID, new BaselineLockService.LockCommand(true, "出院 30 天内确认无误"));

        assertTrue(view.locked());
        verify(mapper).lock(PATIENT_ID, "出院 30 天内确认无误", OPERATOR_ID);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("BASELINE_LOCK", entry.getValue().action());
        assertTrue(
                entry.getValue().description().contains("锁定后基线只读"), entry.getValue().description());
    }

    @Test
    void lockReasonIsOptional() {
        when(gateway.lock(PATIENT_ID)).thenReturn(null, lockedRow(true, null));

        service.setLocked(PATIENT_ID, new BaselineLockService.LockCommand(true, "  "));

        verify(mapper).lock(eq(PATIENT_ID), eq(null), eq(OPERATOR_ID));
    }

    @Test
    void doubleLockIsRejected() {
        when(gateway.lock(PATIENT_ID)).thenReturn(lockedRow(true, null));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.setLocked(
                                        PATIENT_ID,
                                        new BaselineLockService.LockCommand(true, null)));
        assertEquals(40001, error.code());
        verify(mapper, never()).lock(anyLong(), anyString(), anyLong());
    }

    // ---------------------------------------------------------------- 解锁（需权限并填写原因，留痕）

    @Test
    void unlockRequiresReason() {
        when(gateway.lock(PATIENT_ID)).thenReturn(lockedRow(true, null));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.setLocked(
                                        PATIENT_ID,
                                        new BaselineLockService.LockCommand(false, "  ")));
        assertEquals(40001, error.code());
        assertEquals("解锁基线必须填写原因", error.getMessage());
        verify(mapper, never()).unlock(anyLong(), anyString(), anyLong());
    }

    @Test
    void unlockWritesRowAndAuditWithReason() {
        when(gateway.lock(PATIENT_ID)).thenReturn(lockedRow(true, null), lockedRow(false, "抄录错误"));

        BaselineLockService.LockView view =
                service.setLocked(PATIENT_ID, new BaselineLockService.LockCommand(false, " 抄录错误 "));

        assertFalse(view.locked());
        verify(mapper).unlock(PATIENT_ID, "抄录错误", OPERATOR_ID);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("BASELINE_UNLOCK", entry.getValue().action());
        assertTrue(entry.getValue().description().contains("抄录错误"), entry.getValue().description());
    }

    @Test
    void unlockWhenNotLockedIsRejected() {
        when(gateway.lock(PATIENT_ID)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.setLocked(
                                        PATIENT_ID,
                                        new BaselineLockService.LockCommand(false, "误操作")));
        assertEquals(40001, error.code());
    }

    @Test
    void overlongReasonIsRejected() {
        when(gateway.lock(PATIENT_ID)).thenReturn(lockedRow(true, null));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.setLocked(
                                        PATIENT_ID,
                                        new BaselineLockService.LockCommand(
                                                false, "长".repeat(256))));
        assertEquals(40001, error.code());
        verify(mapper, never()).unlock(anyLong(), anyString(), anyLong());
    }

    // ---------------------------------------------------------------- 越范围（AGENTS §5.2：40400 不暴露存在）

    @Test
    void outOfScopePatientIs40400() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.setLocked(
                                        PATIENT_ID,
                                        new BaselineLockService.LockCommand(true, null)));
        assertEquals(40400, error.code());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    @Test
    void lockCommandWithoutFlagIs40001() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.setLocked(
                                        PATIENT_ID,
                                        new BaselineLockService.LockCommand(null, null)));
        assertEquals(40001, error.code());
    }

    // ---------------------------------------------------------------- 锁定拦截（锁定后只读）

    @Test
    void requireUnlockedThrows40901WhenLocked() {
        when(gateway.lock(PATIENT_ID)).thenReturn(lockedRow(true, null));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.requireUnlocked(PATIENT_ID, "基线已锁定，不能保存（需先解锁）"));
        assertEquals(40901, error.code());
        assertEquals("基线已锁定，不能保存（需先解锁）", error.getMessage());
    }

    @Test
    void requireUnlockedPassesWhenNotLocked() {
        when(gateway.lock(PATIENT_ID)).thenReturn(null);

        service.requireUnlocked(PATIENT_ID, "不该出现");
    }

    @Test
    void lockViewOfMissingRowIsUnlocked() {
        when(gateway.lock(PATIENT_ID)).thenReturn(null);

        BaselineLockService.LockView view = service.lockView(PATIENT_ID);

        assertFalse(view.locked());
    }

    // ---------------------------------------------------------------- 内部

    private static LockRowRaw lockedRow(boolean locked, String unlockReason) {
        return new LockRowRaw(
                1L,
                PATIENT_ID,
                locked,
                locked ? "出院 30 天内确认无误" : null,
                OPERATOR_ID,
                LocalDateTime.of(2026, 11, 6, 10, 0),
                unlockReason,
                locked ? null : OPERATOR_ID,
                locked ? null : LocalDateTime.of(2026, 11, 6, 11, 0));
    }

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
                List.of("followup:patient:view", "followup:patient:lock"),
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
