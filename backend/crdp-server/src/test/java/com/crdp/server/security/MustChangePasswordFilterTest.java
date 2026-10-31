package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** E04-T05 / PLT-AUTH-010：会话带 mustChangePassword 时，除改密 / 登出 / me 外一律 40305； 未认证请求与会话正常的请求不受影响。 */
class MustChangePasswordFilterTest {

    private final ApiErrorWriter errors = mock(ApiErrorWriter.class);
    private MustChangePasswordFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MustChangePasswordFilter(errors);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static SessionPrincipal session(boolean mustChangePassword) {
        return new SessionPrincipal(
                "token",
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
                mustChangePassword,
                null);
    }

    private static void authenticate(SessionPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** 过滤链是否被放行（未被 40305 短路）。 */
    private boolean run(String method, String uri, SessionPrincipal principal)
            throws ServletException, IOException {
        if (principal != null) {
            authenticate(principal);
        }
        AtomicBoolean chainReached = new AtomicBoolean(false);
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainReached.set(true));
        return chainReached.get();
    }

    @Test
    void mustChangeSessionIsBlockedWith40305OutsideTheAllowlist() throws Exception {
        boolean reached = run("GET", "/api/hospital/menus", session(true));

        assertFalse(reached, "放行清单外的接口不得继续过滤链");
        verify(errors).write(any(), eq(403), eq(40305), eq("首次登录或密码已过期，请先修改密码"));
    }

    @Test
    void passwordChangeLogoutAndMeAreAllowedForMustChangeSessions() throws Exception {
        assertTrue(run("POST", "/api/hospital/auth/password", session(true)));
        assertTrue(run("POST", "/api/hospital/auth/logout", session(true)));
        assertTrue(run("GET", "/api/hospital/auth/me", session(true)));
        assertTrue(run("POST", "/api/platform/auth/password", session(true)));
        assertTrue(run("POST", "/api/platform/auth/logout", session(true)));
        assertTrue(run("GET", "/api/platform/auth/me", session(true)));
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
    }

    @Test
    void methodMattersTwoAllowlistEntries() throws Exception {
        // GET /auth/password 不在放行清单（放行的是 POST）
        assertFalse(run("GET", "/api/hospital/auth/password", session(true)));
        // codes / menus 被拦截：前端强制改密期间不需要它们
        assertFalse(run("GET", "/api/hospital/auth/codes", session(true)));
    }

    @Test
    void normalSessionsAndAnonymousRequestsPassThrough() throws Exception {
        assertTrue(run("GET", "/api/hospital/menus", session(false)), "正常会话不受影响");
        assertTrue(run("POST", "/api/hospital/followup/patients", null), "未认证请求交给后续链处理");
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
    }
}
