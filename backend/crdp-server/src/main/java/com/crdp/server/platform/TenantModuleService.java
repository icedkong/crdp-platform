package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.tenant.UnknownTenantDataSourceException;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.module.ModuleDefinition;
import com.crdp.platform.module.ModuleRegistry;
import com.crdp.server.config.TenantSchemaMigrator;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 按租户开通 / 关闭模块（E05-T05 / PLT-MOD-004、005，验收：开通后菜单可见，关闭后接口拒绝）。
 *
 * <p>开通按平台规格 §3「开通与关闭流程」执行：校验依赖与租户状态 → 只对该租户库执行该模块迁移（失败置 开通失败并写审计告警）→ 模块菜单进入租户授权范围（授予租户库
 * TENANT_ADMIN，业务角色模板随 E06 落地）→ 初始化模块配置默认值；关闭做影响评估、依赖校验与 二次确认（原因必填），只收回菜单与接口（{@code @RequireModule}
 * 40301），数据与租户库角色授权保留（重新开通自动恢复）。开通 / 关闭都递增 该院全部用户的权限版本号，在线会话下一次请求即重算 openedModules 快照——菜单立即可见 /
 * 消失，模块接口立即放行 / 拒绝（E04-T06 机制）。
 *
 * <p>模块级配置（PLT-MOD-008）按租户覆盖代码登记的默认值；开通 / 关闭操作记录（PLT-MOD-009）落 plt_tenant_module_log，升级（UPGRADE）随
 * PLT-MOD-007 灰度批次写入。
 */
@Service
public class TenantModuleService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 租户模块状态（平台规格 §3 关键字段·租户模块开通）。 */
    public static final String STATUS_OPENING = "OPENING";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_OPEN_FAILED = "OPEN_FAILED";
    public static final String STATUS_MAINTENANCE = "MAINTENANCE";
    public static final String STATUS_CLOSED = "CLOSED";

    /** 可执行模块开通的租户状态：试用 / 启用 / 宽限期（与登录闸门 routable() 同口径）。 */
    static final Set<String> OPEN_ALLOWED_TENANT_STATUSES = Set.of("TRIAL", "ENABLED", "GRACE");

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private static final int DETAIL_MAX = 512;

    private static final int REASON_MAX = 200;

    private final JdbcTemplate platform;
    private final ModuleRegistry registry;
    private final TenantRoutingDataSource routing;
    private final TenantSchemaMigrator migrator;
    private final AuditLogger audit;
    private final SessionStore sessions;
    private final PermissionVersionStore permissionVersions;

    public TenantModuleService(
            JdbcTemplate platform,
            ModuleRegistry registry,
            TenantRoutingDataSource routing,
            TenantSchemaMigrator migrator,
            AuditLogger audit,
            SessionStore sessions,
            PermissionVersionStore permissionVersions) {
        this.platform = platform;
        this.registry = registry;
        this.routing = routing;
        this.migrator = migrator;
        this.audit = audit;
        this.sessions = sessions;
        this.permissionVersions = permissionVersions;
    }

    // ---------------------------------------------------------------- DTO

    /** 关闭请求（PLT-MOD-005）：影响评估后二次确认，原因必填。 */
    public record CloseRequest(String reason) {}

    /** 开通请求（PLT-MOD-004）：原因可留空（非必须），留痕用。 */
    public record OpenRequest(String reason) {}

    /** 开通 / 关闭结果。 */
    public record ChangeResult(
            long tenantId,
            String tenantCode,
            String moduleCode,
            String previousStatus,
            String status,
            String migrationVersion) {}

    /** 租户详情 · 模块页签的行：目录信息 + 该租户的开通状态。 */
    public record TenantModuleView(
            String code,
            String name,
            String description,
            boolean baseModule,
            String shelfStatus,
            String featureVersion,
            String migrationVersion,
            List<String> dependsOn,
            String status,
            String currentMigrationVersion,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt,
            String reason) {}

    public record TenantModulesView(
            long tenantId,
            String code,
            String name,
            String tenantStatus,
            List<TenantModuleView> modules) {}

    /** 关闭影响评估（PLT-MOD-005）：在线用户数、进行中任务数与仍依赖它的已开通模块。 */
    public record CloseImpactView(
            long tenantId,
            String tenantCode,
            String moduleCode,
            String moduleName,
            long onlineUserCount,
            Long pendingTaskCount,
            List<String> dependentModules) {}

    /** 模块级配置（PLT-MOD-008）：默认值来自代码登记，租户覆盖值优先。 */
    public record ConfigValueView(
            String code,
            String name,
            String defaultValue,
            String description,
            String value,
            String effectiveValue,
            OffsetDateTime updatedAt) {}

    public record ModuleConfigView(
            long tenantId, String tenantCode, String moduleCode, List<ConfigValueView> items) {}

    public record ConfigUpdateRequest(Map<String, String> values, String reason) {}

    /** 开通记录（PLT-MOD-009）：开通、关闭（升级随 PLT-MOD-007）。 */
    public record OpenLogView(
            long id,
            long tenantId,
            String tenantCode,
            String tenantName,
            String moduleCode,
            String action,
            String reason,
            String detail,
            Long operatorId,
            OffsetDateTime createdAt) {}

    // ---------------------------------------------------------------- 查询

    /** 租户详情 · 模块页签（PLT-MOD-004、005）：目录全量行 + 该租户的开通状态与痕迹。 */
    public TenantModulesView modulesOf(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    Map<String, Map<String, Object>> states = tenantModuleRows(tenantId);
                    List<Map<String, Object>> catalog =
                            platform.queryForList(
                                    "SELECT code, name, description, base_module, status,"
                                            + " feature_version, migration_version, depends_on"
                                            + " FROM plt_module ORDER BY base_module DESC, code");
                    List<TenantModuleView> modules =
                            catalog.stream()
                                    .map(row -> toView(row, states.get(str(row.get("code")))))
                                    .toList();
                    return new TenantModulesView(
                            tenantId,
                            str(tenant.get("code")),
                            str(tenant.get("name")),
                            str(tenant.get("status")),
                            modules);
                });
    }

    /** 关闭影响评估（PLT-MOD-005）：对话框二次确认前展示。 */
    public CloseImpactView closeImpact(long tenantId, String moduleCode) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    Map<String, Object> module = requireModule(moduleCode);
                    Map<String, Map<String, Object>> states = tenantModuleRows(tenantId);
                    String current = stateStatus(states.get(moduleCode));
                    if (!STATUS_OPEN.equals(current)) {
                        throw new ApiException(
                                409,
                                40901,
                                "模块未开通（当前状态：" + (current == null ? "未开通" : current) + "），无需关闭");
                    }
                    return new CloseImpactView(
                            tenantId,
                            str(tenant.get("code")),
                            moduleCode,
                            str(module.get("name")),
                            sessions.listForTenant(tenantId).size(),
                            pendingTaskCount(tenantId, str(tenant.get("code"))),
                            dependentOpenModules(moduleCode, states));
                });
    }

    // ---------------------------------------------------------------- 开通（PLT-MOD-004）

    public ChangeResult open(long tenantId, String moduleCode, OpenRequest request) {
        String reason = optionalReason(request == null ? null : request.reason());
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    Map<String, Object> module = requireModule(moduleCode);
                    Map<String, Map<String, Object>> states = tenantModuleRows(tenantId);
                    String current = stateStatus(states.get(moduleCode));
                    checkOpenAllowed(
                            str(tenant.get("status")),
                            str(module.get("status")),
                            current,
                            dependsOf(module),
                            openStatuses(states));
                    String previous = current;

                    // 第 2 步：置开通中，再执行该租户库的模块迁移；失败置开通失败并告警（审计）
                    upsertState(tenantId, moduleCode, STATUS_OPENING, null);
                    String migrationVersion = str(module.get("migration_version"));
                    try {
                        ModuleDefinition definition = registry.find(moduleCode).orElse(null);
                        if (definition != null) {
                            runModuleMigration(tenantId, str(tenant.get("code")), definition);
                            migrationVersion = registry.migrationVersionOf(definition);
                        } else if (!routing.hasTenant(tenantId)) {
                            throw new ApiException(409, 40901, "租户库未注册路由，无法执行模块迁移，请先完成租户开通");
                        }
                        // 第 3 步：模块菜单与按钮进入租户授权范围（授予租户管理员；授权范围模板上限是 E05-T07）
                        int grantedMenus =
                                grantModuleMenusToTenantAdmin(tenantId, tenant, moduleCode);
                        // 第 4 步：初始化模块配置默认值（已有覆盖值不覆盖）；业务角色模板随 E06 落地
                        int configDefaults = seedConfigDefaults(tenantId, moduleCode);
                        // 先置 OPEN（含版本与时间），再递增权限版本：会话重算读 openedModules 时必须已可见，
                        // 否则被刷新的会话会带着旧快照停在最新版本号上（开通后菜单不可见）
                        upsertState(tenantId, moduleCode, STATUS_OPEN, null);
                        platform.update(
                                "UPDATE plt_tenant_module SET migration_version = ?,"
                                        + " opened_at = CURRENT_TIMESTAMP(3), closed_at = NULL,"
                                        + " reason = NULL, operator_id = ?"
                                        + " WHERE tenant_id = ? AND module_code = ?",
                                migrationVersion,
                                operatorId(),
                                tenantId,
                                moduleCode);
                        // 菜单立即可见：递增该院全部用户的权限版本，在线会话下次请求重算 openedModules
                        int refreshedUsers = bumpTenantPermissionVersions(tenantId, tenant);
                        insertLog(
                                tenantId,
                                moduleCode,
                                "OPEN",
                                reason,
                                "迁移版本 "
                                        + migrationVersion
                                        + "；授权菜单 "
                                        + grantedMenus
                                        + " 项；配置默认值 "
                                        + configDefaults
                                        + " 项；刷新 "
                                        + refreshedUsers
                                        + " 个用户的权限版本");
                        audit.record(
                                new AuditEvent(
                                        "MODULE_OPEN",
                                        operatorId(),
                                        "INSERT",
                                        "plt_tenant:" + str(tenant.get("code")) + ":" + moduleCode,
                                        abbreviate(
                                                (previous == null ? "未开通" : previous)
                                                        + "→OPEN（迁移版本 "
                                                        + migrationVersion
                                                        + "；授权菜单 "
                                                        + grantedMenus
                                                        + " 项）"
                                                        + suffixReason(reason),
                                                DETAIL_MAX)));
                        return new ChangeResult(
                                tenantId,
                                str(tenant.get("code")),
                                moduleCode,
                                previous,
                                STATUS_OPEN,
                                migrationVersion);
                    } catch (ApiException ex) {
                        return openFailed(tenantId, tenant, moduleCode, previous, reason, ex);
                    } catch (RuntimeException ex) {
                        return openFailed(
                                tenantId,
                                tenant,
                                moduleCode,
                                previous,
                                reason,
                                new ApiException(500, 50000, ex.getMessage()));
                    }
                });
    }

    // ---------------------------------------------------------------- 关闭（PLT-MOD-005）

    public ChangeResult close(long tenantId, String moduleCode, CloseRequest request) {
        String reason = reasonOf(request == null ? null : request.reason());
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    Map<String, Object> module = requireModule(moduleCode);
                    Map<String, Map<String, Object>> states = tenantModuleRows(tenantId);
                    String current = stateStatus(states.get(moduleCode));
                    checkCloseAllowed(
                            ((Number) module.get("base_module")).intValue() == 1,
                            current,
                            dependentOpenModules(moduleCode, states));
                    String previous = current;

                    // 只收回菜单与接口（数据与租户库角色授权保留，重新开通自动恢复）：
                    // 状态置 CLOSED 后 openedModules 不再含该模块，菜单过滤与 @RequireModule(40301) 都以它为准；
                    // 在线会话靠权限版本重算立即生效；模块级定时任务随业务作业接入（E14 起按模块校验 openedModules）。
                    platform.update(
                            "UPDATE plt_tenant_module SET status = ?, closed_at ="
                                    + " CURRENT_TIMESTAMP(3), reason = ?, operator_id = ?"
                                    + " WHERE tenant_id = ? AND module_code = ?",
                            STATUS_CLOSED,
                            reason,
                            operatorId(),
                            tenantId,
                            moduleCode);
                    int refreshedUsers = bumpTenantPermissionVersions(tenantId, tenant);
                    insertLog(
                            tenantId,
                            moduleCode,
                            "CLOSE",
                            reason,
                            "收回菜单与接口（40301）；数据保留；刷新 " + refreshedUsers + " 个用户的权限版本");
                    audit.record(
                            new AuditEvent(
                                    "MODULE_CLOSE",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_tenant:" + str(tenant.get("code")) + ":" + moduleCode,
                                    abbreviate(
                                            previous
                                                    + "→CLOSED（数据保留，角色授权保留但不生效）"
                                                    + suffixReason(reason),
                                            DETAIL_MAX)));
                    return new ChangeResult(
                            tenantId,
                            str(tenant.get("code")),
                            moduleCode,
                            previous,
                            STATUS_CLOSED,
                            str(states.get(moduleCode).get("migration_version")));
                });
    }

    // ---------------------------------------------------------------- 模块级配置（PLT-MOD-008）

    public ModuleConfigView configOf(long tenantId, String moduleCode) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    requireModule(moduleCode);
                    Map<String, Map<String, Object>> overrides = configRows(tenantId, moduleCode);
                    List<ConfigValueView> items =
                            definitionOf(moduleCode).configItems().stream()
                                    .map(
                                            item -> {
                                                Map<String, Object> row =
                                                        overrides.get(item.code());
                                                String value =
                                                        row == null
                                                                ? null
                                                                : str(row.get("config_value"));
                                                return new ConfigValueView(
                                                        item.code(),
                                                        item.name(),
                                                        item.defaultValue(),
                                                        item.description(),
                                                        value,
                                                        value == null ? item.defaultValue() : value,
                                                        row == null
                                                                ? null
                                                                : offsetDateTime(
                                                                        row.get("updated_at")));
                                            })
                                    .toList();
                    return new ModuleConfigView(
                            tenantId, str(tenant.get("code")), moduleCode, items);
                });
    }

    public ModuleConfigView applyConfig(
            long tenantId, String moduleCode, ConfigUpdateRequest request) {
        Map<String, String> normalized =
                normalizeConfigValues(
                        request == null ? null : request.values(),
                        definitionOf(moduleCode).configItems());
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    requireModule(moduleCode);
                    Map<String, Map<String, Object>> states = tenantModuleRows(tenantId);
                    String current = stateStatus(states.get(moduleCode));
                    if (!STATUS_OPEN.equals(current)) {
                        throw new ApiException(409, 40901, "只有已开通的模块能修改配置（当前状态：" + current + "）");
                    }
                    Map<String, Map<String, Object>> existing = configRows(tenantId, moduleCode);
                    List<String> changed = new ArrayList<>();
                    for (Map.Entry<String, String> entry : normalized.entrySet()) {
                        String before =
                                existing.containsKey(entry.getKey())
                                        ? str(existing.get(entry.getKey()).get("config_value"))
                                        : null;
                        if (entry.getValue().equals(before)) {
                            continue;
                        }
                        platform.update(
                                "INSERT INTO plt_tenant_module_config"
                                        + " (tenant_id, module_code, config_code, config_value, updated_by)"
                                        + " VALUES (?, ?, ?, ?, ?)"
                                        + " ON DUPLICATE KEY UPDATE config_value = VALUES(config_value),"
                                        + " updated_by = VALUES(updated_by)",
                                tenantId,
                                moduleCode,
                                entry.getKey(),
                                entry.getValue(),
                                operatorId());
                        changed.add(
                                entry.getKey()
                                        + " "
                                        + (before == null ? "默认" : before)
                                        + "→"
                                        + entry.getValue());
                    }
                    if (!changed.isEmpty()) {
                        audit.record(
                                new AuditEvent(
                                        "MODULE_CONFIG",
                                        operatorId(),
                                        "UPDATE",
                                        "plt_tenant:" + str(tenant.get("code")) + ":" + moduleCode,
                                        abbreviate(String.join("；", changed), DETAIL_MAX)));
                    }
                    return configOf(tenantId, moduleCode);
                });
    }

    // ---------------------------------------------------------------- 开通记录（PLT-MOD-009）

    /** 开通 / 关闭 / 升级操作记录，倒序（最新在前）；tenantId 可选过滤。 */
    public List<OpenLogView> openLogs(String moduleCode, Long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    requireModule(moduleCode);
                    StringBuilder sql =
                            new StringBuilder(
                                    "SELECT l.id, l.tenant_id, l.action, l.reason, l.detail,"
                                            + " l.operator_id, l.created_at, t.code AS tenant_code,"
                                            + " t.name AS tenant_name"
                                            + " FROM plt_tenant_module_log l"
                                            + " JOIN plt_tenant t ON t.id = l.tenant_id"
                                            + " WHERE l.module_code = ?");
                    List<Object> args = new ArrayList<>();
                    args.add(moduleCode);
                    if (tenantId != null) {
                        sql.append(" AND l.tenant_id = ?");
                        args.add(tenantId);
                    }
                    sql.append(" ORDER BY l.id DESC");
                    return platform.query(
                            sql.toString(),
                            (rs, rowNum) ->
                                    new OpenLogView(
                                            rs.getLong("id"),
                                            rs.getLong("tenant_id"),
                                            rs.getString("tenant_code"),
                                            rs.getString("tenant_name"),
                                            moduleCode,
                                            rs.getString("action"),
                                            rs.getString("reason"),
                                            rs.getString("detail"),
                                            rs.getObject("operator_id") == null
                                                    ? null
                                                    : rs.getLong("operator_id"),
                                            offsetDateTime(rs.getTimestamp("created_at"))),
                            args.toArray());
                });
    }

    // ---------------------------------------------------------------- 开通编排接入（E02-T07 向导第 2 步）

    /**
     * 新建租户开通向导选定的模块（可重入）：base 必开，列表里逐个置 OPEN 并初始化配置与授权菜单。 迁移已由开通编排的 migrate-register
     * 步骤全量执行，这里不再重复跑脚本。
     */
    public List<String> openForOnboarding(long tenantId, List<String> moduleCodes) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        wanted.add("base");
        if (moduleCodes != null) {
            wanted.addAll(moduleCodes);
        }
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    Map<String, Map<String, Object>> states = tenantModuleRows(tenantId);
                    List<String> opened = new ArrayList<>();
                    for (String code : wanted) {
                        Map<String, Object> module = requireModule(code);
                        if (!"ON".equals(str(module.get("status")))) {
                            throw new ApiException(409, 40901, "模块已下架，不能再被新开通：" + code);
                        }
                        String current = stateStatus(states.get(code));
                        if (STATUS_OPEN.equals(current)) {
                            opened.add(code);
                            continue;
                        }
                        upsertState(tenantId, code, STATUS_OPEN, null);
                        String migrationVersion = str(module.get("migration_version"));
                        platform.update(
                                "UPDATE plt_tenant_module SET migration_version = ?,"
                                        + " opened_at = CURRENT_TIMESTAMP(3), closed_at = NULL,"
                                        + " reason = NULL WHERE tenant_id = ? AND module_code = ?",
                                migrationVersion,
                                tenantId,
                                code);
                        grantModuleMenusToTenantAdmin(tenantId, tenant, code);
                        seedConfigDefaults(tenantId, code);
                        insertLog(
                                tenantId,
                                code,
                                "OPEN",
                                null,
                                "新建租户开通（迁移版本 " + migrationVersion + "）");
                        opened.add(code);
                    }
                    audit.record(
                            new AuditEvent(
                                    "MODULE_OPEN",
                                    operatorId(),
                                    "INSERT",
                                    "plt_tenant:" + str(tenant.get("code")),
                                    "新建租户开通模块：" + String.join("、", opened)));
                    return opened;
                });
    }

    /** 重试续开（E02-T04 可重入）：向导未传模块清单时（失败重试），沿用上次已登记的 非关闭模块，避免重跑丢开通项。 */
    public List<String> reopenExistingForRetry(long tenantId) {
        List<String> existing =
                TenantContext.callAsPlatform(
                        () ->
                                platform.queryForList(
                                        "SELECT module_code FROM plt_tenant_module"
                                                + " WHERE tenant_id = ? AND status IN ('OPEN', 'OPENING',"
                                                + " 'OPEN_FAILED') ORDER BY module_code",
                                        String.class,
                                        tenantId));
        return openForOnboarding(
                tenantId, existing.stream().filter(code -> !"base".equals(code)).toList());
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /**
     * 开通校验（PLT-MOD-004、006）：租户可用（试用 / 启用 / 宽限期）、模块上架（下架不能再新开通）、 未处于已开通 / 开通中，依赖模块全部已开通（返回 40901
     * 带依赖说明）。base 无注册定义（种子数据），开通只补登记行、 不跑迁移；正常租户开通编排已保证 base 在册。
     */
    static void checkOpenAllowed(
            String tenantStatus,
            String shelfStatus,
            String currentStatus,
            List<String> dependsOn,
            Map<String, String> openStatuses) {
        if (!OPEN_ALLOWED_TENANT_STATUSES.contains(tenantStatus)) {
            throw new ApiException(409, 40901, "租户当前状态（" + tenantStatus + "）不能开通模块，请先恢复租户可用");
        }
        if (!"ON".equals(shelfStatus)) {
            throw new ApiException(409, 40901, "模块已下架，不能再被新开通");
        }
        if (STATUS_OPEN.equals(currentStatus)) {
            throw new ApiException(409, 40901, "模块已开通");
        }
        if (STATUS_OPENING.equals(currentStatus) || STATUS_MAINTENANCE.equals(currentStatus)) {
            throw new ApiException(
                    409,
                    40901,
                    "模块正在" + (STATUS_OPENING.equals(currentStatus) ? "开通中" : "维护中") + "，请稍后重试");
        }
        List<String> missing = missingDependencies(dependsOn, openStatuses);
        if (!missing.isEmpty()) {
            throw new ApiException(409, 40901, "依赖校验失败：须先开通依赖模块 " + String.join("、", missing));
        }
    }

    /** 关闭校验（PLT-MOD-005、006）：基础模块必选不可关闭、只有已开通能关闭、 被其他已开通模块依赖时先关依赖方。 */
    static void checkCloseAllowed(
            boolean baseModule, String currentStatus, List<String> dependentOpenModules) {
        if (baseModule) {
            throw new ApiException(409, 40901, "平台基础模块必选，不能关闭");
        }
        if (currentStatus == null) {
            throw new ApiException(409, 40901, "模块未开通，无需关闭");
        }
        if (!STATUS_OPEN.equals(currentStatus)) {
            throw new ApiException(409, 40901, "只有已开通的模块能关闭（当前状态：" + currentStatus + "）");
        }
        if (!dependentOpenModules.isEmpty()) {
            throw new ApiException(
                    409,
                    40901,
                    "依赖校验失败：模块仍被已开通模块 " + String.join("、", dependentOpenModules) + " 依赖，请先关闭它们");
        }
    }

    /** 依赖模块里未处于已开通状态的编码（保持声明顺序）。 */
    static List<String> missingDependencies(
            List<String> dependsOn, Map<String, String> openStatuses) {
        List<String> missing = new ArrayList<>();
        for (String dep : dependsOn) {
            if (!STATUS_OPEN.equals(openStatuses.get(dep))) {
                missing.add(dep);
            }
        }
        return missing;
    }

    /** 配置取值校验（PLT-MOD-008）：键必须是模块登记的配置项，值必填 ≤255。 */
    static Map<String, String> normalizeConfigValues(
            Map<String, String> values, List<ModuleDefinition.ConfigItem> items) {
        if (values == null || values.isEmpty()) {
            throw new ApiException(400, 40001, "配置取值不能为空");
        }
        Set<String> known = new LinkedHashSet<>();
        for (ModuleDefinition.ConfigItem item : items) {
            known.add(item.code());
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!known.contains(entry.getKey())) {
                throw new ApiException(400, 40001, "未知的模块配置项：" + entry.getKey());
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty()) {
                throw new ApiException(400, 40001, "配置项 " + entry.getKey() + " 的取值不能为空");
            }
            if (value.length() > 255) {
                throw new ApiException(400, 40001, "配置项 " + entry.getKey() + " 的取值过长（最多 255 个字符）");
            }
            normalized.put(entry.getKey(), value);
        }
        return normalized;
    }

    /** 关闭原因（PLT-MOD-005：二次确认并填写原因）：必填、去首尾空格、最长 200 字。 */
    static String reasonOf(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "必须填写关闭原因");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "关闭原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return trimmed;
    }

    /** 开通原因：可留空，填了则去首尾空格、最长 200 字（留痕）。 */
    static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "开通原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return trimmed;
    }

    // ---------------------------------------------------------------- 内部

    private ModuleDefinition definitionOf(String moduleCode) {
        // 未登记的模块（如 base）没有代码登记的配置项清单，返回空定义占位
        return registry.find(moduleCode)
                .orElseGet(
                        () ->
                                new ModuleDefinition(
                                        moduleCode,
                                        "",
                                        "",
                                        false,
                                        "",
                                        "",
                                        List.of(),
                                        null,
                                        List.of()));
    }

    private Map<String, Object> requireTenant(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, status FROM plt_tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> requireModule(String moduleCode) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT code, name, base_module, status, feature_version, migration_version,"
                                + " depends_on FROM plt_module WHERE code = ?",
                        moduleCode);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "模块不存在");
        }
        return rows.get(0);
    }

    private Map<String, Map<String, Object>> tenantModuleRows(long tenantId) {
        Map<String, Map<String, Object>> states = new HashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT module_code, status, migration_version, opened_at, closed_at, reason"
                                + " FROM plt_tenant_module WHERE tenant_id = ?",
                        tenantId)) {
            states.put(str(row.get("module_code")), row);
        }
        return states;
    }

    private Map<String, Map<String, Object>> configRows(long tenantId, String moduleCode) {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT config_code, config_value, updated_at FROM plt_tenant_module_config"
                                + " WHERE tenant_id = ? AND module_code = ?",
                        tenantId,
                        moduleCode)) {
            rows.put(str(row.get("config_code")), row);
        }
        return rows;
    }

    private TenantModuleView toView(Map<String, Object> module, Map<String, Object> state) {
        String code = str(module.get("code"));
        String dependsOn = str(module.get("depends_on"));
        return new TenantModuleView(
                code,
                str(module.get("name")),
                str(module.get("description")),
                ((Number) module.get("base_module")).intValue() == 1,
                str(module.get("status")),
                str(module.get("feature_version")),
                str(module.get("migration_version")),
                dependsOn.isEmpty() ? List.of() : List.of(dependsOn.split(",")),
                stateStatus(state),
                state == null ? "" : str(state.get("migration_version")),
                state == null ? null : offsetDateTime(state.get("opened_at")),
                state == null ? null : offsetDateTime(state.get("closed_at")),
                state == null ? null : strOrNull(state.get("reason")));
    }

    private static String stateStatus(Map<String, Object> state) {
        return state == null ? null : str(state.get("status"));
    }

    private Map<String, String> openStatuses(Map<String, Map<String, Object>> states) {
        Map<String, String> statuses = new HashMap<>();
        states.forEach((code, row) -> statuses.put(code, str(row.get("status"))));
        return statuses;
    }

    private static List<String> dependsOf(Map<String, Object> module) {
        String dependsOn = str(module.get("depends_on"));
        return dependsOn.isEmpty() ? List.of() : List.of(dependsOn.split(","));
    }

    /** 仍处于已开通状态且依赖该模块的模块编码（依赖声明取目录 depends_on）。 */
    private List<String> dependentOpenModules(
            String moduleCode, Map<String, Map<String, Object>> states) {
        List<String> dependents = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            if (!STATUS_OPEN.equals(stateStatus(entry.getValue()))) {
                continue;
            }
            List<String> dependsOn = dependsOf(requireModule(entry.getKey()));
            if (dependsOn.contains(moduleCode)) {
                dependents.add(entry.getKey());
            }
        }
        dependents.sort(String::compareTo);
        return dependents;
    }

    private void upsertState(long tenantId, String moduleCode, String status, String reason) {
        platform.update(
                "INSERT INTO plt_tenant_module (tenant_id, module_code, status, reason, operator_id)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE status = VALUES(status), reason = VALUES(reason),"
                        + " operator_id = VALUES(operator_id)",
                tenantId,
                moduleCode,
                status,
                reason,
                operatorId());
    }

    /** 迁移失败（PLT-MOD-004 第 2 步）：置开通失败、写开通记录与审计告警，向操作者返回 40901。 */
    private ChangeResult openFailed(
            long tenantId,
            Map<String, Object> tenant,
            String moduleCode,
            String previous,
            String reason,
            ApiException cause) {
        String failure = abbreviate(cause.getMessage(), REASON_MAX);
        upsertState(tenantId, moduleCode, STATUS_OPEN_FAILED, failure);
        insertLog(
                tenantId,
                moduleCode,
                "OPEN",
                reason,
                abbreviate("开通失败：" + cause.getMessage(), DETAIL_MAX));
        audit.record(
                new AuditEvent(
                        "MODULE_OPEN",
                        operatorId(),
                        "INSERT",
                        "plt_tenant:" + str(tenant.get("code")) + ":" + moduleCode,
                        abbreviate(
                                (previous == null ? "未开通" : previous)
                                        + "→"
                                        + STATUS_OPEN_FAILED
                                        + "（告警："
                                        + cause.getMessage()
                                        + "）",
                                DETAIL_MAX)));
        throw new ApiException(409, 40901, "模块开通失败：" + cause.getMessage());
    }

    private void runModuleMigration(long tenantId, String tenantCode, ModuleDefinition definition) {
        if (!routing.hasTenant(tenantId)) {
            throw new ApiException(409, 40901, "租户库未注册路由，请先完成租户开通");
        }
        TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () -> {
                    migrator.migrateTenantModule(routing.current(), definition.migrationLocation());
                    return null;
                });
    }

    /**
     * 模块菜单进入授权范围（PLT-MOD-004 第 3 步）：把该模块医院端菜单 / 按钮授予租户库 TENANT_ADMIN（可分配范围上限是 E05-T07）；INSERT
     * IGNORE 幂等，关闭时不回收（授权保留但不生效，重新开通自动恢复）。
     */
    private int grantModuleMenusToTenantAdmin(
            long tenantId, Map<String, Object> tenant, String moduleCode) {
        List<Long> menuIds =
                platform.queryForList(
                        "SELECT id FROM plt_menu WHERE module_code = ? AND side = 'HOSPITAL'"
                                + " AND status = 'ENABLED'",
                        Long.class,
                        moduleCode);
        if (menuIds.isEmpty()) {
            return 0;
        }
        return TenantContext.callAsTenant(
                tenantId,
                str(tenant.get("code")),
                () -> {
                    List<Long> roles =
                            platform.queryForList(
                                    "SELECT id FROM sys_role WHERE code = ?",
                                    Long.class,
                                    TENANT_ADMIN_ROLE);
                    if (roles.isEmpty()) {
                        // 首个管理员可跳过（启用前补设），角色不存在时无可授予对象；重开 / 后续开通会再补
                        return 0;
                    }
                    long roleId = roles.get(0);
                    int granted = 0;
                    for (Long menuId : menuIds) {
                        granted +=
                                platform.update(
                                        "INSERT IGNORE INTO sys_role_menu (role_id, menu_id)"
                                                + " VALUES (?, ?)",
                                        roleId,
                                        menuId);
                    }
                    return granted;
                });
    }

    /** 初始化模块配置默认值（PLT-MOD-004 第 4 步 / PLT-MOD-008）：已有租户覆盖值不覆盖。 */
    private int seedConfigDefaults(long tenantId, String moduleCode) {
        ModuleDefinition definition = registry.find(moduleCode).orElse(null);
        if (definition == null || definition.configItems().isEmpty()) {
            return 0;
        }
        int seeded = 0;
        for (ModuleDefinition.ConfigItem item : definition.configItems()) {
            seeded +=
                    platform.update(
                            "INSERT IGNORE INTO plt_tenant_module_config"
                                    + " (tenant_id, module_code, config_code, config_value)"
                                    + " VALUES (?, ?, ?, ?)",
                            tenantId,
                            moduleCode,
                            item.code(),
                            item.defaultValue());
        }
        return seeded;
    }

    /** 递增该租户全部用户的权限版本（E04-T06）：在线会话下一次请求重算 openedModules，菜单与模块接口立即生效。 */
    private int bumpTenantPermissionVersions(long tenantId, Map<String, Object> tenant) {
        if (!routing.hasTenant(tenantId)) {
            return 0;
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    str(tenant.get("code")),
                    () -> {
                        List<Long> userIds =
                                platform.queryForList("SELECT id FROM sys_user", Long.class);
                        for (Long userId : userIds) {
                            permissionVersions.bump("HOSPITAL", tenantId, userId);
                        }
                        return userIds.size();
                    });
        } catch (UnknownTenantDataSourceException | DataAccessException ex) {
            // 租户库暂不可达：菜单收回在会话重算 / 下次登录时生效，不阻塞开通 / 关闭本身
            return 0;
        }
    }

    /** 进行中随访任务数（关闭影响评估）：fu_task 表随 E15 提供，未建或租户库不可达时返回空。 */
    private Long pendingTaskCount(long tenantId, String tenantCode) {
        if (!routing.hasTenant(tenantId)) {
            return null;
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    tenantCode,
                    () -> {
                        Integer present =
                                platform.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.TABLES"
                                                + " WHERE TABLE_SCHEMA = DATABASE()"
                                                + " AND TABLE_NAME = 'fu_task'",
                                        Integer.class);
                        if (present == null || present == 0) {
                            return null;
                        }
                        return platform.queryForObject(
                                "SELECT COUNT(*) FROM fu_task"
                                        + " WHERE status NOT IN ('DONE', 'CLOSED', 'CANCELLED')",
                                Long.class);
                    });
        } catch (UnknownTenantDataSourceException | DataAccessException ex) {
            return null;
        }
    }

    private void insertLog(
            long tenantId, String moduleCode, String action, String reason, String detail) {
        platform.update(
                "INSERT INTO plt_tenant_module_log"
                        + " (tenant_id, module_code, action, reason, detail, operator_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                tenantId,
                moduleCode,
                action,
                reason,
                detail,
                operatorId());
    }

    private static Long operatorId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static String suffixReason(String reason) {
        return reason == null ? "" : "；原因：" + reason;
    }

    static String abbreviate(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static OffsetDateTime offsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        LocalDateTime local =
                value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
        return local.atZone(ZONE).toOffsetDateTime();
    }
}
