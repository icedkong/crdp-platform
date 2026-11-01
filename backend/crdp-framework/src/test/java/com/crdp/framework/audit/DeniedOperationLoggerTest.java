package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * E08-T01 / audit.md §1：被拒绝的请求（40300/40301/40302/40303/40306）也要写 result=DENIED。
 * 全局兜底组件：已登录会话的越权请求写一条 DENIED 操作日志；切面已记录（标记位）或匿名请求不重复写。
 */
class DeniedOperationLoggerTest {

    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private DeniedOperationLogger deniedLogger;

    @BeforeEach
    void setUp() {
        deniedLogger = new DeniedOperationLogger(auditLogger);
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token-1",
                        "HOSPITAL",
                        7L,
                        "demoa",
                        "示例医院",
                        42L,
                        "doctor.demo",
                        "张医生（示例）",
                        List.of("DOCTOR"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/hospital/system/roles");
        request.addHeader("X-Forwarded-For", "10.2.3.4, 172.16.0.9");
        request.addHeader("User-Agent", "Mozilla/5.0 (test)");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        AuditMarkers.clearDenied();
    }

    @Test
    void deniedApiExceptionIsRecorded() {
        TenantContext.useTenant(7L, "demoa");
        deniedLogger.onDenied(new ApiException(403, 40300, "没有权限"));

        verify(auditLogger)
                .append(
                        argThat(
                                entry ->
                                        entry.type() == AuditType.OPERATION
                                                && entry.result() == AuditResult.DENIED
                                                && entry.errorCode() == 40300
                                                && "被拒绝的请求：POST /api/hospital/system/roles"
                                                        .equals(entry.description())
                                                && entry.module().equals("system")
                                                && entry.userId() == null));
    }

    @Test
    void onlyForbiddenCodesAreRecorded() {
        TenantContext.useTenant(7L, "demoa");
        // 40101 是登录失败、40400 是不暴露存在：都不是「被拒绝的操作」
        deniedLogger.onDenied(new ApiException(401, 40101, "租户、账号或密码不正确"));
        deniedLogger.onDenied(new ApiException(404, 40400, "对象不存在"));
        verifyNoInteractions(auditLogger);
    }

    @Test
    void markerSuppressesDuplicateDeniedEntry() {
        TenantContext.useTenant(7L, "demoa");
        AuditMarkers.markDeniedRecorded();
        deniedLogger.onDenied(new ApiException(403, 40301, "模块未开通，请联系平台"));
        verifyNoInteractions(auditLogger);
    }

    @Test
    void anonymousRequestsAreSkipped() {
        SecurityContextHolder.clearContext();
        deniedLogger.onDenied(new ApiException(403, 40300, "没有权限"));
        verifyNoInteractions(auditLogger);
        assertThat(AuditMarkers.deniedRecorded()).isFalse();
    }
}
