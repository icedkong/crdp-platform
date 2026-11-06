package com.crdp.platform.configtemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配置表注册表（E12-T01 / A-FLD-014，tech-plan §6「配置模板」）。
 *
 * <p>导出（E12-T01）与导入（E12-T02）只处理本注册表显式登记的表——「配置表在注册表中显式标注，未标注的表一律不 导出」。登记范围 =
 * A-FLD-014「复制」清单在当前租户库已存在的表 + 「只复制结构」两类；报告单类型 / 字段集、 首页布局、对比规则等配置表随后续 Epic（E16 / E17 /
 * E24）落地时补充登记。
 *
 * <p><b>包含与排除规则（E12-T03）</b>：租户库每张表都必须显式分类——登记（{@link #ENTRIES}）或排除（ {@link
 * #NEVER_EXPORT_TABLES}），完备性由 {@code ConfigTableCoverageTest}（迁移 CREATE TABLE 全量对照）把关，
 * 杜绝「忘了分类」的灰色表。
 *
 * <p>双保险：患者与业务数据表（{@link #NEVER_EXPORT_TABLES}）登记即类初始化失败——配置模板的验收是「导出包
 * 只含配置，不含患者数据」，这道闸门在编译期与启动期都要挡住。
 */
public final class ConfigTableRegistry {

    /** 注册表条目（按导出包顺序）。 */
    public static final List<ConfigTableEntry> ENTRIES =
            List.of(
                    // ---- base：字典、参数、功能开关（A-FLD-014「复制」）----
                    entry("cfg_dict", "base", "业务字典", "租户自有业务字典类型（含患者类型的本院自有类型）"),
                    entry(
                            "cfg_dict_item",
                            "base",
                            "业务字典",
                            "业务字典项：患者类型等取值随模板复制；术者取值按「只复制结构」剔除（A-FLD-014）",
                            Map.of("dict_code", "surgeon")),
                    entry("cfg_param", "base", "统计口径参数", "租户参数覆盖（统计口径等，A-FLD-014「统计口径」）"),
                    entry("cfg_feature", "base", "功能开关", "按租户功能开关（医院间差异，A-FLD-014「功能开关」）"),
                    // ---- base：角色模板与权限（用户账号本身不复制）----
                    entry("sys_role", "base", "角色模板与权限", "租户角色（业务角色模板；导入时与新租户可分配范围取交集）"),
                    entry("sys_role_menu", "base", "角色模板与权限", "角色—菜单授权"),
                    entry("sys_role_scope", "base", "角色模板与权限", "角色数据范围"),
                    entry("sys_role_scope_dept", "base", "角色模板与权限", "自定义科室范围（引用 sys_dept，导入时重映射）"),
                    entry("sys_role_field_policy", "base", "角色模板与权限", "角色字段策略"),
                    // ---- followup：字段字典与表单（A-FLD-014「字段字典与表单」）----
                    entry("fe_field", "followup", "字段字典与表单", "字段字典（显示名、选项、范围、必填、条件显示、派生公式）"),
                    entry("fe_option_set", "followup", "字段字典与表单", "选项集定义"),
                    entry("fe_option", "followup", "字段字典与表单", "选项集取值"),
                    entry("fe_form", "followup", "字段字典与表单", "表单定义"),
                    entry("fe_form_version", "followup", "字段字典与表单", "表单版本（旧版本可读，随模板复制）"),
                    entry("fe_form_version_field", "followup", "字段字典与表单", "表单版本字段快照"),
                    entry("fe_rule", "followup", "字段字典与表单", "跨字段校验规则"),
                    // ---- followup：计划模板（A-FLD-014「随访计划模板」）----
                    entry("fu_plan", "followup", "随访计划模板", "计划版本（code + version，每版本一行）"),
                    entry("fu_plan_node", "followup", "随访计划模板", "子计划与节点参数（偏移、提醒、窗口、周期）"),
                    // ---- followup：通知设置（A-FLD-014「通知设置」）----
                    entry("ntf_rule", "followup", "通知设置", "通知规则（逾期、报告单归档等事件的提醒规则）"),
                    entry("ntf_setting", "followup", "通知设置", "通知渠道与全局设置"),
                    // ---- followup：只复制结构（A-FLD-014「术者、病区 / 中心」）----
                    structure(
                            "ward",
                            "followup",
                            "病区 / 中心（只复制结构）",
                            "病区 / 中心字典：保留定义不带取值；默认负责人等引用用户的列随取值一并不入包"));

    /**
     * 显式排除表（E12-T03 与 {@code ConfigTableCoverageTest} 共同保证租户库表全量分类）。 登记进 {@link #ENTRIES}
     * 会在类加载时直接失败 （防回归双保险；主闸门是白名单本身）。排除原因分四类：
     *
     * <ul>
     *   <li>患者与业务数据（A-FLD-014「不复制」清单：患者及子表、随访记录与任务、沟通记录、报告单与文件、审计、用户账号）；
     *   <li>组织架构（sys_org / sys_dept：不在 A-FLD-014 复制清单，科室范围行导入时丢弃，见 E12-T02）；
     *   <li>租户自身信息（cfg_brand 品牌标识：属新租户自己的配置，不从源租户带过来）；
     *   <li>凭据与集成密钥（sys_webhook 含 secret_token、加密数据密钥）：凭据绝不随模板复制（OQ-90 ② 定稿）。
     * </ul>
     */
    public static final Set<String> NEVER_EXPORT_TABLES =
            Set.of(
                    // 患者与子表（ControlledTables 口径）
                    "pt_patient",
                    "pt_baseline_section",
                    "pt_baseline_lock",
                    "pt_contact",
                    "pt_status_history",
                    "pt_ward_history",
                    "pt_patient_summary",
                    // 随访业务数据
                    "fu_enrollment",
                    "fu_task",
                    "fu_record",
                    "fu_contact_log",
                    "fu_contact_audio",
                    "fu_saved_filter",
                    // 表单记录与字段历史（数据，不是模板）
                    "fe_record",
                    "fe_field_history",
                    // 用户账号（角色复制、账号不复制）
                    "sys_user",
                    "sys_user_role",
                    "sys_user_ward",
                    "sys_user_dept_scope",
                    "sys_password_history",
                    // 组织架构（不在 A-FLD-014 复制清单；sys_dept 不复制 → 科室范围行导入时丢弃）
                    "sys_org",
                    "sys_dept",
                    // 租户自身信息（品牌标识属新租户自己的配置）
                    "cfg_brand",
                    // 凭据与集成（Webhook 含 secret_token，凭据绝不随模板复制）
                    "sys_webhook",
                    // 通知数据与个人偏好
                    "ntf_message",
                    "ntf_send_log",
                    "ntf_user_pref",
                    // 审计与作业（aud_platform_access_log 在平台库，不属租户库表，不在本清单）
                    "aud_log",
                    "aud_login_log",
                    "aud_data_access_log",
                    "aud_chain_head",
                    "aud_archive",
                    "job_export",
                    "job_export_approval",
                    "job_tenant_run",
                    // 文件、密钥、outbox
                    "sys_file",
                    "sys_webhook_delivery",
                    "crypto_data_key",
                    "crypto_reencrypt_progress",
                    "sys_outbox",
                    // 变更日志类（配置变更留痕随审计，不随模板复制）
                    "cfg_dict_log",
                    "cfg_param_log",
                    "fe_field_dict_log",
                    "fu_plan_log");

    private static final Map<String, ConfigTableEntry> BY_TABLE = index(ENTRIES);

    static {
        validate(ENTRIES);
    }

    private ConfigTableRegistry() {}

    /** 注册表全部表名（导出包顺序）。 */
    public static List<String> tables() {
        return ENTRIES.stream().map(ConfigTableEntry::table).toList();
    }

    /** 按表名取条目；未登记返回 null（导出方据此跳过）。 */
    public static ConfigTableEntry entry(String table) {
        return BY_TABLE.get(table);
    }

    /** 自检：表名唯一、格式合法、患者与业务数据表未被登记。条目写错在类加载即失败（fail-fast）， 不等导出时才暴露。 */
    static void validate(List<ConfigTableEntry> entries) {
        Set<String> seen = new LinkedHashSet<>();
        for (ConfigTableEntry e : entries) {
            if (!seen.add(e.table())) {
                throw new IllegalStateException("配置表注册表存在重复表名: " + e.table());
            }
            if (NEVER_EXPORT_TABLES.contains(e.table())) {
                throw new IllegalStateException(
                        "患者 / 业务数据表不允许登记为配置表: " + e.table() + "（A-FLD-014 不复制清单）");
            }
        }
    }

    private static Map<String, ConfigTableEntry> index(List<ConfigTableEntry> entries) {
        return entries.stream()
                .collect(
                        Collectors.toMap(
                                ConfigTableEntry::table,
                                Function.identity(),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new));
    }

    private static ConfigTableEntry entry(
            String table, String module, String category, String description) {
        return new ConfigTableEntry(
                table, module, ConfigCopyMode.COPY, category, description, null);
    }

    private static ConfigTableEntry entry(
            String table,
            String module,
            String category,
            String description,
            Map<String, String> excludeWhen) {
        return new ConfigTableEntry(
                table, module, ConfigCopyMode.COPY, category, description, excludeWhen);
    }

    private static ConfigTableEntry structure(
            String table, String module, String category, String description) {
        return new ConfigTableEntry(
                table, module, ConfigCopyMode.STRUCTURE_ONLY, category, description, null);
    }

    /** 供测试与工具读取的防御性拷贝。 */
    public static List<ConfigTableEntry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }
}
