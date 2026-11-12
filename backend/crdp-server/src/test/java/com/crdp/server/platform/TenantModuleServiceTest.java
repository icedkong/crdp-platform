package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.module.ModuleRegistry;
import com.crdp.server.config.TenantSchemaMigrator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E05-T05（PLT-MOD-004、005、006、008、009）单元测试：开通 / 关闭的依赖校验与状态校验（纯函数）、 配置取值校验、原因校验、视图装配与审计写入。真实 MySQL
 * 的开通后菜单可见 / 关闭后接口拒绝 由 {@code PlatformTenantModuleMySqlIT} 验证。
 */
class TenantModuleServiceTest {

    private static final long TENANT_ID = 1L;

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final PermissionVersionStore permissionVersions = mock(PermissionVersionStore.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
    private final ModuleRegistry registry = new ModuleRegistry();
    private final RoleTemplateService roleTemplates = mock(RoleTemplateService.class);

    private TenantModuleService service;

    @BeforeEach
    void setUp() {
        service =
                new TenantModuleService(
                        platform,
                        registry,
                        routing,
                        migrator,
                        audit,
                        sessions,
                        permissionVersions,
                        roleTemplates);
        lenient().when(routing.hasTenant(anyLong())).thenReturn(true);
        lenient()
                .when(roleTemplates.seedToTenant(anyLong(), anyString(), anyString()))
                .thenReturn(0);
        TenantContext.usePlatform();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 纯函数校验

    @Test
    void openRejectsUnavailableTenantStatuses() {
        for (String status : List.of("PROVISIONING", "DISABLED", "CANCELLING", "CANCELLED")) {
            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () ->
                                    TenantModuleService.checkOpenAllowed(
                                            status, "ON", null, List.of(), Map.of()));
            assertEquals(40901, ex.code());
            assertTrue(ex.getMessage().contains(status), ex.getMessage());
        }
    }

    @Test
    void openAllowsTrialEnabledAndGraceTenants() {
        for (String status : List.of("TRIAL", "ENABLED", "GRACE")) {
            TenantModuleService.checkOpenAllowed(status, "ON", null, List.of(), Map.of());
        }
    }

    @Test
    void openRejectsOffShelfModule() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantModuleService.checkOpenAllowed(
                                        "ENABLED", "OFF", null, List.of(), Map.of()));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("下架"));
    }

    @Test
    void openRejectsAlreadyOpenOrTransitioningStates() {
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.checkOpenAllowed(
                                                "ENABLED", "ON", "OPEN", List.of(), Map.of()))
                        .code());
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.checkOpenAllowed(
                                                "ENABLED", "ON", "OPENING", List.of(), Map.of()))
                        .code());
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.checkOpenAllowed(
                                                "ENABLED",
                                                "ON",
                                                "MAINTENANCE",
                                                List.of(),
                                                Map.of()))
                        .code());
        // 开通失败可重开（重新开通执行增量迁移）
        TenantModuleService.checkOpenAllowed("ENABLED", "ON", "OPEN_FAILED", List.of(), Map.of());
    }

    @Test
    void openValidatesDependenciesWithExplanation() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantModuleService.checkOpenAllowed(
                                        "ENABLED",
                                        "ON",
                                        null,
                                        List.of("base", "core"),
                                        Map.of("base", "OPEN")));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("core"), "依赖说明须列出未开通项：" + ex.getMessage());
        // 依赖全部已开通时放行
        TenantModuleService.checkOpenAllowed(
                "ENABLED", "ON", null, List.of("base"), Map.of("base", "OPEN"));
    }

    @Test
    void closeRejectsBaseModuleAndNonOpenStates() {
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.checkCloseAllowed(
                                                true, "OPEN", List.of()))
                        .code());
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () -> TenantModuleService.checkCloseAllowed(false, null, List.of()))
                        .code());
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.checkCloseAllowed(
                                                false, "CLOSED", List.of()))
                        .code());
        TenantModuleService.checkCloseAllowed(false, "OPEN", List.of());
    }

    @Test
    void closeValidatesDependentsFirst() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> TenantModuleService.checkCloseAllowed(false, "OPEN", List.of("rct")));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("rct"), ex.getMessage());
    }

    @Test
    void missingDependenciesKeepsDeclaredOrder() {
        assertEquals(
                List.of("base", "core"),
                TenantModuleService.missingDependencies(
                        List.of("base", "core"), Map.of("core", "CLOSED")));
    }

    @Test
    void configValuesAreValidatedAgainstRegisteredItems() {
        List<com.crdp.platform.module.ModuleDefinition.ConfigItem> items =
                registry.find("followup").orElseThrow().configItems();
        // 未知配置项
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.normalizeConfigValues(
                                                Map.of("unknown", "x"), items))
                        .code());
        // 空值 / 空白
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.normalizeConfigValues(
                                                Map.of("defaultNotifyChannel", " "), items))
                        .code());
        // 超长
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantModuleService.normalizeConfigValues(
                                                Map.of("defaultNotifyChannel", "长".repeat(256)),
                                                items))
                        .code());
        // 合法：去首尾空格
        assertEquals(
                Map.of("defaultNotifyChannel", "短信"),
                TenantModuleService.normalizeConfigValues(
                        Map.of("defaultNotifyChannel", " 短信 "), items));
    }

    @Test
    void closeReasonIsRequiredAndBounded() {
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> TenantModuleService.reasonOf(null)).code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> TenantModuleService.reasonOf(" ")).code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantModuleService.reasonOf("长".repeat(201)))
                        .code());
        assertEquals("医院要求暂停（示例）", TenantModuleService.reasonOf(" 医院要求暂停（示例） "));
    }

    @Test
    void openReasonIsOptionalButBounded() {
        assertNull(TenantModuleService.optionalReason(null));
        assertNull(TenantModuleService.optionalReason("  "));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantModuleService.optionalReason("长".repeat(201)))
                        .code());
    }

    // ---------------------------------------------------------------- 视图装配

    @Test
    void modulesViewJoinsCatalogWithTenantStates() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_module ORDER BY")))
                .thenReturn(
                        List.of(
                                moduleRow("base", "平台基础", 1, "ON"),
                                moduleRow("followup", "随访", 0, "ON")));
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(
                        List.of(
                                tenantModuleRow("base", "OPEN"),
                                tenantModuleRow("followup", TenantModuleService.STATUS_CLOSED)));

        TenantModuleService.TenantModulesView view = service.modulesOf(TENANT_ID);

        assertEquals("demoa", view.code());
        assertEquals("ENABLED", view.tenantStatus());
        assertEquals(2, view.modules().size());
        TenantModuleService.TenantModuleView base =
                view.modules().stream()
                        .filter(m -> m.code().equals("base"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(base.baseModule());
        assertEquals("OPEN", base.status());
        TenantModuleService.TenantModuleView followup =
                view.modules().stream()
                        .filter(m -> m.code().equals("followup"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(TenantModuleService.STATUS_CLOSED, followup.status());
        assertEquals("合同到期（示例）", followup.reason());
    }

    @Test
    void closeImpactReportsSessionsTasksAndDependents() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("followup", "OPEN")));
        when(sessions.listForTenant(TENANT_ID)).thenReturn(List.of());
        when(platform.queryForObject(contains("information_schema.TABLES"), eq(Integer.class)))
                .thenReturn(0);
        // 目录里 rct 未登记（首期不登记），用 followup 依赖 base 的反向场景：base 的依赖方为空
        TenantModuleService.CloseImpactView impact = service.closeImpact(TENANT_ID, "followup");

        assertEquals("followup", impact.moduleCode());
        assertEquals(0, impact.onlineUserCount());
        assertNull(impact.pendingTaskCount());
        assertTrue(impact.dependentModules().isEmpty());
    }

    @Test
    void closeImpactRejectsModuleNotOpen() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(
                        List.of(tenantModuleRow("followup", TenantModuleService.STATUS_CLOSED)));
        ApiException ex =
                assertThrows(ApiException.class, () -> service.closeImpact(TENANT_ID, "followup"));
        assertEquals(40901, ex.code());
    }

    // ---------------------------------------------------------------- 开通 / 关闭流程

    @Test
    void closeWritesStateLogAndAuditAndBumpsTenantUsers() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("followup", "OPEN")));
        when(platform.queryForList(eq("SELECT id FROM sys_user"), eq(Long.class)))
                .thenReturn(List.of(9L, 10L));

        TenantModuleService.ChangeResult result =
                service.close(
                        TENANT_ID, "followup", new TenantModuleService.CloseRequest("医院要求暂停（示例）"));

        assertEquals(TenantModuleService.STATUS_CLOSED, result.status());
        assertEquals("OPEN", result.previousStatus());
        verify(platform)
                .update(
                        contains("UPDATE plt_tenant_module SET status = ?"),
                        eq(TenantModuleService.STATUS_CLOSED),
                        any(),
                        any(),
                        eq(TENANT_ID),
                        eq("followup"));
        verify(platform)
                .update(
                        contains("INSERT INTO plt_tenant_module_log"),
                        eq(TENANT_ID),
                        eq("followup"),
                        eq("CLOSE"),
                        eq("医院要求暂停（示例）"),
                        contains("数据保留"),
                        any());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MODULE_CLOSE", event.getValue().eventType());
        assertEquals("plt_tenant:demoa:followup", event.getValue().target());
        // 权限版本递增：在线会话下一次请求重算 openedModules，菜单立即收回、接口立即拒绝
        verify(permissionVersions).bump("HOSPITAL", TENANT_ID, 9L);
        verify(permissionVersions).bump("HOSPITAL", TENANT_ID, 10L);
    }

    @Test
    void closeRejectsMissingReasonBeforeAnyWrite() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("followup", "OPEN")));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.close(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.CloseRequest(" ")));
        assertEquals(40001, ex.code());
        verify(platform, never())
                .update(contains("UPDATE plt_tenant_module SET status = ?"), any(Object[].class));
    }

    @Test
    void closeRejectsWhenDependentModuleStillOpen() {
        stubTenantAndModuleRows();
        Map<String, Object> rct = moduleRow("rct", "RCT 随访", 0, "ON");
        rct.put("depends_on", "followup");
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("rct")))
                .thenReturn(List.of(rct));
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(
                        List.of(
                                tenantModuleRow("followup", "OPEN"),
                                tenantModuleRow("rct", "OPEN")));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.close(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.CloseRequest("合同到期（示例）")));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("rct"), ex.getMessage());
        verify(platform, never())
                .update(contains("UPDATE plt_tenant_module SET status = ?"), any(Object[].class));
    }

    @Test
    void openRunsMigrationGrantsMenusSeedsConfigAndWritesLog() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("base", "OPEN")));
        // 菜单进入授权范围：模块的医院端菜单
        when(platform.queryForList(
                        contains("FROM plt_menu WHERE module_code"),
                        eq(Long.class),
                        eq("followup")))
                .thenReturn(List.of(21L, 211L));
        // 租户库：TENANT_ADMIN 角色与用户清单
        when(platform.queryForList(
                        eq("SELECT id FROM sys_role WHERE code = ?"),
                        eq(Long.class),
                        eq("TENANT_ADMIN")))
                .thenReturn(List.of(5L));
        when(platform.queryForList(eq("SELECT id FROM sys_user"), eq(Long.class)))
                .thenReturn(List.of(9L));

        TenantModuleService.ChangeResult result =
                service.open(
                        TENANT_ID, "followup", new TenantModuleService.OpenRequest("合同签订（示例）"));

        assertEquals(TenantModuleService.STATUS_OPEN, result.status());
        assertNull(result.previousStatus());
        // 模块迁移只对该租户库执行（followup 目录）
        verify(migrator).migrateTenantModule(any(), eq("db/tenant/followup"));
        // 授权菜单（INSERT IGNORE 幂等）
        verify(platform).update(contains("INSERT IGNORE INTO sys_role_menu"), eq(5L), eq(21L));
        verify(platform).update(contains("INSERT IGNORE INTO sys_role_menu"), eq(5L), eq(211L));
        // 配置默认值（PLT-MOD-008：开通时初始化）
        verify(platform)
                .update(
                        contains("INSERT IGNORE INTO plt_tenant_module_config"),
                        eq(TENANT_ID),
                        eq("followup"),
                        eq("defaultNotifyChannel"),
                        eq("站内信"));
        // 业务角色模板（E06-T05 / PLT-ROLE-005：开通流程第 4 步下发到租户库）
        verify(roleTemplates).seedToTenant(TENANT_ID, "demoa", "followup");
        // 状态落 OPEN、开通时间与操作人
        verify(platform)
                .update(
                        contains("UPDATE plt_tenant_module SET migration_version"),
                        eq("93"),
                        any(),
                        eq(TENANT_ID),
                        eq("followup"));
        // 开通记录（PLT-MOD-009）
        verify(platform)
                .update(
                        contains("INSERT INTO plt_tenant_module_log"),
                        eq(TENANT_ID),
                        eq("followup"),
                        eq("OPEN"),
                        eq("合同签订（示例）"),
                        contains("迁移版本 93"),
                        any());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MODULE_OPEN", event.getValue().eventType());
        // 权限版本递增：开通后菜单立即可见
        verify(permissionVersions).bump("HOSPITAL", TENANT_ID, 9L);
    }

    @Test
    void openFailureMarksOpenFailedWithAlert() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("base", "OPEN")));
        org.mockito.Mockito.doThrow(new RuntimeException("duplicate column"))
                .when(migrator)
                .migrateTenantModule(any(), anyString());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.open(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.OpenRequest(null)));

        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("开通失败"));
        // 状态置开通失败并带原因
        verify(platform)
                .update(
                        contains("INSERT INTO plt_tenant_module"),
                        eq(TENANT_ID),
                        eq("followup"),
                        eq(TenantModuleService.STATUS_OPEN_FAILED),
                        contains("duplicate column"),
                        any());
        // 告警（审计）记录失败
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertTrue(event.getValue().detail().contains(TenantModuleService.STATUS_OPEN_FAILED));
        assertTrue(event.getValue().detail().contains("duplicate column"));
    }

    @Test
    void openRejectsDependencyNotOpen() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("base", TenantModuleService.STATUS_CLOSED)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.open(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.OpenRequest(null)));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("base"), ex.getMessage());
        verify(migrator, never()).migrateTenantModule(any(), anyString());
    }

    @Test
    void openRejectsOffShelfAndAlreadyOpen() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("followup", "OPEN")));
        ApiException already =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.open(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.OpenRequest(null)));
        assertEquals(40901, already.code());

        Map<String, Object> offShelf = moduleRow("followup", "随访", 0, "OFF");
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("followup")))
                .thenReturn(List.of(offShelf));
        ApiException off =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.open(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.OpenRequest(null)));
        assertEquals(40901, off.code());
        assertTrue(off.getMessage().contains("下架"));
    }

    @Test
    void openRejectsUnknownTenantOrModule() {
        when(platform.queryForList(contains("FROM plt_tenant WHERE id ="), eq(TENANT_ID)))
                .thenReturn(List.of());
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.open(
                                                TENANT_ID,
                                                "followup",
                                                new TenantModuleService.OpenRequest(null)))
                        .code());

        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("unknown")))
                .thenReturn(List.of());
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.open(
                                                TENANT_ID,
                                                "unknown",
                                                new TenantModuleService.OpenRequest(null)))
                        .code());
    }

    // ---------------------------------------------------------------- 模块级配置

    @Test
    void configViewFallsBackToDefinitionDefaults() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("followup", "OPEN")));
        when(platform.queryForList(
                        contains("FROM plt_tenant_module_config"), eq(TENANT_ID), eq("followup")))
                .thenReturn(List.of(configRow("defaultNotifyChannel", "短信")));

        TenantModuleService.ModuleConfigView view = service.configOf(TENANT_ID, "followup");

        assertEquals(1, view.items().size());
        TenantModuleService.ConfigValueView item = view.items().get(0);
        assertEquals("defaultNotifyChannel", item.code());
        assertEquals("站内信", item.defaultValue());
        assertEquals("短信", item.value());
        assertEquals("短信", item.effectiveValue());
    }

    @Test
    void applyConfigUpsertsOnlyChangedValuesAndAudits() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of(tenantModuleRow("followup", "OPEN")));
        when(platform.queryForList(
                        contains("FROM plt_tenant_module_config"), eq(TENANT_ID), eq("followup")))
                .thenReturn(List.of(configRow("defaultNotifyChannel", "短信")));

        service.applyConfig(
                TENANT_ID,
                "followup",
                new TenantModuleService.ConfigUpdateRequest(
                        Map.of("defaultNotifyChannel", "短信"), null));

        // 取值未变化：不写库、不写审计
        verify(platform, never())
                .update(contains("INSERT INTO plt_tenant_module_config"), any(Object[].class));
        verify(audit, never()).record(any());

        service.applyConfig(
                TENANT_ID,
                "followup",
                new TenantModuleService.ConfigUpdateRequest(
                        Map.of("defaultNotifyChannel", "短信 + 微信（示例）"), "渠道调整（示例）"));

        verify(platform)
                .update(
                        contains("INSERT INTO plt_tenant_module_config"),
                        eq(TENANT_ID),
                        eq("followup"),
                        eq("defaultNotifyChannel"),
                        eq("短信 + 微信（示例）"),
                        any());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MODULE_CONFIG", event.getValue().eventType());
        assertTrue(event.getValue().detail().contains("defaultNotifyChannel"));
    }

    @Test
    void applyConfigRejectsClosedModule() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(
                        List.of(tenantModuleRow("followup", TenantModuleService.STATUS_CLOSED)));
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.applyConfig(
                                        TENANT_ID,
                                        "followup",
                                        new TenantModuleService.ConfigUpdateRequest(
                                                Map.of("defaultNotifyChannel", "短信"), null)));
        assertEquals(40901, ex.code());
    }

    // ---------------------------------------------------------------- 开通记录

    @Test
    void openLogsJoinTenantAndOrderByNewestFirst() {
        stubTenantAndModuleRows();
        when(platform.query(
                        anyString(),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        any(Object[].class)))
                .thenReturn(List.of());

        List<TenantModuleService.OpenLogView> logs = service.openLogs("followup", null);

        assertTrue(logs.isEmpty());
        verify(platform)
                .query(
                        contains("ORDER BY l.id DESC"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        any(Object[].class));
    }

    @Test
    void openLogsRejectUnknownModule() {
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("unknown")))
                .thenReturn(List.of());
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.openLogs("unknown", null)).code());
    }

    // ---------------------------------------------------------------- 向导接入

    @Test
    void onboardingOpensBaseAndSelectedModules() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_tenant_module WHERE"), eq(TENANT_ID)))
                .thenReturn(List.of());
        when(platform.queryForList(
                        contains("FROM plt_menu WHERE module_code"),
                        eq(Long.class),
                        eq("followup")))
                .thenReturn(List.of(21L));
        when(platform.queryForList(
                        eq("SELECT id FROM sys_role WHERE code = ?"),
                        eq(Long.class),
                        eq("TENANT_ADMIN")))
                .thenReturn(List.of(5L));

        List<String> opened = service.openForOnboarding(TENANT_ID, List.of("followup"));

        assertEquals(List.of("base", "followup"), opened);
        // base 与 followup 都落 OPEN 登记（含开通时间与迁移版本）
        verify(platform)
                .update(
                        contains("UPDATE plt_tenant_module SET migration_version"),
                        eq(""),
                        eq(TENANT_ID),
                        eq("base"));
        verify(platform)
                .update(
                        contains("UPDATE plt_tenant_module SET migration_version"),
                        eq("93"),
                        eq(TENANT_ID),
                        eq("followup"));
        // 新建租户同样下发业务角色模板（E06-T05；base 未登记模板，seeder 返回 0）
        verify(roleTemplates).seedToTenant(TENANT_ID, "demoa", "followup");
        // 新租户没有在线会话，不递增权限版本
        verify(permissionVersions, never()).bump(anyString(), anyLong(), anyLong());
    }

    @Test
    void onboardingRejectsOffShelfModule() {
        stubTenantAndModuleRows();
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("followup")))
                .thenReturn(List.of(moduleRow("followup", "随访", 0, "OFF")));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.openForOnboarding(TENANT_ID, List.of("followup")));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("下架"));
    }

    // ---------------------------------------------------------------- 桩

    private void stubTenantAndModuleRows() {
        when(platform.queryForList(contains("FROM plt_tenant WHERE id ="), eq(TENANT_ID)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", TENANT_ID,
                                        "code", "demoa",
                                        "name", "虚构示例医院甲",
                                        "status", "ENABLED")));
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("base")))
                .thenReturn(List.of(moduleRow("base", "平台基础", 1, "ON")));
        when(platform.queryForList(contains("FROM plt_module WHERE code ="), eq("followup")))
                .thenReturn(List.of(moduleRow("followup", "随访", 0, "ON")));
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
        row.put("migration_version", baseModule == 1 ? "" : "93");
        row.put("depends_on", baseModule == 1 ? "" : "base");
        return row;
    }

    private static Map<String, Object> configRow(String code, String value) {
        Map<String, Object> row = new HashMap<>();
        row.put("config_code", code);
        row.put("config_value", value);
        row.put("updated_at", null);
        return row;
    }

    private static Map<String, Object> tenantModuleRow(String code, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("module_code", code);
        row.put("status", status);
        row.put("migration_version", "42");
        row.put("opened_at", null);
        row.put("closed_at", null);
        row.put("reason", TenantModuleService.STATUS_CLOSED.equals(status) ? "合同到期（示例）" : null);
        return row;
    }
}
