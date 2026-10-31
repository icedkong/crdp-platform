package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E04-T02 / PLT-AUTH-008：会话链路逐请求查服务端会话，吊销后的令牌立即失效； 令牌所属租户与访问入口子域名必须一致（E04-T01 移交的一致性校验）。 过滤器在
 * finally 里清理上下文，因此断言都在过滤链内完成。
 */
class TokenAuthenticationFilterTest {

    private static final String TOKEN = "opaque-token-value";
    private static final String BASE_DOMAIN = "crdp.example.com";

    private final SessionStore sessionStore = mock(SessionStore.class);
    private TokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        TenantAccessProperties properties = new TenantAccessProperties();
        properties.setBaseDomain(BASE_DOMAIN);
        filter = new TokenAuthenticationFilter(sessionStore, new TenantIdentifier(properties));
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
                ScopeSnapshot.allowAll());
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
                ScopeSnapshot.allowAll());
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
        filter = new TokenAuthenticationFilter(sessionStore, new TenantIdentifier(empty));
        when(sessionStore.find(TOKEN)).thenReturn(Optional.of(hospitalSession()));
        assertAuthenticatedAs(withToken("demob." + BASE_DOMAIN), "doctor.demo");
    }
}
