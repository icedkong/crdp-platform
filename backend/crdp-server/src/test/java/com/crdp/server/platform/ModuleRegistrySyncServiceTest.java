package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.crdp.platform.module.ModuleRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E05-T04（PLT-MOD-002、003，验收：只登记随访模块、启动时同步）：启动把代码登记的 followup 模块同步进
 * plt_module——缺失注册、版本变化更新并写版本记录、无变化只刷新同步时间；展示信息与上架状态不被覆盖。迁移版本取 db/tenant/followup 里最大的 Flyway
 * 脚本版本（真实 classpath，当前最大 V59__sys_webhook.sql → "59"（E09-T06））。
 */
class ModuleRegistrySyncServiceTest {

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ModuleRegistry registry = new ModuleRegistry();

    private ModuleRegistrySyncService service;

    @BeforeEach
    void setUp() {
        service = new ModuleRegistrySyncService(platform, registry, audit);
    }

    private void stubCurrentRow(String featureVersion, String migrationVersion, String dependsOn) {
        when(platform.queryForList(contains("FROM plt_module WHERE code = ?"), eq("followup")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "feature_version",
                                        featureVersion,
                                        "migration_version",
                                        migrationVersion,
                                        "depends_on",
                                        dependsOn)));
    }

    @Test
    void startupRunTriggersSyncOfRegisteredModules() {
        stubCurrentRow("0.1.0", "59", "base");

        service.run(new DefaultApplicationArguments());

        // 验收：启动时同步（run 与手动 sync 走同一套逻辑）
        verify(platform)
                .update(
                        eq("UPDATE plt_module SET synced_at = CURRENT_TIMESTAMP(3) WHERE code = ?"),
                        eq("followup"));
    }

    @Test
    void missingModuleIsRegisteredWithDefaultsAndVersionRecord() {
        when(platform.queryForList(contains("FROM plt_module WHERE code = ?"), eq("followup")))
                .thenReturn(List.of());

        ModuleRegistrySyncService.SyncResult result = service.sync();

        assertEquals(1, result.outcomes().size(), "验收：只登记随访一个模块");
        ModuleRegistrySyncService.SyncOutcome outcome = result.outcomes().get(0);
        assertEquals("followup", outcome.code());
        assertEquals("REGISTERED", outcome.action());
        assertEquals("0.1.0", outcome.featureVersion());
        assertEquals("59", outcome.migrationVersion(), "迁移版本取 followup 目录最大脚本版本 V59");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // 列序：code, name, description, base_module, feature_version, migration_version, depends_on
        verify(platform)
                .update(
                        sql.capture(),
                        eq("followup"),
                        eq("随访"),
                        contains("共用"),
                        eq(0),
                        eq("0.1.0"),
                        eq("59"),
                        eq("base"));
        // 注册写入默认展示信息与版本列，上架状态默认 ON（SQL 字面量，不在参数里）
        assertTrue(sql.getValue().contains("INSERT INTO plt_module ("), sql.getValue());
        assertTrue(sql.getValue().contains("'ON'"), "注册后默认上架：" + sql.getValue());

        verify(platform)
                .update(
                        contains("INSERT INTO plt_module_version"),
                        eq("followup"),
                        eq("0.1.0"),
                        eq("59"),
                        contains("随访"));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MODULE_SYNC", event.getValue().eventType());
        assertEquals("plt_module:followup", event.getValue().target());
        assertTrue(event.getValue().detail().contains("注册模块"), event.getValue().detail());
    }

    @Test
    void changedVersionUpdatesOnlyRegistryColumnsAndRecordsVersion() {
        // V14 之后、首次同步之前：种子行的版本列为空串
        stubCurrentRow("", "", "");

        ModuleRegistrySyncService.SyncResult result = service.sync();

        assertEquals("UPDATED", result.outcomes().get(0).action());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // 列序：feature_version, migration_version, depends_on, code
        verify(platform).update(sql.capture(), eq("0.1.0"), eq("59"), eq("base"), eq("followup"));
        // 只更新随发版注册的列；name / description / status 由平台端维护，同步不覆盖（PLT-MOD-002）
        assertTrue(
                sql.getValue().contains("UPDATE plt_module SET feature_version"), sql.getValue());
        assertTrue(!sql.getValue().contains("name ="), sql.getValue());
        assertTrue(!sql.getValue().contains("status ="), sql.getValue());

        verify(platform)
                .update(
                        contains("INSERT INTO plt_module_version"),
                        eq("followup"),
                        eq("0.1.0"),
                        eq("59"),
                        contains("首期登记"));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertTrue(event.getValue().detail().contains("更新版本"), event.getValue().detail());
    }

    @Test
    void unchangedModuleOnlyRefreshesSyncedAt() {
        stubCurrentRow("0.1.0", "59", "base");

        ModuleRegistrySyncService.SyncResult result = service.sync();

        assertEquals("UNCHANGED", result.outcomes().get(0).action());
        verify(platform)
                .update(
                        eq("UPDATE plt_module SET synced_at = CURRENT_TIMESTAMP(3) WHERE code = ?"),
                        eq("followup"));
        verify(platform, never()).update(contains("plt_module_version"), any(Object[].class));
        verify(audit, never()).record(any());
    }

    @Test
    void registryFollowupMigrationLocationResolvesOnClasspath() {
        assertEquals("59", registry.migrationVersionOf(registry.find("followup").orElseThrow()));
    }
}
