package com.crdp.server.configtemplate;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.configtemplate.ConfigCopyMode;
import com.crdp.platform.configtemplate.ConfigImportMode;
import com.crdp.platform.configtemplate.ConfigImportSpec;
import com.crdp.platform.configtemplate.ConfigImportSpecs;
import com.crdp.platform.configtemplate.ConfigTableEntry;
import com.crdp.platform.configtemplate.ConfigTableRegistry;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配置模板导入（E12-T02 / A-FLD-014，验收「导入后引用关系正确」，docs/api/platform.md §7.1）。
 *
 * <p>把 {@code ConfigTemplateExportService} 导出的配置包应用到目标租户库（典型：新建租户选择模板初始化，医院 B 由医院 A 模板初始化）：
 *
 * <ul>
 *   <li><b>ID 重映射</b>：每张 COPY 表按自然键 upsert，插入行由目标库自增分配新 ID，逐表维护「源 ID → 目标 ID」映射并改写数值引用列
 *       （sys_role_menu / sys_role_scope / sys_role_field_policy 的 role_id、fe_form_version_field 的
 *       version_id、 fu_plan_node 的 plan_id 与 parent_id 自引用）；引用解析不到目标行的行丢弃并在结果中计数；
 *   <li><b>用户引用置空</b>：created_by / updated_by / published_by 一律置 NULL——账号不随模板复制（A-FLD-014），不留源租户用户
 *       ID 的悬空引用；
 *   <li><b>角色权限取交集</b>：sys_role_menu 的 menu_id 须落在目标租户生效可分配范围（{@code
 *       TenantScopeService.assignableMenuIds}， = base 固定能力 ∪ 模板调整 ∩
 *       已开通模块）内，超出（模块未开通、敏感未放开、菜单不存在）丢弃；
 *   <li><b>合并语义（源值胜，重放幂等）</b>：自然键相同的行用包内值更新；sys_role_menu 取并集（INSERT IGNORE）； fu_plan_node
 *       无自然键，按计划版本整组替换（先删后插），重放不叠加重复节点；
 *   <li><b>整表策略</b>：sys_role_scope_dept 行全部丢弃（sys_dept 组织架构不在 A-FLD-014 复制清单，dept_id 无法重映射）；
 *       STRUCTURE_ONLY（术者、病区 / 中心）与 present=false / 目标库缺表的节跳过。
 * </ul>
 *
 * <p>导入在目标租户库单个事务内执行（全成或全败，不留断链的中间态）；平台库读取（包、租户、可分配范围）全部在事务外完成——事务内只碰目标租户库。导入后递增该租户全部用户的权限版本（角色授权可能变化，在线会话下次请求重算）。写
 * CONFIG_TEMPLATE_APPLY 审计。包含 / 排除规则与导出侧敏感信息检查随 E12-T03。
 */
@Service
public class ConfigTemplateImportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 应用请求：tenantId = 目标租户（不能是模板源租户）。 */
    public record ApplyRequest(Long tenantId) {}

    /** 单表导入结果。 */
    public record TableResult(
            String table,
            String mode,
            boolean imported,
            long packageRows,
            long inserted,
            long merged,
            long skipped,
            long dropped,
            String note) {

        static TableResult skip(String table, String mode, long packageRows, String note) {
            return new TableResult(table, mode, false, packageRows, 0, 0, packageRows, 0, note);
        }
    }

    /** 应用结果：模板与目标租户头信息 + 总计数 + 分表明细。 */
    public record AppliedView(
            long templateId,
            String templateName,
            int templateVersion,
            String templateHash,
            long sourceTenantId,
            String sourceTenantCode,
            long tenantId,
            String tenantCode,
            String tenantName,
            String appliedAt,
            long insertedRows,
            long mergedRows,
            long skippedRows,
            long droppedRows,
            List<TableResult> tables) {}

    /** 导入执行汇总（内部）。 */
    private record Outcome(
            List<TableResult> tables, long inserted, long merged, long skipped, long dropped) {

        long changedRows() {
            return inserted + merged;
        }
    }

    /** 单行计数器（内部）。 */
    private static final class Counts {
        long inserted;
        long merged;
        long skipped;
        long dropped;

        void add(Counts other) {
            inserted += other.inserted;
            merged += other.merged;
            skipped += other.skipped;
            dropped += other.dropped;
        }
    }

    private final JdbcTemplate jdbc;
    private final TenantConfigReader reader;
    private final ConfigTemplateExportService exporter;
    private final com.crdp.server.platform.TenantScopeService tenantScopes;
    private final TenantRoutingDataSource routing;
    private final AuditLogger audit;
    private final PermissionVersionStore permissionVersions;
    private final TransactionTemplate transactions;

    public ConfigTemplateImportService(
            JdbcTemplate jdbc,
            TenantConfigReader reader,
            ConfigTemplateExportService exporter,
            com.crdp.server.platform.TenantScopeService tenantScopes,
            TenantRoutingDataSource routing,
            AuditLogger audit,
            PermissionVersionStore permissionVersions,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.exporter = exporter;
        this.tenantScopes = tenantScopes;
        this.routing = routing;
        this.audit = audit;
        this.permissionVersions = permissionVersions;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- 应用入口

    /** 把模板 {@code templateId} 应用到目标租户（platform:tenant:create）。 */
    public AppliedView apply(long templateId, ApplyRequest request) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        if (request == null || request.tenantId() == null) {
            throw new ApiException(400, 40001, "tenantId 必填");
        }
        // ---- 平台库读取（导入事务外：事务内连接绑定目标租户库，不能再切平台库）----
        ConfigTemplateExportService.ExportedView head = exporter.headerOf(templateId);
        Map<String, Object> tenant = tenantRow(request.tenantId());
        String tenantCode = String.valueOf(tenant.get("code"));
        String tenantName = String.valueOf(tenant.get("name"));
        if (head.tenantId() == request.tenantId()) {
            throw new ApiException(
                    400, 40001, "目标租户不能是模板源租户（" + head.tenantCode() + "）：配置模板用于初始化其它租户");
        }
        if (!routing.hasTenant(request.tenantId())) {
            throw new ApiException(409, 40901, "租户数据源未注册（未开通或已注销），无法导入配置：" + tenantCode);
        }
        ConfigPackage pkg = exporter.packageOf(templateId);
        if (pkg.formatVersion() != ConfigTemplateExportService.FORMAT_VERSION) {
            throw new ApiException(
                    409,
                    40901,
                    "配置包结构版本不支持：包 v"
                            + pkg.formatVersion()
                            + "，当前 v"
                            + ConfigTemplateExportService.FORMAT_VERSION);
        }
        Set<Long> assignable = tenantScopes.assignableMenuIds(request.tenantId());

        // ---- 目标租户库导入：单事务全成或全败 ----
        Outcome outcome =
                TenantContext.callAsTenant(
                        request.tenantId(),
                        tenantCode,
                        () -> transactions.execute(status -> importAll(pkg, assignable)));

        // 角色授权可能变化：递增该租户全部用户权限版本（失败不回滚导入，下次登录重算）
        int refreshed = bumpPermissionVersions(request.tenantId(), tenantCode);
        String appliedAt = OffsetDateTime.now(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        audit.append(
                AuditEntry.operation("platform", "CONFIG_TEMPLATE_APPLY")
                        .tenantId(request.tenantId())
                        .objectType("plt_config_template")
                        .objectId(String.valueOf(templateId))
                        .description(
                                "将 "
                                        + head.tenantCode()
                                        + " 配置模板 v"
                                        + head.version()
                                        + " 应用到租户 "
                                        + tenantCode
                                        + "：表 "
                                        + outcome.tables().size()
                                        + " 张 / 插入 "
                                        + outcome.inserted()
                                        + " 行 / 合并 "
                                        + outcome.merged()
                                        + " 行 / 跳过 "
                                        + outcome.skipped()
                                        + " 行 / 丢弃 "
                                        + outcome.dropped()
                                        + " 行 / 刷新 "
                                        + refreshed
                                        + " 个用户权限版本")
                        .recordCount(outcome.changedRows())
                        .fileHash(head.packageHash())
                        .success()
                        .build());
        return new AppliedView(
                templateId,
                head.name(),
                head.version(),
                head.packageHash(),
                head.tenantId(),
                head.tenantCode(),
                request.tenantId(),
                tenantCode,
                tenantName,
                appliedAt,
                outcome.inserted(),
                outcome.merged(),
                outcome.skipped(),
                outcome.dropped(),
                outcome.tables());
    }

    // ---------------------------------------------------------------- 导入主流程（目标租户上下文内）

    private Outcome importAll(ConfigPackage pkg, Set<Long> assignable) {
        Map<String, Map<Long, Long>> idMaps = new LinkedHashMap<>();
        List<TableResult> tables = new ArrayList<>();
        for (ConfigPackage.TableSection section : pkg.tables()) {
            tables.add(importSection(section, idMaps, assignable));
        }
        long inserted = tables.stream().mapToLong(TableResult::inserted).sum();
        long merged = tables.stream().mapToLong(TableResult::merged).sum();
        long skipped = tables.stream().mapToLong(TableResult::skipped).sum();
        long dropped = tables.stream().mapToLong(TableResult::dropped).sum();
        return new Outcome(tables, inserted, merged, skipped, dropped);
    }

    private TableResult importSection(
            ConfigPackage.TableSection section,
            Map<String, Map<Long, Long>> idMaps,
            Set<Long> assignable) {
        String table = section.table();
        long packageRows = section.rows() == null ? 0 : section.rows().size();
        ConfigImportSpec spec = ConfigImportSpecs.of(table);
        ConfigTableEntry entry = ConfigTableRegistry.entry(table);
        if (entry == null) {
            // 包来自更新 / 更旧的代码版本：未登记的表一律不导不进
            return TableResult.skip(table, "UNKNOWN", packageRows, "表不在配置表注册表内（包与代码版本不一致），未导入");
        }
        String mode = entry.mode().name();
        if (entry.mode() == ConfigCopyMode.STRUCTURE_ONLY) {
            return new TableResult(
                    table, mode, true, 0, 0, 0, 0, 0, "只复制结构（A-FLD-014）：表结构由目标租户库迁移保证，不落行");
        }
        if (spec == null) {
            return TableResult.skip(table, mode, packageRows, "表缺少导入规格（包与代码版本不一致），未导入");
        }
        if (!section.present()) {
            return new TableResult(
                    table, mode, true, 0, 0, 0, 0, 0, "源租户未开通该表所属模块（present=false），包内无行");
        }
        if (spec.mode() == ConfigImportMode.DROP_ROWS) {
            return new TableResult(
                    table,
                    mode,
                    true,
                    packageRows,
                    0,
                    0,
                    0,
                    packageRows,
                    "sys_dept 组织架构不随模板复制（A-FLD-014），科室范围行无法重映射，全部丢弃");
        }
        if (packageRows == 0) {
            return new TableResult(table, mode, true, 0, 0, 0, 0, 0, "包内无行");
        }
        if (!reader.tablePresent(table)) {
            return TableResult.skip(table, mode, packageRows, "目标租户库不存在该表（模块未开通），未导入");
        }
        // 列交集：包列 ∩ 目标库列（迁移差异容忍；表名、列名都来自注册表与 information_schema）
        Set<String> targetColumns = new LinkedHashSet<>(reader.columns(table));
        List<String> columns =
                section.columns().stream()
                        .filter(targetColumns::contains)
                        .collect(Collectors.toList());
        List<String> missing =
                section.columns().stream()
                        .filter(c -> !targetColumns.contains(c))
                        .collect(Collectors.toList());
        String note = missing.isEmpty() ? "" : "目标库缺列已跳过：" + String.join("、", missing);
        if (spec.mode() != ConfigImportMode.REPLACE_ROWS
                && !columns.containsAll(spec.keyColumns())) {
            return TableResult.skip(
                    table,
                    mode,
                    packageRows,
                    "自然键列（" + String.join("、", spec.keyColumns()) + "）在目标库缺失，未导入");
        }
        Counts counts = new Counts();
        switch (spec.mode()) {
            case COPY_ROWS -> copyRows(section, spec, columns, idMaps, counts);
            case LINK_ROWS -> linkRows(section, spec, columns, idMaps, assignable, counts);
            case SINGLE_ROW -> singleRow(section, spec, columns, idMaps, counts);
            case REPLACE_ROWS -> replaceRows(section, spec, columns, idMaps, counts);
            default -> counts.skipped += packageRows;
        }
        return new TableResult(
                table,
                mode,
                true,
                packageRows,
                counts.inserted,
                counts.merged,
                counts.skipped,
                counts.dropped,
                note);
    }

    /** 常规 COPY 表：自然键 upsert（不存在插入新 ID，存在用包值更新），并登记 ID 映射。 */
    private void copyRows(
            ConfigPackage.TableSection section,
            ConfigImportSpec spec,
            List<String> columns,
            Map<String, Map<Long, Long>> idMaps,
            Counts counts) {
        String table = section.table();
        List<String> keyColumns = spec.keyColumns();
        Map<String, Long> existing = loadExistingIds(table, keyColumns);
        List<String> insertColumns =
                columns.stream().filter(c -> !"id".equals(c)).collect(Collectors.toList());
        List<String> updateColumns =
                insertColumns.stream()
                        .filter(c -> !keyColumns.contains(c))
                        .collect(Collectors.toList());
        for (Map<String, Object> row : section.rows()) {
            String dropReason = prepareRow(row, spec, idMaps);
            if (dropReason != null) {
                counts.dropped++;
                continue;
            }
            String key = keyOf(row, keyColumns);
            Long targetId = existing.get(key);
            if (targetId == null) {
                targetId = insertReturningId(table, insertColumns, row);
                counts.inserted++;
            } else {
                if (!updateColumns.isEmpty()) {
                    updateByKey(table, updateColumns, keyColumns, row);
                }
                counts.merged++;
            }
            Long sourceId = asLong(row.get("id"));
            if (sourceId != null) {
                idMaps.computeIfAbsent(table, t -> new LinkedHashMap<>()).put(sourceId, targetId);
            }
        }
    }

    /** 关联表（sys_role_menu）：引用解析 + 可分配范围交集后 INSERT IGNORE（并集、幂等）。 */
    private void linkRows(
            ConfigPackage.TableSection section,
            ConfigImportSpec spec,
            List<String> columns,
            Map<String, Map<Long, Long>> idMaps,
            Set<Long> assignable,
            Counts counts) {
        String table = section.table();
        for (Map<String, Object> row : section.rows()) {
            Long roleId = asLong(row.get("role_id"));
            Long mappedRole =
                    roleId == null ? null : idMaps.getOrDefault("sys_role", Map.of()).get(roleId);
            if (mappedRole == null) {
                counts.dropped++;
                continue;
            }
            Long menuId = asLong(row.get("menu_id"));
            if (menuId == null) {
                counts.dropped++;
                continue;
            }
            if (spec.menuRangeFilter() && !assignable.contains(menuId)) {
                // 角色权限与新租户可分配范围取交集（tech-plan §6）：模块未开通 / 敏感未放开 / 菜单不存在的授权丢弃
                counts.dropped++;
                continue;
            }
            int affected =
                    jdbc.update(
                            "INSERT IGNORE INTO `" + table + "` (role_id, menu_id) VALUES (?, ?)",
                            mappedRole,
                            menuId);
            if (affected == 1) {
                counts.inserted++;
            } else {
                counts.merged++;
            }
        }
    }

    /** 单行表（ntf_setting）：带显式 id 的 upsert（固定主键，不参与自增重映射）。 */
    private void singleRow(
            ConfigPackage.TableSection section,
            ConfigImportSpec spec,
            List<String> columns,
            Map<String, Map<Long, Long>> idMaps,
            Counts counts) {
        String table = section.table();
        List<String> upsertColumns =
                columns.stream().filter(c -> !"id".equals(c)).collect(Collectors.toList());
        for (Map<String, Object> row : section.rows()) {
            String dropReason = prepareRow(row, spec, idMaps);
            if (dropReason != null) {
                counts.dropped++;
                continue;
            }
            Long id = asLong(row.get("id"));
            if (id == null || upsertColumns.isEmpty()) {
                counts.skipped++;
                continue;
            }
            String setters =
                    upsertColumns.stream()
                            .map(c -> "`" + c + "` = VALUES(`" + c + "`)")
                            .collect(Collectors.joining(", "));
            Object[] values = upsertColumns.stream().map(row::get).toArray(Object[]::new);
            Object[] args = new Object[values.length + 1];
            args[0] = id;
            System.arraycopy(values, 0, args, 1, values.length);
            int affected =
                    jdbc.update(
                            "INSERT INTO `"
                                    + table
                                    + "` (`id`, "
                                    + quote(upsertColumns)
                                    + ") VALUES ("
                                    + placeholders(values.length + 1)
                                    + ") ON DUPLICATE KEY UPDATE "
                                    + setters,
                            args);
            if (affected == 1) {
                counts.inserted++;
            } else {
                counts.merged++;
            }
            idMaps.computeIfAbsent(table, t -> new LinkedHashMap<>()).put(id, id);
        }
    }

    /** 无自然键的子行表（fu_plan_node）：按父引用（plan_id）分组整组替换——先删目标既有行再插包内行，重放不叠加。 */
    private void replaceRows(
            ConfigPackage.TableSection section,
            ConfigImportSpec spec,
            List<String> columns,
            Map<String, Map<Long, Long>> idMaps,
            Counts counts) {
        String table = section.table();
        String groupColumn =
                spec.references().entrySet().stream()
                        .filter(e -> !e.getValue().equals(table))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("REPLACE_ROWS 缺少分组引用: " + table));
        List<String> insertColumns =
                columns.stream().filter(c -> !"id".equals(c)).collect(Collectors.toList());
        Map<Long, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : section.rows()) {
            Long groupId = asLong(row.get(groupColumn));
            groups.computeIfAbsent(groupId, g -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<Long, List<Map<String, Object>>> group : groups.entrySet()) {
            Long sourceGroupId = group.getKey();
            if (sourceGroupId == null) {
                counts.dropped += group.getValue().size();
                continue;
            }
            String referencedTable = spec.references().get(groupColumn);
            Long targetGroupId = idMaps.getOrDefault(referencedTable, Map.of()).get(sourceGroupId);
            if (targetGroupId == null) {
                counts.dropped += group.getValue().size();
                continue;
            }
            counts.merged +=
                    jdbc.update(
                            "DELETE FROM `" + table + "` WHERE `" + groupColumn + "` = ?",
                            targetGroupId);
            // 两遍插入：先父行（自引用为空），再子行（自引用按第一遍的映射改写）
            String selfReference = selfReferenceColumn(spec, table);
            for (boolean childPass : List.of(false, true)) {
                for (Map<String, Object> row : group.getValue()) {
                    boolean isChild =
                            selfReference != null && asLong(row.get(selfReference)) != null;
                    if (isChild != childPass) {
                        continue;
                    }
                    String dropReason = prepareRow(row, spec, idMaps);
                    if (dropReason != null) {
                        counts.dropped++;
                        continue;
                    }
                    long newId = insertReturningId(table, insertColumns, row);
                    counts.inserted++;
                    Long sourceId = asLong(row.get("id"));
                    if (sourceId != null) {
                        idMaps.computeIfAbsent(table, t -> new LinkedHashMap<>())
                                .put(sourceId, newId);
                    }
                }
            }
        }
    }

    /** REPLACE_ROWS 的自引用列（如 fu_plan_node.parent_id）；无自引用返回 null。 */
    private static String selfReferenceColumn(ConfigImportSpec spec, String table) {
        return spec.references().entrySet().stream()
                .filter(e -> e.getValue().equals(table))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- 行级纯逻辑（供单测）

    /**
     * 行导入前的变换（纯函数，直接改写传入行）：用户引用列置 NULL；数值引用列按「源 ID → 目标 ID」映射改写。
     *
     * @return 丢弃原因；null = 行可导入
     */
    static String prepareRow(
            Map<String, Object> row, ConfigImportSpec spec, Map<String, Map<Long, Long>> idMaps) {
        for (String column : ConfigImportSpecs.USER_REFERENCE_COLUMNS) {
            if (row.containsKey(column)) {
                row.put(column, null);
            }
        }
        for (Map.Entry<String, String> reference : spec.references().entrySet()) {
            Long sourceId = asLong(row.get(reference.getKey()));
            if (sourceId == null) {
                continue;
            }
            Long targetId = idMaps.getOrDefault(reference.getValue(), Map.of()).get(sourceId);
            if (targetId == null) {
                return reference.getKey()
                        + "="
                        + sourceId
                        + " 在目标租户无对应行（"
                        + reference.getValue()
                        + "）";
            }
            row.put(reference.getKey(), targetId);
        }
        return null;
    }

    // ---------------------------------------------------------------- SQL 帮助

    private Map<String, Long> loadExistingIds(String table, List<String> keyColumns) {
        List<String> select = new ArrayList<>(List.of("id"));
        select.addAll(keyColumns);
        Map<String, Long> existing = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList("SELECT " + quote(select) + " FROM `" + table + "`")) {
            Long id = asLong(row.get("id"));
            if (id == null) {
                continue;
            }
            existing.put(keyOf(row, keyColumns), id);
        }
        return existing;
    }

    private Long insertReturningId(String table, List<String> columns, Map<String, Object> row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String sql =
                "INSERT INTO `"
                        + table
                        + "` ("
                        + quote(columns)
                        + ") VALUES ("
                        + placeholders(columns.size())
                        + ")";
        jdbc.update(
                (PreparedStatementCreator)
                        connection -> {
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            sql, Statement.RETURN_GENERATED_KEYS);
                            for (int i = 0; i < columns.size(); i++) {
                                statement.setObject(i + 1, row.get(columns.get(i)));
                            }
                            return statement;
                        },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取到 " + table + " 自增主键");
        }
        return key.longValue();
    }

    private void updateByKey(
            String table,
            List<String> updateColumns,
            List<String> keyColumns,
            Map<String, Object> row) {
        String assignments =
                updateColumns.stream()
                        .map(c -> "`" + c + "` = ?")
                        .collect(Collectors.joining(", "));
        String condition =
                keyColumns.stream()
                        .map(c -> "`" + c + "` = ?")
                        .collect(Collectors.joining(" AND "));
        List<Object> args = new ArrayList<>();
        updateColumns.forEach(c -> args.add(row.get(c)));
        keyColumns.forEach(c -> args.add(row.get(c)));
        jdbc.update(
                "UPDATE `" + table + "` SET " + assignments + " WHERE " + condition,
                args.toArray());
    }

    /** 自然键归一化文本：Number 统一按 long、Boolean 按 0/1、null 用占位符，避免包装类型差异误判不等。 */
    static String keyOf(Map<String, Object> row, List<String> keyColumns) {
        StringBuilder key = new StringBuilder();
        for (String column : keyColumns) {
            key.append(normalizedKeyPart(row.get(column))).append('￟');
        }
        return key.toString();
    }

    private static String normalizedKeyPart(Object value) {
        if (value == null) {
            return "␀";
        }
        if (value instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        return String.valueOf(value);
    }

    static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1L : 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String quote(List<String> columns) {
        return columns.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
    }

    private static String placeholders(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append("?");
        }
        return text.toString();
    }

    private Map<String, Object> tenantRow(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    List<Map<String, Object>> rows =
                            jdbc.queryForList(
                                    "SELECT id, code, name, status FROM plt_tenant WHERE id = ?",
                                    tenantId);
                    if (rows.isEmpty()) {
                        throw new ApiException(404, 40400, "租户不存在: " + tenantId);
                    }
                    return rows.get(0);
                });
    }

    private int bumpPermissionVersions(long tenantId, String tenantCode) {
        if (!routing.hasTenant(tenantId)) {
            return 0;
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    tenantCode,
                    () -> {
                        List<Long> userIds =
                                jdbc.queryForList("SELECT id FROM sys_user", Long.class);
                        userIds.forEach(
                                userId -> permissionVersions.bump("HOSPITAL", tenantId, userId));
                        return userIds.size();
                    });
        } catch (RuntimeException ex) {
            // 权限刷新失败不回滚导入：会话在下一次登录 / 重算时自然拿到新授权
            return 0;
        }
    }
}
