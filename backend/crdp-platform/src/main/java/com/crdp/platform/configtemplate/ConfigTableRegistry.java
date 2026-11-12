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
                    // ---- followup：器械→计划映射（E21-T05 / C-FU-003；device_type 唯一，
                    //      plan_code 为计划族编码引用不改写——fu_plan 整包同序导入后自然一致）----
                    entry(
                            "fu_plan_mapping",
                            "followup",
                            "随访计划模板",
                            "器械类型→计划族映射（医院 C 按器械类型自动入组，C-FU-003）"),
                    // ---- followup：通知设置（A-FLD-014「通知设置」）----
                    entry("ntf_rule", "followup", "通知设置", "通知规则（逾期、报告单归档等事件的提醒规则）"),
                    entry("ntf_setting", "followup", "通知设置", "通知渠道与全局设置"),
                    // ---- followup：报告单设置（E16-T01 / A-ATT-003、011、012；模板先于类型 / 名称，
                    //      rpt_name.indicator_tpl_id 数值引用在导入时按已导入的模板行重映射）----
                    entry(
                            "rpt_indicator_tpl",
                            "followup",
                            "报告单设置",
                            "指标模板（租户管理员维护，A-ATT-012 / C-SCAN-004）"),
                    entry("rpt_indicator_item", "followup", "报告单设置", "指标模板项目（名称、代码、参考范围、单位、顺序）"),
                    entry(
                            "rpt_type",
                            "followup",
                            "报告单设置",
                            "资料类型（编码、字段集引用 fe_form、名称必填、默认电子化状态、排序、启停）"),
                    entry("rpt_name", "followup", "报告单设置", "名称字典（类型下名称、绑定指标模板、电子化状态覆盖）"),
                    // ---- followup：报告单设置（E23-T03 / A-ATT-013；A 心脏超声、C 程控报告单同一机制，
                    //      rpt_mapping.indicator_tpl_id 数值引用在导入时按已导入的模板行重映射）----
                    entry(
                            "rpt_mapping",
                            "followup",
                            "报告单设置",
                            "字段映射（来源类型、目标记录与基线时相、绑定指标模板、附带写入、启停）"),
                    entry(
                            "rpt_mapping_item",
                            "followup",
                            "报告单设置",
                            "字段映射项目（项目代码 → 目标字段键，itemCode 可为固定 REPORT_DATE）"),
                    // ---- followup：对比标红规则（E17-T01 / A-FU-027、C-FU-011 随 E21）----
                    entry(
                            "fu_compare_rule",
                            "followup",
                            "对比标红规则",
                            "录入时对比提示规则（每条可开关、调阈值；form_code / rule_code 自然键合并，"
                                    + "引用的 fe_field 字段随字段字典整包同序导入后自然一致）"),
                    // ---- followup：首页卡片配置（E19-T06 / A-DASH-013「随配置模板复制」；role_id 重映射，
                    //      card_code 为编码引用不改写——目录行不入模板、由目标租户迁移种子提供）----
                    entry("dash_role_layout", "followup", "首页卡片配置", "按角色的首页卡片显示与顺序（含「允许用户隐藏」）"),
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
                    // 植入记录与电极（医院 C 患者数据，E21-T01；绝不随配置模板导出）
                    "pt_implant",
                    "pt_lead",
                    "pt_implant_lead",
                    // 随访编号年度流水（医院 C 患者编号计数，E21-T10；本院自增数据，绝不随模板复制）
                    "pt_visit_no_seq",
                    // 随访业务数据
                    "fu_enrollment",
                    "fu_task",
                    "fu_record",
                    "fu_contact_log",
                    "fu_contact_audio",
                    "fu_saved_filter",
                    // 报告单业务数据（E16-T02；配置表 rpt_type 等四张在 ENTRIES，数据表不入模板）
                    "rpt_report",
                    "rpt_report_link",
                    "rpt_report_file",
                    "rpt_indicator_value",
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
                    // 导入作业、暂存行与校验问题（E18-T02 / E18-T03：业务数据 + 含密文载荷，绝不随模板复制）
                    "imp_job",
                    "imp_row",
                    "imp_issue",
                    // 导入提交批次与写数清单（E18-T05：回滚凭据属业务数据，绝不随模板复制）
                    "imp_batch",
                    "imp_batch_write",
                    // 统计事实表（E19-T01：派生只读数据，每日按本院业务数据重建，不入模板）
                    "st_node_occurrence",
                    // 首页卡片目录与用户偏好（E19-T06：目录随卡片发版以迁移落库，属代码能力不是租户数据；
                    // dash_user_pref 是个人隐藏偏好，同 ntf_user_pref 不复制）
                    "dash_card",
                    "dash_user_pref",
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
