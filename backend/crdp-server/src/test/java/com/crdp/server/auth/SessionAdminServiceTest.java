package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E04-T06 / PLT-AUTH-008、009：在线会话管理与强制下线。 管理员按会话号踢线（删除会话 + 写审计）；本人只能看到与下线自己的会话； 他人 / 不存在的会话号统一
 * 40400，不暴露存在性。
 */
class SessionAdminServiceTest {

    private final SessionStore sessionStore = mock(SessionStore.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private SessionAdminService service;

    private static final long TENANT_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new SessionAdminService(sessionStore, auditLogger);
        loginAs(adminSession());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(SessionPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static SessionPrincipal adminSession() {
        return hospitalSession("nurse.demo", 200L, "token-admin");
    }

    private static SessionPrincipal hospitalSession(String username, long userId, String token) {
        long now = Instant.now().getEpochSecond();
        return new SessionPrincipal(
                token,
                "HOSPITAL",
                TENANT_ID,
                "demoa",
                "虚构示例医院甲",
                userId,
                username,
                "演示用户（示例）",
                List.of("TENANT_ADMIN"),
                List.of(10L),
                List.of("tenant:session:view"),
                List.of("base"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "203.0.113.9",
                now - 600,
                now - 30,
                1800L);
    }

    @Test
    void listTenantSessionsReturnsTheTenantOnlineSessions() {
        SessionPrincipal doctor = hospitalSession("doctor.demo", 100L, "token-doctor");
        when(sessionStore.listForTenant(TENANT_ID)).thenReturn(List.of(adminSession(), doctor));

        List<SessionAdminService.OnlineSessionView> sessions = service.listTenantSessions();

        assertEquals(2, sessions.size());
        SessionAdminService.OnlineSessionView view = sessions.get(1);
        assertEquals("doctor.demo", view.username());
        assertEquals("203.0.113.9", view.ip(), "在线用户列表展示登录 IP（PLT-AUTH-009）");
        assertEquals(SessionPrincipal.sessionIdOf("token-doctor"), view.sessionId(), "对外只暴露会话号");
        assertTrue(
                view.issuedAt().endsWith("+08:00") || view.issuedAt().contains("+"),
                "时间用 ISO-8601 带时区");
        assertEquals(false, view.current(), "管理员列表不带 current 标记");
    }

    @Test
    void kickDeletesTheSessionAndWritesAudit() {
        SessionPrincipal doctor = hospitalSession("doctor.demo", 100L, "token-doctor");
        when(sessionStore.listForTenant(TENANT_ID)).thenReturn(List.of(doctor));

        SessionAdminService.OnlineSessionView kicked =
                service.kick(SessionPrincipal.sessionIdOf("token-doctor"));

        assertEquals("doctor.demo", kicked.username());
        verify(sessionStore).delete("token-doctor");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).record(event.capture());
        assertEquals("SESSION_KICK", event.getValue().eventType(), "强制下线写审计（PLT-AUD-002）");
        assertEquals(200L, event.getValue().actorId(), "操作者是管理员本人");
        assertTrue(event.getValue().detail().contains("doctor.demo"));
    }

    @Test
    void kickUnknownSessionIdReturns40400() {
        when(sessionStore.listForTenant(TENANT_ID)).thenReturn(List.of());
        ApiException ex = assertThrows(ApiException.class, () -> service.kick("deadbeefdeadbeef"));
        assertEquals(40400, ex.code());
        verify(sessionStore, never()).delete(any());
        verify(auditLogger, never()).record(any());
    }

    @Test
    void listMySessionsMarksTheCurrentOne() {
        SessionPrincipal admin = adminSession();
        SessionPrincipal other = hospitalSession("nurse.demo", 200L, "token-other-device");
        when(sessionStore.listForUser(admin)).thenReturn(List.of(other, admin));

        List<SessionAdminService.OnlineSessionView> sessions = service.listMySessions(admin);

        assertEquals(2, sessions.size());
        assertEquals(
                true,
                sessions.stream()
                        .filter(s -> s.sessionId().equals(admin.sessionId()))
                        .findFirst()
                        .orElseThrow()
                        .current(),
                "本人列表标记当前会话");
        assertEquals(
                false,
                sessions.stream()
                        .filter(s -> s.sessionId().equals(other.sessionId()))
                        .findFirst()
                        .orElseThrow()
                        .current());
    }

    @Test
    void revokeMySessionDeletesItAndWritesAudit() {
        SessionPrincipal admin = adminSession();
        SessionPrincipal other = hospitalSession("nurse.demo", 200L, "token-other-device");
        when(sessionStore.listForUser(admin)).thenReturn(List.of(other, admin));

        service.revokeMySession(admin, other.sessionId());

        verify(sessionStore).delete("token-other-device");
        verify(auditLogger).record(any(AuditEvent.class));
    }

    @Test
    void revokeCannotTouchSomeoneElsesSession() {
        // 会话号属于其他账号：本人索引里查不到，统一 40400（不暴露他人会话是否存在）
        SessionPrincipal admin = adminSession();
        when(sessionStore.listForUser(admin)).thenReturn(List.of(admin));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.revokeMySession(
                                        admin, SessionPrincipal.sessionIdOf("token-doctor")));

        assertEquals(40400, ex.code());
        verify(sessionStore, never()).delete("token-doctor");
    }

    @Test
    void platformSessionCannotUseTheTenantAdminEndpoints() {
        SessionPrincipal platform =
                new SessionPrincipal(
                        "token-platform",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        1L,
                        "platform.admin",
                        "平台管理员（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of("platform:tenant:view"),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        loginAs(platform);
        when(sessionStore.listForTenant(TENANT_ID)).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> service.listTenantSessions());
        assertEquals(40300, ex.code(), "平台会话不能调用医院端在线会话接口（端别隔离内层防线）");
    }
}
