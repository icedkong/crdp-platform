package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.module.ModuleDefinition;
import com.crdp.platform.module.ModuleRegistry;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 模块目录（E05-T04 / PLT-MOD-001、002、003）：展示模块编码、名称、说明、版本（功能 / 迁移）、依赖、菜单清单、
 * 权限码清单、配置项与版本历史；目录数据来自平台库（plt_module / plt_menu / plt_module_version），配置项清单来自 代码登记（{@link
 * ModuleRegistry}）。展示信息（名称、说明）与上架状态由平台端维护，变更写 MODULE_UPDATE 审计。
 */
@Service
public class ModuleCatalogService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 上架状态（PLT-MOD-001 关键字段：上架 / 下架；下架后不能再被新开通）。 */
    private static final Set<String> SHELF_STATUSES = Set.of("ON", "OFF");

    private final JdbcTemplate platform;
    private final ModuleRegistry registry;
    private final AuditLogger audit;

    public ModuleCatalogService(JdbcTemplate platform, ModuleRegistry registry, AuditLogger audit) {
        this.platform = platform;
        this.registry = registry;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 模块的菜单清单（随发版由迁移登记进 plt_menu，module_code 即归属模块）。 */
    public record MenuRef(long id, String side, String type, String title) {}

    /** 模块配置项（PLT-MOD-001 目录展示；租户级取值是 PLT-MOD-008）。 */
    public record ConfigItemView(
            String code, String name, String defaultValue, String description) {}

    /** 版本记录（PLT-MOD-003）：功能版本、迁移版本与变更说明，倒序（最新在前）。 */
    public record VersionView(
            String featureVersion,
            String migrationVersion,
            String changeNote,
            OffsetDateTime createdAt) {}

    /** 目录行（PLT-MOD-001）。 */
    public record ModuleView(
            String code,
            String name,
            String description,
            boolean baseModule,
            String status,
            String featureVersion,
            String migrationVersion,
            List<String> dependsOn,
            OffsetDateTime syncedAt,
            List<MenuRef> menus,
            List<String> permissions,
            List<ConfigItemView> configItems,
            List<VersionView> versions) {}

    /** 维护展示信息与上下架（PLT-MOD-002）：只允许改名称、说明、上架状态。 */
    public record UpdateRequest(String name, String description, String status) {}

    // ---------------------------------------------------------------- 查询

    public List<ModuleView> list() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<Map<String, Object>> rows =
                            platform.queryForList(
                                    "SELECT code, name, description, base_module, status,"
                                            + " feature_version, migration_version, depends_on,"
                                            + " synced_at FROM plt_module ORDER BY base_module DESC, code");
                    Map<String, List<MenuRef>> menus = menusByModule();
                    Map<String, List<String>> permissions = permissionsByModule();
                    Map<String, List<VersionView>> versions = versionsByModule();
                    return rows.stream()
                            .map(
                                    row ->
                                            toView(
                                                    row,
                                                    menus.getOrDefault(
                                                            str(row.get("code")), List.of()),
                                                    permissions.getOrDefault(
                                                            str(row.get("code")), List.of()),
                                                    versions.getOrDefault(
                                                            str(row.get("code")), List.of())))
                            .toList();
                });
    }

    // ---------------------------------------------------------------- 维护

    public ModuleView update(String code, UpdateRequest request) {
        validate(request);
        return TenantContext.callAsPlatform(
                () -> {
                    List<Map<String, Object>> rows =
                            platform.queryForList(
                                    "SELECT code, name, description, base_module, status,"
                                            + " feature_version, migration_version, depends_on,"
                                            + " synced_at FROM plt_module WHERE code = ?",
                                    code);
                    if (rows.isEmpty()) {
                        throw new ApiException(404, 40400, "模块不存在");
                    }
                    Map<String, Object> current = rows.get(0);
                    String name = request.name().trim();
                    String description =
                            request.description() == null ? "" : request.description().trim();
                    String status =
                            request.status() == null
                                    ? str(current.get("status"))
                                    : request.status();

                    if (((Number) current.get("base_module")).intValue() == 1
                            && "OFF".equals(status)) {
                        throw new ApiException(409, 40901, "平台基础模块必选，不能下架");
                    }

                    List<String> changed = new ArrayList<>();
                    if (!name.equals(str(current.get("name")))) {
                        changed.add("name");
                    }
                    if (!description.equals(str(current.get("description")))) {
                        changed.add("description");
                    }
                    if (!status.equals(str(current.get("status")))) {
                        changed.add("status");
                    }

                    if (!changed.isEmpty()) {
                        platform.update(
                                "UPDATE plt_module SET name = ?, description = ?, status = ?"
                                        + " WHERE code = ?",
                                name,
                                description,
                                status,
                                code);
                        audit.record(
                                new AuditEvent(
                                        "MODULE_UPDATE",
                                        CurrentSession.current()
                                                .map(principal -> principal.userId())
                                                .orElse(null),
                                        "UPDATE",
                                        "plt_module:" + code,
                                        "上架状态 "
                                                + str(current.get("status"))
                                                + "→"
                                                + status
                                                + "；修改字段："
                                                + String.join(",", changed)));
                    }
                    return list().stream()
                            .filter(view -> view.code().equals(code))
                            .findFirst()
                            .orElseThrow();
                });
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    static void validate(UpdateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(400, 40001, "模块名称必填");
        }
        if (request.name().trim().length() > 64) {
            throw new ApiException(400, 40001, "模块名称过长（最多 64 个字符）");
        }
        if (request.description() != null && request.description().trim().length() > 255) {
            throw new ApiException(400, 40001, "模块说明过长（最多 255 个字符）");
        }
        if (request.status() != null && !SHELF_STATUSES.contains(request.status())) {
            throw new ApiException(400, 40001, "未知的上架状态：" + request.status());
        }
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, List<MenuRef>> menusByModule() {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, module_code, side, type, title FROM plt_menu"
                                + " WHERE status = 'ENABLED' ORDER BY side, sort_no, id");
        Map<String, List<MenuRef>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(str(row.get("module_code")), key -> new ArrayList<>())
                    .add(
                            new MenuRef(
                                    ((Number) row.get("id")).longValue(),
                                    str(row.get("side")),
                                    str(row.get("type")),
                                    str(row.get("title"))));
        }
        return grouped;
    }

    private Map<String, List<String>> permissionsByModule() {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT DISTINCT module_code, perm_code FROM plt_menu"
                                + " WHERE perm_code IS NOT NULL AND status = 'ENABLED'"
                                + " ORDER BY module_code, perm_code");
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(str(row.get("module_code")), key -> new ArrayList<>())
                    .add(str(row.get("perm_code")));
        }
        return grouped;
    }

    private Map<String, List<VersionView>> versionsByModule() {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT module_code, feature_version, migration_version, change_note,"
                                + " created_at FROM plt_module_version ORDER BY module_code, id DESC");
        Map<String, List<VersionView>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(str(row.get("module_code")), key -> new ArrayList<>())
                    .add(
                            new VersionView(
                                    str(row.get("feature_version")),
                                    str(row.get("migration_version")),
                                    str(row.get("change_note")),
                                    offsetDateTime(row.get("created_at"))));
        }
        return grouped;
    }

    private ModuleView toView(
            Map<String, Object> row,
            List<MenuRef> menus,
            List<String> permissions,
            List<VersionView> versions) {
        String code = str(row.get("code"));
        List<ModuleDefinition.ConfigItem> configItems =
                registry.find(code).map(ModuleDefinition::configItems).orElse(List.of());
        String dependsOn = str(row.get("depends_on"));
        return new ModuleView(
                code,
                str(row.get("name")),
                str(row.get("description")),
                ((Number) row.get("base_module")).intValue() == 1,
                str(row.get("status")),
                str(row.get("feature_version")),
                str(row.get("migration_version")),
                dependsOn.isEmpty() ? List.of() : List.of(dependsOn.split(",")),
                offsetDateTime(row.get("synced_at")),
                menus,
                permissions,
                configItems.stream()
                        .map(
                                item ->
                                        new ConfigItemView(
                                                item.code(),
                                                item.name(),
                                                item.defaultValue(),
                                                item.description()))
                        .toList(),
                versions);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
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
