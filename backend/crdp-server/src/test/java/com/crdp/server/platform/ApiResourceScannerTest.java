package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.platform.apires.ApiResourceRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.server.security.ApiResourceRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * E05-T06 / PLT-PERM-004：启动扫描把「接口 → 权限码 → 模块」登记进 plt_api_resource——@RequiresPermission
 * / @RequireModule / @PublicApi 的推导规则、同键幂等（只 touch）、平台端标记列不被覆盖、SCAN 来源行随接口消失清除。 用真实
 * RequestMappingInfo + HandlerMethod 构造映射表。
 */
@ExtendWith(MockitoExtension.class)
class ApiResourceScannerTest {

    @RestController
    static class DemoEndpoints {

        @GetMapping("/api/hospital/followup/ping")
        @RequireModule("followup")
        @RequiresPermission("followup:task:view")
        public String ping() {
            return "pong";
        }

        @PublicApi
        @GetMapping("/api/hospital/hello")
        public String hello() {
            return "hello";
        }

        /** 公共标记与权限码同写：以权限码为准（更严），不按公共接口放行。 */
        @PublicApi
        @RequiresPermission("platform:menu:view")
        @GetMapping("/api/platform/menus/tree")
        public String tree() {
            return "tree";
        }

        @PutMapping("/api/platform/menus/{id}")
        @RequiresPermission("platform:menu:edit")
        public String update(@PathVariable long id) {
            return "ok";
        }

        @GetMapping("/api/platform/auth/me")
        public String me() {
            return "me";
        }
    }

    @Mock private PlatformMapper platformMapper;
    @Mock private AuditLogger audit;
    @Mock private ResourceChangeRecorder resourceChanges;
    private final ApiResourceRegistry registry = new ApiResourceRegistry();
    private ApiResourceScanner scanner;

    private void buildScanner() {
        scanner =
                new ApiResourceScanner(
                        handlerMappingOf(DemoEndpoints.class),
                        platformMapper,
                        registry,
                        audit,
                        resourceChanges);
    }

    private static RequestMappingHandlerMapping handlerMappingOf(Class<?> beanType) {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> methods = new java.util.LinkedHashMap<>();
        for (Method method : beanType.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (get == null && put == null) {
                continue;
            }
            String path = get != null ? get.value()[0] : put.value()[0];
            RequestMappingInfo info =
                    RequestMappingInfo.paths(path)
                            .methods(RequestMethod.valueOf(get != null ? "GET" : "PUT"))
                            .options(new RequestMappingInfo.BuilderConfiguration())
                            .build();
            methods.put(info, new HandlerMethod(beanType, method));
        }
        lenient().when(mapping.getHandlerMethods()).thenReturn(methods);
        return mapping;
    }

    private static ApiResourceScanner.ScannedEndpoint endpoint(
            String methodName, String httpMethod, String path, Class<?>... parameterTypes)
            throws Exception {
        return new ApiResourceScanner.ScannedEndpoint(
                httpMethod,
                path,
                new HandlerMethod(
                        DemoEndpoints.class,
                        DemoEndpoints.class.getDeclaredMethod(methodName, parameterTypes)));
    }

    // ---------------------------------------------------------------- 推导规则（toRow）

    @Test
    void derivesPermAndModuleFromAnnotations() throws Exception {
        ApiResourceRow row =
                ApiResourceScanner.toRow(
                        endpoint("ping", "GET", "/api/hospital/followup/ping"),
                        Set.of("base", "followup"));
        assertEquals("followup:task:view", row.getPermCode());
        assertEquals("followup", row.getModuleCode(), "@RequireModule 优先");
        assertFalse(Boolean.TRUE.equals(row.getPublicAccess()));
        assertEquals("QUERY", row.getActionType(), "GET → QUERY");
        assertEquals("SCAN", row.getSource());
    }

    @Test
    void derivesModuleFromPermCodePrefixWhenNoRequireModule() throws Exception {
        ApiResourceRow row =
                ApiResourceScanner.toRow(
                        endpoint("update", "PUT", "/api/platform/menus/{id}", long.class),
                        Set.of("base", "followup"));
        assertEquals("platform:menu:edit", row.getPermCode());
        assertEquals("base", row.getModuleCode(), "platform 不是登记模块 → 归平台基础");
        assertEquals("WRITE", row.getActionType(), "PUT → WRITE");
    }

    @Test
    void publicApiMarksLoginFreeAccessButPermCodeWins() throws Exception {
        ApiResourceRow hello =
                ApiResourceScanner.toRow(
                        endpoint("hello", "GET", "/api/hospital/hello"), Set.of("base"));
        assertTrue(Boolean.TRUE.equals(hello.getPublicAccess()), "@PublicApi → 登录即可访问");
        assertEquals("base", hello.getModuleCode());

        ApiResourceRow tree =
                ApiResourceScanner.toRow(
                        endpoint("tree", "GET", "/api/platform/menus/tree"), Set.of("base"));
        assertFalse(
                Boolean.TRUE.equals(tree.getPublicAccess()),
                "同标 @PublicApi 与 @RequiresPermission 时以权限码为准");
        assertEquals("platform:menu:view", tree.getPermCode());
    }

    @Test
    void unannotatedEndpointIsNeitherPublicNorBound() throws Exception {
        ApiResourceRow row =
                ApiResourceScanner.toRow(
                        endpoint("me", "GET", "/api/platform/auth/me"), Set.of("base"));
        assertEquals(null, row.getPermCode());
        assertFalse(Boolean.TRUE.equals(row.getPublicAccess()));
    }

    // ---------------------------------------------------------------- 登记流程（scan）

    @Test
    void startupRunRegistersMissingEndpointsAndAudits() {
        buildScanner();
        when(platformMapper.listModuleCodes()).thenReturn(List.of("base", "followup"));
        when(platformMapper.listApiResources()).thenReturn(List.of());

        scanner.run(new DefaultApplicationArguments());

        ArgumentCaptor<ApiResourceRow> inserted = ArgumentCaptor.forClass(ApiResourceRow.class);
        verify(platformMapper, atLeastOnce()).insertApiResource(inserted.capture());
        assertTrue(
                inserted.getAllValues().stream()
                        .anyMatch(
                                row ->
                                        "/api/platform/menus/{id}".equals(row.getPathPattern())
                                                && "platform:menu:edit".equals(row.getPermCode())),
                "应登记菜单维护接口");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("API_SYNC", event.getValue().eventType());
        assertTrue(event.getValue().detail().contains("REGISTERED"), event.getValue().detail());
    }

    @Test
    void unchangedRowsAreTouchedAndManualMarksSurvive() {
        buildScanner();
        when(platformMapper.listModuleCodes()).thenReturn(List.of("base", "followup"));
        ApiResourceRow existing = new ApiResourceRow();
        existing.setId(9L);
        existing.setHttpMethod("GET");
        existing.setPathPattern("/api/hospital/hello");
        existing.setPermCode(null);
        existing.setModuleCode("base");
        existing.setPublicAccess(true);
        existing.setAuditLevel("OPERATION");
        existing.setSensitiveLevel("SENSITIVE");
        existing.setActionType("QUERY");
        existing.setSource("MANUAL");
        when(platformMapper.listApiResources()).thenReturn(List.of(existing));

        ApiResourceScanner.ScanResult result = scanner.scan();

        assertEquals(5, result.outcomes().size(), "四个接口 + 一个已存在行");
        assertTrue(result.changed(), "新接口登记为变化");
        // 已存在且推导一致：只刷新同步时间，不覆盖平台端标记列
        verify(platformMapper).touchApiResource(9L);
        verify(platformMapper, never()).updateApiResourceRegistration(any());
        verify(platformMapper, never()).updateApiResourceMarks(any());
    }

    @Test
    void removedScanRowsAreCleanedButSeedRowsKept() {
        buildScanner();
        when(platformMapper.listModuleCodes()).thenReturn(List.of("base", "followup"));
        ApiResourceRow stale = new ApiResourceRow();
        stale.setId(11L);
        stale.setHttpMethod("GET");
        stale.setPathPattern("/api/gone/endpoint");
        stale.setSource("SCAN");
        ApiResourceRow seed = new ApiResourceRow();
        seed.setId(12L);
        seed.setHttpMethod("POST");
        seed.setPathPattern("/api/hospital/auth/login");
        seed.setPermCode(null);
        seed.setModuleCode("base");
        seed.setPublicAccess(true);
        seed.setSource("SEED");
        when(platformMapper.listApiResources()).thenReturn(List.of(stale, seed));

        ApiResourceScanner.ScanResult result = scanner.scan();

        verify(platformMapper).deleteScannedApiResource(11L);
        verify(platformMapper, never()).deleteScannedApiResource(12L);
        assertTrue(
                result.outcomes().stream().anyMatch(outcome -> "REMOVED".equals(outcome.action())));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("API_SYNC", event.getValue().eventType());
        assertTrue(event.getValue().detail().contains("REMOVED"), event.getValue().detail());
    }

    @Test
    void registrationChangeUpdatesOnlyCodeDeclaredColumns() {
        buildScanner();
        when(platformMapper.listModuleCodes()).thenReturn(List.of("base", "followup"));
        // 库内 hello 行还是种子时代的 public_access=0：扫描应按 @PublicApi 修正为公共
        ApiResourceRow existing = new ApiResourceRow();
        existing.setId(20L);
        existing.setHttpMethod("GET");
        existing.setPathPattern("/api/hospital/hello");
        existing.setPermCode(null);
        existing.setModuleCode("base");
        existing.setPublicAccess(false);
        existing.setAuditLevel("NONE");
        existing.setSensitiveLevel("NORMAL");
        existing.setActionType("QUERY");
        existing.setSource("SEED");
        when(platformMapper.listApiResources()).thenReturn(List.of(existing));

        scanner.scan();

        ArgumentCaptor<ApiResourceRow> updated = ArgumentCaptor.forClass(ApiResourceRow.class);
        verify(platformMapper).updateApiResourceRegistration(updated.capture());
        assertEquals(20L, updated.getValue().getId());
        assertTrue(Boolean.TRUE.equals(updated.getValue().getPublicAccess()));
        assertEquals("NONE", existing.getAuditLevel(), "更新注册列时不得动平台端维护的审计级别（断言原行未变）");
    }

    // ---------------------------------------------------------------- 资源变更记录（PLT-PERM-012）

    @Test
    void scanWritesResourceChangeLogForRegisterUpdateAndRemove() {
        buildScanner();
        when(platformMapper.listModuleCodes()).thenReturn(List.of("base"));
        // 库内只有 hello（SEED、非公共，与代码推导不一致 → UPDATED）；tree 为新接口（REGISTERED）；
        // stale 已不在代码里（SCAN → REMOVED）
        ApiResourceRow existing = new ApiResourceRow();
        existing.setId(20L);
        existing.setHttpMethod("GET");
        existing.setPathPattern("/api/hospital/hello");
        existing.setPermCode(null);
        existing.setModuleCode("base");
        existing.setPublicAccess(false);
        existing.setSource("SEED");
        ApiResourceRow stale = new ApiResourceRow();
        stale.setId(11L);
        stale.setHttpMethod("GET");
        stale.setPathPattern("/api/hospital/gone");
        stale.setPermCode(null);
        stale.setModuleCode("base");
        stale.setPublicAccess(false);
        stale.setSource("SCAN");
        when(platformMapper.listApiResources()).thenReturn(List.of(existing, stale));

        scanner.scan();

        // 新接口 → REGISTER / SYNC
        verify(resourceChanges)
                .record(
                        eq("API"),
                        eq(null),
                        eq("GET /api/platform/menus/tree"),
                        eq("/api/platform/menus/tree"),
                        eq("REGISTER"),
                        eq("SYNC"),
                        argThat(detail -> detail.contains("接口首次登记")));
        // 登记变化 → UPDATE / SYNC（权限码以权限码为准：tree 带 @RequiresPermission）
        verify(resourceChanges)
                .record(
                        eq("API"),
                        eq(20L),
                        eq("GET /api/hospital/hello"),
                        eq("/api/hospital/hello"),
                        eq("UPDATE"),
                        eq("SYNC"),
                        argThat(detail -> detail.contains("公共 否 → 是")));
        // 消失接口 → DELETE / SYNC
        verify(resourceChanges)
                .record(
                        eq("API"),
                        eq(11L),
                        eq("GET /api/hospital/gone"),
                        eq("/api/hospital/gone"),
                        eq("DELETE"),
                        eq("SYNC"),
                        argThat(detail -> detail.contains("清除登记")));
    }
}
