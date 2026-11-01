package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.DeniedOperationLogger;
import com.crdp.framework.security.ReauthStore;
import com.crdp.framework.security.RequireReauth;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * E04-T08 / PLT-AUTH-012：敏感接口的二次认证闸门。 标注 {@code @RequireReauth} 的处理方法，请求必须携带有效
 * X-Reauth-Token（同会话签发、5 分钟内），否则 40304 并写 result=DENIED 审计； 未标注的方法不受影响；匿名请求交给认证链（40100），不在本层重复判。
 */
class ReauthInterceptorTest {

    /** 供测试用的假控制器：一个标注敏感、一个不标。 */
    static class SensitiveController {
        @RequireReauth("导出审计日志")
        public String exportSomething() {
            return "ok";
        }

        public String plainQuery() {
            return "ok";
        }
    }

    private final ReauthStore reauthStore = mock(ReauthStore.class);
    private final ApiErrorWriter errors = mock(ApiErrorWriter.class);
    private final DeniedOperationLogger deniedLogger = mock(DeniedOperationLogger.class);

    private final ReauthInterceptor interceptor =
            new ReauthInterceptor(reauthStore, errors, deniedLogger);

    private final SessionPrincipal principal =
            new SessionPrincipal(
                    "session-token",
                    "HOSPITAL",
                    7L,
                    "demoa",
                    "演示医院 A",
                    1L,
                    "doctor.demo",
                    "张医生（示例）",
                    List.of("NURSE"),
                    List.of(1L),
                    List.of("tenant:audit:export"),
                    List.of(),
                    1,
                    ScopeSnapshot.allowAll(),
                    false,
                    null);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(
                new SensitiveController(), SensitiveController.class.getMethod(methodName));
    }

    @Test
    void annotatedHandlerWithoutTokenIsRejected40304AndAudited() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/hospital/system/audit/exports");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(reauthStore.isValid(null, principal)).thenReturn(false);

        boolean proceed = interceptor.preHandle(request, response, handler("exportSomething"));

        assertFalse(proceed, "缺 X-Reauth-Token 不得进入业务逻辑");
        verify(errors).write(response, 403, 40304, "敏感操作需二次认证，请重新验证身份");
        // audit.md §1：被拒请求写 result=DENIED（拦截器层不经过全局异常处理器）
        verify(deniedLogger)
                .onDenied(
                        any(ApiException.class),
                        eq("POST"),
                        eq("/api/hospital/system/audit/exports"));
    }

    @Test
    void annotatedHandlerWithValidTokenProceeds() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/hospital/system/audit/exports/1/download");
        request.addHeader(ReauthInterceptor.REAUTH_HEADER, "valid-reauth-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(reauthStore.isValid("valid-reauth-token", principal)).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, handler("exportSomething")));
        verify(errors, never())
                .write(
                        any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any());
    }

    @Test
    void expiredOrForgedTokenIsRejected() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/hospital/system/audit/exports");
        request.addHeader(ReauthInterceptor.REAUTH_HEADER, "forged");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(reauthStore.isValid("forged", principal)).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, handler("exportSomething")));
        verify(errors).write(response, 403, 40304, "敏感操作需二次认证，请重新验证身份");
    }

    @Test
    void unannotatedHandlerIsUntouched() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/hospital/system/audit/operation/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("plainQuery")));
        verifyNoInteractions(reauthStore, deniedLogger);
    }

    @Test
    void nonHandlerMethodHandlerIsUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object resourceHandler =
                mock(org.springframework.web.servlet.resource.ResourceHttpRequestHandler.class);

        assertTrue(interceptor.preHandle(request, response, resourceHandler));
        verifyNoInteractions(reauthStore, deniedLogger);
    }

    @Test
    void anonymousRequestIsLeftToAuthChain() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/hospital/system/audit/exports");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 认证链（SecurityConfig）在拦截器之前，未认证请求到不了这里；防御式放行避免把 40100 掩盖成 40304
        assertTrue(interceptor.preHandle(request, response, handler("exportSomething")));
        verify(errors, never())
                .write(
                        any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any());
    }

    private static void verifyNoInteractions(Object... mocks) {
        for (Object mock : mocks) {
            org.mockito.Mockito.verifyNoInteractions(mock);
        }
    }
}
