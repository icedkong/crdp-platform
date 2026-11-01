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
import static org.mockito.Mockito.when;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.server.auth.TenantLookupCache;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E05-T03 / PLT-TEN-011、012：宽限期配置为只读的租户，医院端写请求（POST / PUT / PATCH / DELETE）一律 40306
 * 「宽限期只读，禁止写入」，读请求照常；宽限期未配只读（默认正常使用）、非宽限期、平台会话与未认证请求不受影响。
 * 身份类接口（/api/hospital/auth/**）放行：改密、登出是会话自救手段，不属于业务新增修改删除。
 */
class GraceReadOnlyFilterTest {

    private final ApiErrorWriter errors = mock(ApiErrorWriter.class);
    private final TenantLookupCache tenantLookup = mock(TenantLookupCache.class);
    private GraceReadOnlyFilter filter;

    @BeforeEach
    void setUp() {
        filter =
                new GraceReadOnlyFilter(
                        errors,
                        tenantLookup,
                        mock(com.crdp.framework.audit.DeniedOperationLogger.class));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void tenant(String status, boolean graceReadOnly) {
        when(tenantLookup.findByCode("demoa"))
                .thenReturn(
                        Optional.of(
                                new TenantRecord(7L, "demoa", "虚构示例医院甲", status, graceReadOnly)));
    }

    private static SessionPrincipal hospitalSession() {
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
                false,
                null);
    }

    private static SessionPrincipal platformSession() {
        return new SessionPrincipal(
                "token",
                "PLATFORM",
                null,
                null,
                null,
                1L,
                "platform.admin",
                "平台管理员（示例）",
                List.of("PLATFORM_ADMIN"),
                List.of(1L),
                List.of("platform:tenant:view"),
                List.of("base"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private boolean run(String method, String uri, SessionPrincipal principal)
            throws ServletException, IOException {
        if (principal != null) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new org.springframework.security.authentication
                                    .UsernamePasswordAuthenticationToken(
                                    principal, null, List.of()));
        }
        AtomicBoolean chainReached = new AtomicBoolean(false);
        filter.doFilter(
                new MockHttpServletRequest(method, uri),
                new MockHttpServletResponse(),
                (req, res) -> chainReached.set(true));
        return chainReached.get();
    }

    @Test
    void graceReadOnlyBlocksHospitalWrites() throws Exception {
        tenant("GRACE", true);

        assertFalse(run("POST", "/api/hospital/followup/patients", hospitalSession()));
        assertFalse(run("PUT", "/api/hospital/followup/records/9", hospitalSession()));
        assertFalse(run("PATCH", "/api/hospital/followup/tasks/9", hospitalSession()));
        assertFalse(run("DELETE", "/api/hospital/followup/tasks/9", hospitalSession()));
        verify(errors, org.mockito.Mockito.times(4))
                .write(any(), eq(403), eq(40306), eq("宽限期只读，禁止写入，请联系平台续费"));
    }

    @Test
    void graceReadOnlyKeepsReadsWorking() throws Exception {
        tenant("GRACE", true);

        assertTrue(run("GET", "/api/hospital/followup/patients", hospitalSession()), "读请求照常");
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
    }

    @Test
    void authEndpointsStayUsableSoSessionsCanSelfRescue() throws Exception {
        tenant("GRACE", true);

        assertTrue(run("POST", "/api/hospital/auth/password", hospitalSession()));
        assertTrue(run("POST", "/api/hospital/auth/logout", hospitalSession()));
        assertTrue(run("GET", "/api/hospital/auth/me", hospitalSession()));
        assertTrue(run("GET", "/api/hospital/auth/tenant-state", hospitalSession()));
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
    }

    @Test
    void graceWithoutReadOnlyKeepsNormalUse() throws Exception {
        tenant("GRACE", false);

        assertTrue(run("POST", "/api/hospital/followup/patients", hospitalSession()), "默认正常使用");
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
    }

    @Test
    void nonGraceTenantsPlatformsAndAnonymousRequestsPassThrough() throws Exception {
        tenant("ENABLED", true);
        assertTrue(run("POST", "/api/hospital/followup/patients", hospitalSession()));

        tenant("TRIAL", true);
        assertTrue(run("POST", "/api/hospital/followup/patients", hospitalSession()));

        tenant("GRACE", true);
        assertTrue(run("POST", "/api/platform/tenants/7/status", platformSession()), "平台端不受宽限只读约束");
        assertTrue(run("POST", "/api/hospital/followup/patients", null), "未认证请求交给后续链处理");
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
    }
}
