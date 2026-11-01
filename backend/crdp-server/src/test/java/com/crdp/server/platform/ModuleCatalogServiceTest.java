package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.module.ModuleRegistry;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E05-T04（PLT-MOD-001、002、003）：目录装配（菜单 / 权限码 / 配置项 / 版本历史）、展示信息与上下架维护的 校验与审计。真实落库由 {@code
 * PlatformModuleRegistryMySqlIT} 验证。
 */
class ModuleCatalogServiceTest {

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private ModuleCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModuleCatalogService(platform, new ModuleRegistry(), audit);
    }

    private static Map<String, Object> moduleRow(
            String code, String name, int baseModule, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        row.put("name", name);
        row.put("description", "说明（示例）");
        row.put("base_module", baseModule);
        row.put("status", status);
        row.put("feature_version", baseModule == 1 ? "" : "0.1.0");
        row.put("migration_version", baseModule == 1 ? "" : "2");
        row.put("depends_on", baseModule == 1 ? "" : "base");
        row.put("synced_at", baseModule == 1 ? null : LocalDateTime.of(2026, 11, 1, 9, 0));
        return row;
    }

    private void stubCatalogRows() {
        when(platform.queryForList(contains("FROM plt_module ORDER BY")))
                .thenReturn(
                        List.of(
                                moduleRow("base", "平台基础", 1, "ON"),
                                moduleRow("followup", "随访", 0, "ON")));
        when(platform.queryForList(contains("sort_no, id")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", 21L,
                                        "module_code", "followup",
                                        "side", "HOSPITAL",
                                        "type", "MENU",
                                        "title", "患者档案"),
                                Map.of(
                                        "id", 211L,
                                        "module_code", "followup",
                                        "side", "HOSPITAL",
                                        "type", "BUTTON",
                                        "title", "查看明文")));
        when(platform.queryForList(contains("DISTINCT module_code")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "module_code",
                                        "followup",
                                        "perm_code",
                                        "followup:patient:plain"),
                                Map.of(
                                        "module_code",
                                        "followup",
                                        "perm_code",
                                        "followup:patient:view")));
        when(platform.queryForList(contains("FROM plt_module_version ORDER BY")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "module_code", "followup",
                                        "feature_version", "0.1.0",
                                        "migration_version", "2",
                                        "change_note", "首期登记随访模块（示例）",
                                        "created_at", LocalDateTime.of(2026, 11, 1, 9, 0))));
    }

    @Test
    void listAssemblesCatalogMenusPermissionsConfigItemsAndVersions() {
        stubCatalogRows();

        List<ModuleCatalogService.ModuleView> modules = service.list();

        assertEquals(2, modules.size(), "平台基础 + 随访");
        ModuleCatalogService.ModuleView base = modules.get(0);
        ModuleCatalogService.ModuleView followup = modules.get(1);
        assertTrue(base.baseModule());
        assertEquals("followup", followup.code());
        assertEquals(List.of("base"), followup.dependsOn(), "依赖来自 depends_on 逗号拆分");
        assertEquals("0.1.0", followup.featureVersion());
        assertEquals("2", followup.migrationVersion());
        assertEquals(2, followup.menus().size(), "菜单清单按 module_code 归属");
        assertEquals("患者档案", followup.menus().get(0).title());
        assertEquals(
                List.of("followup:patient:plain", "followup:patient:view"),
                followup.permissions(),
                "权限码清单来自模块按钮与菜单的 perm_code");
        assertEquals(1, followup.configItems().size(), "配置项来自代码登记（PLT-MOD-001）");
        assertEquals("defaultNotifyChannel", followup.configItems().get(0).code());
        assertEquals(1, followup.versions().size(), "版本历史来自 plt_module_version（PLT-MOD-003）");
        assertEquals("首期登记随访模块（示例）", followup.versions().get(0).changeNote());
        assertEquals("2026-11-01T09:00+08:00", followup.syncedAt().toString());
        assertTrue(base.configItems().isEmpty(), "base 不在代码登记清单里，配置项为空");
    }

    @Test
    void updateValidatesNameDescriptionAndShelfStatus() {
        ApiException blankName =
                assertThrows(
                        ApiException.class,
                        () ->
                                ModuleCatalogService.validate(
                                        new ModuleCatalogService.UpdateRequest(" ", null, null)));
        assertEquals(40001, blankName.code());
        assertThrows(
                ApiException.class,
                () ->
                        ModuleCatalogService.validate(
                                new ModuleCatalogService.UpdateRequest(
                                        "x".repeat(65), null, null)));
        assertThrows(
                ApiException.class,
                () ->
                        ModuleCatalogService.validate(
                                new ModuleCatalogService.UpdateRequest(
                                        "随访", "y".repeat(256), null)));
        assertThrows(
                ApiException.class,
                () ->
                        ModuleCatalogService.validate(
                                new ModuleCatalogService.UpdateRequest("随访", null, "GONE")));
        // ON / OFF 与省略 status（沿用现值）都合法
        ModuleCatalogService.validate(new ModuleCatalogService.UpdateRequest("随访", null, "ON"));
        ModuleCatalogService.validate(new ModuleCatalogService.UpdateRequest("随访", null, null));
        verifyNoWrites();
    }

    @Test
    void updateOfUnknownModuleIs40400() {
        when(platform.queryForList(contains("WHERE code = ?"), eq("rct"))).thenReturn(List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "rct",
                                        new ModuleCatalogService.UpdateRequest("RCT", null, null)));
        assertEquals(40400, ex.code());
        verifyNoWrites();
    }

    @Test
    void baseModuleCannotBeTakenOffShelf() {
        when(platform.queryForList(contains("WHERE code = ?"), eq("base")))
                .thenReturn(List.of(moduleRow("base", "平台基础", 1, "ON")));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "base",
                                        new ModuleCatalogService.UpdateRequest(
                                                "平台基础", null, "OFF")));
        assertEquals(40901, ex.code(), "基础模块必选不可下架");
        verifyNoWrites();
    }

    @Test
    void updateWritesChangedDisplayFieldsAndShelfStatusWithAudit() {
        when(platform.queryForList(contains("WHERE code = ?"), eq("followup")))
                .thenReturn(List.of(moduleRow("followup", "随访", 0, "ON")));
        stubCatalogRows();

        ModuleCatalogService.ModuleView updated =
                service.update(
                        "followup",
                        new ModuleCatalogService.UpdateRequest("随访（演示）", "演示说明", "OFF"));

        verify(platform)
                .update(
                        contains("UPDATE plt_module SET name = ?"),
                        eq("随访（演示）"),
                        eq("演示说明"),
                        eq("OFF"),
                        eq("followup"));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MODULE_UPDATE", event.getValue().eventType());
        assertEquals("plt_module:followup", event.getValue().target());
        assertTrue(event.getValue().detail().contains("ON→OFF"), event.getValue().detail());
        // mock 下重读仍是原行；真实落库与回读由 IT 验证
        assertEquals("随访", updated.name());
    }

    @Test
    void updateWithoutAnyChangeSkipsWriteAndAudit() {
        when(platform.queryForList(contains("WHERE code = ?"), eq("followup")))
                .thenReturn(List.of(moduleRow("followup", "随访", 0, "ON")));
        stubCatalogRows();

        service.update("followup", new ModuleCatalogService.UpdateRequest("随访", "说明（示例）", "ON"));

        verifyNoWrites();
        verify(audit, never()).record(any());
    }

    private void verifyNoWrites() {
        verify(platform, never()).update(any(String.class), any(Object[].class));
        verify(audit, never()).record(any());
    }
}
