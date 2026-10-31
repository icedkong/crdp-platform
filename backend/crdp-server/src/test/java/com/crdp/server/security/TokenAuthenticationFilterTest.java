package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionRefresher;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.auth.TenantAccessProperties;
import com.crdp.server.auth.TenantIdentifier;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E04-T02 / PLT-AUTH-008：会话链路逐请求查服务端会话，吊销后的令牌立即失效； 令牌所属租户与访问入口子域名必须一致（E04-T01 移交的一致性校验）。 E04-T03 /
 * PLT-AUTH-005：平台端与医院端接口前缀各自只接受对应端的会话，跨端令牌按未认证拒绝。 过滤器在 finally 里清理上下文，因此断言都在过滤链内完成。
 */
class TokenAuthenticationFilterTest {

    private static final String TOKEN = "opaque-token-value";
    private static final String BASE_DOMAIN = "crdp.example.com";

    private final SessionStore sessionStore = mock(SessionStore.class);
    private final PermissionVersionStore permissionVersions = mock(PermissionVersionStore.class);
    private final SessionRefresher sessionRefresher = mock(SessionRefresher.class);
    private TokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        TenantAccessProperties properties = new TenantAccessProperties();
        properties.setBaseDomain(BASE_DOMAIN);
        filter =
                new TokenAuthenticationFilter(
                        sessionStore,
                        new TenantIdentifier(properties),
                        permissionVersions,
                        sessionRefresher);
        // 版本未递增（current=1）时不触发重算；touch（滑动续期）原样返回会话
        when(permissionVersions.current(org.mockito.ArgumentMatchers.any(SessionPrincipal.class)))
                .thenReturn(1L);
        when(sessionStore.touch(org.mockito.ArgumentMatchers.any(SessionPrincipal.class)))
                .thenAnswer(inv -> inv.getArgument(0, SessionPrincipal.class));
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static SessionPrincipal hospitalSession() {
        return new SessionPrincipal(
                TOKEN,
                "HOSPITAL",
                7L,
                "demoa",
                "虚构示例医院甲",
                100L,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L),
                List.of("followup:patient:view"),
                List.of("base"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private static SessionPrincipal platformSession() {
        return new SessionPrincipal(
                TOKEN,
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
    }

    /** 在过滤链内快照认证与租户上下文，供断言使用。 */
    private static class Snapshot {
        final AtomicReference<Authentication> authentication = new AtomicReference<>();
        final AtomicReference<TenantContext.State> tenant = new AtomicReference<>();
    }

    private Snapshot runFilter(String host, String authorization)
            throws ServletException, IOException {
        Snapshot snapshot = new Snapshot();
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (host != null) {
            request.addHeader("Host", host);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    snapshot.authentication.set(
                            SecurityContextHolder.getContext().getAuthentication());
                    snapshot.tenant.set(TenantContext.current().orElse(null));
                });
        return snapshot;
    }

    private Snapshot withToken(String host) throws ServletException, IOException {
        return runFilter(host, "Bearer " + TOKEN);
    }

    private static void assertAuthenticatedAs(Snapshot snapshot, String username) {
        Authentication authentication = snapshot.authentication.get();
        assertTrue(authentication != null, "应完成认证");
        SessionPrincipal principal = (SessionPrincipal) authentication.getPrincipal();
        assertEquals(username, principal.username());
    }

    @Test
    void validSessionAuthenticatesAndSetsTenantContext() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        Snapshot snapshot = withToken("api.internal:18080");
        assertAuthenticatedAs(snapshot, "doctor.demo");
        assertEquals(7L, snapshot.tenant.get().tenantId(), "医院会话应带入租户上下文");
    }

    @Test
    void revokedSessionNoLongerAuthenticates() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.empty());
        Snapshot snapshot = withToken("api.internal:18080");
        assertNull(snapshot.authentication.get(), "吊销后的令牌不得再认证");
        assertNull(snapshot.tenant.get(), "吊销后的令牌不得带入租户上下文");
    }

    @Test
    void requestWithoutTokenStaysAnonymous() throws Exception {
        Snapshot snapshot = runFilter("demoa." + BASE_DOMAIN, null);
        assertNull(snapshot.authentication.get());
    }

    @Test
    void subdomainMatchingTheSessionTenantAuthenticates() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        assertAuthenticatedAs(withToken("demoa." + BASE_DOMAIN), "doctor.demo");
    }

    @Test
    void subdomainOfAnotherTenantIsRejected() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        Snapshot snapshot = withToken("demob." + BASE_DOMAIN);
        assertNull(snapshot.authentication.get(), "demoa 的令牌不能在 demob 的入口使用");
        assertNull(snapshot.tenant.get(), "被拒会话不得带入租户上下文");
    }

    @Test
    void baseDomainAndForeignHostsDoNotConstrainTheSession() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        assertAuthenticatedAs(withToken(BASE_DOMAIN), "doctor.demo");
        assertAuthenticatedAs(withToken("127.0.0.1:18080"), "doctor.demo");
    }

    @Test
    void platformSessionOnATenantSubdomainIsRejected() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(platformSession()));
        Snapshot snapshot = withToken("demoa." + BASE_DOMAIN);
        assertNull(snapshot.authentication.get(), "平台会话不能出现在租户子域名入口");
    }

    @Test
    void platformSessionOnTheBaseDomainAuthenticates() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(platformSession()));
        Snapshot snapshot = withToken(BASE_DOMAIN);
        assertAuthenticatedAs(snapshot, "platform.admin");
        assertTrue(
                snapshot.tenant.get() != null && snapshot.tenant.get().platform(), "平台会话应带入平台上下文");
    }

    @Test
    void subdomainCheckIsDisabledWhenBaseDomainIsNotConfigured() throws Exception {
        TenantAccessProperties empty = new TenantAccessProperties();
        filter =
                new TokenAuthenticationFilter(
                        sessionStore,
                        new TenantIdentifier(empty),
                        permissionVersions,
                        sessionRefresher);
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        assertAuthenticatedAs(withToken("demob." + BASE_DOMAIN), "doctor.demo");
    }

    // ---- E04-T03：平台端独立登录，两端令牌不能混用（PLT-AUTH-005） ----
    // 过滤器按会话端别附加 SESSION_* 权限，SecurityConfig 的路径规则据此拒绝跨端令牌（40300）；
    // 端到端拒绝见 TenantIsolationAcceptanceIT / ProvisionedTenantIsolationIT。

    @Test
    void hospitalSessionCarriesOnlyTheHospitalSideAuthority() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        Snapshot snapshot = withToken("api.internal:18080");
        assertAuthenticatedAs(snapshot, "doctor.demo");
        assertTrue(
                snapshot.authentication.get().getAuthorities().stream()
                        .anyMatch(
                                granted ->
                                        granted.getAuthority()
                                                .equals(
                                                        TokenAuthenticationFilter
                                                                .SESSION_HOSPITAL_AUTHORITY)),
                "医院会话应带医院端别权限，供 SecurityConfig 拒绝其访问平台接口");
        assertTrue(
                snapshot.authentication.get().getAuthorities().stream()
                        .noneMatch(
                                granted ->
                                        granted.getAuthority()
                                                .equals(
                                                        TokenAuthenticationFilter
                                                                .SESSION_PLATFORM_AUTHORITY)),
                "医院会话不得带平台端别权限");
    }

    @Test
    void platformSessionCarriesOnlyThePlatformSideAuthority() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(platformSession()));
        Snapshot snapshot = withToken(BASE_DOMAIN);
        assertAuthenticatedAs(snapshot, "platform.admin");
        assertTrue(
                snapshot.authentication.get().getAuthorities().stream()
                        .anyMatch(
                                granted ->
                                        granted.getAuthority()
                                                .equals(
                                                        TokenAuthenticationFilter
                                                                .SESSION_PLATFORM_AUTHORITY)),
                "平台会话应带平台端别权限，供 SecurityConfig 拒绝其访问医院接口");
        assertTrue(
                snapshot.authentication.get().getAuthorities().stream()
                        .noneMatch(
                                granted ->
                                        granted.getAuthority()
                                                .equals(
                                                        TokenAuthenticationFilter
                                                                .SESSION_HOSPITAL_AUTHORITY)),
                "平台会话不得带医院端别权限");
    }

    @Test
    void revokedSessionCarriesNoSideAuthority() throws Exception {
        when(sessionStore.find(TOKEN)).thenReturn(Optional.empty());
        Snapshot snapshot = withToken("api.internal:18080");
        assertNull(snapshot.authentication.get(), "吊销后的令牌不再携带任何端别权限");
    }

    // ---- E04-T06 / PLT-PERM-010：权限版本落后时按新权限重算（PLT-AUTH-008 滑动续期） ----

    @Test
    void activeSessionIsRenewedOnEveryRequest() throws Exception {
        SessionPrincipal session = hospitalSession();
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(session));
        withToken("api.internal:18080");
        verify(sessionStore).touch(session);
        verify(sessionStore, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void stalePermissionVersionIsRefreshedBeforeTheRequestRuns() throws Exception {
        SessionPrincipal session = hospitalSession(); // 版本 1
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(session));
        when(permissionVersions.current(session)).thenReturn(3L);
        SessionPrincipal refreshed =
                session.withFreshGrants(
                        new SessionPrincipal(
                                "draft-token",
                                "HOSPITAL",
                                7L,
                                "demoa",
                                "虚构示例医院甲",
                                100L,
                                "doctor.demo",
                                "演示医生",
                                List.of("TENANT_ADMIN"),
                                List.of(10L, 30L),
                                List.of("tenant:session:view", "tenant:session:kick"),
                                List.of("base"),
                                3L,
                                ScopeSnapshot.allowAll(),
                                false,
                                null));
        when(sessionRefresher.refresh(session)).thenReturn(Optional.of(refreshed));

        Snapshot snapshot = withToken("api.internal:18080");

        assertAuthenticatedAs(snapshot, "doctor.demo");
        assertTrue(
                snapshot.authentication.get().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("tenant:session:kick")),
                "重算后的会话应携带新权限码执行本次请求");
        assertTrue(
                snapshot.authentication.get().getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("followup:patient:view")),
                "旧权限快照自此失效");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SessionPrincipal> saved = ArgumentCaptor.forClass(SessionPrincipal.class);
        verify(sessionStore).save(saved.capture());
        assertEquals(3L, saved.getValue().permissionVersion(), "重算后的会话保存新版本号");
        assertEquals(TOKEN, saved.getValue().token(), "重算保留令牌");
    }

    @Test
    void staleVersionWithAccountGoneDropsTheSession() throws Exception {
        SessionPrincipal session = hospitalSession();
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(session));
        when(permissionVersions.current(session)).thenReturn(2L);
        when(sessionRefresher.refresh(session)).thenReturn(Optional.empty());

        Snapshot snapshot = withToken("api.internal:18080");

        assertNull(snapshot.authentication.get(), "账号已停用 / 不存在：会话删除，按未认证处理");
        assertNull(snapshot.tenant.get(), "不得带入租户上下文");
        verify(sessionStore).delete(TOKEN);
    }
}
