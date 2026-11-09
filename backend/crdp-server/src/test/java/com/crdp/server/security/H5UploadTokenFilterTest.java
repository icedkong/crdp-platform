package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.app.MobileUploadTokenStore;
import com.crdp.followup.report.app.MobileUploadTokenStore.Payload;
import com.crdp.followup.report.app.ReportService.LocationInput;
import com.crdp.followup.report.infra.mapper.MobileUploadMapper;
import com.crdp.framework.audit.DeniedOperationLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.server.auth.TenantLookupCache;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T10 验收「二维码过期失效」的过滤器层锁定（A-ATT-015 / C-SCAN-008 / C-NFR-006）：有效令牌装载签发人实时会话 （SESSION_H5 端别权限 +
 * 租户上下文）；令牌不存在 / 已提交 / 签发人会话结束不装载（服务层统一 40101）；C 打开 h5_upload_login 后 未登录本院账号 40100、 /context
 * 引导口豁免；模块未开通 40301、签发人权限被收回 40300、宽限期只读写请求 40306。
 */
class H5UploadTokenFilterTest {

    private final MobileUploadTokenStore tokens = mock(MobileUploadTokenStore.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final MobileUploadMapper mapper = mock(MobileUploadMapper.class);
    private final TenantLookupCache tenantLookup = mock(TenantLookupCache.class);
    private final DeniedOperationLogger denied = mock(DeniedOperationLogger.class);

    private H5UploadTokenFilter filter;

    /** 最近一次 run() 的响应（每次新建，避免多次执行之间状态残留）。 */
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter =
                new H5UploadTokenFilter(
                        tokens,
                        sessions,
                        mapper,
                        tenantLookup,
                        new ApiErrorWriter(new com.fasterxml.jackson.databind.ObjectMapper()),
                        denied);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        lenient().when(mapper.findLoginRequired()).thenReturn(false);
        lenient()
                .when(sessions.find("issuer-token"))
                .thenReturn(Optional.of(issuer(List.of("followup:scan:upload"), false)));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void validPendingTokenLoadsIssuerSessionWithH5AuthorityAndTenantContext()
            throws ServletException, IOException {
        when(tokens.find("tok-1")).thenReturn(pending(null));

        run("GET", "/api/h5/upload/tok-1");

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(
                SecurityContextHolder.getContext()
                        .getAuthentication()
                        .getAuthorities()
                        .contains(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(
                                        H5UploadTokenFilter.SESSION_H5_AUTHORITY)));
        // 端别权限不含 SESSION_HOSPITAL：装载的会话进不了 /api/hospital/**
        assertTrue(
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .noneMatch(
                                a ->
                                        a.getAuthority()
                                                .equals(
                                                        TokenAuthenticationFilter
                                                                .SESSION_HOSPITAL_AUTHORITY)));
        assertEquals("demoa", TenantContext.current().orElseThrow().tenantCode());
    }

    @Test
    void missingSubmittedOrSessionEndedTokenLoadsNothing() throws ServletException, IOException {
        when(tokens.find("missing")).thenReturn(null);
        when(tokens.find("used"))
                .thenReturn(
                        new Payload(
                                "issuer-token",
                                1L,
                                "demoa",
                                42L,
                                null,
                                List.of("101"),
                                MobileUploadTokenStore.STATUS_SUBMITTED,
                                "9001"));
        when(tokens.find("session-gone")).thenReturn(pending(null));
        when(sessions.find("issuer-token")).thenReturn(Optional.empty());

        for (String token : List.of("missing", "used", "session-gone")) {
            SecurityContextHolder.clearContext();
            run("GET", "/api/h5/upload/" + token);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            // 放行给服务层统一 40101（防探测口径），过滤器不抢答
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void loginRequiredTenantRejectsWithoutHospitalSessionButExemptsContext()
            throws ServletException, IOException {
        when(tokens.find("tok-1")).thenReturn(pending(null));
        when(mapper.findLoginRequired()).thenReturn(true);

        run("GET", "/api/h5/upload/tok-1");

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("请先登录本院账号"));
        // /context 引导口豁免（登录表单要先拿租户编码）
        run("GET", "/api/h5/upload/tok-1/context");
        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void loginRequiredAcceptsSameTenantHospitalSession() throws ServletException, IOException {
        when(tokens.find("tok-1")).thenReturn(pending(null));
        when(mapper.findLoginRequired()).thenReturn(true);
        // 手机端 Bearer 会话（TokenAuthenticationFilter 已装载）与令牌同租户
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                phoneSession(),
                                null,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority(
                                                TokenAuthenticationFilter
                                                        .SESSION_HOSPITAL_AUTHORITY))));

        run("GET", "/api/h5/upload/tok-1");

        assertEquals(200, response.getStatus());
        // 装载的是签发人会话（不是手机端会话）：H5 侧身份 = 二维码签发人
        assertEquals(
                "issuer-token",
                ((SessionPrincipal)
                                SecurityContextHolder.getContext()
                                        .getAuthentication()
                                        .getPrincipal())
                        .token());
    }

    @Test
    void moduleClosedRejectsWith40301() throws ServletException, IOException {
        when(tokens.find("tok-1")).thenReturn(pending(null));
        when(sessions.find("issuer-token"))
                .thenReturn(Optional.of(issuer(List.of("followup:scan:upload"), false, List.of())));

        run("POST", "/api/h5/upload/tok-1/files");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("40301"));
        verify(denied).onDenied(any(), eq("POST"), anyString());
    }

    @Test
    void permissionRevokedRejectsWith40300() throws ServletException, IOException {
        when(tokens.find("tok-1")).thenReturn(pending(null));
        when(sessions.find("issuer-token")).thenReturn(Optional.of(issuer(List.of(), false)));

        run("GET", "/api/h5/upload/tok-1");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("40300"));
    }

    @Test
    void graceReadOnlyTenantBlocksWriteRequestsOnly() throws ServletException, IOException {
        when(tokens.find("tok-1")).thenReturn(pending(null));
        when(tenantLookup.findByCode("demoa"))
                .thenReturn(Optional.of(new TenantRecord(1L, "demoa", "医院 A（示例）", "GRACE", true)));

        run("POST", "/api/h5/upload/tok-1/submit");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("40306"));

        run("GET", "/api/h5/upload/tok-1");
        assertEquals(200, response.getStatus());
    }

    @Test
    void nonH5PathPassesThroughUntouched() throws ServletException, IOException {
        run("GET", "/api/hospital/followup/reports");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
        verify(tokens, never()).find(anyString());
    }

    /** 执行过滤器（链尾空实现）；响应留在 {@link #response} 供断言。 */
    private void run(String method, String uri) throws ServletException, IOException {
        response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest(method, uri), response, (request, chainResponse) -> {});
    }

    // ---------------------------------------------------------------- 桩

    private static Payload pending(LocationInput preset) {
        return new Payload(
                "issuer-token",
                1L,
                "demoa",
                42L,
                preset,
                List.of(),
                MobileUploadTokenStore.STATUS_PENDING,
                null);
    }

    private static SessionPrincipal issuer(List<String> permissions, boolean mustChangePassword) {
        return issuer(permissions, mustChangePassword, List.of("followup"));
    }

    private static SessionPrincipal issuer(
            List<String> permissions, boolean mustChangePassword, List<String> modules) {
        return new SessionPrincipal(
                "issuer-token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                7L,
                "doctor.demo",
                "王医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                permissions,
                modules,
                1L,
                ScopeSnapshot.allowAll(),
                mustChangePassword,
                null);
    }

    private static SessionPrincipal phoneSession() {
        return new SessionPrincipal(
                "phone-token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                8L,
                "nurse.demo",
                "李护士（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:scan:upload"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }
}
