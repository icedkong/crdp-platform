package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.crdp.server.platform.ViewRuleResolver.Rule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 视角规则的配置与效果预览（E20-T02 / PLT-VIEW-007、008、009，docs/api/platform.md §6）。
 *
 * <p>配置（PLT-VIEW-007）：平台管理员按层级（全局默认 / 租户 / 模块 / 字段）维护规则——可见性（明文 / 脱敏 / 设盲 / 隐藏）、操作（只读 /
 * 可编辑）、是否允许导出，适用平台角色（全部或指定）；无需医院参与。 普通变更即时生效（高敏变更的「待复核 + 第二人复核」随 E20-T05 /
 * PLT-VIEW-014，本任务先落状态与版本列）。 增删改写平台访问审计（action 前缀 {@code VIEW_RULE}，审计中心「视角规则变更」页签，OQ-56 ④）。
 *
 * <p>默认规则（PLT-VIEW-008）：系统初始默认以种子规则行（V109）交付——敏感字段脱敏、原件附件隐藏、RCT
 * 设盲字段设盲、禁止导出；平台管理员可修改、停用。默认布局避免同层互相抵消（OQ-99）。
 *
 * <p>优先级与预览（PLT-VIEW-009，验收「预览与实际执行一致」）：匹配与合并只有 {@link ViewRuleResolver} 一个实现——预览接口与 E20-T03 执行点（脱敏
 * / 设盲 / 只读）喂同一批 ACTIVE 规则即得同一效果； {@link #activeRuleVersion}
 * 给视角会话台账（plt_view_session.rule_version）盖「生效规则版本」戳 （ACTIVE 规则集内容哈希），规则一变即换值，审计可对账。
 */
@Service
public class ViewRuleService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final int NAME_MAX = 128;

    private final JdbcTemplate platform;

    private final AuditLogger audit;

    public ViewRuleService(JdbcTemplate platform, AuditLogger audit) {
        this.platform = platform;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 规则行（配置页列表 / 详情）。roleCodes 为空 = 全部平台角色。 */
    public record RuleView(
            long id,
            String code,
            String name,
            String layer,
            Long tenantId,
            String tenantCode,
            String tenantName,
            String moduleCode,
            String moduleName,
            String resource,
            String resourceLabel,
            String field,
            String fieldLabel,
            List<String> roleCodes,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            String status,
            int version,
            OffsetDateTime updatedAt) {}

    /** 新建 / 维护请求（状态与版本为系统字段：status 允许 ACTIVE / DISABLED，version 服务端维护）。 */
    public record SaveRequest(
            String name,
            String layer,
            Long tenantId,
            String moduleCode,
            String resource,
            String field,
            List<Long> roleIds,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            String status) {}

    /** 校验后的落库载荷（纯函数 {@link #validate} 的产物）。 */
    public record SaveCommand(
            String name,
            String layer,
            Long tenantId,
            String moduleCode,
            String resource,
            String field,
            Set<Long> roleIds,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            String status) {}

    /** 视角字段目录（配置页 FIELD 层与预览用）。 */
    public record CatalogField(
            String resource, String field, String label, String moduleCode, String key) {}

    public record Catalog(List<CatalogField> fields, List<String> maskRules) {}

    /** 效果预览请求：平台角色（多选，非空）+ 租户。 */
    public record PreviewRequest(long tenantId, List<Long> roleIds) {}

    /** 模块（页面）级效果：可见性按模块层规则、操作与导出与字段共享同一合并口径。 */
    public record ModuleEffect(
            String moduleCode,
            String moduleName,
            boolean opened,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            List<ViewRuleResolver.Trace> matched,
            List<ViewRuleResolver.Trace> overridden) {}

    /** 字段级效果：可见性 / 脱敏码 / 操作 / 导出与「由哪条规则决定」。 */
    public record FieldEffect(
            String resource,
            String field,
            String label,
            String moduleCode,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            List<ViewRuleResolver.Trace> matched,
            List<ViewRuleResolver.Trace> overridden) {}

    /** 预览结果：该角色组合 + 租户下，各模块（页面）与各字段目录项的最终效果。 */
    public record PreviewResult(
            long tenantId,
            String tenantCode,
            String tenantName,
            List<String> roleCodes,
            List<ModuleEffect> modules,
            List<FieldEffect> fields) {}

    /** 内部行（含角色绑定码集合；供执行点与预览共用装载，测试比对用，公开只读）。 */
    public record RuleRow(
            long id,
            String code,
            String name,
            String layer,
            Long tenantId,
            String moduleCode,
            String resource,
            String field,
            Set<String> roleCodes,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            String status,
            int version,
            LocalDateTime updatedAt) {

        public Rule toResolverRule() {
            return new Rule(
                    id,
                    code,
                    name,
                    layer,
                    tenantId,
                    moduleCode,
                    resource,
                    field,
                    roleCodes,
                    visibility,
                    maskRule,
                    operation,
                    allowExport,
                    status);
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 规则列表（量级小，不分页；过滤器均可空）。 */
    public List<RuleView> list(
            String layer, String status, Long tenantId, String moduleCode, String resource) {
        return TenantContext.callAsPlatform(
                () -> {
                    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
                    List<Object> args = new ArrayList<>();
                    if (notBlank(layer)) {
                        where.append(" AND layer = ?");
                        args.add(layer.trim());
                    }
                    if (notBlank(status)) {
                        where.append(" AND status = ?");
                        args.add(status.trim());
                    }
                    if (tenantId != null) {
                        where.append(" AND tenant_id = ?");
                        args.add(tenantId);
                    }
                    if (notBlank(moduleCode)) {
                        where.append(" AND module_code = ?");
                        args.add(moduleCode.trim());
                    }
                    if (notBlank(resource)) {
                        where.append(" AND resource = ?");
                        args.add(resource.trim());
                    }
                    return rows(
                            "SELECT r.* FROM plt_view_rule r" + where + " ORDER BY r.layer, r.id",
                            args.toArray());
                });
    }

    /** 视角字段目录（FIELD 层引用与预览的字段清单）。 */
    public Catalog catalog() {
        return new Catalog(
                ViewRuleCatalog.all().stream()
                        .map(
                                entry ->
                                        new CatalogField(
                                                entry.resource(),
                                                entry.field(),
                                                entry.label(),
                                                entry.moduleCode(),
                                                entry.key()))
                        .toList(),
                List.copyOf(new TreeSet<>(ViewRuleCatalog.MASK_RULES)));
    }

    // ---------------------------------------------------------------- 新建 / 维护

    /** 新建规则（PLT-VIEW-007）：规则编号服务端生成（VR-00001），普通变更即时生效。 */
    public RuleView create(SaveRequest request) {
        SaveCommand command = validate(request);
        SessionPrincipal caller = CurrentSession.requirePlatform();
        return TenantContext.callAsPlatform(
                () -> {
                    requireReferences(command);
                    requireRoles(command.roleIds());
                    var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
                    // code 占位（NOT NULL 唯一列）：插入拿自增 id 后立即改为 VR-00001；占位带随机尾避免并发冲突
                    String placeholder = "PENDING-" + java.util.UUID.randomUUID();
                    platform.update(
                            con -> {
                                java.sql.PreparedStatement statement =
                                        con.prepareStatement(
                                                "INSERT INTO plt_view_rule (code, name, layer,"
                                                        + " tenant_id, module_code, resource, field,"
                                                        + " visibility, mask_rule, operation,"
                                                        + " allow_export, status, created_by,"
                                                        + " updated_by)"
                                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                                        + " ?, ?, ?, ?)",
                                                java.sql.Statement.RETURN_GENERATED_KEYS);
                                statement.setString(1, placeholder);
                                statement.setString(2, command.name());
                                statement.setString(3, command.layer());
                                setNullable(statement, 4, command.tenantId());
                                setNullable(statement, 5, command.moduleCode());
                                setNullable(statement, 6, command.resource());
                                setNullable(statement, 7, command.field());
                                statement.setString(8, command.visibility());
                                setNullable(statement, 9, command.maskRule());
                                statement.setString(10, command.operation());
                                statement.setBoolean(11, command.allowExport());
                                statement.setString(12, command.status());
                                setNullable(statement, 13, caller.userId());
                                setNullable(statement, 14, caller.userId());
                                return statement;
                            },
                            keyHolder);
                    long id = ((Number) keyHolder.getKeys().get("GENERATED_KEY")).longValue();
                    String code = "VR-%05d".formatted(id);
                    platform.update("UPDATE plt_view_rule SET code = ? WHERE id = ?", code, id);
                    replaceRoles(id, command.roleIds());
                    audit.append(
                            ruleAudit(command, "VIEW_RULE_CREATE", caller)
                                    .objectId(code)
                                    .description(
                                            "新建视角规则 "
                                                    + code
                                                    + "："
                                                    + command.name()
                                                    + "（"
                                                    + describe(command)
                                                    + "）")
                                    .success()
                                    .build());
                    return viewOf(id);
                });
    }

    /** 维护规则（含停用 / 启用，PLT-VIEW-007）：版本 +1；高敏变更复核随 E20-T05。 */
    public RuleView update(long id, SaveRequest request) {
        SaveCommand command = validate(request);
        SessionPrincipal caller = CurrentSession.requirePlatform();
        return TenantContext.callAsPlatform(
                () -> {
                    String before = existing(id);
                    requireReferences(command);
                    String code = beforeCode(id);
                    platform.update(
                            "UPDATE plt_view_rule SET name = ?, layer = ?, tenant_id = ?,"
                                    + " module_code = ?, resource = ?, field = ?, visibility = ?,"
                                    + " mask_rule = ?, operation = ?, allow_export = ?, status = ?,"
                                    + " version = version + 1, updated_by = ? WHERE id = ?",
                            command.name(),
                            command.layer(),
                            command.tenantId(),
                            command.moduleCode(),
                            command.resource(),
                            command.field(),
                            command.visibility(),
                            command.maskRule(),
                            command.operation(),
                            command.allowExport(),
                            command.status(),
                            caller.userId(),
                            id);
                    replaceRoles(id, command.roleIds());
                    String action =
                            switch (command.status()) {
                                case "DISABLED" -> "VIEW_RULE_DISABLE";
                                default -> "VIEW_RULE_UPDATE";
                            };
                    audit.append(
                            ruleAudit(command, action, caller)
                                    .objectId(code)
                                    .description(
                                            "修改视角规则 "
                                                    + code
                                                    + "："
                                                    + command.name()
                                                    + "（"
                                                    + describe(command)
                                                    + "；变更前 "
                                                    + before
                                                    + "）")
                                    .success()
                                    .build());
                    return viewOf(id);
                });
    }

    // ---------------------------------------------------------------- 效果预览与解析

    /**
     * 效果预览（PLT-VIEW-009）：选平台角色（多选）+ 租户，返回各模块（页面）与各字段目录项的最终效果。 与实际执行共用 {@link
     * ViewRuleResolver}——预览值即执行值（验收口径）。
     */
    public PreviewResult preview(PreviewRequest request) {
        if (request == null || request.roleIds() == null || request.roleIds().isEmpty()) {
            throw new ApiException(400, 40001, "请选择至少一个平台角色再预览");
        }
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(request.tenantId());
                    Map<Long, String> roleCodes = enabledRoleCodes(request.roleIds());
                    if (roleCodes.isEmpty()) {
                        throw new ApiException(400, 40001, "所选平台角色不存在或已停用");
                    }
                    List<Rule> rules = activeRules().stream().map(RuleRow::toResolverRule).toList();
                    Set<String> selected = new LinkedHashSet<>(roleCodes.values());
                    long tenantId = request.tenantId();

                    List<ModuleEffect> modules = new ArrayList<>();
                    Set<String> opened =
                            new HashSet<>(
                                    platform.queryForList(
                                            "SELECT module_code FROM plt_tenant_module"
                                                    + " WHERE tenant_id = ? AND status = 'OPEN'",
                                            String.class,
                                            tenantId));
                    for (Map<String, Object> module : modules()) {
                        String code = str(module.get("code"));
                        Effect effect =
                                ViewRuleResolver.resolve(
                                        rules, selected, tenantId, code, null, null);
                        modules.add(
                                new ModuleEffect(
                                        code,
                                        str(module.get("name")),
                                        opened.contains(code),
                                        effect.visibility(),
                                        effect.maskRule(),
                                        effect.operation(),
                                        effect.allowExport(),
                                        effect.matched(),
                                        effect.overridden()));
                    }

                    List<FieldEffect> fields = new ArrayList<>();
                    for (ViewRuleCatalog.Entry entry : ViewRuleCatalog.all()) {
                        Effect effect =
                                ViewRuleResolver.resolve(
                                        rules,
                                        selected,
                                        tenantId,
                                        entry.moduleCode(),
                                        entry.resource(),
                                        entry.field());
                        fields.add(
                                new FieldEffect(
                                        entry.resource(),
                                        entry.field(),
                                        entry.label(),
                                        entry.moduleCode(),
                                        effect.visibility(),
                                        effect.maskRule(),
                                        effect.operation(),
                                        effect.allowExport(),
                                        effect.matched(),
                                        effect.overridden()));
                    }
                    return new PreviewResult(
                            tenantId,
                            str(tenant.get("code")),
                            str(tenant.get("name")),
                            new ArrayList<>(selected),
                            modules,
                            fields);
                });
    }

    /** 全部 ACTIVE 规则（执行点与预览共用同一装载口径，含角色绑定码集合；自设平台上下文，可嵌套）。 */
    public List<RuleRow> activeRules() {
        return TenantContext.callAsPlatform(this::loadActiveRules);
    }

    private List<RuleRow> loadActiveRules() {
        List<Map<String, Object>> raw =
                platform.queryForList(
                        "SELECT id, code, name, layer, tenant_id, module_code, resource, field,"
                                + " visibility, mask_rule, operation, allow_export, status, version,"
                                + " updated_at FROM plt_view_rule WHERE status = 'ACTIVE'"
                                + " ORDER BY id");
        Map<Long, Set<String>> roleCodes = roleCodesByRule();
        List<RuleRow> rules = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            long id = ((Number) row.get("id")).longValue();
            rules.add(
                    new RuleRow(
                            id,
                            str(row.get("code")),
                            str(row.get("name")),
                            str(row.get("layer")),
                            row.get("tenant_id") == null
                                    ? null
                                    : ((Number) row.get("tenant_id")).longValue(),
                            emptyToNull(str(row.get("module_code"))),
                            emptyToNull(str(row.get("resource"))),
                            emptyToNull(str(row.get("field"))),
                            roleCodes.getOrDefault(id, Set.of()),
                            str(row.get("visibility")),
                            emptyToNull(str(row.get("mask_rule"))),
                            str(row.get("operation")),
                            ((Number) row.get("allow_export")).intValue() == 1,
                            str(row.get("status")),
                            ((Number) row.get("version")).intValue(),
                            (LocalDateTime) row.get("updated_at")));
        }
        return rules;
    }

    /**
     * 生效规则版本（plt_view_session.rule_version 的取值）：ACTIVE 规则集内容哈希（SHA-256 前 16 位
     * 十六进制），规则内容一变即换值；无规则时为空集哈希（与初始默认回落值配对）。
     */
    public String activeRuleVersion() {
        return TenantContext.callAsPlatform(this::computeRuleVersion);
    }

    private String computeRuleVersion() {
        StringBuilder canonical = new StringBuilder();
        for (RuleRow row : activeRules()) {
            canonical
                    .append(row.id())
                    .append('|')
                    .append(row.code())
                    .append('|')
                    .append(row.layer())
                    .append('|')
                    .append(row.tenantId())
                    .append('|')
                    .append(row.moduleCode())
                    .append('|')
                    .append(row.resource())
                    .append('|')
                    .append(row.field())
                    .append('|')
                    .append(new TreeSet<>(row.roleCodes()))
                    .append('|')
                    .append(row.visibility())
                    .append('|')
                    .append(row.maskRule())
                    .append('|')
                    .append(row.operation())
                    .append('|')
                    .append(row.allowExport())
                    .append(';');
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append("%02x".formatted(digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /**
     * 保存校验（前端先行同口径）：名称、层级形状（GLOBAL 不带选择器 / TENANT 必带租户 / MODULE 必带模块 / FIELD
     * 必带目录内资源与字段）、可见性与脱敏码一致性、枚举与状态。目录与引用存在性查库在 {@link #create} / {@link #update} 内追加。
     */
    public static SaveCommand validate(SaveRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(400, 40001, "请填写规则名称");
        }
        if (name.length() > NAME_MAX) {
            throw new ApiException(400, 40001, "规则名称过长（最多 " + NAME_MAX + " 个字符）");
        }
        String layer = trim(request.layer());
        if (!ViewRuleCatalog.LAYERS.contains(layer)) {
            throw new ApiException(400, 40001, "层级只允许 GLOBAL / TENANT / MODULE / FIELD");
        }
        String visibility = trim(request.visibility());
        if (!ViewRuleCatalog.VISIBILITIES.contains(visibility)) {
            throw new ApiException(400, 40001, "可见性只允许 PLAIN / MASKED / BLINDED / HIDDEN");
        }
        String operation = trim(request.operation());
        if (!ViewRuleCatalog.OPERATIONS.contains(operation)) {
            throw new ApiException(400, 40001, "操作只允许 READONLY / EDITABLE");
        }
        String status = trim(request.status());
        if (status.isEmpty()) {
            status = ViewRuleCatalog.STATUS_ACTIVE;
        }
        if (!ViewRuleCatalog.STATUSES.contains(status)) {
            throw new ApiException(400, 40001, "状态只允许 ACTIVE / DISABLED");
        }
        String maskRule = trimToNull(request.maskRule());
        if (ViewRuleCatalog.VISIBILITY_MASKED.equals(visibility)) {
            if (maskRule == null || !ViewRuleCatalog.MASK_RULES.contains(maskRule)) {
                throw new ApiException(
                        400,
                        40001,
                        "可见性为脱敏时必须选择脱敏规则（name / phone / idCard /"
                                + " medicalRecordNo / address / generic）");
            }
        } else if (maskRule != null) {
            throw new ApiException(400, 40001, "只有脱敏可见性才允许填脱敏规则");
        }
        String moduleCode = trimToNull(request.moduleCode());
        String resource = trimToNull(request.resource());
        String field = trimToNull(request.field());
        switch (layer) {
            case ViewRuleCatalog.LAYER_GLOBAL -> {
                requireNoSelectors(request, "全局默认");
                moduleCode = null;
                resource = null;
                field = null;
            }
            case ViewRuleCatalog.LAYER_TENANT -> {
                if (request.tenantId() == null) {
                    throw new ApiException(400, 40001, "租户层级必须选择租户");
                }
                if (moduleCode != null || resource != null || field != null) {
                    throw new ApiException(400, 40001, "租户层级不能带模块或字段选择器");
                }
            }
            case ViewRuleCatalog.LAYER_MODULE -> {
                if (moduleCode == null) {
                    throw new ApiException(400, 40001, "模块层级必须选择模块");
                }
                if (resource != null || field != null) {
                    throw new ApiException(400, 40001, "模块层级不能带字段选择器");
                }
            }
            case ViewRuleCatalog.LAYER_FIELD -> {
                if (resource == null || !ViewRuleCatalog.resourceKnown(resource)) {
                    throw new ApiException(400, 40001, "字段层级必须选择目录内的资源对象");
                }
                if (field != null && ViewRuleCatalog.find(resource, field).isEmpty()) {
                    throw new ApiException(400, 40001, "字段不在该资源对象的目录清单内");
                }
            }
            default -> {
                // 枚举已拦截
            }
        }
        return new SaveCommand(
                name,
                layer,
                ViewRuleCatalog.LAYER_GLOBAL.equals(layer) ? null : request.tenantId(),
                moduleCode,
                resource,
                field,
                request.roleIds() == null ? Set.of() : new LinkedHashSet<>(request.roleIds()),
                visibility,
                maskRule,
                operation,
                request.allowExport(),
                status);
    }

    private static void requireNoSelectors(SaveRequest request, String layerText) {
        if (request.tenantId() != null
                || trimToNull(request.moduleCode()) != null
                || trimToNull(request.resource()) != null
                || trimToNull(request.field()) != null) {
            throw new ApiException(400, 40001, layerText + "层级不能带租户 / 模块 / 字段选择器");
        }
    }

    // ---------------------------------------------------------------- 内部（查库与装配）

    private List<RuleView> rows(String sql, Object... args) {
        List<Map<String, Object>> raw = platform.queryForList(sql, args);
        Map<Long, Set<String>> roleCodes = roleCodesByRule();
        Map<Long, String[]> tenants = tenantNames();
        Map<String, String> moduleNames = moduleNames();
        List<RuleView> views = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            long id = ((Number) row.get("id")).longValue();
            Long tenantId =
                    row.get("tenant_id") == null
                            ? null
                            : ((Number) row.get("tenant_id")).longValue();
            String tenantCode =
                    tenantId == null
                            ? null
                            : tenants.getOrDefault(tenantId, new String[] {null, null})[0];
            String tenantName =
                    tenantId == null
                            ? null
                            : tenants.getOrDefault(tenantId, new String[] {null, null})[1];
            String moduleCode = str(row.get("module_code"));
            String resource = row.get("resource") == null ? null : str(row.get("resource"));
            String field = row.get("field") == null ? null : str(row.get("field"));
            views.add(
                    new RuleView(
                            id,
                            str(row.get("code")),
                            str(row.get("name")),
                            str(row.get("layer")),
                            tenantId,
                            tenantCode,
                            tenantName,
                            moduleCode.isEmpty() ? null : moduleCode,
                            moduleNames.get(moduleCode),
                            resource,
                            resource == null ? null : ViewRuleCatalog.resourceLabel(resource),
                            field,
                            field == null
                                    ? null
                                    : ViewRuleCatalog.find(resource, field)
                                            .map(ViewRuleCatalog.Entry::label)
                                            .orElse(null),
                            new ArrayList<>(new TreeSet<>(roleCodes.getOrDefault(id, Set.of()))),
                            str(row.get("visibility")),
                            row.get("mask_rule") == null ? null : str(row.get("mask_rule")),
                            str(row.get("operation")),
                            ((Number) row.get("allow_export")).intValue() == 1,
                            str(row.get("status")),
                            ((Number) row.get("version")).intValue(),
                            offset((java.time.LocalDateTime) row.get("updated_at"))));
        }
        return views;
    }

    private RuleView viewOf(long id) {
        List<RuleView> views = rows("SELECT r.* FROM plt_view_rule r WHERE r.id = ?", id);
        if (views.isEmpty()) {
            throw new ApiException(404, 40400, "视角规则不存在或已删除");
        }
        return views.get(0);
    }

    /** 规则 → 适用角色码集合（清单空 = 全部角色）。 */
    private Map<Long, Set<String>> roleCodesByRule() {
        Map<Long, Set<String>> byRule = new LinkedHashMap<>();
        platform.queryForList(
                        "SELECT rr.rule_id, r.code FROM plt_view_rule_role rr"
                                + " JOIN plt_role r ON r.id = rr.role_id")
                .forEach(
                        row ->
                                byRule.computeIfAbsent(
                                                ((Number) row.get("rule_id")).longValue(),
                                                key -> new TreeSet<>())
                                        .add(str(row.get("code"))));
        return byRule;
    }

    private Map<Long, String[]> tenantNames() {
        Map<Long, String[]> names = new LinkedHashMap<>();
        platform.queryForList("SELECT id, code, name FROM plt_tenant")
                .forEach(
                        row ->
                                names.put(
                                        ((Number) row.get("id")).longValue(),
                                        new String[] {str(row.get("code")), str(row.get("name"))}));
        return names;
    }

    private Map<String, String> moduleNames() {
        Map<String, String> names = new LinkedHashMap<>();
        platform.queryForList("SELECT code, name FROM plt_module")
                .forEach(row -> names.put(str(row.get("code")), str(row.get("name"))));
        return names;
    }

    private List<Map<String, Object>> modules() {
        return platform.queryForList(
                "SELECT code, name FROM plt_module ORDER BY base_module DESC, code");
    }

    /** 适用角色存在且启用（不存在 / 停用 40001）；落库前先验，避免半截行。 */
    private void requireRoles(Set<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        List<Long> existing =
                platform.queryForList(
                        "SELECT id FROM plt_role WHERE id IN ("
                                + String.join(",", roleIds.stream().map(String::valueOf).toList())
                                + ") AND status = 'ENABLED'",
                        Long.class);
        if (existing.size() != roleIds.size()) {
            throw new ApiException(400, 40001, "适用平台角色不存在或已停用");
        }
    }

    /** 角色绑定替换（整体覆盖：空清单 = 全部角色）。 */
    private void replaceRoles(long ruleId, Set<Long> roleIds) {
        requireRoles(roleIds);
        platform.update("DELETE FROM plt_view_rule_role WHERE rule_id = ?", ruleId);
        for (Long roleId : roleIds) {
            platform.update(
                    "INSERT INTO plt_view_rule_role (rule_id, role_id) VALUES (?, ?)",
                    ruleId,
                    roleId);
        }
    }

    /** 引用存在性校验（租户 / 模块）：在规则落库前调用，未知引用 40001。 */
    private void requireReferences(SaveCommand command) {
        if (command.tenantId() != null
                && platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_tenant WHERE id = ?",
                                Integer.class,
                                command.tenantId())
                        == 0) {
            throw new ApiException(400, 40001, "所选租户不存在");
        }
        if (command.moduleCode() != null
                && platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_module WHERE code = ?",
                                Integer.class,
                                command.moduleCode())
                        == 0) {
            throw new ApiException(400, 40001, "所选模块不存在");
        }
    }

    private Map<Long, String> enabledRoleCodes(List<Long> roleIds) {
        Map<Long, String> codes = new LinkedHashMap<>();
        for (Long roleId : new LinkedHashSet<>(roleIds)) {
            List<String> code =
                    platform.queryForList(
                            "SELECT code FROM plt_role WHERE id = ? AND status = 'ENABLED'",
                            String.class,
                            roleId);
            if (!code.isEmpty()) {
                codes.put(roleId, code.get(0));
            }
        }
        return codes;
    }

    private Map<String, Object> requireTenant(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name FROM plt_tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private String existing(long id) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT code, name, layer, tenant_id, module_code, resource, field,"
                                + " visibility, mask_rule, operation, allow_export, status"
                                + " FROM plt_view_rule WHERE id = ?",
                        id);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "视角规则不存在或已删除");
        }
        Map<String, Object> row = rows.get(0);
        return "层级 %s，可见性 %s，操作 %s，允许导出 %s，状态 %s"
                .formatted(
                        str(row.get("layer")),
                        str(row.get("visibility")),
                        str(row.get("operation")),
                        ((Number) row.get("allow_export")).intValue() == 1 ? "是" : "否",
                        str(row.get("status")));
    }

    private String beforeCode(long id) {
        return platform.queryForObject(
                "SELECT code FROM plt_view_rule WHERE id = ?", String.class, id);
    }

    private AuditEntry.Builder ruleAudit(
            SaveCommand command, String action, SessionPrincipal caller) {
        return AuditEntry.platformAccess(command.tenantId(), "base", action)
                .userId(caller.userId())
                .username(caller.username())
                .displayName(caller.displayName())
                .roleCodes(caller.roles())
                .objectType("plt_view_rule");
    }

    private static String describe(SaveCommand command) {
        return "层级 %s%s%s%s，可见性 %s%s，操作 %s，允许导出 %s，状态 %s"
                .formatted(
                        command.layer(),
                        command.tenantId() == null ? "" : "，租户 " + command.tenantId(),
                        command.moduleCode() == null ? "" : "，模块 " + command.moduleCode(),
                        command.resource() == null
                                ? ""
                                : "，字段 "
                                        + command.resource()
                                        + (command.field() == null ? "" : "." + command.field()),
                        command.visibility(),
                        command.maskRule() == null ? "" : "（" + command.maskRule() + "）",
                        command.operation(),
                        command.allowExport() ? "是" : "否",
                        command.status());
    }

    private static void setNullable(java.sql.PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullable(java.sql.PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
