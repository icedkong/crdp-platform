package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * E23-T15 端到端演练（验收原文：B 租户开通，配置与 A 一致）。医院 B 在本地 / CI 的化身是新建租户 e23t15b（本地 / CI 不动种子租户 demob——它承载其它 IT
 * 的夹具；生产 B 走同一路径，见 docs/ops/hospital-b-onboarding.md）。 与 E12-T05
 * 演练（ConfigTemplateTenantInitMySqlIT，三域 13 表）的区别：本用例按 A-FLD-014 完整口径比对**全注册表**——23 张等价表（E12-T06 的
 * 22 张 + 首页卡片配置 dash_role_layout，B 首页节点随访率卡片以它为载体， 第 14 章）多重集相等、角色三表 + 角色菜单包含、只复制结构与不复制清单逐项断言：
 *
 * <ol>
 *   <li>从 demoa（医院 A 的化身）导出配置模板：详情断言注册表 29 张表全登记，ward 为 STRUCTURE_ONLY 且零行（病区只复制结构，A-FLD-014）；
 *   <li>新建租户向导提交带 templateId（第 14 章「新建租户时选择医院 A 的配置模板」），开通编排 config-template 步骤 SUCCESS， 租户
 *       TRIAL、plt_tenant.init_template_id 登记所选模板、应用写 CONFIG_TEMPLATE_APPLY 审计；
 *   <li>全注册表配置与 A 逐表一致——业务列多重集比较，数值引用（version_id / plan_id / tpl_id / mapping_id / role_id）join
 *       到本院行取值，不比跨库自增 ID、用户引用与时间列（导入置空属既有口径，OQ-90 ④ / OQ-91 ③）；
 *   <li>角色并集（OQ-91 ①）：sys_role / sys_role_scope / sys_role_field_policy 源行全量落地（多重集包含），sys_role_menu
 *       按角色编码 join 后包含；sys_role_scope_dept 导入丢弃（口径，不比对）；
 *   <li>只复制配置（A-FLD-014 不复制清单）：患者与随访业务表为空；配置行不带源租户用户引用（导入置空）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis）；自建自清（演练租户库与平台登记、模板与审计）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantBOnboardingMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long SOURCE_TENANT = 1L; // demoa（医院 A 的本地化身）
    private static final String TEMPLATE_NAME = "E23-T15 医院乙初始化模板（示例）";
    private static final String TENANT_B_CODE = "e23t15b";

    /**
     * 等价表（23 张 = 注册表 28 张 COPY 表中的等价面）：导入后与源的业务列多重集完全一致。 {db} 占位符替换为比对侧库名（t_demoa = A，t_e23t15b =
     * 演练 B）。查询口径与 ConfigTemplateRoundTripMySqlIT.EQUALITY_QUERIES 同源，新增
     * dash_role_layout（首页卡片配置，A-DASH-013「随配置模板复制」）。
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
        // ---- followup：字段字典与表单（fe_* 七表）----
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
        // ---- followup：计划模板（B 首期沿用 A 的计划模板能力，本院预置随 E23-T16 另配）----
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
        // ---- followup：通知设置 ----
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
        // ---- followup：报告单设置（六表，E16-T01 / E23-T03）----
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
        // ---- followup：对比标红规则（A-FU-027，第 14 章「对比标红沿用 A-FU-027」）----
        {
            "fu_compare_rule",
            "SELECT form_code, rule_code, name, category_key, target_field, rule_type, params,"
                    + " baseline_form_code, baseline_field, message, sort_no, status FROM"
                    + " `{db}`.fu_compare_rule"
        },
        // ---- followup：首页卡片配置（A-DASH-013「随配置模板复制」；role_id join 角色编码比较，
        //      B 的节点随访率卡片初始化后在 E23-T16 / T17 开启）----
        {
            "dash_role_layout",
            "SELECT r.code, d.card_code, d.visible, d.sort_order, d.allow_user_hide FROM"
                    + " `{db}`.dash_role_layout d JOIN `{db}`.sys_role r ON r.id = d.role_id"
        },
    };

    /** 并集表（OQ-91 ①）：源行全量落地（多重集包含），目标独有角色保留。 */
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

    /** 角色菜单：join 角色编码后源行 ⊆ 目标（可分配范围交集外的丢弃属口径，同向包含不误报）。 */
    private static final String ROLE_MENU_QUERY =
            "SELECT r.code, rm.menu_id FROM `{db}`.sys_role_menu rm JOIN `{db}`.sys_role r"
                    + " ON r.id = rm.role_id";

    private static JdbcTemplate platform;

    /** 特权连接：demoa 与演练 B 库的配置行比对都走它（crdp_app 无新租户库授权，与隔离 IT 同口径）。 */
    private static JdbcTemplate root;

    private static Long templateId;
    private static boolean tenantCreated;
    private static boolean controlTenantCreated;

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
            root =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/" + params, "root", "DevOnly#root2026"));
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        for (String code : cleanupCodes()) {
            root.execute("DROP DATABASE IF EXISTS t_" + code);
            root.execute("DROP USER IF EXISTS `t_" + code + "`@'%'");
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
        platform.update("DELETE FROM plt_config_template WHERE name = ?", TEMPLATE_NAME);
        // 演练模板的导出 / 应用审计随模板一起清（object_id = 模板 id；开通与模块审计无唯一约束，残留不挡重跑，
        // 与 ConfigTemplateTenantInitMySqlIT 同口径）。审计表带只读触发器，必须走维护开关。
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('CONFIG_TEMPLATE_EXPORT', 'CONFIG_TEMPLATE_APPLY') AND object_id = ?",
                templateId == null ? "-1" : String.valueOf(templateId));
    }

    private static List<String> cleanupCodes() {
        List<String> codes = new ArrayList<>();
        if (tenantCreated) {
            codes.add(TENANT_B_CODE);
        }
        if (controlTenantCreated) {
            codes.add(TENANT_B_CODE + "c");
        }
        return codes;
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 演练主链路：导出 A 模板 → 带 templateId 开通医院乙（演练化身）→ 全注册表配置与 A 逐表一致。
     *
     * <p>与 E12-T05 演练（三域 13 表）相比，本用例是 E23-T15 验收「配置与 A 一致」的完整口径：全注册表 23 张等价表 + 角色包含 + 只复制结构 /
     * 不复制清单。
     */
    @Test
    @Order(1)
    void onboardingInitializesTenantBConsistentWithA() {
        connect();
        String token = loginPlatform();

        // 1. 从 demoa（医院 A 的化身）导出配置模板（第 14 章「选择医院 A 的配置模板」）
        Map<?, ?> exported =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates",
                        token,
                        Map.of("tenantId", SOURCE_TENANT, "name", TEMPLATE_NAME));
        templateId = ((Number) exported.get("id")).longValue();
        assertThat(exported.get("tenantCode")).isEqualTo("demoa");
        // 30 = 29 + fu_plan_mapping（E21-T05 器械→计划映射随配置模板复制，COPY / device_type 自然键）
        assertThat(((Number) exported.get("tableCount")).intValue()).isEqualTo(30);
        assertThat(((Number) exported.get("rowCount")).longValue()).isPositive();

        // 模板详情：ward 只复制结构且零行（「术者」「病区/中心」字典只复制结构、不复制取值，A-FLD-014）
        Map<?, ?> detail =
                request(
                        HttpMethod.GET,
                        "/api/platform/config-templates/" + templateId,
                        token,
                        null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) detail.get("tables");
        Map<String, Object> wardSection =
                tables.stream()
                        .filter(t -> "ward".equals(t.get("table")))
                        .findFirst()
                        .orElseThrow();
        assertThat(wardSection.get("mode")).isEqualTo("STRUCTURE_ONLY");
        assertThat(((Number) wardSection.get("rowCount")).longValue()).isZero();

        // 2. 向导提交：选择 A 配置模板开通医院乙演练化身（「新建租户时选择模板初始化」，A-FLD-014）
        Map<?, ?> created =
                request(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        token,
                        Map.of(
                                "name",
                                "虚构医院乙（示例）",
                                "code",
                                TENANT_B_CODE,
                                "modules",
                                List.of("followup"),
                                "templateId",
                                templateId));
        tenantCreated = true;
        assertThat(((Number) created.get("tenantId")).longValue()).isPositive();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) created.get("steps");
        assertThat(steps)
                .anySatisfy(
                        step -> {
                            assertThat(step.get("step")).isEqualTo("config-template");
                            assertThat(step.get("status")).isEqualTo("SUCCESS");
                        });
        assertThat(steps).allMatch(step -> !"FAILED".equals(step.get("status")));

        // 3. B 租户开通：TRIAL、登记所选模板、应用写审计
        assertThat(
                        platform.queryForObject(
                                "SELECT status FROM plt_tenant WHERE code = ?",
                                String.class,
                                TENANT_B_CODE))
                .isEqualTo("TRIAL");
        assertThat(
                        platform.queryForObject(
                                "SELECT init_template_id FROM plt_tenant WHERE code = ?",
                                Long.class,
                                TENANT_B_CODE))
                .isEqualTo(templateId);
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                        + " 'CONFIG_TEMPLATE_APPLY' AND object_id = ?",
                                Integer.class,
                                String.valueOf(templateId)))
                .isPositive();

        // 4. 全注册表配置与 A 逐表一致（业务列多重集；数值引用 join 到本院行）
        for (String[] query : EQUALITY_QUERIES) {
            List<Map<String, Object>> fromA =
                    sortedRows(root.queryForList(query[1].replace("{db}", "t_demoa")));
            List<Map<String, Object>> fromB =
                    sortedRows(root.queryForList(query[1].replace("{db}", "t_" + TENANT_B_CODE)));
            assertThat(fromB)
                    .as("医院乙（演练）%s 配置与 A 一致（%d 行）", query[0], fromA.size())
                    .isEqualTo(fromA);
        }
        // 角色并集：A 的角色 / 数据范围 / 字段策略全量落地（多重集包含，目标独有角色保留）
        for (String[] query : CONTAINMENT_QUERIES) {
            List<Map<String, Object>> fromA =
                    sortedRows(root.queryForList(query[1].replace("{db}", "t_demoa")));
            List<Map<String, Object>> fromB =
                    sortedRows(root.queryForList(query[1].replace("{db}", "t_" + TENANT_B_CODE)));
            assertThat(fromB)
                    .as("医院乙（演练）%s 包含 A 的全部角色配置（%d 行）", query[0], fromA.size())
                    .containsAll(fromA);
        }
        List<Map<String, Object>> menusA =
                sortedRows(root.queryForList(ROLE_MENU_QUERY.replace("{db}", "t_demoa")));
        List<Map<String, Object>> menusB =
                sortedRows(
                        root.queryForList(ROLE_MENU_QUERY.replace("{db}", "t_" + TENANT_B_CODE)));
        assertThat(menusB).as("医院乙（演练）角色菜单包含 A 的全部授权（%d 行）", menusA.size()).containsAll(menusA);

        // 5. 只复制配置（A-FLD-014 不复制清单）：患者与随访业务数据为空
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_" + TENANT_B_CODE + "`.pt_patient",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_" + TENANT_B_CODE + "`.fu_enrollment",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_" + TENANT_B_CODE + "`.fu_task",
                                Long.class))
                .isZero();
        // 配置行不带源租户用户引用（账号不复制，导入置空；tech-plan §6 敏感信息检查）
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_"
                                        + TENANT_B_CODE
                                        + "`.fe_field WHERE updated_by IS NOT NULL",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_"
                                        + TENANT_B_CODE
                                        + "`.rpt_type WHERE updated_by IS NOT NULL",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_"
                                        + TENANT_B_CODE
                                        + "`.ntf_rule WHERE updated_by IS NOT NULL",
                                Long.class))
                .isZero();
    }

    /** 向导第 2 步未选模板的对照：config-template 记 SKIPPED（E12-T05 口径在 B 演练下不变）。 */
    @Test
    @Order(2)
    void wizardWithoutTemplateSkipsConfigStepForB() {
        connect();
        String token = loginPlatform();
        Map<?, ?> created =
                request(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        token,
                        Map.of(
                                "name",
                                "虚构医院乙对照（示例）",
                                "code",
                                TENANT_B_CODE + "c",
                                "modules",
                                List.of("followup")));
        controlTenantCreated = true;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) created.get("steps");
        assertThat(steps)
                .anySatisfy(
                        step -> {
                            assertThat(step.get("step")).isEqualTo("config-template");
                            assertThat(step.get("status")).isEqualTo("SKIPPED");
                        });
        assertThat(
                        platform.queryForObject(
                                "SELECT init_template_id FROM plt_tenant WHERE code = ?",
                                Long.class,
                                TENANT_B_CODE + "c"))
                .isNull();
    }

    // ---------------------------------------------------------------- 比对帮助

    /**
     * 行排序后比较：同表同列查询两侧列序一致，Map#toString 稳定； 排序消除 SQL 端 ORDER BY 对无全序业务键的表（fu_plan_node
     * 无自然键）的依赖，比较多重集合。
     */
    private static List<Map<String, Object>> sortedRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Map::toString));
        return sorted;
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
