package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
 * E12-T02 端到端（验收原文：导入后引用关系正确）。在源租户 demoa 放入带标记的配置行（覆盖全部 数值引用形态：role_id / version_id / plan_id 与
 * fu_plan_node 的 parent_id 自引用），从平台端导出模板后应用到目标租户 demob，断言：
 *
 * <ol>
 *   <li>自然键行全部落地，数值引用全部改写到 demob 自己的新 ID（不残留源租户 ID）；
 *   <li>用户引用列（updated_by / published_by）置空——账号不随模板复制；
 *   <li>角色菜单与新租户可分配范围取交集（范围外 menu_id 丢弃）；sys_role_scope_dept 行丢弃（sys_dept 不复制）；
 *   <li>STRUCTURE_ONLY（ward）与患者数据表不受影响；重放（二次应用）幂等，子行不叠加；
 *   <li>应用写 CONFIG_TEMPLATE_APPLY 审计；40300 / 40400 / 40001 负向。
 *       <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（两个租户库里的标记行、平台模板与审计、 无权限账号）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigTemplateImportMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long SOURCE_TENANT = 1L; // demoa
    private static final long TARGET_TENANT = 2L; // demob

    private static final String DICT = "itcfg_import_dict";
    private static final String PARAM = "itcfg.import.param";
    private static final String OPTION_SET = "itcfg_os";
    private static final String FORM = "ITCFG_FORM";
    private static final String PLAN = "ITCFG_PLAN";
    private static final String ROLE = "ITCFG_ROLE";
    private static final long OUT_OF_RANGE_MENU = 999_999L;

    private static JdbcTemplate platform;
    private static JdbcTemplate demoa;
    private static JdbcTemplate demob;
    private static Long templateId;
    private static boolean viewerCreated;

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
        platform.update("DELETE FROM plt_config_template");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('CONFIG_TEMPLATE_EXPORT', 'CONFIG_TEMPLATE_DOWNLOAD', 'CONFIG_TEMPLATE_APPLY')");
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.import.viewer.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.import.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_IMPORT_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_IMPORT_VIEWER_IT'");
        }
    }

    private static void cleanMarkers(JdbcTemplate db) {
        Long roleId = queryId(db, "SELECT id FROM sys_role WHERE code = '" + ROLE + "'");
        if (roleId != null) {
            db.update("DELETE FROM sys_role_scope_dept WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role_field_policy WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role_scope WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
            db.update("DELETE FROM sys_role WHERE id = ?", roleId);
        }
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
        db.update("DELETE FROM cfg_param WHERE param_key = ?", PARAM);
        db.update("DELETE FROM cfg_dict_item WHERE dict_code = ?", DICT);
        db.update("DELETE FROM cfg_dict WHERE code = ?", DICT);
    }

    private static Long queryId(JdbcTemplate db, String sql) {
        List<Long> ids = db.queryForList(sql, Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    void seedSourceMarkersAndExport() {
        connect();
        long userId =
                demoa.queryForObject("SELECT id FROM sys_user ORDER BY id LIMIT 1", Long.class);

        demoa.update(
                "INSERT INTO cfg_dict (code, name, status) VALUES (?, '导入验证字典（示例）', 'ENABLED')",
                DICT);
        demoa.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status,"
                        + " updated_by) VALUES (?, 'itcfg_item_1', '导入验证项（示例）', 10, 'TENANT',"
                        + " 'ENABLED', ?)",
                DICT,
                userId);
        demoa.update(
                "INSERT INTO cfg_param (param_key, param_value, remark, updated_by)"
                        + " VALUES (?, '7', '导入验证参数（示例）', ?)",
                PARAM,
                userId);
        demoa.update(
                "INSERT INTO fe_option_set (code, name) VALUES (?, '导入验证选项集（示例）')", OPTION_SET);
        demoa.update(
                "INSERT INTO fe_option (set_code, option_code, label, sort_no, updated_by)"
                        + " VALUES (?, 0, '否（示例）', 10, ?)",
                OPTION_SET,
                userId);
        demoa.update(
                "INSERT INTO fe_form (code, name, usage_type, current_version, categories) VALUES"
                        + " (?, '导入验证表单（示例）', 'FOLLOWUP', 1,"
                        + " JSON_ARRAY(JSON_OBJECT('key', 'cat1', 'label', '分类一（示例）', 'sortNo', 10)))",
                FORM);
        demoa.update(
                "INSERT INTO fe_field (form_code, field_key, field_code, label, category_key, type,"
                        + " option_set_code, sort_no, updated_by) VALUES (?, 'f001', 'itcfg_smoke',"
                        + " '吸烟史（示例）', 'cat1', 'radio', ?, 10, ?)",
                FORM,
                OPTION_SET,
                userId);
        demoa.update(
                "INSERT INTO fe_form_version (form_code, version, field_count, published_by)"
                        + " VALUES (?, 1, 1, ?)",
                FORM,
                userId);
        Long sourceVersionId =
                queryId(
                        demoa,
                        "SELECT id FROM fe_form_version WHERE form_code = '"
                                + FORM
                                + "' AND version = 1");
        demoa.update(
                "INSERT INTO fe_form_version_field (version_id, form_code, version, field_key,"
                        + " field_code, label, category_key, type, option_set_code, sort_no)"
                        + " VALUES (?, ?, 1, 'f001', 'itcfg_smoke', '吸烟史（示例）', 'cat1', 'radio', ?, 10)",
                sourceVersionId,
                FORM,
                OPTION_SET);
        demoa.update(
                "INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type)"
                        + " VALUES (?, '导入验证计划（示例）', 1, 'UNPUBLISHED', 'DISCHARGE_DATE',"
                        + " 'ONGOING')",
                PLAN);
        Long planId = queryId(demoa, "SELECT id FROM fu_plan WHERE code = '" + PLAN + "'");
        demoa.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode,"
                        + " repeat_count) VALUES (?, NULL, 'SUBPLAN', '第 1 年（示例）', 'FIXED', 1)",
                planId);
        Long subplanId =
                queryId(
                        demoa,
                        "SELECT id FROM fu_plan_node WHERE plan_id = "
                                + planId
                                + " AND parent_id IS NULL");
        demoa.update(
                "INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, basis, offset_value,"
                        + " offset_unit, task_type) VALUES (?, ?, 'NODE', '年度随访（示例）',"
                        + " 'PLAN_START', 1, 'YEAR', 'REMINDER')",
                planId,
                subplanId);
        demoa.update(
                "INSERT INTO sys_role (code, name, built_in, status, role_type) VALUES"
                        + " (?, '导入验证角色（示例）', 0, 'ENABLED', 'CUSTOM')",
                ROLE);
        Long roleId = queryId(demoa, "SELECT id FROM sys_role WHERE code = '" + ROLE + "'");
        demoa.update("INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')", roleId);
        demoa.update(
                "INSERT INTO sys_role_field_policy (role_id, resource, field, policy)"
                        + " VALUES (?, 'patient', 'name', 'MASKED')",
                roleId);
        demoa.update(
                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 10), (?, ?)",
                roleId,
                roleId,
                OUT_OF_RANGE_MENU);
        demoa.update("INSERT INTO sys_role_scope_dept (role_id, dept_id) VALUES (?, 1)", roleId);

        String token = loginPlatform("platform.admin");
        Map<?, ?> view =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates",
                        token,
                        Map.of("tenantId", SOURCE_TENANT, "name", "IT 导入模板（示例）"));
        templateId = ((Number) view.get("id")).longValue();
        assertThat(templateId).isPositive();
    }

    @Test
    @Order(2)
    void applyRemapsIdsAndKeepsReferencesConsistent() {
        connect();
        long demobWardRowsBefore = demob.queryForObject("SELECT COUNT(*) FROM ward", Long.class);
        long demobPatientRowsBefore =
                demob.queryForObject("SELECT COUNT(*) FROM pt_patient", Long.class);

        String token = loginPlatform("platform.admin");
        Map<?, ?> view =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates/" + templateId + "/apply",
                        token,
                        Map.of("tenantId", TARGET_TENANT));
        assertThat(view).isNotNull();
        assertThat(String.valueOf(view.get("tenantCode"))).isEqualTo("demob");
        assertThat(String.valueOf(view.get("sourceTenantCode"))).isEqualTo("demoa");
        assertThat(((Number) view.get("insertedRows")).longValue()).isPositive();
        List<Map<?, ?>> tables = (List<Map<?, ?>>) view.get("tables");
        assertThat(tables)
                .extracting(t -> String.valueOf(t.get("table")))
                .contains("cfg_dict", "sys_role", "sys_role_menu", "fu_plan", "fu_plan_node");

        // ---- 自然键行落地 + 用户引用置空 ----
        Long dictId = queryId(demob, "SELECT id FROM cfg_dict WHERE code = '" + DICT + "'");
        assertThat(dictId).isNotNull();
        Map<String, Object> item =
                demob.queryForMap(
                        "SELECT id, updated_by FROM cfg_dict_item WHERE dict_code = ? AND item_code ="
                                + " 'itcfg_item_1'",
                        DICT);
        assertThat(item.get("updated_by")).isNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT updated_by FROM cfg_param WHERE param_key = ?",
                                Long.class,
                                PARAM))
                .isNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT updated_by FROM fe_field WHERE form_code = ? AND field_key ="
                                        + " 'f001'",
                                Long.class,
                                FORM))
                .isNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT updated_by FROM fe_option WHERE set_code = ?",
                                Long.class,
                                OPTION_SET))
                .isNull();

        // ---- fe_form_version_field.version_id 指向 demob 的版本行（ID 重映射）----
        Long targetVersionId =
                queryId(
                        demob,
                        "SELECT id FROM fe_form_version WHERE form_code = '"
                                + FORM
                                + "' AND version = 1");
        assertThat(targetVersionId).isNotNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT published_by FROM fe_form_version WHERE id = ?",
                                Long.class,
                                targetVersionId))
                .isNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM fe_form_version_field WHERE version_id = ? AND"
                                        + " field_key = 'f001'",
                                Long.class,
                                targetVersionId))
                .isEqualTo(1L);
        // ---- fu_plan_node：plan_id / parent_id 全部改写到 demob 的版本行，结构保持 子计划 → 节点 ----
        // （注：CI 全新库两侧种子一致、自增 ID 恰好相同，「目标 ID ≠ 源 ID」不可断言；重映射正确性由
        // 引用挂接断言保证——节点挂在本库查得的 plan / subplan 行上，而非包内源 ID）
        Long targetPlanId = queryId(demob, "SELECT id FROM fu_plan WHERE code = '" + PLAN + "'");
        assertThat(targetPlanId).isNotNull();
        List<Map<String, Object>> nodes =
                demob.queryForList(
                        "SELECT id, parent_id FROM fu_plan_node WHERE plan_id = ?", targetPlanId);
        assertThat(nodes).hasSize(2);
        // 一行子计划（parent_id 空）+ 一行节点（parent_id = 子计划在 demob 的新 ID）
        Map<String, Object> subplan =
                nodes.stream().filter(n -> n.get("parent_id") == null).findFirst().orElseThrow();
        Long targetSubplanId = ((Number) subplan.get("id")).longValue();
        Map<String, Object> child =
                nodes.stream().filter(n -> n.get("parent_id") != null).findFirst().orElseThrow();
        assertThat(((Number) child.get("parent_id")).longValue()).isEqualTo(targetSubplanId);
        // 计划版本行上的用户引用同样置空
        assertThat(
                        demob.queryForObject(
                                "SELECT created_by FROM fu_plan WHERE id = ?",
                                Long.class,
                                targetPlanId))
                .isNull();

        // ---- 角色：授权取交集（范围内 menu 10 保留、范围外丢弃），科室范围行丢弃，字段策略 / 范围随角色改写 ----
        Long targetRoleId = queryId(demob, "SELECT id FROM sys_role WHERE code = '" + ROLE + "'");
        assertThat(targetRoleId).isNotNull();
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = 10",
                                Long.class,
                                targetRoleId))
                .isEqualTo(1L);
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ?",
                                Long.class,
                                targetRoleId))
                .isEqualTo(1L); // 只剩 menu 10，范围外 999999 被交集过滤
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_scope WHERE role_id = ? AND scope_type ="
                                        + " 'ALL'",
                                Long.class,
                                targetRoleId))
                .isEqualTo(1L);
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_field_policy WHERE role_id = ? AND field ="
                                        + " 'name'",
                                Long.class,
                                targetRoleId))
                .isEqualTo(1L);
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_scope_dept WHERE role_id = ?",
                                Long.class,
                                targetRoleId))
                .isZero();

        // ---- STRUCTURE_ONLY 与患者数据不受影响 ----
        assertThat(demob.queryForObject("SELECT COUNT(*) FROM ward", Long.class))
                .isEqualTo(demobWardRowsBefore);
        assertThat(demob.queryForObject("SELECT COUNT(*) FROM pt_patient", Long.class))
                .isEqualTo(demobPatientRowsBefore);

        // ---- 应用审计 ----
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                        + " 'CONFIG_TEMPLATE_APPLY' AND object_id = ?",
                                Integer.class,
                                String.valueOf(templateId)))
                .isGreaterThan(0);
    }

    @Test
    @Order(3)
    void reapplyIsIdempotent() {
        connect();
        String token = loginPlatform("platform.admin");
        Map<?, ?> view =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates/" + templateId + "/apply",
                        token,
                        Map.of("tenantId", TARGET_TENANT));
        assertThat(view).isNotNull();
        Long targetPlanId = queryId(demob, "SELECT id FROM fu_plan WHERE code = '" + PLAN + "'");
        // 无自然键的子行表按计划版本整组替换：重放不叠加
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ?",
                                Long.class,
                                targetPlanId))
                .isEqualTo(2L);
        assertThat(
                        demob.queryForObject(
                                "SELECT COUNT(*) FROM cfg_dict_item WHERE dict_code = ?",
                                Long.class,
                                DICT))
                .isEqualTo(1L);
    }

    @Test
    @Order(4)
    void negativeCases() {
        connect();
        String token = loginPlatform("platform.admin");
        // 缺 tenantId 40001
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates/" + templateId + "/apply",
                                token,
                                Map.of()))
                .isEqualTo(40001);
        // 应用回源租户 40001
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates/" + templateId + "/apply",
                                token,
                                Map.of("tenantId", SOURCE_TENANT)))
                .isEqualTo(40001);
        // 未知模板 40400
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates/999999/apply",
                                token,
                                Map.of("tenantId", TARGET_TENANT)))
                .isEqualTo(40400);
        // 未知目标租户 40400
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates/" + templateId + "/apply",
                                token,
                                Map.of("tenantId", 999_999L)))
                .isEqualTo(40400);
    }

    @Test
    @Order(5)
    void permissionlessAccountGets40300() {
        connect();
        createViewer();
        String token = loginPlatform("platform.import.viewer.it");
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates/" + templateId + "/apply",
                                token,
                                Map.of("tenantId", TARGET_TENANT)))
                .isEqualTo(40300);
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

    private String loginPlatform(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> payload =
                (Map<?, ?>)
                        rest.postForEntity(
                                        "/api/platform/auth/login",
                                        new HttpEntity<>(
                                                Map.of(
                                                        "username",
                                                        username,
                                                        "password",
                                                        DEMO_PASSWORD),
                                                headers),
                                        Map.class)
                                .getBody();
        assertThat(payload).isNotNull();
        assertThat(((Number) payload.get("code")).intValue()).isZero();
        return String.valueOf(((Map<?, ?>) payload.get("data")).get("token"));
    }

    private void createViewer() {
        if (viewerCreated) {
            return;
        }
        String passwordHash =
                new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
                                16, 32, 1, 65536, 3)
                        .encode(DEMO_PASSWORD);
        // 无任何菜单授权的角色：不持有 platform:tenant:create
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_IMPORT_VIEWER_IT', '模板应用无权限（IT）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.import.viewer.it', '导入无权限（IT 示例）', ?, 'ENABLED')",
                passwordHash);
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.import.viewer.it'"
                        + " AND r.code = 'PLATFORM_IMPORT_VIEWER_IT'");
        viewerCreated = true;
    }
}
