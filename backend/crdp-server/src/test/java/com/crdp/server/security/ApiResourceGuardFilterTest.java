package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.platform.apires.ApiResourceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E05-T06 验收「未登记接口默认拒绝」（PLT-PERM-004）：/api/** 必须登记在 plt_api_resource——未登记 40307； 公共接口放行（是否匿名由
 * SecurityConfig 决定）；绑定权限码的接口会话须持码（否则 40300）；已登记但无权限码且未标公共 → 40307； OPTIONS 与非 /api 路径不经过本层。
 */
class ApiResourceGuardFilterTest {

    private static final ThreadLocal<Integer> STATUS = new ThreadLocal<>();
    private static final ThreadLocal<String> BODY = new ThreadLocal<>();

    private final ApiErrorWriter errors = new ApiErrorWriter(new ObjectMapper());
    private final ApiResourceRegistry registry = new ApiResourceRegistry();
    private ApiResourceGuardFilter filter;

    @BeforeEach
    void setUp() {
        registry.refresh(
                List.of(
                        row("GET", "/api/public/ping", null, true),
                        row("GET", "/api/platform/tenants", "platform:tenant:view", false),
                        row(
                                "GET",
                                "/api/platform/tenants/{tenantId}",
                                "platform:tenant:view",
                                false),
                        row("GET", "/api/broken/unbound", null, false)));
        filter = new ApiResourceGuardFilter(registry, errors);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static ApiResourceRow row(
            String method, String path, String perm, boolean publicAccess) {
        ApiResourceRow entry = new ApiResourceRow();
        entry.setHttpMethod(method);
        entry.setPathPattern(path);
        entry.setPermCode(perm);
        entry.setPublicAccess(publicAccess);
        return entry;
    }

    private void login(List<String> permissionCodes) {
        SessionPrincipal principal =
                new SessionPrincipal(
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
                        permissionCodes,
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** 执行过滤器；返回链路是否到达业务层（true = 放行），响应状态与报文留在 ThreadLocal 供断言。 */
    private boolean run(String method, String uri) throws ServletException, IOException {
        AtomicBoolean reached = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest(method, uri),
                response,
                (request, chainResponse) -> reached.set(true));
        STATUS.set(response.getStatus());
        BODY.set(response.getContentAsString());
        return reached.get();
    }

    @Test
    void unregisteredApiIsDeniedByDefault() throws Exception {
        // 验收标准：未登记接口默认拒绝
        assertFalse(run("GET", "/api/platform/nonexistent"), "未登记接口不得进入业务层");
        assertEquals(403, STATUS.get());
        assertTrue(BODY.get().contains("40307"), BODY.get());
    }

    @Test
    void registeredPublicApiPassesWithoutPermissionCode() throws Exception {
        assertTrue(run("GET", "/api/public/ping"));
        assertEquals(200, STATUS.get());
    }

    @Test
    void permittedCodePassesAndPatternPathMatches() throws Exception {
        login(List.of("platform:tenant:view"));
        assertTrue(run("GET", "/api/platform/tenants"));
        assertTrue(run("GET", "/api/platform/tenants/42"));
    }

    @Test
    void missingPermissionCodeIs40300() throws Exception {
        login(List.of("platform:menu:view"));
        assertFalse(run("GET", "/api/platform/tenants"), "无权限码不得进入业务层");
        assertEquals(403, STATUS.get());
        assertTrue(BODY.get().contains("40300"), BODY.get());
    }

    @Test
    void unauthenticatedRequestIsLeftToSecurityConfig() throws Exception {
        // 未认证不在本层拒绝（防探测口径不变，交给 SecurityConfig 40100）
        assertTrue(run("GET", "/api/platform/tenants"));
    }

    @Test
    void registeredButUnboundAndNotPublicIsDenied() throws Exception {
        // platform-spec §4：非公共接口未绑定权限码一律不可访问
        assertFalse(run("GET", "/api/broken/unbound"));
        assertEquals(403, STATUS.get());
        assertTrue(BODY.get().contains("40307"), BODY.get());
    }

    @Test
    void optionsAndNonApiPathsBypassGuard() throws Exception {
        assertTrue(run("OPTIONS", "/api/platform/nonexistent"));
        assertTrue(run("GET", "/actuator/health"));
        assertTrue(run("GET", "/v3/api-docs"));
    }
}
