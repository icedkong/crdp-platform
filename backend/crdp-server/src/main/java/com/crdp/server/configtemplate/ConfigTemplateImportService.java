package com.crdp.server.configtemplate;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
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
 * 配置模板导入（E12-T02 / OQR-02 / A-FLD-014）。
 *
 * <p>在用租户更新先只读预检，再在 REPEATABLE_READ 事务中锁定目标配置、校验同一应用计划摘要并执行。
 * 已有计划及节点不覆盖、不删除；变化导入为新未发布版本。已有表单快照发生冲突则拒绝整包应用。 ID 重映射、授权范围交集、敏感信息拦截及审计沿用既有规则，差异响应不返回配置值。
 *
 * <p>新租户开通调用独立 initialize 入口，仅允许 PROVISIONING 状态，保留初始化合并与节点替换语义。
 * 所有平台库读取在目标库事务外完成；目标事务只访问本租户配置表，成功后递增用户权限版本。
 */
@Service
public class ConfigTemplateImportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 应用请求：tenantId = 目标租户（不能是模板源租户）。 */
    public record ApplyRequest(Long tenantId, String planHash) {
        public ApplyRequest(Long tenantId) {
            this(tenantId, null);
        }
    }

    /** 真实差异只返回行序号、动作与字段名，不泄露目标配置值。 */
    public record PreviewView(
            String planHash,
            boolean applicable,
            String mode,
            List<ConfigImportPlan.Change> changes,
            List<TableResult> tables) {}

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

    private final com.crdp.framework.crypto.DigestService digest;
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
            PlatformTransactionManager transactionManager,
            com.crdp.framework.crypto.DigestService digest) {
        this.digest = digest;
        this.jdbc = jdbc;
        this.reader = reader;
        this.exporter = exporter;
        this.tenantScopes = tenantScopes;
        this.routing = routing;
        this.audit = audit;
        this.permissionVersions = permissionVersions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // ---------------------------------------------------------------- 应用入口

    /** 在用租户更新：必须提交同一预检摘要；只执行锁定目标后仍一致的应用计划。 */
    public AppliedView apply(long templateId, ApplyRequest request) {
        return applyInternal(templateId, request, false);
    }

    /** 仅供开通编排使用，不经公开 apply 接口暴露；仅 PROVISIONING 租户可走旧初始化合并。 */
    public AppliedView initialize(long templateId, ApplyRequest request) {
        return applyInternal(templateId, request, true);
    }

    private AppliedView applyInternal(
            long templateId, ApplyRequest request, boolean initialization) {
        CurrentSession.requirePlatform();
        if (request == null || request.tenantId() == null) {
            throw new ApiException(400, 40001, "tenantId 必填");
        }
        // ---- 平台库读取（导入事务外：事务内连接绑定目标租户库，不能再切平台库）----
        ConfigTemplateExportService.ExportedView head = exporter.headerOf(templateId);
        Map<String, Object> tenant = tenantRow(request.tenantId());
        String tenantCode = String.valueOf(tenant.get("code"));
        if (initialization && !"PROVISIONING".equals(tenant.get("status"))) {
            throw new ApiException(409, 40901, "仅开通中的租户允许初始化配置；在用租户必须先预检");
        }
        if (Set.of("CANCELLING", "CANCELLED").contains(String.valueOf(tenant.get("status")))) {
            throw new ApiException(409, 40901, "注销中或已注销的租户不能导入配置");
        }
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
        // 敏感信息检查（E12-T03，验收「含敏感信息的包被拦截」）：包可能来自旧版代码或被篡改——
        // 混入患者 / 业务数据表、未标注表、术者取值、疑似患者敏感值（手机号 / 证件号）→ 应用前拒绝
        List<SensitiveConfigChecker.Violation> violations = SensitiveConfigChecker.check(pkg);
        if (!violations.isEmpty()) {
            String summary = SensitiveConfigChecker.summarize(violations);
            audit.append(
                    AuditEntry.operation("platform", "CONFIG_TEMPLATE_APPLY")
                            .tenantId(request.tenantId())
                            .objectType("plt_config_template")
                            .objectId(String.valueOf(templateId))
                            .description(
                                    "将 "
                                            + head.tenantCode()
                                            + " 配置模板应用到租户 "
                                            + tenantCode
                                            + " 被拦截（含敏感信息）")
                            .fileHash(head.packageHash())
                            .fail("含敏感信息，导入被拦截：" + summary)
                            .errorCode(40901)
                            .build());
            throw new ApiException(409, 40901, "配置包含敏感信息，导入被拦截：" + summary);
        }
        Set<Long> assignable = tenantScopes.assignableMenuIds(request.tenantId());

        // ---- 目标租户库导入：单事务全成或全败 ----
        Outcome outcome;
        try {
            outcome =
                    TenantContext.callAsTenant(
                            request.tenantId(),
                            tenantCode,
                            () ->
                                    transactions.execute(
                                            status -> {
                                                if (initialization)
                                                    return importAll(pkg, assignable);
                                                if (request.planHash() == null
                                                        || request.planHash().isBlank()) {
                                                    throw new ApiException(
                                                            409, 40900, "请先生成目标租户差异预览，再确认应用");
                                                }
                                                Map<String, ConfigImportPlan.Target> snapshot =
                                                        snapshot(true);
                                                ConfigImportPlan plan =
                                                        new ConfigImportPlan(
                                                                pkg, snapshot, assignable, false);
                                                String hash =
                                                        planHash(
                                                                templateId,
                                                                request.tenantId(),
                                                                head.packageHash(),
                                                                pkg,
                                                                snapshot,
                                                                assignable,
                                                                plan);
                                                if (!hash.equals(request.planHash())) {
                                                    throw new ApiException(
                                                            409, 40900, "模板、权限范围或目标配置已变化，请重新预览");
                                                }
                                                if (!plan.applicable())
                                                    throw new ApiException(
                                                            409, 40901, "差异计划存在冲突，不能应用");
                                                execute(plan);
                                                return outcome(plan.tables);
                                            }));
        } catch (RuntimeException ex) {
            int error = ex instanceof ApiException api ? api.code() : 50000;
            audit.append(
                    AuditEntry.operation("platform", "CONFIG_TEMPLATE_APPLY")
                            .tenantId(request.tenantId())
                            .objectType("plt_config_template")
                            .objectId(String.valueOf(templateId))
                            .description("目标租户配置应用失败，事务未提交")
                            .fileHash(head.packageHash())
                            .fail("应用被拒绝或执行失败，请重新预检并核对冲突")
                            .errorCode(error)
                            .build());
            throw ex;
        }

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

    /** 预检是只读目标库事务，不执行 INSERT / UPDATE / DELETE，也不刷新权限版本。 */
    public PreviewView preview(long templateId, ApplyRequest request) {
        CurrentSession.requirePlatform();
        try {
            return previewInternal(templateId, request);
        } catch (RuntimeException ex) {
            int error = ex instanceof ApiException api ? api.code() : 50000;
            audit.append(
                    AuditEntry.operation("platform", "CONFIG_TEMPLATE_PREVIEW")
                            .tenantId(request == null ? null : request.tenantId())
                            .objectType("plt_config_template")
                            .objectId(String.valueOf(templateId))
                            .description("目标租户配置差异预检失败")
                            .fail("预检被拒绝或配置不可读取")
                            .errorCode(error)
                            .build());
            throw ex;
        }
    }

    private PreviewView previewInternal(long templateId, ApplyRequest request) {
        if (request == null || request.tenantId() == null)
            throw new ApiException(400, 40001, "tenantId 必填");
        var head = exporter.headerOf(templateId);
        var tenant = tenantRow(request.tenantId());
        if (head.tenantId() == request.tenantId())
            throw new ApiException(400, 40001, "目标租户不能是模板源租户");
        if (!routing.hasTenant(request.tenantId())
                || Set.of("CANCELLING", "CANCELLED")
                        .contains(String.valueOf(tenant.get("status")))) {
            throw new ApiException(409, 40901, "目标租户不可应用配置");
        }
        var pkg = exporter.packageOf(templateId);
        if (pkg.formatVersion() != ConfigTemplateExportService.FORMAT_VERSION
                || !SensitiveConfigChecker.check(pkg).isEmpty())
            throw new ApiException(409, 40901, "配置包版本不支持或包含禁止导入的内容");
        Set<Long> assignable = tenantScopes.assignableMenuIds(request.tenantId());
        TransactionTemplate readonly =
                new TransactionTemplate(transactions.getTransactionManager());
        readonly.setReadOnly(true);
        readonly.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        PreviewView view =
                TenantContext.callAsTenant(
                        request.tenantId(),
                        String.valueOf(tenant.get("code")),
                        () ->
                                readonly.execute(
                                        status -> {
                                            var snapshot = snapshot(false);
                                            var plan =
                                                    new ConfigImportPlan(
                                                            pkg, snapshot, assignable, false);
                                            return new PreviewView(
                                                    planHash(
                                                            templateId,
                                                            request.tenantId(),
                                                            head.packageHash(),
                                                            pkg,
                                                            snapshot,
                                                            assignable,
                                                            plan),
                                                    plan.applicable(),
                                                    "UPDATE",
                                                    plan.changes,
                                                    plan.tables);
                                        }));
        audit.append(
                AuditEntry.operation("platform", "CONFIG_TEMPLATE_PREVIEW")
                        .tenantId(request.tenantId())
                        .objectType("plt_config_template")
                        .objectId(String.valueOf(templateId))
                        .description("目标租户配置只读差异预检")
                        .fileHash(head.packageHash())
                        .success()
                        .build());
        return view;
    }

    private Map<String, ConfigImportPlan.Target> snapshot(boolean lock) {
        Map<String, ConfigImportPlan.Target> result = new LinkedHashMap<>();
        for (var entry : ConfigTableRegistry.ENTRIES) {
            if (entry.mode() != ConfigCopyMode.COPY || !reader.tablePresent(entry.table()))
                continue;
            List<String> columns = reader.columns(entry.table());
            // 全表锁定及范围间隙锁保证校验后执行期间配置不能被另一个事务插入或修改。
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT * FROM `" + entry.table() + "`" + (lock ? " FOR UPDATE" : ""));
            List<Map<String, Object>> normalized =
                    rows.stream()
                            .map(
                                    row -> {
                                        Map<String, Object> copy = new LinkedHashMap<>();
                                        row.forEach(
                                                (k, v) -> copy.put(k, ConfigPackage.cellValue(v)));
                                        return copy;
                                    })
                            .sorted(java.util.Comparator.comparing(ConfigImportPlan::canonical))
                            .toList();
            result.put(entry.table(), new ConfigImportPlan.Target(columns, normalized));
        }
        return result;
    }

    private String planHash(
            long templateId,
            long tenantId,
            String sourceHash,
            ConfigPackage pkg,
            Map<String, ConfigImportPlan.Target> snapshot,
            Set<Long> assignable,
            ConfigImportPlan plan) {
        return digest.hashHex(
                ConfigImportPlan.canonical(
                        List.of(
                                "OQR02-v1",
                                templateId,
                                tenantId,
                                CurrentSession.requirePlatform().userId(),
                                sourceHash,
                                pkg,
                                snapshot,
                                assignable.stream().sorted().toList(),
                                plan.writes,
                                plan.changes)));
    }

    private void execute(ConfigImportPlan plan) {
        Map<Long, Long> generated = new LinkedHashMap<>();
        for (var write : plan.writes) {
            Map<String, Object> values = new LinkedHashMap<>(write.values());
            var spec = ConfigImportSpecs.of(write.table());
            for (String column : spec.references().keySet()) {
                Long value = asLong(values.get(column));
                if (value != null && value < 0) {
                    if (!generated.containsKey(value))
                        throw new IllegalStateException("应用计划引用顺序错误");
                    values.put(column, generated.get(value));
                }
            }
            if (write.action().equals("INSERT")) {
                if (spec.mode() == ConfigImportMode.SINGLE_ROW) {
                    values.put("id", write.id());
                    jdbc.update(
                            "INSERT INTO `"
                                    + write.table()
                                    + "` ("
                                    + quote(List.copyOf(values.keySet()))
                                    + ") VALUES ("
                                    + placeholders(values.size())
                                    + ")",
                            values.values().toArray());
                } else if (spec.mode() == ConfigImportMode.LINK_ROWS) {
                    jdbc.update(
                            "INSERT INTO `"
                                    + write.table()
                                    + "` ("
                                    + quote(List.copyOf(values.keySet()))
                                    + ") VALUES ("
                                    + placeholders(values.size())
                                    + ")",
                            values.values().toArray());
                } else {
                    long id =
                            insertReturningId(write.table(), List.copyOf(values.keySet()), values);
                    generated.put(write.id(), id);
                }
            } else {
                Long id = write.id();
                if (id != null && id < 0) id = generated.get(id);
                values.put("id", id);
                updateByKey(
                        write.table(),
                        write.values().keySet().stream().toList(),
                        List.of("id"),
                        values);
            }
        }
    }

    private static Outcome outcome(List<TableResult> tables) {
        return new Outcome(
                tables,
                tables.stream().mapToLong(TableResult::inserted).sum(),
                tables.stream().mapToLong(TableResult::merged).sum(),
                tables.stream().mapToLong(TableResult::skipped).sum(),
                tables.stream().mapToLong(TableResult::dropped).sum());
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
            // 防御深度：E12-T03 敏感检查在应用前已拦截未标注表，这里理论上不可达——保留兜底
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
