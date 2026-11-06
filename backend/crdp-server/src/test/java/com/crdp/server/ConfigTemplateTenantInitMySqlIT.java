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
 * E12-T05 端到端演练（验收原文：新租户表单、计划、报告单配置与 A 一致）。医院 A 在本地 / CI 的化身是源租户 demoa（承载 A 预置配置 V38 / V61 / V62
 * 与种子）；演练流程 = 产品将来给医院 B 开通的完整路径（E23-T15 复用）：
 *
 * <ol>
 *   <li>从 demoa 导出配置模板（E12-T01）；
 *   <li>新建租户向导提交带 templateId（A-FLD-014「新建租户时选择模板初始化」），开通编排新增 config-template 步骤 在模块开通后应用模板（E12-T02
 *       导入：ID 重映射、敏感拦截、授权取交集）；
 *   <li>逐表比对三域配置业务列与 A 一致——表单（fe_form / fe_field / fe_option_set / fe_option / fe_form_version /
 *       fe_form_version_field / fe_rule）、计划（fu_plan / fu_plan_node）、报告单（rpt_type / rpt_name /
 *       rpt_indicator_tpl / rpt_indicator_item / rpt_mapping / rpt_mapping_item，字段映射随 E23-T03）；
 *       数值引用不残留源租户 ID（join 到本院行后取值比较）；
 *   <li>只复制配置：新租户库患者与随访业务表为空、配置行不带源租户用户引用；
 *   <li>plt_tenant.init_template_id 登记所选模板；未选模板的租户 config-template 记 SKIPPED；未知模板 40400 先拒绝
 *       （不建库不登记）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis）；自建自清（测试租户库与平台登记、模板与审计）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigTemplateTenantInitMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long SOURCE_TENANT = 1L; // demoa（医院 A 的本地化身）
    private static final String TEMPLATE_NAME = "E12-T05 演练模板（示例）";
    private static final String DRILL_CODE = "e12t05a";
    private static final String PLAIN_CODE = "e12t05b";

    /**
     * 三域配置比对清单：表名 → 业务列查询（数值引用一律 join 到本院行取编码 / 名称，不比较跨库自增 ID）。 SQL 里的 {db} 占位符替换为比对侧库名（t_demoa =
     * A，t_e12t05a = 演练新租户）。
     */
    private static final String[][] COMPARE_QUERIES = {
        // ---- 表单（A-FLD-014「字段字典与表单」）----
        {
            "fe_form",
            "SELECT code, name, usage_type, current_version, categories, status"
                    + " FROM `{db}`.fe_form"
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
        // ---- 计划（A-FLD-014「随访计划模板」）----
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
        // ---- 报告单（E16-T01 / A-ATT-003、011、012）----
        {
            "rpt_type",
            "SELECT code, name, form_code, name_required, default_digitize_status, sort_no,"
                    + " status, remark FROM `{db}`.rpt_type"
        },
        {"rpt_indicator_tpl", "SELECT name, status, remark FROM `{db}`.rpt_indicator_tpl"},
        {
            "rpt_indicator_item",
            "SELECT t.name AS tpl_name, i.item_name, i.item_code, i.unit, i.ref_range,"
                    + " i.ref_range_male, i.ref_range_female, i.sort_no"
                    + " FROM `{db}`.rpt_indicator_item i JOIN `{db}`.rpt_indicator_tpl t"
                    + " ON t.id = i.tpl_id"
        },
        {
            "rpt_name",
            "SELECT n.type_code, n.name, t.name AS tpl_name, n.digitize_status_override,"
                    + " n.sort_no, n.status FROM `{db}`.rpt_name n"
                    + " LEFT JOIN `{db}`.rpt_indicator_tpl t ON t.id = n.indicator_tpl_id"
        },
        // ---- 报告单（E23-T03 / A-ATT-013：字段映射随配置模板复制，indicator_tpl_id 数值引用 join 取名）----
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
    };

    private static JdbcTemplate platform;

    /** 特权连接：demoa 与演练租户库的配置行比对都走它（crdp_app 无新租户库授权，与隔离 IT 同口径）。 */
    private static JdbcTemplate root;

    private static Long templateId;
    private static boolean plainTenantCreated;

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
        // 与 PlatformTenantWizardMySqlIT 同口径）。审计表带只读触发器，必须走维护开关。
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('CONFIG_TEMPLATE_EXPORT', 'CONFIG_TEMPLATE_APPLY') AND object_id = ?",
                templateId == null ? "-1" : String.valueOf(templateId));
    }

    private static List<String> cleanupCodes() {
        List<String> codes = new ArrayList<>(List.of(DRILL_CODE));
        if (plainTenantCreated) {
            codes.add(PLAIN_CODE);
        }
        return codes;
    }

    // ---------------------------------------------------------------- 用例

    /** 演练主链路：导出 A 模板 → 带 templateId 开通测试租户 → 三域配置与 A 逐表一致。 */
    @Test
    @Order(1)
    void drillInitializesTestTenantConsistentWithA() {
        connect();
        String token = loginPlatform();

        // 1. 从 demoa（医院 A 的化身）导出配置模板
        Map<?, ?> exported =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates",
                        token,
                        Map.of("tenantId", SOURCE_TENANT, "name", TEMPLATE_NAME));
        templateId = ((Number) exported.get("id")).longValue();
        assertThat(exported.get("tenantCode")).isEqualTo("demoa");
        assertThat(((Number) exported.get("rowCount")).longValue()).isPositive();

        // 2. 向导提交：选择模板开通测试租户（E12-T05 / A-FLD-014「新建租户时选择模板初始化」）
        Map<?, ?> created =
                request(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        token,
                        Map.of(
                                "name",
                                "虚构演练租户甲（示例）",
                                "code",
                                DRILL_CODE,
                                "modules",
                                List.of("followup"),
                                "templateId",
                                templateId));
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

        // 3. 租户已 TRIAL、登记了所选模板、应用写了审计
        assertThat(
                        platform.queryForObject(
                                "SELECT status FROM plt_tenant WHERE code = ?",
                                String.class,
                                DRILL_CODE))
                .isEqualTo("TRIAL");
        assertThat(
                        platform.queryForObject(
                                "SELECT init_template_id FROM plt_tenant WHERE code = ?",
                                Long.class,
                                DRILL_CODE))
                .isEqualTo(templateId);
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                        + " 'CONFIG_TEMPLATE_APPLY' AND object_id = ?",
                                Integer.class,
                                String.valueOf(templateId)))
                .isPositive();

        // 4. 三域配置与 A 逐表一致（业务列；数值引用 join 到本院行）
        for (String[] query : COMPARE_QUERIES) {
            List<Map<String, Object>> fromA =
                    sortedRows(root.queryForList(query[1].replace("{db}", "t_demoa")));
            List<Map<String, Object>> fromTenant =
                    sortedRows(root.queryForList(query[1].replace("{db}", "t_" + DRILL_CODE)));
            assertThat(fromTenant)
                    .as("新租户 %s 配置与 A 一致（%d 行）", query[0], fromA.size())
                    .isEqualTo(fromA);
        }

        // 5. 只复制配置（A-FLD-014 不复制清单）：患者与随访业务数据为空
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_" + DRILL_CODE + "`.pt_patient",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_" + DRILL_CODE + "`.fu_enrollment",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_" + DRILL_CODE + "`.fu_task", Long.class))
                .isZero();
        // 配置行不带源租户用户引用（账号不复制，导入置空）
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_"
                                        + DRILL_CODE
                                        + "`.fe_field WHERE updated_by IS NOT NULL",
                                Long.class))
                .isZero();
        assertThat(
                        root.queryForObject(
                                "SELECT COUNT(*) FROM `t_"
                                        + DRILL_CODE
                                        + "`.fe_form_version WHERE published_by IS NOT NULL",
                                Long.class))
                .isZero();
    }

    /** 未选模板：config-template 记 SKIPPED，租户照常开通。 */
    @Test
    @Order(2)
    void tenantWithoutTemplateSkipsTheStep() {
        connect();
        String token = loginPlatform();
        Map<?, ?> created =
                request(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        token,
                        Map.of(
                                "name",
                                "虚构演练租户乙（示例）",
                                "code",
                                PLAIN_CODE,
                                "modules",
                                List.of("followup")));
        plainTenantCreated = true;
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
                                PLAIN_CODE))
                .isNull();
    }

    /** 未知模板：开工前 40400 拒绝，不登记租户、不建库。 */
    @Test
    @Order(3)
    void unknownTemplateIsRejectedBeforeProvisioning() {
        connect();
        String token = loginPlatform();
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/tenants",
                                token,
                                Map.of(
                                        "name",
                                        "虚构演练租户丙（示例）",
                                        "code",
                                        "e12t05c",
                                        "templateId",
                                        9_999_999L)))
                .isEqualTo(40400);
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_tenant WHERE code = 'e12t05c'",
                                Long.class))
                .isZero();
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

    private int codeOf(HttpMethod method, String path, String token, Object body) {
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
        return Integer.parseInt(String.valueOf(payload.get("code")));
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
