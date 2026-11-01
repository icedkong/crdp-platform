package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.module.ModuleDefinition;
import com.crdp.platform.module.ModuleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 模块注册同步（E05-T04 / PLT-MOD-002、003，验收：启动时同步）。
 *
 * <p>应用启动时把 {@link ModuleRegistry} 里随发版登记的模块同步进平台库 plt_module：缺失则注册（首次写入默认 名称与说明，上架状态默认 ON），版本（功能版本
 * / 迁移版本 / 依赖）变化则更新并写一条 plt_module_version 版本 记录（含变更说明），无变化只刷新
 * synced_at。同步**不覆盖**平台端维护的展示信息（名称、说明）与上架状态。 每次有注册或版本变化写一条 MODULE_SYNC 审计。
 */
@Service
@Order(5)
public class ModuleRegistrySyncService implements ApplicationRunner {

    private final JdbcTemplate platform;
    private final ModuleRegistry registry;
    private final AuditLogger audit;

    public ModuleRegistrySyncService(
            JdbcTemplate platform, ModuleRegistry registry, AuditLogger audit) {
        this.platform = platform;
        this.registry = registry;
        this.audit = audit;
    }

    /** 同步结果：动作 REGISTERED（新注册）/ UPDATED（版本变化）/ UNCHANGED（仅刷新同步时间）。 */
    public record SyncOutcome(
            String code, String action, String featureVersion, String migrationVersion) {}

    public record SyncResult(List<SyncOutcome> outcomes) {}

    /** 启动即同步（验收标准）；平台库 Flyway 在数据源初始化时已执行完，这里的表结构必然就绪。 */
    @Override
    public void run(ApplicationArguments args) {
        sync();
    }

    /** 同步全部登记模块；也可由平台端 POST /api/platform/modules/sync 手动触发。 */
    public SyncResult sync() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<SyncOutcome> outcomes = new ArrayList<>();
                    for (ModuleDefinition definition : registry.definitions()) {
                        outcomes.add(syncOne(definition));
                    }
                    return new SyncResult(List.copyOf(outcomes));
                });
    }

    private SyncOutcome syncOne(ModuleDefinition definition) {
        String migrationVersion = registry.migrationVersionOf(definition);
        String dependsOn = String.join(",", definition.dependsOn());
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT feature_version, migration_version, depends_on FROM plt_module"
                                + " WHERE code = ?",
                        definition.code());

        if (rows.isEmpty()) {
            platform.update(
                    "INSERT INTO plt_module (code, name, description, base_module, status,"
                            + " feature_version, migration_version, depends_on, synced_at)"
                            + " VALUES (?, ?, ?, ?, 'ON', ?, ?, ?, CURRENT_TIMESTAMP(3))",
                    definition.code(),
                    definition.defaultName(),
                    definition.defaultDescription(),
                    definition.baseModule() ? 1 : 0,
                    definition.featureVersion(),
                    migrationVersion,
                    dependsOn);
            recordVersion(definition, migrationVersion);
            audit(
                    definition,
                    "REGISTERED",
                    "注册模块：功能版本 " + definition.featureVersion() + " / 迁移版本 " + migrationVersion);
            return new SyncOutcome(
                    definition.code(), "REGISTERED", definition.featureVersion(), migrationVersion);
        }

        Map<String, Object> current = rows.get(0);
        String currentFeature = str(current.get("feature_version"));
        String currentMigration = str(current.get("migration_version"));
        String currentDependsOn = str(current.get("depends_on"));
        if (definition.featureVersion().equals(currentFeature)
                && migrationVersion.equals(currentMigration)
                && dependsOn.equals(currentDependsOn)) {
            platform.update(
                    "UPDATE plt_module SET synced_at = CURRENT_TIMESTAMP(3) WHERE code = ?",
                    definition.code());
            return new SyncOutcome(
                    definition.code(), "UNCHANGED", currentFeature, currentMigration);
        }

        // 只更新随发版注册的列；name / description / status 由平台端维护，同步不覆盖（PLT-MOD-002）
        platform.update(
                "UPDATE plt_module SET feature_version = ?, migration_version = ?, depends_on = ?,"
                        + " synced_at = CURRENT_TIMESTAMP(3) WHERE code = ?",
                definition.featureVersion(),
                migrationVersion,
                dependsOn,
                definition.code());
        recordVersion(definition, migrationVersion);
        audit(
                definition,
                "UPDATED",
                "更新版本：功能版本 "
                        + currentFeature
                        + "→"
                        + definition.featureVersion()
                        + " / 迁移版本 "
                        + currentMigration
                        + "→"
                        + migrationVersion);
        return new SyncOutcome(
                definition.code(), "UPDATED", definition.featureVersion(), migrationVersion);
    }

    private void recordVersion(ModuleDefinition definition, String migrationVersion) {
        platform.update(
                "INSERT INTO plt_module_version (module_code, feature_version, migration_version,"
                        + " change_note) VALUES (?, ?, ?, ?)",
                definition.code(),
                definition.featureVersion(),
                migrationVersion,
                definition.changeNote());
    }

    private void audit(ModuleDefinition definition, String action, String detail) {
        audit.record(
                new AuditEvent(
                        "MODULE_SYNC",
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        action,
                        "plt_module:" + definition.code(),
                        detail));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
