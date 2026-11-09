package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E20-T03 端到端（验收原文：只读视角写接口全部拒绝）。场景按序：
 *
 * <ol>
 *   <li>只读执行（PLT-VIEW-012）：管理员进入 demoa 视角换视角令牌后，登记患者（POST）、编辑用户（PUT）、删除（DELETE）、 导出（POST
 *       exports）、明文查看（POST plaintext）、原件签发（GET preview-url）全部 40303； 被拒写平台访问审计 VIEW_DENIED 并递增台账
 *       denied_count；视角令牌调平台端仍 40300（端别互斥不变）。
 *   <li>读放行与脱敏（PLT-VIEW-010）：医院账号登记患者后，视角令牌 POST .../search（复杂查询）与 GET 筛选 / 用户列表 200；
 *       行内姓名只含脱敏值（前端不接收原值）；读放行写 VIEW_QUERY 审计并递增 query_count。
 *   <li>设盲执行（PLT-VIEW-011）：平台库临时插入一条「患者姓名设盲」FIELD 规则后，同会话下一次检索姓名列变「已设盲」；
 *       删除规则即恢复脱敏值（规则变更即时生效，platform-spec §8）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（患者 legacy_no 前缀 IT-E20T03-、 规则
 * VR-IT-BLIND、视角会话行与 VIEW_ 审计）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTenantViewExecutionMySqlIT {

    private static final String PLATFORM_PASSWORD = "Demo@2026";
    private static final String HOSPITAL_PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E20T03";
    private static final String NAME = "王盲试（示例）";
    private static final String MRN = "IT-MRN-E20T03";
    private static final String NEW_PASSWORD = "View#2026xC";

    /** 本 IT 创建的平台账号 / 角色 → 清理用。 */
    private static final java.util.List<String> itAccounts = new java.util.ArrayList<>();

    private static final java.util.List<Long> itRoles = new java.util.ArrayList<>();

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private String viewToken;
    private String sessionNo;
    private long demoaId;

    @BeforeEach
    void cleanState() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
        deleteKeys("crdp:view-enter:*");
        demoaId =
                platformDirect()
                        .queryForObject(
                                "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");
        // 会话与令牌按场景现取（写拒绝场景要干净计数，读放行 / 设盲场景各自进入）
        viewToken = null;
        sessionNo = null;
    }

    // ---------------------------------------------------------------- 1. 只读执行（验收：写接口全部拒绝）

    @Test
    @Order(1)
    void viewWritesAreAllRejected() {
        // E20-T06 起 platform.admin 持代为操作权限（写受控放行）——「只读视角写接口全部拒绝」按无代为权限的角色验收
        String admin = platformToken();
        long roleId = createRole(admin, "IT-E20T06-NOPROXY");
        createAccount(admin, "it.view.noproxy", "视角只读账号（示例）", roleId);
        enterViewAs("it.view.noproxy", NEW_PASSWORD);

        // 写接口全家桶（PLT-VIEW-012 新增 / 修改 / 删除 / 批量与导出）
        assertEquals(
                40303,
                codeOf(post("/api/hospital/followup/patients", viewToken, registerBody())),
                "登记患者拒绝");
        assertEquals(
                40303,
                codeOf(
                        put(
                                "/api/hospital/system/users/1",
                                viewToken,
                                Map.of("displayName", "视角改用户（示例）"))),
                "编辑用户拒绝");
        assertEquals(
                40303, codeOf(delete("/api/hospital/followup/patients/1", viewToken)), "删除患者拒绝");
        assertEquals(
                40303,
                codeOf(
                        post(
                                "/api/hospital/followup/exports",
                                viewToken,
                                Map.of("scope", "SEARCH"))),
                "导出按规则单独控制（默认禁止导出）");
        assertEquals(
                40303,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/1/plaintext",
                                viewToken,
                                Map.of("field", "name"))),
                "明文查看专用接口对视角禁用（PLT-VIEW-010）");
        assertEquals(
                40303,
                codeOf(get("/api/hospital/files/1/preview-url", viewToken)),
                "报告单与电子化资料原件不提供预览（PLT-VIEW-008 原件隐藏）");

        // 端别互斥不变：视角令牌不能调平台端
        assertEquals(
                40300,
                codeOf(get("/api/platform/tenant-view/sessions/current", viewToken)),
                "视角令牌不能调平台端");

        // 被拒写平台访问审计（VIEW_DENIED，只进平台库）并递增台账 denied_count
        JdbcTemplate platform = platformDirect();
        Integer denied =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action = 'VIEW_DENIED'"
                                + " AND view_session_id = ?",
                        Integer.class,
                        sessionNo);
        assertTrue(denied != null && denied >= 6, "每次被拒都写 VIEW_DENIED 审计：" + denied);
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT denied_count, query_count FROM plt_view_session WHERE session_no = ?",
                        sessionNo);
        assertTrue(((Number) row.get("denied_count")).longValue() >= 6, "台账被拒计数递增");
        assertEquals(0L, ((Number) row.get("query_count")).longValue(), "无读请求时查询计数为 0");

        exitView();
    }

    // ---------------------------------------------------------------- 2. 读放行与脱敏（PLT-VIEW-010）

    @Test
    @Order(2)
    void viewReadsPassMaskedAndAudited() {
        String doctor = hospitalToken("doctor.demo");
        register(doctor);

        enterView();

        // 复杂查询（POST .../search，AGENTS.md §6）与只读 GET 放行
        Map<?, ?> page =
                post("/api/hospital/followup/patients/search", viewToken, Map.of("keyword", NAME));
        assertEquals(0, codeOf(page), "视角检索患者列表放行：" + page.get("message"));
        List<?> items = (List<?>) data(page).get("items");
        assertEquals(1, items.size(), "按姓名精确命中一名");
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals("王*）", row.get("name"), "行内姓名只含脱敏值（前端不接收原值）");
        assertEquals(
                0,
                codeOf(get("/api/hospital/followup/patients/filter-options", viewToken)),
                "筛选候选放行");
        assertEquals(
                0,
                codeOf(get("/api/hospital/system/users?pageNo=1&pageSize=20", viewToken)),
                "用户列表（配置类）读放行");

        // 读放行写 VIEW_QUERY 审计 + 台账 query_count 递增；菜单请求记页面数
        get("/api/hospital/menus", viewToken);
        JdbcTemplate platform = platformDirect();
        Integer queries =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action = 'VIEW_QUERY'"
                                + " AND view_session_id = ?",
                        Integer.class,
                        sessionNo);
        assertTrue(queries != null && queries >= 3, "读放行写 VIEW_QUERY 审计：" + queries);
        // E20-T05 / PLT-VIEW-016：查询明细带「对象与条数」——患者检索记 object_type=patient、
        // record_count=total（本场景命中 1 名）；菜单请求对象为空（未绑权限码）
        Map<String, Object> patientQuery =
                platform.queryForMap(
                        "SELECT object_type, record_count FROM aud_platform_access_log"
                                + " WHERE action = 'VIEW_QUERY' AND view_session_id = ?"
                                + " AND page = '/api/hospital/followup/patients/search'"
                                + " ORDER BY id DESC LIMIT 1",
                        sessionNo);
        assertEquals("patient", patientQuery.get("object_type"), "查询明细带对象（权限码资源段）");
        assertEquals(
                1L, ((Number) patientQuery.get("record_count")).longValue(), "查询明细带条数（分页 total）");
        Map<String, Object> counts =
                platform.queryForMap(
                        "SELECT query_count, page_count FROM plt_view_session WHERE session_no = ?",
                        sessionNo);
        assertTrue(((Number) counts.get("query_count")).longValue() >= 3, "查询计数递增");
        assertTrue(((Number) counts.get("page_count")).longValue() >= 1, "菜单请求记页面数");

        // 医院会话不受影响：doctor.demo 登记第二名患者照常（视角执行点只对 TENANT_VIEW 生效）
        register(hospitalToken("doctor.demo"));
        Map<?, ?> doctorPage =
                post("/api/hospital/followup/patients/search", doctor, Map.of("keyword", NAME));
        assertEquals(2, ((Number) data(doctorPage).get("total")).intValue(), "医院会话行为不变");

        exitView();
    }

    // ---------------------------------------------------------------- 3. 设盲执行（PLT-VIEW-011，规则即时生效）

    @Test
    @Order(3)
    void blindedRuleMasksNameAsBlinded() {
        String doctor = hospitalToken("doctor.demo");
        register(doctor);
        JdbcTemplate platform = platformDirect();
        platform.update(
                "INSERT INTO plt_view_rule (code, name, layer, tenant_id, module_code, resource,"
                        + " field, visibility, mask_rule, operation, allow_export, status)"
                        + " VALUES ('VR-IT-BLIND', 'IT：患者姓名设盲（示例）', 'FIELD', NULL,"
                        + " 'followup', 'patient', 'name', 'BLINDED', NULL, 'READONLY', 0,"
                        + " 'ACTIVE')");

        try {
            enterView();
            Map<?, ?> page =
                    post(
                            "/api/hospital/followup/patients/search",
                            viewToken,
                            Map.of("keyword", NAME));
            assertEquals(0, codeOf(page));
            Map<?, ?> row = (Map<?, ?>) ((List<?>) data(page).get("items")).get(0);
            assertEquals("已设盲", row.get("name"), "命中设盲规则的字段接口不返回原值（PLT-VIEW-011）");

            // 医院会话同名检索仍是脱敏值（设盲只作用于视角）
            Map<?, ?> doctorPage =
                    post("/api/hospital/followup/patients/search", doctor, Map.of("keyword", NAME));
            Map<?, ?> doctorRow = (Map<?, ?>) ((List<?>) data(doctorPage).get("items")).get(0);
            assertEquals("王*）", doctorRow.get("name"), "医院会话不受视角规则影响");
            exitView();
        } finally {
            platform.update(
                    "DELETE FROM plt_view_rule_role WHERE rule_id IN"
                            + " (SELECT id FROM plt_view_rule WHERE code = 'VR-IT-BLIND')");
            platform.update("DELETE FROM plt_view_rule WHERE code = 'VR-IT-BLIND'");
        }

        // 规则删除即时生效：下一次视角检索恢复脱敏值（同一账号重新进入视角）
        enterView();
        Map<?, ?> restored =
                post("/api/hospital/followup/patients/search", viewToken, Map.of("keyword", NAME));
        assertEquals(0, codeOf(restored));
        Map<?, ?> restoredRow = (Map<?, ?>) ((List<?>) data(restored).get("items")).get(0);
        assertEquals("王*）", restoredRow.get("name"), "规则删除后恢复脱敏值（即时生效）");
        exitView();
    }

    // ---------------------------------------------------------------- 视角会话进出与清理

    /** 进入视角并换取视角令牌（E20-T01 / E20-T04 链路，默认平台管理员）。 */
    private void enterView() {
        enterViewAs("platform.admin", PLATFORM_PASSWORD);
    }

    /** 以指定平台账号进入视角（场景一的「无代为权限」角色用）。 */
    private void enterViewAs(String username, String password) {
        String platform = loginToken(username, password);
        Map<?, ?> entered =
                post(
                        "/api/platform/tenant-view/sessions",
                        platform,
                        Map.of(
                                "tenantId",
                                demoaId,
                                "purpose",
                                "TROUBLESHOOT",
                                "note",
                                "执行点验证（示例）"));
        assertEquals(0, codeOf(entered), "进入视角：" + entered.get("message"));
        sessionNo = (String) data(entered).get("sessionNo");
        Map<?, ?> ticket =
                post("/api/platform/tenant-view/sessions/current/entry-ticket", platform, Map.of());
        assertEquals(0, codeOf(ticket), "签发进入凭据：" + ticket.get("message"));
        Map<?, ?> exchanged =
                post(
                        "/api/hospital/view/enter",
                        null,
                        Map.of("ticket", data(ticket).get("ticket")));
        assertEquals(0, codeOf(exchanged), "换取视角令牌：" + exchanged.get("message"));
        viewToken = (String) data(exchanged).get("token");
        assertNotNull(viewToken);
    }

    private void exitView() {
        if (viewToken != null) {
            delete("/api/hospital/view/session", viewToken);
            viewToken = null;
        }
    }

    private String platformToken() {
        return loginToken("platform.admin", PLATFORM_PASSWORD);
    }

    private String loginToken(String username, String password) {
        Map<?, ?> login =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", username, "password", password));
        assertEquals(0, codeOf(login), "平台登录：" + login.get("message"));
        return (String) data(login).get("token");
    }

    /** 建 IT 角色（只带进入视角权限，三个代为开关全关 → 默认关闭口径）。 */
    private long createRole(String adminToken, String name) {
        Map<?, ?> response =
                post(
                        "/api/platform/roles",
                        adminToken,
                        Map.of(
                                "name",
                                name,
                                "menuIds",
                                List.of(
                                        platformDirect()
                                                .queryForObject(
                                                        "SELECT id FROM plt_menu WHERE"
                                                                + " perm_code = 'platform:view:enter'",
                                                        Long.class)),
                                "viewCanEnter",
                                true,
                                "viewVisibleScope",
                                "CONFIG_AND_BUSINESS",
                                "viewTenantScope",
                                "ALL"));
        assertEquals(0, codeOf(response), "建 IT 角色：" + response.get("message"));
        long id = ((Number) data(response).get("id")).longValue();
        itRoles.add(id);
        return id;
    }

    /** 建 IT 平台账号并完成首次改密（后续登录直接可用）。 */
    private void createAccount(
            String adminToken, String username, String displayName, long roleId) {
        Map<?, ?> response =
                post(
                        "/api/platform/accounts",
                        adminToken,
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                displayName,
                                "phone",
                                "139000099" + (10 + itAccounts.size() % 80),
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, codeOf(response), "建 IT 账号：" + response.get("message"));
        itAccounts.add(username);
        String first = loginToken(username, NEW_PASSWORD + "A");
        Map<?, ?> changed =
                post(
                        "/api/platform/auth/password",
                        first,
                        Map.of("oldPassword", NEW_PASSWORD + "A", "newPassword", NEW_PASSWORD));
        assertEquals(0, codeOf(changed), "IT 账号改密：" + changed.get("message"));
    }

    private String hospitalToken(String username) {
        Map<?, ?> login =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "username",
                                username,
                                "password",
                                HOSPITAL_PASSWORD));
        assertEquals(0, codeOf(login), "医院登录：" + login.get("message"));
        return (String) data(login).get("token");
    }

    /** 医院账号登记患者（沿用 E13-T04 造数口径，全部虚构；病案号带随机尾避免场景间重复）。 */
    private void register(String token) {
        Map<?, ?> created = post("/api/hospital/followup/patients", token, registerBody());
        assertEquals(0, codeOf(created), "登记患者：" + created.get("message"));
    }

    private Map<String, Object> registerBody() {
        return new HashMap<>(
                Map.of(
                        "medicalRecordNo",
                        MRN + "-" + System.nanoTime() % 100000,
                        "name",
                        NAME,
                        "gender",
                        "F",
                        "birthDate",
                        "1950-05-05",
                        "wardId",
                        wardIdByCode("W-DEMO-1"),
                        "surgeryDate",
                        "2026-03-01",
                        "admissionDate",
                        "2026-02-25",
                        "dischargeDate",
                        "2026-03-08",
                        "dischargeStatus",
                        1,
                        "legacyNo",
                        LEGACY));
    }

    private long wardIdByCode(String code) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                1L,
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private JdbcTemplate platformDirect() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    @AfterAll
    static void cleanUp() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        platform.update(
                "DELETE FROM plt_view_rule_role WHERE rule_id IN"
                        + " (SELECT id FROM plt_view_rule WHERE code = 'VR-IT-BLIND')");
        platform.update("DELETE FROM plt_view_rule WHERE code = 'VR-IT-BLIND'");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE view_session_id IN"
                        + " (SELECT session_no FROM plt_view_session WHERE account_name ="
                        + " 'platform.admin')");
        platform.update("DELETE FROM plt_view_session WHERE account_name = 'platform.admin'");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE view_session_id IN"
                        + " (SELECT session_no FROM plt_view_session WHERE account_name ="
                        + " 'it.view.noproxy')");
        platform.update("DELETE FROM plt_view_session WHERE account_name = 'it.view.noproxy'");
        for (Long roleId : itRoles) {
            platform.update("DELETE FROM plt_role_menu WHERE role_id = ?", roleId);
            platform.update("DELETE FROM plt_role WHERE id = ?", roleId);
        }
        for (String username : itAccounts) {
            platform.update(
                    "DELETE FROM plt_account_role WHERE account_id IN"
                            + " (SELECT id FROM plt_account WHERE username = ?)",
                    username);
            platform.update("DELETE FROM plt_account WHERE username = ?", username);
        }

        JdbcTemplate tenant =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/t_demoa" + params, "crdp_app", "DevOnly#app2026"));
        tenant.update(
                "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                        + " (SELECT id FROM pt_patient)");
        tenant.update("DELETE FROM pt_patient WHERE legacy_no = ?", LEGACY);
        AuditTableMaintenance.delete(
                tenant,
                "DELETE FROM aud_data_access_log WHERE action = 'BATCH_QUERY'"
                        + " AND description = '检索患者列表'");
    }

    // ---------------------------------------------------------------- HTTP 辅助（与
    // PlatformTenantViewMySqlIT 同款）

    private void deleteKeys(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private Map<?, ?> post(String path, String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return response == null ? -1 : ((Number) response.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<?, ?> response) {
        return response.get("data") == null
                ? new HashMap<>()
                : (Map<String, Object>) response.get("data");
    }
}
