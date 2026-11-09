package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.server.platform.TenantViewAccessRecorder;
import com.crdp.server.platform.TenantViewProxyService;
import com.crdp.server.platform.TenantViewProxyService.WriteClass;
import com.crdp.server.platform.TenantViewRuleExecutor;
import com.crdp.server.platform.ViewRuleResolver.Effect;
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
 * E20-T03 / PLT-VIEW-012 + E20-T06 受控放行（PLT-VIEW-013、018）：视角令牌访问医院端业务接口—— 未授权的写（PUT / PATCH /
 * DELETE、非 view 动作的 POST）与明文（GET + plain）一律 40303（默认关闭）； 代为配置 / 编辑业务数据经 TenantViewProxyService
 * 判定（角色授权 ∧ 规则 ∧ X-View-Reason 原因）后放行并记 VIEW_PROXY_*； 导出 / 下载按规则 allowExport
 * 判定（默认禁）；原件签发口（preview-url / download-url）一律拒； POST .../search 复杂查询与 GET 只读放行并记
 * VIEW_QUERY；医院会话与视角外壳（/api/hospital/view/**）不受本层影响； 模块未开通 40301（按该院实时状态）。
 */
class TenantViewGuardFilterTest {

    private final ApiResourceRegistry registry = mock(ApiResourceRegistry.class);
    private final TenantViewRuleExecutor executor = mock(TenantViewRuleExecutor.class);
    private final TenantViewAccessRecorder recorder = mock(TenantViewAccessRecorder.class);
    private final TenantViewProxyService proxy = mock(TenantViewProxyService.class);
    private final ApiErrorWriter errors = mock(ApiErrorWriter.class);
    private TenantViewGuardFilter filter;

    @BeforeEach
    void setUp() {
        filter =
                new TenantViewGuardFilter(
                        registry,
                        executor,
                        recorder,
                        proxy,
                        errors,
                        new com.fasterxml.jackson.databind.ObjectMapper());
        when(executor.moduleOpen(anyLong(), any())).thenReturn(true);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void login(SessionPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** 视角会话（TENANT_VIEW）：平台账号身份 + 被查看租户。 */
    private static SessionPrincipal viewSession() {
        return new SessionPrincipal(
                "view-token",
                "TENANT_VIEW",
                7L,
                "demoa",
                "虚构示例医院甲",
                1L,
                "platform.admin",
                "平台管理员（示例）",
                List.of("PLATFORM_ADMIN"),
                List.of(),
                List.of(),
                List.of(),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                900L,
                7200L,
                FieldPolicySnapshot.defaults());
    }

    private static SessionPrincipal hospitalSession() {
        return new SessionPrincipal(
                "h-token",
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
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private void registered(String method, String uri, String permCode, String module) {
        when(registry.find(method, uri))
                .thenReturn(
                        Optional.of(
                                new ApiResourceRegistry.Entry(
                                        method, uri, permCode, module, false)));
    }

    // ---------------------------------------------------------------- 分类纯函数

    @Test
    void writeMethodsAndNonViewActionsAreNotRead() {
        assertFalse(TenantViewGuardFilter.readOnly("POST", "followup:patient:create"));
        assertFalse(TenantViewGuardFilter.readOnly("PUT", "followup:patient:edit"));
        assertFalse(TenantViewGuardFilter.readOnly("PATCH", "followup:patient:edit"), "PATCH 同为写");
        assertFalse(TenantViewGuardFilter.readOnly("DELETE", "followup:patient:delete"));
        assertFalse(TenantViewGuardFilter.readOnly("GET", "followup:patient:plain"), "明文动作非读");
        assertFalse(TenantViewGuardFilter.readOnly("POST", null), "未绑码 POST 按写（fail closed）");
    }

    @Test
    void viewActionAndPublicGetsAreRead() {
        assertTrue(TenantViewGuardFilter.readOnly("GET", "followup:patient:view"));
        assertTrue(
                TenantViewGuardFilter.readOnly("POST", "followup:patient:view"),
                "复杂查询 POST .../search 动作为 view（AGENTS.md §6）");
        assertTrue(
                TenantViewGuardFilter.readOnly("POST", "followup:plan:view,followup:plan:edit"),
                "登记多候选码时任一 view 即读（OQ-13）");
        assertTrue(TenantViewGuardFilter.readOnly("GET", null), "未绑码公共读接口");
        assertTrue(TenantViewGuardFilter.readOnly("HEAD", null));
    }

    @Test
    void exportActionsAreSeparatelyControlled() {
        assertTrue(TenantViewGuardFilter.exportAction("followup:patient:export"));
        assertTrue(TenantViewGuardFilter.exportAction("followup:scan:download"));
        assertFalse(TenantViewGuardFilter.exportAction("followup:patient:view"));
        assertFalse(TenantViewGuardFilter.exportAction(null));
    }

    @Test
    void fileGrantUrisAreOriginals() {
        assertTrue(TenantViewGuardFilter.isFileGrantUri("/api/hospital/files/12345/preview-url"));
        assertTrue(TenantViewGuardFilter.isFileGrantUri("/api/hospital/files/12345/download-url"));
        assertFalse(TenantViewGuardFilter.isFileGrantUri("/api/hospital/files/policy"));
        assertFalse(TenantViewGuardFilter.isFileGrantUri("/api/hospital/followup/patients/1"));
    }

    // ---------------------------------------------------------------- 过滤行为

    @Test
    void viewWritesAreRejectedWith40303() throws Exception {
        login(viewSession());
        registered(
                "POST", "/api/hospital/followup/patients", "followup:patient:create", "followup");

        MockHttpServletResponse response = reject("/api/hospital/followup/patients", "POST");

        verify(errors).write(response, 403, 40303, "租户视角只读，禁止写入");
        verify(recorder)
                .recordDenied(
                        any(),
                        eq("POST"),
                        eq("/api/hospital/followup/patients"),
                        eq(40303),
                        anyString());
    }

    @Test
    void viewPlainProbeIsRejected() throws Exception {
        login(viewSession());
        registered(
                "GET",
                "/api/hospital/followup/patients/9/plain/name",
                "followup:patient:plain",
                "followup");

        MockHttpServletResponse response =
                reject("/api/hospital/followup/patients/9/plain/name", "GET");

        verify(errors).write(response, 403, 40303, "租户视角只读，禁止写入");
    }

    @Test
    void viewPutsAndDeletesAreRejected() throws Exception {
        login(viewSession());
        registered("PUT", "/api/hospital/system/users/5", "tenant:user:edit", "base");
        registered(
                "DELETE",
                "/api/hospital/followup/patients/9",
                "followup:patient:delete",
                "followup");

        reject("/api/hospital/system/users/5", "PUT");
        reject("/api/hospital/followup/patients/9", "DELETE");
        verify(recorder).recordDenied(any(), eq("PUT"), anyString(), eq(40303), anyString());
        verify(recorder).recordDenied(any(), eq("DELETE"), anyString(), eq(40303), anyString());
    }

    @Test
    void exportDeniedByDefaultRule() throws Exception {
        login(viewSession());
        registered(
                "POST",
                "/api/hospital/followup/patients/export",
                "followup:patient:export",
                "followup");
        when(executor.operationEffect(7L, "followup", "patient"))
                .thenReturn(new Effect("PLAIN", null, "READONLY", false, List.of(), List.of()));

        MockHttpServletResponse response = reject("/api/hospital/followup/patients/export", "POST");

        verify(errors).write(response, 403, 40303, "租户视角禁止导出与下载");
    }

    @Test
    void exportAllowedWhenRulePermits() throws Exception {
        login(viewSession());
        registered(
                "POST",
                "/api/hospital/followup/patients/export",
                "followup:patient:export",
                "followup");
        when(executor.operationEffect(7L, "followup", "patient"))
                .thenReturn(new Effect("PLAIN", null, "READONLY", true, List.of(), List.of()));

        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(
                request("POST", "/api/hospital/followup/patients/export"),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        assertTrue(reached.get(), "allowExport 规则命中时导出放行（PLT-VIEW-012 按规则单独控制）");
    }

    @Test
    void originalGrantUrisAlwaysRejected() throws Exception {
        login(viewSession());
        // preview-url 为 @PublicApi（无权限码），登记为公共接口
        when(registry.find("GET", "/api/hospital/files/123/preview-url"))
                .thenReturn(
                        Optional.of(
                                new ApiResourceRegistry.Entry(
                                        "GET",
                                        "/api/hospital/files/123/preview-url",
                                        null,
                                        "followup",
                                        true)));

        MockHttpServletResponse response = reject("/api/hospital/files/123/preview-url", "GET");

        verify(errors).write(response, 403, 40303, "租户视角只读，报告单与电子化资料原件不提供预览与下载");
    }

    @Test
    void viewReadsPassWithQueryAudit() throws Exception {
        login(viewSession());
        registered(
                "POST",
                "/api/hospital/followup/patients/search",
                "followup:patient:view",
                "followup");
        registered(
                "GET",
                "/api/hospital/followup/patients/filter-options",
                "followup:patient:view",
                "followup");

        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(
                request("POST", "/api/hospital/followup/patients/search"),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        assertTrue(reached.get(), "POST .../search 复杂查询放行");

        filter.doFilter(
                request("GET", "/api/hospital/followup/patients/filter-options"),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        assertTrue(reached.get(), "只读 GET 放行");
        verify(recorder, never())
                .recordDenied(any(), anyString(), anyString(), anyInt(), anyString());
        verify(recorder)
                .recordQuery(
                        any(),
                        eq("POST"),
                        eq("/api/hospital/followup/patients/search"),
                        eq(false),
                        eq("patient"),
                        isNull());
        verify(recorder)
                .recordQuery(
                        any(),
                        eq("GET"),
                        eq("/api/hospital/followup/patients/filter-options"),
                        eq(false),
                        eq("patient"),
                        isNull());
    }

    @Test
    void menusRequestCountsAsPage() throws Exception {
        login(viewSession());
        when(registry.find("GET", "/api/hospital/menus"))
                .thenReturn(
                        Optional.of(
                                new ApiResourceRegistry.Entry(
                                        "GET", "/api/hospital/menus", null, null, true)));

        filter.doFilter(
                request("GET", "/api/hospital/menus"),
                new MockHttpServletResponse(),
                (req, res) -> {});

        verify(recorder)
                .recordQuery(
                        any(), eq("GET"), eq("/api/hospital/menus"), eq(true), isNull(), isNull());
    }

    @Test
    void moduleClosedForTenantRejects40301() throws Exception {
        login(viewSession());
        registered(
                "GET",
                "/api/hospital/followup/patients/filter-options",
                "followup:patient:view",
                "followup");
        when(executor.moduleOpen(7L, "followup")).thenReturn(false);

        MockHttpServletResponse response =
                reject("/api/hospital/followup/patients/filter-options", "GET");

        verify(errors).write(response, 403, 40301, "模块未开通，请联系平台");
    }

    @Test
    void shellAndHospitalSessionsBypass() throws Exception {
        AtomicBoolean reached = new AtomicBoolean(false);
        // 未登录 / 医院会话：本层直通
        filter.doFilter(
                request("POST", "/api/hospital/followup/patients"),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        assertTrue(reached.get());

        login(hospitalSession());
        filter.doFilter(
                request("PUT", "/api/hospital/system/users/5"),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());

        // 视角会话的外壳接口（横幅 / 退出）不属业务执行点
        login(viewSession());
        filter.doFilter(
                request("DELETE", "/api/hospital/view/session"),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
        verify(recorder, never())
                .recordDenied(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void queryAuditCarriesObjectAndRecordCount() throws Exception {
        login(viewSession());
        registered(
                "POST",
                "/api/hospital/followup/patients/search",
                "followup:patient:view",
                "followup");

        // 统一响应 {code, data:{items, total}}：record_count 取 total，object_type 取权限码资源段（PLT-VIEW-016）
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("POST", "/api/hospital/followup/patients/search"),
                response,
                (req, res) -> {
                    res.setContentType("application/json");
                    try {
                        res.getWriter()
                                .write(
                                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"items\":[{},{},{}],"
                                                + "\"total\":3,\"pageNo\":1,\"pageSize\":20}}");
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
        verify(recorder)
                .recordQuery(
                        any(),
                        eq("POST"),
                        eq("/api/hospital/followup/patients/search"),
                        eq(false),
                        eq("patient"),
                        eq(3L));

        // 响应体必须原样透传（缓存包装回写，不吞内容）
        assertTrue(response.getContentAsString().contains("\"total\":3"), "响应体在审计包装后原样返回");
    }

    @Test
    void recordCountExtractionShapes() throws Exception {
        // 纯函数：分页 total / 数组 / items / 单对象 / 空数据 / 业务错误码 / 非 JSON
        assertEquals(
                3L, extract("{\"code\":0,\"data\":{\"items\":[],\"total\":3}}"), "分页响应取 total");
        assertEquals(2L, extract("{\"code\":0,\"data\":[1,2]}"), "数组取长度");
        assertEquals(2L, extract("{\"code\":0,\"data\":{\"items\":[1,2]}}"), "无 total 的 items 取长度");
        assertEquals(1L, extract("{\"code\":0,\"data\":{\"id\":7}}"), "单对象记 1");
        assertEquals(0L, extract("{\"code\":0,\"data\":null}"), "空数据记 0");
        assertNull(extract("{\"code\":40300,\"data\":{\"total\":9}}"), "业务错误不计条数");
        assertNull(extract("not-json"), "非 JSON 返回 null（不阻塞放行）");
        assertNull(extract(""), "空响应体返回 null");
    }

    // ---------------------------------------------------------------- E20-T06
    // 受控放行（PLT-VIEW-013、018）

    @Test
    void releasedConfigWriteReachesChainAndAuditsProxy() throws Exception {
        login(viewSession());
        registered("PUT", "/api/hospital/system/users/5", "tenant:user:edit", "base");
        when(proxy.releaseAction(any(), eq(WriteClass.CONFIG), eq("base"), eq("user")))
                .thenReturn(TenantViewProxyService.ACTION_PROXY_CONFIG);

        MockHttpServletRequest request = request("PUT", "/api/hospital/system/users/5");
        request.addHeader(TenantViewProxyService.REASON_HEADER, "配置核对后修正（示例）");
        request.setContentType("application/json");
        request.setContent(
                "{\"displayName\":\"张医生（示例）\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {
                    reached.set(true);
                    // 模拟控制器读请求体（ContentCachingRequestWrapper 按读取缓存，PLT-VIEW-016 前后值）
                    try {
                        req.getInputStream().readAllBytes();
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });

        assertTrue(reached.get(), "角色可代为配置 ∧ 资源在范围内 → 写放行（受控）");
        verify(errors, never()).write(any(), anyInt(), anyInt(), anyString());
        verify(recorder, never())
                .recordDenied(any(), anyString(), anyString(), anyInt(), anyString());
        // 放行标记供 PermissionAuthorizationAspect 内层防线核对（写动作只在外层判定一次）
        assertEquals(
                TenantViewProxyService.ACTION_PROXY_CONFIG,
                request.getAttribute(
                        com.crdp.framework.security.PermissionAuthorizationAspect
                                .VIEW_PROXY_RELEASED));
        verify(proxy)
                .recordProxy(
                        any(),
                        eq("PUT"),
                        eq("/api/hospital/system/users/5"),
                        eq(TenantViewProxyService.ACTION_PROXY_CONFIG),
                        eq("user"),
                        eq("5"),
                        eq("配置核对后修正（示例）"),
                        isNull(),
                        eq("{\"displayName\":\"张医生（示例）\"}"));
    }

    @Test
    void releasedBusinessWriteAuditsProxyEdit() throws Exception {
        login(viewSession());
        registered("PUT", "/api/hospital/followup/patients/9", "followup:patient:edit", "followup");
        when(proxy.releaseAction(any(), eq(WriteClass.BUSINESS), eq("followup"), eq("patient")))
                .thenReturn(TenantViewProxyService.ACTION_PROXY_EDIT);

        MockHttpServletRequest request = request("PUT", "/api/hospital/followup/patients/9");
        request.addHeader(TenantViewProxyService.REASON_HEADER, "排障修正录入（示例）");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {
                    reached.set(true);
                    try {
                        req.getInputStream().readAllBytes();
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });

        assertTrue(reached.get(), "可编辑业务数据 ∧ 命中可编辑规则 → 写放行（PLT-VIEW-018）");
        assertEquals(
                TenantViewProxyService.ACTION_PROXY_EDIT,
                request.getAttribute(
                        com.crdp.framework.security.PermissionAuthorizationAspect
                                .VIEW_PROXY_RELEASED));
        verify(proxy)
                .recordProxy(
                        any(),
                        eq("PUT"),
                        eq("/api/hospital/followup/patients/9"),
                        eq(TenantViewProxyService.ACTION_PROXY_EDIT),
                        eq("patient"),
                        eq("9"),
                        eq("排障修正录入（示例）"),
                        isNull(),
                        eq("{}"));
    }

    @Test
    void releasedWriteWithoutReasonRejected() throws Exception {
        login(viewSession());
        registered("PUT", "/api/hospital/system/users/5", "tenant:user:edit", "base");
        when(proxy.releaseAction(any(), eq(WriteClass.CONFIG), eq("base"), eq("user")))
                .thenReturn(TenantViewProxyService.ACTION_PROXY_CONFIG);

        MockHttpServletResponse response = reject("/api/hospital/system/users/5", "PUT");

        verify(errors).write(eq(response), eq(400), eq(40001), anyString());
        verify(recorder).recordDenied(any(), eq("PUT"), anyString(), eq(40001), anyString());
        verify(proxy, never())
                .recordProxy(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void releasedWriteWithBlankReasonRejected() throws Exception {
        login(viewSession());
        registered("PUT", "/api/hospital/system/users/5", "tenant:user:edit", "base");
        when(proxy.releaseAction(any(), eq(WriteClass.CONFIG), eq("base"), eq("user")))
                .thenReturn(TenantViewProxyService.ACTION_PROXY_CONFIG);

        MockHttpServletRequest request = request("PUT", "/api/hospital/system/users/5");
        request.addHeader(TenantViewProxyService.REASON_HEADER, " ");
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));

        assertFalse(reached.get(), "二次确认原因必填（PLT-VIEW-013）");
        verify(errors).write(any(), eq(400), eq(40001), anyString());
    }

    @Test
    void unauthorizedWriteStillRejected40303() throws Exception {
        login(viewSession());
        registered("PUT", "/api/hospital/system/users/5", "tenant:user:edit", "base");
        // proxy.releaseAction 默认返回 null（默认关闭：角色未授权 / 规则只读）
        MockHttpServletResponse response = reject("/api/hospital/system/users/5", "PUT");
        verify(errors).write(response, 403, 40303, "租户视角只读，禁止写入");
    }

    @Test
    void objectIdExtractionFromPath() {
        assertEquals("9", TenantViewGuardFilter.objectIdOf("/api/hospital/followup/patients/9"));
        assertEquals("5", TenantViewGuardFilter.objectIdOf("/api/hospital/system/users/5"));
        assertNull(TenantViewGuardFilter.objectIdOf("/api/hospital/followup/patients"));
        assertNull(TenantViewGuardFilter.objectIdOf("/api/hospital/system/users/import"));
    }

    /** 经缓存包装写入响应体后提取条数（与过滤器真实路径一致）。 */
    private Long extract(String body) throws IOException {
        org.springframework.web.util.ContentCachingResponseWrapper wrapper =
                new org.springframework.web.util.ContentCachingResponseWrapper(
                        new MockHttpServletResponse());
        wrapper.getWriter().write(body);
        wrapper.flushBuffer();
        return filter.extractRecordCount(wrapper);
    }

    // ---------------------------------------------------------------- 工具

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    /** 以视角会话跑过滤器，断言请求被拒（未到链尾）并返回响应以供验证。 */
    private MockHttpServletResponse reject(String uri, String method)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request(method, uri), response, (req, res) -> reached.set(true));
        assertFalse(reached.get(), "请求应被视角执行点拦截：" + method + " " + uri);
        return response;
    }
}
