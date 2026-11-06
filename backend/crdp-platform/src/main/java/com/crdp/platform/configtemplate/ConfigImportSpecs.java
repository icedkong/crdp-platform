package com.crdp.platform.configtemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置表导入规格全集（E12-T02 / A-FLD-014，tech-plan §6「导入时重新分配 ID 并重写引用」）。
 *
 * <p>导入只处理 {@link ConfigTableRegistry} 登记的表；本类为其中 COPY 条目逐一声明：自然键（合并判定）、 数值引用（ID 重映射）与导入方式。引用分两类：
 *
 * <ul>
 *   <li>数值 ID 引用（sys_role_menu.role_id → sys_role、fu_plan_node.plan_id / parent_id → fu_plan /
 *       fu_plan_node、fe_form_version_field.version_id → fe_form_version）——按「源 ID → 目标 ID」映射改写，
 *       引用不到的行丢弃；
 *   <li>编码引用（form_code / option_set_code / dict_code / set_code 等字符串列）——整包同序导入后自然一致， 不改写。
 * </ul>
 *
 * <p>用户引用列（created_by / updated_by / published_by）置空：账号不随模板复制（A-FLD-014），置空比留下 源租户用户 ID
 * 更安全；导出侧的敏感信息检查与拦截随 E12-T03。
 *
 * <p>角色权限与新租户可分配范围取交集：sys_role_menu 的 menu_id 须落在目标租户生效可分配集（TenantScopeService 口径）内，
 * 超出（模块未开通、敏感未放开、菜单不存在）即丢弃。
 */
public final class ConfigImportSpecs {

    /** 用户引用列（sys_user.id）：导入时一律置空（账号不复制，A-FLD-014）。 */
    public static final Set<String> USER_REFERENCE_COLUMNS =
            Set.of("created_by", "updated_by", "published_by");

    /** 注册表全部导入规格（与 ConfigTableRegistry.ENTRIES 同序）。 */
    public static final List<ConfigImportSpec> SPECS =
            List.of(
                    // ---- base：字典、参数、功能开关（自然键合并，无数值引用）----
                    ConfigImportSpec.copy("cfg_dict", List.of("code")),
                    ConfigImportSpec.copy("cfg_dict_item", List.of("dict_code", "item_code")),
                    ConfigImportSpec.copy("cfg_param", List.of("param_key")),
                    ConfigImportSpec.copy("cfg_feature", List.of("feature_code")),
                    // ---- base：角色模板与权限（role_id 重映射；菜单按钮取可分配范围交集）----
                    ConfigImportSpec.copy("sys_role", List.of("code")),
                    new ConfigImportSpec(
                            "sys_role_menu",
                            ConfigImportMode.LINK_ROWS,
                            List.of("role_id", "menu_id"),
                            Map.of("role_id", "sys_role"),
                            true),
                    ConfigImportSpec.copy(
                            "sys_role_scope",
                            List.of("role_id", "scope_type"),
                            Map.of("role_id", "sys_role")),
                    new ConfigImportSpec(
                            "sys_role_scope_dept",
                            ConfigImportMode.DROP_ROWS,
                            List.of(),
                            Map.of(),
                            false),
                    ConfigImportSpec.copy(
                            "sys_role_field_policy",
                            List.of("role_id", "resource", "field"),
                            Map.of("role_id", "sys_role")),
                    // ---- followup：字段字典与表单（version_id 重映射；option_set_code 等编码引用不改写）----
                    ConfigImportSpec.copy("fe_field", List.of("form_code", "field_key")),
                    ConfigImportSpec.copy("fe_option_set", List.of("code")),
                    ConfigImportSpec.copy("fe_option", List.of("set_code", "option_code")),
                    ConfigImportSpec.copy("fe_form", List.of("code")),
                    ConfigImportSpec.copy("fe_form_version", List.of("form_code", "version")),
                    ConfigImportSpec.copy(
                            "fe_form_version_field",
                            List.of("version_id", "field_key"),
                            Map.of("version_id", "fe_form_version")),
                    ConfigImportSpec.copy("fe_rule", List.of("form_code", "rule_id")),
                    // ---- followup：计划模板（plan_id / parent_id 重映射；节点行按计划版本整组替换）----
                    ConfigImportSpec.copy("fu_plan", List.of("code", "version")),
                    new ConfigImportSpec(
                            "fu_plan_node",
                            ConfigImportMode.REPLACE_ROWS,
                            List.of(),
                            Map.of("plan_id", "fu_plan", "parent_id", "fu_plan_node"),
                            false),
                    // ---- followup：通知设置（事件规则合并；单行设置表按固定 id upsert）----
                    ConfigImportSpec.copy("ntf_rule", List.of("event_type")),
                    new ConfigImportSpec(
                            "ntf_setting",
                            ConfigImportMode.SINGLE_ROW,
                            List.of("id"),
                            Map.of(),
                            false));

    private static final Map<String, ConfigImportSpec> BY_TABLE = index(SPECS);

    static {
        validate(SPECS);
    }

    private ConfigImportSpecs() {}

    /** 按表名取导入规格；未登记返回 null（STRUCTURE_ONLY 表无导入规格，导入侧不落行）。 */
    public static ConfigImportSpec of(String table) {
        return BY_TABLE.get(table);
    }

    /** 自检：覆盖注册表全部 COPY 条目、不登记 STRUCTURE_ONLY / 患者数据表、引用指向注册表内 的表、键与方式互相匹配。写错在类加载即失败。 */
    static void validate(List<ConfigImportSpec> specs) {
        Map<String, ConfigTableEntry> entries = new LinkedHashMap<>();
        for (ConfigTableEntry entry : ConfigTableRegistry.ENTRIES) {
            entries.put(entry.table(), entry);
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (ConfigImportSpec spec : specs) {
            if (!seen.add(spec.table())) {
                throw new IllegalStateException("导入规格存在重复表名: " + spec.table());
            }
            ConfigTableEntry entry = entries.get(spec.table());
            if (entry == null) {
                throw new IllegalStateException("导入规格登记了注册表之外的表: " + spec.table());
            }
            if (ConfigTableRegistry.NEVER_EXPORT_TABLES.contains(spec.table())) {
                throw new IllegalStateException("患者 / 业务数据表不允许登记导入规格: " + spec.table());
            }
            if (entry.mode() != ConfigCopyMode.COPY) {
                throw new IllegalStateException(
                        "STRUCTURE_ONLY 表没有可导入的行，不应登记导入规格: " + spec.table());
            }
            if (spec.mode() != ConfigImportMode.DROP_ROWS
                    && spec.mode() != ConfigImportMode.REPLACE_ROWS
                    && spec.keyColumns().isEmpty()) {
                throw new IllegalStateException("导入规格缺少自然键: " + spec.table());
            }
            boolean onlySelfReferences =
                    spec.references().values().stream().allMatch(spec.table()::equals);
            if (spec.mode() == ConfigImportMode.REPLACE_ROWS
                    && (spec.references().isEmpty() || onlySelfReferences)) {
                throw new IllegalStateException("REPLACE_ROWS 至少需要一个指向其它表的分组引用: " + spec.table());
            }
            if (spec.menuRangeFilter() && spec.mode() != ConfigImportMode.LINK_ROWS) {
                throw new IllegalStateException("可分配范围过滤只适用于关联表（LINK_ROWS）: " + spec.table());
            }
            for (String referenced : spec.references().values()) {
                if (!entries.containsKey(referenced)) {
                    throw new IllegalStateException(
                            "导入规格引用了注册表之外的表: " + spec.table() + "." + referenced);
                }
            }
        }
        for (ConfigTableEntry entry : ConfigTableRegistry.ENTRIES) {
            if (entry.mode() == ConfigCopyMode.COPY && !seen.contains(entry.table())) {
                throw new IllegalStateException("注册表 COPY 条目缺少导入规格: " + entry.table());
            }
        }
    }

    private static Map<String, ConfigImportSpec> index(List<ConfigImportSpec> specs) {
        Map<String, ConfigImportSpec> map = new LinkedHashMap<>();
        specs.forEach(spec -> map.put(spec.table(), spec));
        return map;
    }
}
