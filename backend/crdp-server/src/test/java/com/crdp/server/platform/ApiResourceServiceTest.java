package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.apires.ApiResourceRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E05-T06 / PLT-PERM-004：接口资源查询与标记——按模块 / 权限码 / 未绑定筛选；标记公共接口、审计级别、
 * 敏感级别、动作类型（枚举校验）；未绑定权限码又非公共的接口拒绝保存（默认拒绝口径）。
 */
@ExtendWith(MockitoExtension.class)
class ApiResourceServiceTest {

    @Mock private PlatformMapper platformMapper;
    @Mock private ApiResourceScanner scanner;
    @Mock private AuditLogger audit;

    private ApiResourceService service;

    private static ApiResourceRow row(
            long id, String method, String path, String perm, boolean publicAccess) {
        ApiResourceRow row = new ApiResourceRow();
        row.setId(id);
        row.setHttpMethod(method);
        row.setPathPattern(path);
        row.setPermCode(perm);
        row.setModuleCode("base");
        row.setPublicAccess(publicAccess);
        row.setAuditLevel("NONE");
        row.setSensitiveLevel("NORMAL");
        row.setActionType("GET".equals(method) ? "QUERY" : "WRITE");
        row.setSource("SCAN");
        return row;
    }

    @BeforeEach
    void setUp() {
        service = new ApiResourceService(platformMapper, scanner, audit);
        lenient()
                .when(platformMapper.listApiResources())
                .thenReturn(
                        List.of(
                                row(
                                        1L,
                                        "GET",
                                        "/api/platform/menus/tree",
                                        "platform:menu:view",
                                        false),
                                row(2L, "GET", "/api/hospital/hello", null, true),
                                row(3L, "POST", "/api/hospital/auth/login", null, true),
                                row(4L, "GET", "/api/platform/auth/me", null, false)));
    }

    @Test
    void listFiltersByModulePermCodeAndUnbound() {
        assertEquals(4, service.list(null, null, false).size());
        assertEquals(
                3,
                service.list(null, null, true).size(),
                "unbound=true 只看未绑定权限码的接口（hello / login / me）");
        assertEquals(1, service.list("base", "platform:menu:view", false).size());
        assertTrue(
                service.list(null, null, true).stream().allMatch(view -> view.permCode() == null),
                "unbound 集合里全部未绑定权限码");
    }

    @Test
    void markRejectsUnknownEnumValues() {
        when(platformMapper.findApiResourceById(1L))
                .thenReturn(
                        row(1L, "GET", "/api/platform/menus/tree", "platform:menu:view", false));

        ApiException auditLevel =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.mark(
                                        1L,
                                        new ApiResourceService.MarkRequest(
                                                null, null, "EVERYTHING", null, null)));
        assertEquals(40001, auditLevel.code());

        ApiException sensitive =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.mark(
                                        1L,
                                        new ApiResourceService.MarkRequest(
                                                null, null, null, "TOP", null)));
        assertEquals(40001, sensitive.code());

        ApiException action =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.mark(
                                        1L,
                                        new ApiResourceService.MarkRequest(
                                                null, null, null, null, "DELETE")));
        assertEquals(40001, action.code());
    }

    @Test
    void markRejectsUnboundNonPublicRow() {
        // platform-spec §4：未绑定权限码的接口必须标记为公共接口，否则默认拒绝
        when(platformMapper.findApiResourceById(4L))
                .thenReturn(row(4L, "GET", "/api/platform/auth/me", null, false));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.mark(
                                        4L,
                                        new ApiResourceService.MarkRequest(
                                                null, false, null, null, null)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("公共接口"), error.getMessage());
    }

    @Test
    void markWritesManualRowRefreshesRegistryAndAudits() {
        ApiResourceRow before = row(3L, "POST", "/api/hospital/auth/login", null, true);
        ApiResourceRow after = row(3L, "POST", "/api/hospital/auth/login", null, true);
        after.setAuditLevel("OPERATION");
        after.setActionType("WRITE");
        // mark 首尾各读一次：首读原行，末读已标记行（UPDATE 的 SQL 会写 source='MANUAL'）
        when(platformMapper.findApiResourceById(3L)).thenReturn(before, after);

        ApiResourceService.ApiView view =
                service.mark(
                        3L,
                        new ApiResourceService.MarkRequest(
                                null, true, "OPERATION", "NORMAL", "WRITE"));

        assertTrue(view.publicAccess());
        assertEquals("OPERATION", view.auditLevel(), "标记后的审计级别生效");
        ArgumentCaptor<ApiResourceRow> marked = ArgumentCaptor.forClass(ApiResourceRow.class);
        verify(platformMapper).updateApiResourceMarks(marked.capture());
        assertEquals("OPERATION", marked.getValue().getAuditLevel());
        verify(scanner).reloadRegistry();
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("API_UPDATE", event.getValue().eventType());
    }

    @Test
    void markRejectsMalformedPermCode() {
        when(platformMapper.findApiResourceById(2L))
                .thenReturn(row(2L, "GET", "/api/hospital/hello", null, true));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.mark(
                                        2L,
                                        new ApiResourceService.MarkRequest(
                                                "Bad_Code", null, null, null, null)));
        assertEquals(40001, error.code());
        verify(platformMapper, never()).updateApiResourceMarks(any());
    }
}
