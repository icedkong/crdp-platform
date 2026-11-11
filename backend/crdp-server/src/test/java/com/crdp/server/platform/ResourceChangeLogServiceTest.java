package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/** E05-T10 / PLT-PERM-012：资源变更记录查询与导出——条件校验（类型 / 动作 / 渠道 / 关键词长度）、 过滤拼装、CSV 导出（水印列 + 审计留痕）。 */
@ExtendWith(MockitoExtension.class)
class ResourceChangeLogServiceTest {

    @Mock private JdbcTemplate platform;
    @Mock private AuditLogger audit;

    private ResourceChangeLogService service;

    @BeforeEach
    void setUp() {
        service = new ResourceChangeLogService(platform, audit);
        TenantContext.usePlatform();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void login() {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        9L,
                        "ops.demo",
                        "王运维（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of("platform:menu:view"),
                        List.of(),
                        1L,
                        null,
                        false,
                        null,
                        "127.0.0.1",
                        0,
                        0,
                        0);
        SecurityContextHolder.setContext(new SecurityContextImpl());
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(principal, null, List.of()));
    }

    private static Map<String, Object> row(long id, String type, String action, String channel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("resource_type", type);
        row.put("resource_id", 113L);
        row.put("resource_name", "菜单资源");
        row.put("resource_ident", "PlatformMenu");
        row.put("action", action);
        row.put("channel", channel);
        row.put("detail", "维护菜单：标题 a → b");
        row.put("operator_id", 9L);
        row.put("operator_name", "王运维（示例）（ops.demo）");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 10, 0, 0)));
        return row;
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void rejectsUnknownResourceType() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.search("TENANT", null, null, null, null, null));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("resourceType"));
    }

    @Test
    void rejectsUnknownAction() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.search(null, "APPLY", null, null, null, null));
        assertEquals(40001, error.code());
    }

    @Test
    void rejectsUnknownChannel() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.search(null, null, "AUTO", null, null, null));
        assertEquals(40001, error.code());
    }

    @Test
    void rejectsOverlongKeyword() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.search(null, null, null, "关".repeat(65), null, null));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("关键词过长"));
    }

    @Test
    void rejectsTenantContext() {
        TenantContext.useTenant(1L, "demoa");
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.search(null, null, null, null, null, null));
        assertEquals(40300, error.code());
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @SuppressWarnings("unchecked")
    void searchBuildsFiltersAndPages() {
        when(platform.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);
        when(platform.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of());
        var page = service.search("MENU", "UPDATE", "PAGE", "菜单资源", 2, 20);
        assertEquals(3L, page.total());
        assertEquals(2, page.pageNo());
        assertEquals(20, page.pageSize());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(platform)
                .queryForObject(
                        sql.capture(),
                        eq(Long.class),
                        eq(new Object[] {"MENU", "UPDATE", "PAGE", "%菜单资源%", "%菜单资源%", "%菜单资源%"}));
        assertTrue(
                sql.getValue().contains("detail LIKE ?)"),
                "关键词分组括号必须闭合（IT 抓到过未闭合导致 500）：" + sql.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchMapsRowToItem() {
        when(platform.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(platform.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        var page = service.search(null, null, null, null, 1, 20);
        assertEquals(0, page.items().size());
        // 行映射直接验证 of()（JDBC 结果集由集成测试覆盖）
        var item = ResourceChangeLogService.LogItem.of(row(7L, "API", "REGISTER", "SYNC"));
        assertEquals(7L, item.id());
        assertEquals("API", item.resourceType());
        assertEquals(113L, item.resourceId());
        assertEquals("REGISTER", item.action());
        assertEquals("SYNC", item.channel());
        assertEquals("2026-11-01T10:00:00+08:00", item.createdAt());
        assertEquals("王运维（示例）（ops.demo）", item.operatorName());
    }

    // ---------------------------------------------------------------- 导出

    @Test
    void exportRendersWatermarkedCsvAndAudits() {
        login();
        when(platform.queryForList(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(
                                row(1L, "MENU", "UPDATE", "PAGE"),
                                row(2L, "API", "REGISTER", "SYNC")));
        var file = service.export("MENU", null, null, "菜单资源");
        assertTrue(file.fileName().startsWith("资源变更记录-"));
        assertTrue(file.fileName().endsWith(".csv"));
        assertEquals(2L, file.rowCount());
        String csv = new String(file.body(), StandardCharsets.UTF_8);
        assertEquals(0xEF, file.body()[0] & 0xFF, "CSV 应带 UTF-8 BOM");
        assertTrue(csv.contains("水印"), "首列应为水印列");
        assertTrue(csv.contains("王运维（示例）（ops.demo）· 平台资源变更记录"), "水印应含操作人与机构");
        assertTrue(csv.contains("资源变更记录导出 · 类型 MENU，关键词 菜单资源 · 2 条"));
        assertTrue(csv.contains("维护菜单：标题 a → b"));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("EXPORT", entry.accessMethod());
        assertEquals("plt_resource_change_log", entry.objectType());
        assertEquals(Long.valueOf(2L), entry.recordCount());
    }
}
