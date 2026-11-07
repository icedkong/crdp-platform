package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E12-T06 端到端（验收原文：导入后配置一致）。E12-T02 的 IT 用少量标记行验证引用改写与负向，E12-T05 的演练走「新建租户」路径 且只比三域（表单 / 计划 /
 * 报告单）；本用例补齐「导出 → 应用到既有租户 → 全注册表逐表一致」的完整往返矩阵：
 *
 * <ol>
 *   <li><b>等价表</b>（自然键 upsert、源值胜，OQ-91 ①）：导入后目标租户业务列与源逐表完全一致——字典 / 参数 / 功能开关、 字段字典与表单七表、
 *       计划、通知规则与设置、报告单六表、对比规则；数值引用不比自增 ID，一律 join 到本院行取值（E12-T05 同口径）；
 *   <li><b>并集表</b>（角色三表，OQ-91 ①「INSERT IGNORE 并集 / 目标独有行保留」）：源行全量落地（多重集包含），目标既有角色与授权不被删除；
 *       sys_role_menu 只断言落在目标可分配范围内的源授权（范围外按交集丢弃是 A-FLD-014 口径，不属配置漂移）； sys_role_scope_dept
 *       行全部丢弃（OQ-91 ④：sys_dept 组织架构不随模板复制）；
 *   <li><b>整组替换表</b>（fu_plan_node，OQ-91 ②）：目标先放同自然键旧计划 + 多余节点，导入后该计划版本的节点组与源完全一致（多余节点被删）；
 *   <li><b>源值胜收敛</b>：目标先行分叉的 cfg_param / 字典项 / ntf_rule / ntf_setting 单行表，导入后全部收敛到源值；
 *   <li><b>排除与只复制结构</b>（A-FLD-014、OQ-90 ②③）：术者取值不入包（详情 excludedRowCount）、目标病区行与患者业务表零变化；
 *   <li><b>漂移收敛重放</b>：源改值 / 加行后重新导出再应用，目标跟上新值且行数不叠加（重复导入仍一致）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis）；自建自清（两侧租户库的标记行、ntf_setting 原值、平台模板与审计）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigTemplateRoundTripMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long SOURCE_TENANT = 1L; // demoa（导出侧）
    private static final long TARGET_TENANT = 2L; // demob（应用侧，既有租户）
    private static final String TEMPLATE_NAME = "E12-T06 往返一致性模板（示例）";

    // ---- 标记（全部虚构，AGENTS §5.5）----
    private static final String DICT = "e12t06_dict";
    private static final String ITEM_1 = "e12t06_item_1";
    private static final String ITEM_2 = "e12t06_item_2";
    private static final String ITEM_MERGE = "e12t06_merge";
    private static final String SURGEON_ITEM = "E12T06-SURGEON-1";
    private static final String PARAM = "e12t06.marker.param";
    private static final String PARAM_V1 = "源值甲（示例）";
    private static final String PARAM_V2 = "源值乙（示例）";
    private static final String FEATURE = "e12t06_feature";
    private static final String OPTION_SET = "e12t06_os";
    private static final String FORM = "E12T06_FORM";
    private static final String RULE = "e12t06_rule";
    private static final String PLAN = "E12T06_PLAN";
    private static final String NTF_RULE_EVENT = "E12T06_RULE";
    private static final String RPT_TPL = "E12-T06 指标模板（示例）";
    private static final String RPT_TPL_REMARK = "E12-T06（示例）";
    private static final String RPT_TYPE = "E12T06_TYPE";
    private static final String RPT_NAME = "E12-T06 名称（示例）";
    private static final String RPT_MAP = "E12T06_MAP";
    private static final String ROLE = "E12T06_ROLE";
    private static final String ROLE_TARGET_ONLY = "E12T06_TARGET_ONLY_ROLE";
    private static final long ASSIGNABLE_MENU = 10L; // 工作台（base 固定能力，恒可分配）
    private static final String WARD_ROWS_QUERY =
            "SELECT code, name, ward_type, alias, sort_no, status, default_owner_id FROM ward";
    private static final String SETTING_QUERY =
            "SELECT dnd_start, dnd_end, sms_daily_limit, overdue_sms_off_allowed FROM"
                    + " ntf_setting WHERE id = 1";

    private static JdbcTemplate platform;
    private static JdbcTemplate demoa;
    private static JdbcTemplate demob;
    private static final List<Long> templateIds = new ArrayList<>();
    private static Map<String, Object> ntfSettingSourceBefore;
    private static Map<String, Object> ntfSettingTargetBefore;
    private static List<Map<String, Object>> targetWardBefore;
    private static long targetPatientsBefore;
    private static long targetTasksBefore;

    @Autowired private TestRestTemplate rest;

    private static JdbcTemplate connect() {
        if (platform == null) {
            String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
            String params = "?useSSL=false&allowPublicKeyRetrieval=true";
            platform =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/crdp_platform" + params,
                                    "crdp_app",
                                    "DevOnly#app2026"));
            demoa =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/t_demoa" + params, "crdp_app", "DevOnly#app2026"));
            demob =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/t_demob" + params, "crdp_app", "DevOnly#app2026"));
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        cleanMarkers(demoa);
        cleanMarkers(demob);
        cleanRole(demob, ROLE_TARGET_ONLY);
        // demob 先行分叉与 demoa 漂移改动的 ntf_setting 恢复原值
        if (ntfSettingTargetBefore != null) {
            restoreSetting(demob, ntfSettingTargetBefore);
        }
        if (ntfSettingSourceBefore != null) {
            restoreSetting(demoa, ntfSettingSourceBefore);
        }
        for (Long id : templateIds) {
            platform.update("DELETE FROM plt_config_template WHERE id = ?", id);
            AuditTableMaintenance.delete(
                    platform,
                    "DELETE FROM plt_audit_log WHERE event_type IN"
                            + " ('CONFIG_TEMPLATE_EXPORT', 'CONFIG_TEMPLATE_APPLY') AND object_id = ?",
                    String.valueOf(id));
        }
    }

    private static void restoreSetting(JdbcTemplate db, Map<String, Object> original) {
        db.update(
                "UPDATE ntf_setting SET dnd_start = ?, dnd_end = ?, sms_daily_limit = ?,"
                        + " overdue_sms_off_allowed = ?, updated_by = NULL WHERE id = 1",
                original.get("dnd_start"),
                original.get("dnd_end"),
                original.get("sms_daily_limit"),
                original.get("overdue_sms_off_allowed"));
    }

    private static void cleanMarkers(JdbcTemplate db) {
        cleanRole(db, ROLE);
        db.update(
                "DELETE FROM fu_plan_node WHERE plan_id IN (SELECT id FROM fu_plan WHERE code = ?)",
                PLAN);
        db.update("DELETE FROM fu_plan WHERE code = ?", PLAN);
        db.update("DELETE FROM fe_form_version_field WHERE form_code = ?", FORM);
        db.update("DELETE FROM fe_form_version WHERE form_code = ?", FORM);
        db.update("DELETE FROM fe_field WHERE form_code = ?", FORM);
        db.update("DELETE FROM fe_rule WHERE form_code = ?", FORM);
        db.update("DELETE FROM fe_form WHERE code = ?", FORM);
        db.update("DELETE FROM fe_option WHERE set_code = ?", OPTION_SET);
        db.update("DELETE FROM fe_option_set WHERE code = ?", OPTION_SET);
        db.update("DELETE FROM fu_compare_rule WHERE form_code = ?", FORM);
        db.update(
                "DELETE FROM rpt_mapping_item WHERE mapping_id = ?",
                idByCode(db, "rpt_mapping", RPT_MAP));
        db.update("DELETE FROM rpt_mapping WHERE code = ?", RPT_MAP);
        db.update("DELETE FROM rpt_name WHERE type_code = ?", RPT_TYPE);
        db.update("DELETE FROM rpt_type WHERE code = ?", RPT_TYPE);
        db.update(
                "DELETE FROM rpt_indicator_item WHERE tpl_id IN (SELECT id FROM"
                        + " rpt_indicator_tpl WHERE name = ? AND remark = ?)",
                RPT_TPL,
                RPT_TPL_REMARK);
        db.update(
                "DELETE FROM rpt_indicator_tpl WHERE name = ? AND remark = ?",
                RPT_TPL,
                RPT_TPL_REMARK);
        db.update("DELETE FROM ntf_rule WHERE event_type = ?", NTF_RULE_EVENT);
        db.update("DELETE FROM cfg_feature WHERE feature_code = ?", FEATURE);
        db.update("DELETE FROM cfg_param WHERE param_key = ?", PARAM);
        db.update(
                "DELETE FROM cfg_dict_item WHERE dict_code = ? OR item_code = ?",
                DICT,
                SURGEON_ITEM);
        db.update("DELETE FROM cfg_dict WHERE code = ?", DICT);
    }

    private static void cleanRole(JdbcTemplate db, String code) {
        Long roleId = idByCode(db, "sys_role", code);
        if (roleId != null) {
            db.update("DELETE FROM sys_role_scope_dept WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role_field_policy WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role_scope WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role WHERE id = ?", roleId);
        }
    }

    private static Long idByCode(JdbcTemplate db, String table, String code) {
        List<Long> ids =
                db.queryForList("SELECT id FROM `" + table + "` WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static Long planVersionId(JdbcTemplate db) {
        List<Long> ids =
                db.queryForList(
                        "SELECT id FROM fu_plan WHERE code = ? AND version = 1", Long.class, PLAN);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static Long tplId(JdbcTemplate db) {
        List<Long> ids =
                db.queryForList(
                        "SELECT id FROM rpt_indicator_tpl WHERE name = ? AND remark = ?",
                        Long.class,
                        RPT_TPL,
                        RPT_TPL_REMARK);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---------------------------------------------------------------- 配置一致性比对清单

    /**
     * 等价表：导入后目标与源的业务列多重集完全一致。{db} 占位符替换为比对侧库名（t_demoa / t_demob）。 数值引用（version_id / plan_id /
     * tpl_id / mapping_id）一律 join 到本院行取业务值，不比跨库自增 ID；不比 id / 用户引用 / 时间列（导入置空与 ON UPDATE
     * CURRENT_TIMESTAMP 属既有口径，见 OQ-90 ④ / OQ-91 ③）。
     */
    private static final String[][] EQUALITY_QUERIES = {
        // ---- base：字典、参数、功能开关（A-FLD-014「复制」）----
        {"cfg_dict", "SELECT code, name, description, status FROM `{db}`.cfg_dict"},
        {
            "cfg_dict_item",
            "SELECT dict_code, item_code, label, aliases, sort_no, source, status FROM"
                    + " `{db}`.cfg_dict_item WHERE dict_code <> 'surgeon'"
        },
        {"cfg_param", "SELECT param_key, param_value, remark FROM `{db}`.cfg_param"},
        {"cfg_feature", "SELECT feature_code, enabled FROM `{db}`.cfg_feature"},
        // ---- followup：字段字典与表单（fe_* 七表，E12-T05 同口径查询）----
        {
            "fe_form",
            "SELECT code, name, usage_type, current_version, categories, status FROM"
                    + " `{db}`.fe_form"
        },
        {
            "fe_field",
            "SELECT form_code, field_key, field_code, label, category_key, type, unit, decimals,"
                    + " hard_range, soft_range, option_set_code, required, visible_when,"
                    + " required_when, derived_def, sensitive_flag, storage_type, help_text,"
                    + " sort_no, status FROM `{db}`.fe_field"
        },
        {"fe_option_set", "SELECT code, name FROM `{db}`.fe_option_set"},
        {
            "fe_option",
            "SELECT set_code, option_code, label, ordinal, exclusive, needs_detail, aliases,"
                    + " sort_no, status FROM `{db}`.fe_option"
        },
        {
            "fe_form_version",
            "SELECT form_code, version, categories, field_count FROM `{db}`.fe_form_version"
        },
        {
            "fe_form_version_field",
            "SELECT v.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key,"
                    + " f.type, f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code,"
                    + " f.required, f.visible_when, f.required_when, f.derived_def,"
                    + " f.sensitive_flag, f.storage_type, f.help_text, f.sort_no"
                    + " FROM `{db}`.fe_form_version_field f JOIN `{db}`.fe_form_version v"
                    + " ON v.id = f.version_id"
        },
        {
            "fe_rule",
            "SELECT form_code, rule_id, category_key, target_field, severity, rule_type, params,"
                    + " message, sort_no, status FROM `{db}`.fe_rule"
        },
        // ---- followup：计划模板（fu_plan_node join 计划版本 → 整组替换后与源一致）----
        {
            "fu_plan",
            "SELECT code, name, version, status, start_date_field, cycle_type, cycle_years,"
                    + " auto_enroll, remark FROM `{db}`.fu_plan"
        },
        {
            "fu_plan_node",
            "SELECT p.code, p.version, n.node_type, n.name, n.repeat_mode, n.repeat_count,"
                    + " n.cycle_value, n.cycle_unit, n.basis, n.offset_value, n.offset_unit,"
                    + " n.remind_value, n.remind_unit, n.window_before_value, n.window_before_unit,"
                    + " n.window_after_value, n.window_after_unit, n.task_type, n.owner_type,"
                    + " n.form_code, n.passed_window_action, n.enroll_mode, n.sort_no"
                    + " FROM `{db}`.fu_plan_node n JOIN `{db}`.fu_plan p ON p.id = n.plan_id"
        },
        // ---- followup：通知设置（ntf_setting 单行表按固定 id upsert）----
        {
            "ntf_rule",
            "SELECT event_type, enabled, advance_days, send_time, channels, recipients,"
                    + " sms_template_code FROM `{db}`.ntf_rule"
        },
        {
            "ntf_setting",
            "SELECT dnd_start, dnd_end, sms_daily_limit, overdue_sms_off_allowed FROM"
                    + " `{db}`.ntf_setting"
        },
        // ---- followup：报告单设置（六表，E16-T01 / E23-T03；E12-T05 同口径查询）----
        {"rpt_indicator_tpl", "SELECT name, status, remark FROM `{db}`.rpt_indicator_tpl"},
        {
            "rpt_indicator_item",
            "SELECT t.name AS tpl_name, i.item_name, i.item_code, i.unit, i.ref_range,"
                    + " i.ref_range_male, i.ref_range_female, i.sort_no"
                    + " FROM `{db}`.rpt_indicator_item i JOIN `{db}`.rpt_indicator_tpl t"
                    + " ON t.id = i.tpl_id"
        },
        {
            "rpt_type",
            "SELECT code, name, form_code, name_required, default_digitize_status, sort_no,"
                    + " status, remark FROM `{db}`.rpt_type"
        },
        {
            "rpt_name",
            "SELECT n.type_code, n.name, t.name AS tpl_name, n.digitize_status_override,"
                    + " n.sort_no, n.status FROM `{db}`.rpt_name n"
                    + " LEFT JOIN `{db}`.rpt_indicator_tpl t ON t.id = n.indicator_tpl_id"
        },
        {
            "rpt_mapping",
            "SELECT m.code, m.name, m.type_code, m.target_type, m.target_form_code, m.phase,"
                    + " t.name AS tpl_name, m.extra_writes, m.sort_no, m.status, m.remark"
                    + " FROM `{db}`.rpt_mapping m"
                    + " LEFT JOIN `{db}`.rpt_indicator_tpl t ON t.id = m.indicator_tpl_id"
        },
        {
            "rpt_mapping_item",
            "SELECT p.code AS mapping_code, i.item_code, i.target_field_key, i.extra_writes,"
                    + " i.sort_no FROM `{db}`.rpt_mapping_item i"
                    + " JOIN `{db}`.rpt_mapping p ON p.id = i.mapping_id"
        },
        // ---- followup：对比标红规则（E17-T01 / A-FU-027）----
        {
            "fu_compare_rule",
            "SELECT form_code, rule_code, name, category_key, target_field, rule_type, params,"
                    + " baseline_form_code, baseline_field, message, sort_no, status FROM"
                    + " `{db}`.fu_compare_rule"
        },
    };

    /**
     * 并集表：源行全量落地（多重集包含），目标既有行保留（INSERT IGNORE 并集 / 目标独有角色，OQ-91 ①）。 sys_role_menu 单独处理 （可分配范围交集过滤）。
     */
    private static final String[][] CONTAINMENT_QUERIES = {
        {
            "sys_role",
            "SELECT code, name, built_in, status, role_type, module_code, description FROM"
                    + " `{db}`.sys_role"
        },
        {
            "sys_role_scope",
            "SELECT r.code, s.scope_type FROM `{db}`.sys_role_scope s JOIN `{db}`.sys_role r"
                    + " ON r.id = s.role_id"
        },
        {
            "sys_role_field_policy",
            "SELECT r.code, p.resource, p.field, p.policy FROM `{db}`.sys_role_field_policy p"
                    + " JOIN `{db}`.sys_role r ON r.id = p.role_id"
        },
    };

    private static final String ROLE_MENU_QUERY =
            "SELECT r.code, rm.menu_id FROM `{db}`.sys_role_menu rm JOIN `{db}`.sys_role r"
                    + " ON r.id = rm.role_id";

    // ---------------------------------------------------------------- 用例

    /** 主链路：种标记（源全覆盖 + 目标先行分叉）→ 导出 → 应用 → 全注册表逐表一致。 */
    @Test
    @Order(1)
    void applyMakesTargetConfigMatchSourceAcrossRegistry() {
        connect();
        ntfSettingSourceBefore = demoa.queryForMap(SETTING_QUERY);
        ntfSettingTargetBefore = demob.queryForMap(SETTING_QUERY);
        seedSourceMarkers();
        seedTargetDivergence();
        targetWardBefore = demob.queryForList(WARD_ROWS_QUERY);
        targetPatientsBefore = demob.queryForObject("SELECT COUNT(*) FROM pt_patient", Long.class);
        targetTasksBefore = demob.queryForObject("SELECT COUNT(*) FROM fu_task", Long.class);

        String token = loginPlatform();
        long id = exportTemplate(token);
        assertExportShape(token, id);

        Map<?, ?> applied = applyTemplate(token, id);
        assertThat(
                        ((Number) applied.get("insertedRows")).longValue()
                                + ((Number) applied.get("mergedRows")).longValue())
                .isPositive();

        assertEqualTables();
        assertContainmentTables(token);
        assertConvergedRows();
        assertNumericReferencesRewrittenToLocalRows();
        assertExcludedAndStructureOnlyUnchanged();
        assertTargetOnlyRowsPreserved();
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                        + " 'CONFIG_TEMPLATE_APPLY' AND object_id = ?",
                                Integer.class,
                                String.valueOf(id)))
                .isPositive();
    }

    /** 漂移收敛重放：源改值 / 加行后重新导出再应用，目标跟上新值、行数不叠加。 */
    @Test
    @Order(2)
    void reexportAfterSourceDriftConvergesTargetAgain() {
        connect();
        if (ntfSettingSourceBefore == null) {
            return; // Order(1) 未执行（单跑本用例时跳过，避免半途状态）
        }
        demoa.update("UPDATE cfg_param SET param_value = ? WHERE param_key = ?", PARAM_V2, PARAM);
        demoa.update(
                "UPDATE cfg_dict_item SET label = '源标签乙（示例）' WHERE dict_code = ? AND"
                        + " item_code = ?",
                DICT,
                ITEM_1);
        demoa.update(
                "UPDATE cfg_dict_item SET label = '源合并标签乙（示例）' WHERE dict_code = ? AND"
                        + " item_code = ?",
                DICT,
                ITEM_MERGE);
        demoa.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status)"
                        + " VALUES (?, ?, '新增项（示例）', 20, 'TENANT', 'ENABLED')",
                DICT,
                ITEM_2);
        demoa.update("UPDATE ntf_setting SET sms_daily_limit = sms_daily_limit + 1 WHERE id = 1");

        String token = loginPlatform();
        long id = exportTemplate(token);
        applyTemplate(token, id);

        assertEqualTables();
        assertThat(
                        demob.queryForObject(
                                "SELECT param_value FROM cfg_param WHERE param_key = ?",
                                String.class,
                                PARAM))
                .isEqualTo(PARAM_V2);
        assertThat(
                        demob.queryForObject(
                                "SELECT label FROM cfg_dict_item WHERE dict_code = ? AND item_code = ?",
                                String.class,
                                DICT,
                                ITEM_2))
                .isEqualTo("新增项（示例）");
        // 行数不叠加（自然键 upsert 幂等，重放不产生重复行），且与源逐数一致
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM cfg_dict_item WHERE dict_code = ?",
                                Long.class,
                                DICT))
                .isEqualTo(
                        demoa.queryForObject(
                                "SELECT COUNT(*) FROM cfg_dict_item WHERE dict_code = ?",
                                Long.class,
                                DICT))
                .isEqualTo(3L);
        assertThat(demob.queryForMap(SETTING_QUERY)).isEqualTo(demoa.queryForMap(SETTING_QUERY));
    }

    // ---------------------------------------------------------------- 断言

    /** 等价表：两侧业务列多重集完全一致。 */
    private void assertEqualTables() {
        for (String[] query : EQUALITY_QUERIES) {
            List<Map<String, Object>> fromSource =
                    sortedRows(demoa.queryForList(query[1].replace("{db}", "t_demoa")));
            List<Map<String, Object>> fromTarget =
                    sortedRows(demob.queryForList(query[1].replace("{db}", "t_demob")));
            assertThat(fromTarget)
                    .as("导入后 demob.%s 与 demoa 一致（源 %d 行）", query[0], fromSource.size())
                    .isEqualTo(fromSource);
        }
    }

    /** 并集表：源行全量落地（多重集包含），目标独有行不删。 */
    private void assertContainmentTables(String token) {
        for (String[] query : CONTAINMENT_QUERIES) {
            List<Map<String, Object>> fromSource =
                    demoa.queryForList(query[1].replace("{db}", "t_demoa"));
            List<Map<String, Object>> fromTarget =
                    demob.queryForList(query[1].replace("{db}", "t_demob"));
            assertThat(containsAll(new ArrayList<>(fromTarget), fromSource))
                    .as("导入后 demob.%s 落地了 demoa 的全部 %d 行", query[0], fromSource.size())
                    .isTrue();
        }
        // sys_role_menu：源授权按目标可分配范围取交集落地（范围外丢弃是口径，不是配置漂移）
        Set<Long> assignable = assignableMenuIds(token, TARGET_TENANT);
        List<Map<String, Object>> sourceGrants =
                demoa.queryForList(ROLE_MENU_QUERY.replace("{db}", "t_demoa")).stream()
                        .filter(
                                grant ->
                                        assignable.contains(
                                                ((Number) grant.get("menu_id")).longValue()))
                        .toList();
        List<Map<String, Object>> targetGrants =
                demob.queryForList(ROLE_MENU_QUERY.replace("{db}", "t_demob"));
        assertThat(containsAll(new ArrayList<>(targetGrants), sourceGrants))
                .as("导入后 demob.sys_role_menu 落地了 demoa 可分配范围内的全部 %d 条授权", sourceGrants.size())
                .isTrue();
    }

    /** 源值胜收敛：目标先行分叉的行在导入后全部等于源值。 */
    private void assertConvergedRows() {
        assertThat(
                        demob.queryForObject(
                                "SELECT param_value FROM cfg_param WHERE param_key = ?",
                                String.class,
                                PARAM))
                .isEqualTo(PARAM_V1);
        assertThat(
                        demob.queryForObject(
                                "SELECT label FROM cfg_dict_item WHERE dict_code = ? AND item_code = ?",
                                String.class,
                                DICT,
                                ITEM_MERGE))
                .isEqualTo("源合并标签甲（示例）");
        assertThat(
                        demob.queryForObject(
                                "SELECT enabled FROM ntf_rule WHERE event_type = ?",
                                Integer.class,
                                NTF_RULE_EVENT))
                .isEqualTo(1);
        assertThat(demob.queryForMap(SETTING_QUERY)).isEqualTo(ntfSettingSourceBefore);
        // fu_plan 源值胜 + fu_plan_node 整组替换：目标旧计划名收敛、多余节点删除、节点组与源一致
        assertThat(
                        demob.queryForObject(
                                "SELECT name FROM fu_plan WHERE code = ? AND version = 1",
                                String.class,
                                PLAN))
                .isEqualTo("往返验证计划（示例）");
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ?",
                                Long.class,
                                planVersionId(demob)))
                .isEqualTo(3L);
    }

    /** 数值引用改写：目标的引用列挂到本院行，不残留源租户 ID（引用挂接断言，与 E12-T02 IT 同口径）。 */
    private void assertNumericReferencesRewrittenToLocalRows() {
        Long targetVersionId =
                idByForm(
                        demob,
                        "SELECT id FROM fe_form_version WHERE form_code = ? AND version = 1",
                        FORM);
        assertThat(targetVersionId).isNotNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM fe_form_version_field WHERE version_id = ? AND"
                                        + " field_key = 'f001'",
                                Long.class,
                                targetVersionId))
                .isEqualTo(1L);
        Long targetTplId = tplId(demob);
        assertThat(targetTplId).isNotNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT indicator_tpl_id FROM rpt_name WHERE type_code = ? AND"
                                        + " name = ?",
                                Long.class,
                                RPT_TYPE,
                                RPT_NAME))
                .isEqualTo(targetTplId);
        Long targetMappingId = idByCode(demob, "rpt_mapping", RPT_MAP);
        assertThat(targetMappingId).isNotNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM rpt_mapping_item WHERE mapping_id = ? AND"
                                        + " item_code = 'LVEF'",
                                Long.class,
                                targetMappingId))
                .isEqualTo(1L);
        // fu_plan_node：节点挂在本院计划行、子计划自引用闭环（源 ID 不残留）
        Long targetPlanId = planVersionId(demob);
        List<Map<String, Object>> nodes =
                demob.queryForList(
                        "SELECT id, parent_id FROM fu_plan_node WHERE plan_id = ?", targetPlanId);
        assertThat(nodes).hasSize(3);
        Map<String, Object> subplan =
                nodes.stream().filter(n -> n.get("parent_id") == null).findFirst().orElseThrow();
        long subplanId = ((Number) subplan.get("id")).longValue();
        assertThat(
                        nodes.stream()
                                .filter(n -> n.get("parent_id") != null)
                                .allMatch(
                                        n ->
                                                ((Number) n.get("parent_id")).longValue()
                                                        == subplanId))
                .isTrue();
        // 用户引用置空：账号不随模板复制（A-FLD-014）
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM cfg_dict_item WHERE dict_code = ? AND"
                                        + " updated_by IS NOT NULL",
                                Long.class,
                                DICT))
                .isZero();
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM fe_field WHERE form_code = ? AND updated_by"
                                        + " IS NOT NULL",
                                Long.class,
                                FORM))
                .isZero();
    }

    /** 排除与只复制结构：术者取值不入目标、病区行与患者业务表零变化。 */
    private void assertExcludedAndStructureOnlyUnchanged() {
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM cfg_dict_item WHERE dict_code = 'surgeon' AND"
                                        + " item_code = ?",
                                Long.class,
                                SURGEON_ITEM))
                .isZero();
        assertThat(sortedRows(demob.queryForList(WARD_ROWS_QUERY)))
                .isEqualTo(sortedRows(targetWardBefore));
        assertThat(demob.queryForObject("SELECT COUNT(*) FROM pt_patient", Long.class))
                .isEqualTo(targetPatientsBefore);
        assertThat(demob.queryForObject("SELECT COUNT(*) FROM fu_task", Long.class))
                .isEqualTo(targetTasksBefore);
    }

    /** 目标独有行保留：upsert 不删目标自有配置（OQ-91 ① 并集口径）。 */
    private void assertTargetOnlyRowsPreserved() {
        assertThat(idByCode(demob, "sys_role", ROLE_TARGET_ONLY)).isNotNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_scope s JOIN sys_role r ON r.id ="
                                        + " s.role_id WHERE r.code = ?",
                                Long.class,
                                ROLE_TARGET_ONLY))
                .isEqualTo(1L);
        // 科室范围行全部丢弃（OQ-91 ④：sys_dept 不复制，dept_id 无法重映射，不放大目标权限）
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_scope_dept d JOIN sys_role r ON"
                                        + " r.id = d.role_id WHERE r.code = ?",
                                Long.class,
                                ROLE))
                .isZero();
    }

    /** 导出侧形态：包覆盖全注册表、术者取值被剔除计数、病区只复制结构零行。 */
    @SuppressWarnings("unchecked")
    private void assertExportShape(String token, long id) {
        Map<?, ?> detail =
                request(HttpMethod.GET, "/api/platform/config-templates/" + id, token, null);
        Map<?, ?> template = (Map<?, ?>) detail.get("template");
        assertThat(template).isNotNull();
        assertThat(((Number) template.get("tableCount")).intValue())
                .isEqualTo(com.crdp.platform.configtemplate.ConfigTableRegistry.ENTRIES.size());
        List<Map<String, Object>> sections = (List<Map<String, Object>>) detail.get("tables");
        assertThat(sections).isNotNull();
        Map<String, Object> dictItem =
                sections.stream()
                        .filter(s -> "cfg_dict_item".equals(String.valueOf(s.get("table"))))
                        .findFirst()
                        .orElseThrow();
        assertThat(((Number) dictItem.get("excludedRowCount")).longValue())
                .as("术者取值被行排除谓词剔除（A-FLD-014「只复制结构」）")
                .isGreaterThanOrEqualTo(1L);
        Map<String, Object> ward =
                sections.stream()
                        .filter(s -> "ward".equals(String.valueOf(s.get("table"))))
                        .findFirst()
                        .orElseThrow();
        assertThat(String.valueOf(ward.get("mode"))).isEqualTo("STRUCTURE_ONLY");
        assertThat(((Number) ward.get("rowCount")).longValue()).isZero();
    }

    // ---------------------------------------------------------------- 种子（全部虚构）

    private void seedSourceMarkers() {
        long userId =
                demoa.queryForObject("SELECT id FROM sys_user ORDER BY id LIMIT 1", Long.class);
        // 字典：标记类型 + 标记项 + 合并项 + 术者取值（导出侧剔除）
        demoa.update(
                "INSERT INTO cfg_dict (code, name, status) VALUES (?, '往返验证字典（示例）',"
                        + " 'ENABLED')",
                DICT);
        demoa.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status,"
                        + " updated_by) VALUES (?, ?, '源标签甲（示例）', 10, 'TENANT', 'ENABLED', ?)",
                DICT,
                ITEM_1,
                userId);
        demoa.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status,"
                        + " updated_by) VALUES (?, ?, '源合并标签甲（示例）', 11, 'TENANT',"
                        + " 'ENABLED', ?)",
                DICT,
                ITEM_MERGE,
                userId);
        demoa.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status)"
                        + " VALUES ('surgeon', ?, '往返术者（示例）', 10, 'TENANT', 'ENABLED')",
                SURGEON_ITEM);
        demoa.update(
                "INSERT INTO cfg_param (param_key, param_value, remark) VALUES (?, ?,"
                        + " '往返验证参数（示例）')",
                PARAM,
                PARAM_V1);
        demoa.update("INSERT INTO cfg_feature (feature_code, enabled) VALUES (?, 1)", FEATURE);
        // 字段字典与表单（fe_* 七表全覆盖形态）
        demoa.update(
                "INSERT INTO fe_option_set (code, name) VALUES (?, '往返验证选项集（示例）')", OPTION_SET);
        demoa.update(
                "INSERT INTO fe_option (set_code, option_code, label, sort_no, updated_by)"
                        + " VALUES (?, 1, '是（示例）', 10, ?)",
                OPTION_SET,
                userId);
        demoa.update(
                "INSERT INTO fe_form (code, name, usage_type, current_version, categories) VALUES"
                        + " (?, '往返验证表单（示例）', 'FOLLOWUP', 1,"
                        + " JSON_ARRAY(JSON_OBJECT('key', 'cat1', 'label', '分类一（示例）', 'sortNo',"
                        + " 10)))",
                FORM);
        demoa.update(
                "INSERT INTO fe_field (form_code, field_key, field_code, label, category_key, type,"
                        + " option_set_code, sort_no, updated_by) VALUES (?, 'f001', 'e12t06_smoke',"
                        + " '往返字段（示例）', 'cat1', 'radio', ?, 10, ?)",
                FORM,
                OPTION_SET,
                userId);
        demoa.update(
                "INSERT INTO fe_form_version (form_code, version, field_count, published_by)"
                        + " VALUES (?, 1, 1, ?)",
                FORM,
                userId);
        Long versionId =
                idByForm(
                        demoa,
                        "SELECT id FROM fe_form_version WHERE form_code = ? AND version = 1",
                        FORM);
        demoa.update(
                "INSERT INTO fe_form_version_field (version_id, form_code, version, field_key,"
                        + " field_code, label, category_key, type, option_set_code, sort_no)"
                        + " VALUES (?, ?, 1, 'f001', 'e12t06_smoke', '往返字段（示例）', 'cat1',"
                        + " 'radio', ?, 10)",
                versionId,
                FORM,
                OPTION_SET);
        demoa.update(
                "INSERT INTO fe_rule (form_code, rule_id, category_key, target_field, rule_type,"
                        + " params, message) VALUES (?, ?, 'cat1', 'f001', 'COMPARE',"
                        + " JSON_OBJECT('op', 'GT', 'value', 0), '往返规则（示例）')",
                FORM,
                RULE);
        // 计划：子计划 + 两个节点（源侧 3 行）
        demoa.update(
                "INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type)"
                        + " VALUES (?, '往返验证计划（示例）', 1, 'UNPUBLISHED', 'DISCHARGE_DATE',"
                        + " 'ONGOING')",
                PLAN);
        Long planId = planVersionId(demoa);
        demoa.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode,"
                        + " repeat_count) VALUES (?, NULL, 'SUBPLAN', '第 1 年（示例）', 'FIXED', 1)",
                planId);
        Long subplanId =
                demoa.queryForObject(
                        "SELECT id FROM fu_plan_node WHERE plan_id = ? AND parent_id IS NULL",
                        Long.class,
                        planId);
        demoa.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, basis, offset_value,"
                        + " offset_unit, task_type) VALUES (?, ?, 'NODE', '年度随访（示例）',"
                        + " 'PLAN_START', 1, 'YEAR', 'REMINDER')",
                planId,
                subplanId);
        demoa.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, basis, offset_value,"
                        + " offset_unit, task_type, form_code) VALUES (?, ?, 'NODE',"
                        + " '第 2 年随访（示例）', 'PLAN_START', 2, 'YEAR', 'FORM', ?)",
                planId,
                subplanId,
                FORM);
        // 通知：标记规则
        demoa.update(
                "INSERT INTO ntf_rule (event_type, enabled, channels, recipients)"
                        + " VALUES (?, 1, 'INBOX', 'OWNER')",
                NTF_RULE_EVENT);
        // 报告单链：模板 + 项目 + 类型 + 名称 + 映射 + 映射项（数值引用 tpl_id / mapping_id）
        demoa.update(
                "INSERT INTO rpt_indicator_tpl (name, status, remark) VALUES (?, 'ENABLED', ?)",
                RPT_TPL,
                RPT_TPL_REMARK);
        demoa.update(
                "INSERT INTO rpt_indicator_item (tpl_id, item_name, item_code, unit, ref_range,"
                        + " sort_no) VALUES (?, '左室射血分数（示例）', 'LVEF', '%', '≥ 50', 10)",
                tplId(demoa));
        demoa.update(
                "INSERT INTO rpt_type (code, name, form_code, name_required,"
                        + " default_digitize_status) VALUES (?, '往返资料类型（示例）', ?, 1,"
                        + " 'PENDING')",
                RPT_TYPE,
                FORM);
        demoa.update(
                "INSERT INTO rpt_name (type_code, name, indicator_tpl_id) VALUES (?, ?, ?)",
                RPT_TYPE,
                RPT_NAME,
                tplId(demoa));
        demoa.update(
                "INSERT INTO rpt_mapping (code, name, type_code, target_type, target_form_code,"
                        + " phase, indicator_tpl_id) VALUES (?, '往返字段映射（示例）', ?,"
                        + " 'FOLLOWUP_RECORD', ?, 'PREOP_LAST', ?)",
                RPT_MAP,
                RPT_TYPE,
                FORM,
                tplId(demoa));
        demoa.update(
                "INSERT INTO rpt_mapping_item (mapping_id, item_code, target_field_key, sort_no)"
                        + " VALUES (?, 'LVEF', 'f001', 10)",
                idByCode(demoa, "rpt_mapping", RPT_MAP));
        // 对比规则（E17-T01 表）
        demoa.update(
                "INSERT INTO fu_compare_rule (form_code, rule_code, name, category_key,"
                        + " target_field, rule_type, params, baseline_form_code, baseline_field,"
                        + " message) VALUES (?, 'e12t06_diff', '往返对比规则（示例）', 'cat1',"
                        + " 'f001', 'DIFF', JSON_OBJECT('tolerance', 0), 'A_BASELINE', 'f001',"
                        + " '往返提示（示例）')",
                FORM);
        // 角色与授权（并集表源侧标记：菜单 10 为 base 固定能力，恒可分配）
        demoa.update(
                "INSERT INTO sys_role (code, name, built_in, status, role_type, module_code)"
                        + " VALUES (?, '往返验证角色（示例）', 0, 'ENABLED', 'CUSTOM', 'followup')",
                ROLE);
        Long roleId = idByCode(demoa, "sys_role", ROLE);
        demoa.update("INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')", roleId);
        demoa.update(
                "INSERT INTO sys_role_field_policy (role_id, resource, field, policy) VALUES (?,"
                        + " 'patient', 'name', 'MASKED')",
                roleId);
        demoa.update(
                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                roleId,
                ASSIGNABLE_MENU);
        // 科室范围行（sys_role_scope_dept）：OQ-91 ④ 定稿全部丢弃（sys_dept 组织架构不随模板复制）
        demoa.update("INSERT INTO sys_role_scope_dept (role_id, dept_id) VALUES (?, 1)", roleId);
    }

    /** 目标侧先行分叉：同自然键不同值（源值胜收敛）+ 目标独有角色（并集保留）+ 多余计划节点（整组替换）。 */
    private void seedTargetDivergence() {
        demob.update(
                "INSERT INTO cfg_dict (code, name, status) VALUES (?, '目标旧字典（示例）'," + " 'ENABLED')",
                DICT);
        demob.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status)"
                        + " VALUES (?, ?, '目标旧标签（示例）', 10, 'TENANT', 'ENABLED')",
                DICT,
                ITEM_1);
        demob.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status)"
                        + " VALUES (?, ?, '目标旧合并标签（示例）', 11, 'TENANT', 'ENABLED')",
                DICT,
                ITEM_MERGE);
        demob.update(
                "INSERT INTO cfg_param (param_key, param_value, remark) VALUES (?, ?,"
                        + " '目标先行（示例）')",
                PARAM,
                "目标旧值（示例）");
        demob.update(
                "INSERT INTO ntf_rule (event_type, enabled, channels, recipients)"
                        + " VALUES (?, 0, 'INBOX', 'OWNER')",
                NTF_RULE_EVENT);
        demob.update("UPDATE ntf_setting SET sms_daily_limit = 99, updated_by = NULL WHERE id = 1");
        // 目标同名旧计划（自然键相同、名称分叉）+ 多余节点：整组替换后应与源完全一致
        demob.update(
                "INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type)"
                        + " VALUES (?, '目标旧计划（示例）', 1, 'UNPUBLISHED', 'DISCHARGE_DATE',"
                        + " 'ONGOING')",
                PLAN);
        Long planId = planVersionId(demob);
        demob.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode,"
                        + " repeat_count) VALUES (?, NULL, 'SUBPLAN', '目标旧子计划（示例）',"
                        + " 'FIXED', 1)",
                planId);
        Long subplanId =
                demob.queryForObject(
                        "SELECT id FROM fu_plan_node WHERE plan_id = ? AND parent_id IS NULL",
                        Long.class,
                        planId);
        demob.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, basis,"
                        + " offset_value, offset_unit, task_type) VALUES (?, ?, 'NODE',"
                        + " '目标旧节点（示例）', 'PLAN_START', 1, 'YEAR', 'REMINDER')",
                planId,
                subplanId);
        demob.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, basis,"
                        + " offset_value, offset_unit, task_type) VALUES (?, ?, 'NODE',"
                        + " '目标多余节点（示例）', 'PLAN_START', 9, 'YEAR', 'REMINDER')",
                planId,
                subplanId);
        // 目标独有角色 + 范围（并集保留断言用）
        demob.update(
                "INSERT INTO sys_role (code, name, built_in, status, role_type, module_code)"
                        + " VALUES (?, '目标独有角色（示例）', 0, 'ENABLED', 'CUSTOM', 'followup')",
                ROLE_TARGET_ONLY);
        demob.update(
                "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                idByCode(demob, "sys_role", ROLE_TARGET_ONLY));
    }

    // ---------------------------------------------------------------- 导出 / 应用

    private long exportTemplate(String token) {
        Map<?, ?> view =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates",
                        token,
                        Map.of("tenantId", SOURCE_TENANT, "name", TEMPLATE_NAME));
        assertThat(view).isNotNull();
        long id = ((Number) view.get("id")).longValue();
        templateIds.add(id);
        assertThat(((Number) view.get("rowCount")).longValue()).isPositive();
        return id;
    }

    private Map<?, ?> applyTemplate(String token, long id) {
        Map<?, ?> view =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates/" + id + "/apply",
                        token,
                        Map.of("tenantId", TARGET_TENANT));
        assertThat(view).isNotNull();
        assertThat(String.valueOf(view.get("tenantCode"))).isEqualTo("demob");
        assertThat(String.valueOf(view.get("sourceTenantCode"))).isEqualTo("demoa");
        return view;
    }

    /** 目标租户生效可分配菜单集（PLT-SCOPE-001 / 004）：scope 视图里 state = ASSIGNABLE 的项。 */
    @SuppressWarnings("unchecked")
    private Set<Long> assignableMenuIds(String token, long tenantId) {
        Map<?, ?> scope =
                request(
                        HttpMethod.GET,
                        "/api/platform/tenants/" + tenantId + "/scope",
                        token,
                        null);
        assertThat(scope).isNotNull();
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> module : (List<Map<String, Object>>) scope.get("modules")) {
            for (Map<String, Object> item : (List<Map<String, Object>>) module.get("items")) {
                if ("ASSIGNABLE".equals(String.valueOf(item.get("state")))) {
                    ids.add(((Number) item.get("id")).longValue());
                }
            }
        }
        assertThat(ids).isNotEmpty();
        return ids;
    }

    // ---------------------------------------------------------------- 比对帮助

    private static Long idByForm(JdbcTemplate db, String sql, String arg) {
        List<Long> ids = db.queryForList(sql, Long.class, arg);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 行排序后比较：消除 SQL 端 ORDER BY 依赖，比较多重集合（E12-T05 同口径）。 */
    private static List<Map<String, Object>> sortedRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Map::toString));
        return sorted;
    }

    /** 多重集包含：target 逐行消费 source 的每一行（equals 语义），消费不完 = false。 */
    private static boolean containsAll(
            List<Map<String, Object>> target, List<Map<String, Object>> source) {
        for (Map<String, Object> row : source) {
            if (!target.remove(row)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 基础设施

    @SuppressWarnings("unchecked")
    private Map<?, ?> request(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        Map<?, ?> payload =
                (Map<?, ?>) rest.exchange(URI.create(path), method, entity, Map.class).getBody();
        assertThat(payload).isNotNull();
        Number code = (Number) payload.get("code");
        assertThat(code).isNotNull();
        if (code.intValue() != 0) {
            return null;
        }
        return (Map<?, ?>) payload.get("data");
    }

    private String loginPlatform() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> payload =
                (Map<?, ?>)
                        rest.postForEntity(
                                        "/api/platform/auth/login",
                                        new HttpEntity<>(
                                                Map.of(
                                                        "username",
                                                        "platform.admin",
                                                        "password",
                                                        DEMO_PASSWORD),
                                                headers),
                                        Map.class)
                                .getBody();
        assertThat(payload).isNotNull();
        assertThat(((Number) payload.get("code")).intValue()).isZero();
        return String.valueOf(((Map<?, ?>) payload.get("data")).get("token"));
    }
}
