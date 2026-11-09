package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
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
 * E20-T07 验收用例（tasks.csv：脱敏、设盲、只读用例通过）——在 E20-T03 三场景之外补齐执行点矩阵，全部走 真实视角会话（进入 → 换视角令牌 → 调医院端接口）：
 *
 * <ol>
 *   <li>只读矩阵（PLT-VIEW-012）：配置类写（建用户）、批量接口（batch-assign）、原件下载签发口、 导出动作（GET / POST exports）全部
 *       40303；allowExport 规则命中时导出在视角守卫层放行、由内层二次认证防线 40304 收口（fail closed，OQ-107①⑥）。
 *   <li>脱敏矩阵（PLT-VIEW-010）：默认规则下视角读患者检索（POST search）与概览（GET summary）， 行内姓名 / 病案号只含脱敏值（前端不接收原值）；
 *       医院会话同接口同值（行为不变）。
 *   <li>设盲矩阵与优先级（PLT-VIEW-011、009）：FIELD 层病案号设盲 → 检索与概览病案号「已设盲」； TENANT 层姓名设盲压不过 FIELD 层脱敏 （字段 &gt;
 *       租户，执行与预览同引擎）；医院会话不受影响；删规则即时恢复。
 *   <li>双租户隔离（AGENTS.md §6）：带 demoa 租户选择器的字段层设盲规则只对 demoa 视角生效（同层与 全局脱敏默认合并取更严）， demob
 *       视角与两院医院会话仍是脱敏值。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（患者 legacy_no 前缀 IT-E20T07-、 规则
 * VR-IT-E20T07-*、视角会话行与 VIEW_ 审计）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTenantViewAcceptanceMySqlIT {

    private static final String PLATFORM_PASSWORD = "Demo@2026";
    private static final String HOSPITAL_PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E20T07";
    private static final String NAME = "王测视（示例）";
    private static final String NEW_PASSWORD = "View#2026xC";

    /** 本 IT 创建的平台账号 / 角色 → 清理用。 */
    private static final java.util.List<String> itAccounts = new java.util.ArrayList<>();

    private static final java.util.List<Long> itRoles = new java.util.ArrayList<>();

    /** 病案号带随机尾（场景间不重复；检索词按病案号精确命中唯一患者）。 */
    private String mrn() {
        return "IT-MRN-E20T07-" + System.nanoTime() % 100000;
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private String viewToken;
    private String sessionNo;
    private long demoaId;
    private long demobId;

    @BeforeEach
    void cleanState() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
        deleteKeys("crdp:view-enter:*");
        JdbcTemplate platform = platformDirect();
        // 前一场景断言失败时可能未走到退出：收掉残留的 ACTIVE 会话行，避免 40901 级联（自建自清）
        platform.update(
                "UPDATE plt_view_session SET status = 'ENDED', end_method = 'LOGOUT'"
                        + " WHERE account_name = 'platform.admin' AND status = 'ACTIVE'");
        demoaId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");
        demobId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demob");
        viewToken = null;
        sessionNo = null;
    }

    // ---------------------------------------------------------------- 1. 只读矩阵（PLT-VIEW-012）

    @Test
    @Order(1)
    void readonlyMatrixRejectsConfigWriteBatchOriginalAndExport() {
        // E20-T06 起 platform.admin 持代为操作权限（配置 / 业务写受控放行）——只读矩阵按无代为权限的角色验收
        String admin = platformToken();
        long roleId = createRole(admin, "视角只读角色（示例）");
        createAccount(admin, "it.view.noproxy", "视角只读账号（示例）", roleId);
        enterViewAs("it.view.noproxy", NEW_PASSWORD, demoaId);

        assertEquals(
                40303,
                codeOf(post("/api/hospital/system/users", viewToken, Map.of())),
                "配置类写（建用户）对视角拒绝");
        assertEquals(
                40303,
                codeOf(post("/api/hospital/followup/patients/batch-assign", viewToken, Map.of())),
                "批量接口（批量指定负责人）对视角拒绝");
        assertEquals(
                40303,
                codeOf(post("/api/hospital/files/1/download-url", viewToken, Map.of())),
                "原件与附件下载签发口对视角一律拒绝（PLT-VIEW-008）");
        assertEquals(
                40303,
                codeOf(get("/api/hospital/followup/exports", viewToken)),
                "导出动作的读接口（我的导出）按导出规则判：默认禁止");
        assertEquals(
                40303,
                codeOf(
                        post(
                                "/api/hospital/followup/exports",
                                viewToken,
                                Map.of("scope", "SEARCH"))),
                "提交导出默认禁止（allowExport 默认否）");

        // allowExport 规则命中：视角守卫层放行（不再 40303），内层二次认证防线收口 40304（fail closed，OQ-107①⑥）
        insertRule(
                "VR-IT-E20T07-EXPORT",
                "IT：患者导出允许（示例）",
                "FIELD",
                null,
                "followup",
                "patient",
                null,
                "PLAIN",
                "READONLY",
                true);
        try {
            Map<?, ?> reauth =
                    post("/api/hospital/followup/exports", viewToken, Map.of("scope", "SEARCH"));
            assertEquals(
                    40304,
                    codeOf(reauth),
                    "规则允许导出后由二次认证防线拦截（敏感导出在 E20-T06 前对视角 fail closed）：" + reauth.get("message"));
        } finally {
            deleteRules("VR-IT-E20T07-EXPORT");
        }

        // 被拒操作全部写平台访问审计（VIEW_DENIED 只进平台库）并递增台账
        JdbcTemplate platform = platformDirect();
        Integer denied =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action = 'VIEW_DENIED'"
                                + " AND view_session_id = ?",
                        Integer.class,
                        sessionNo);
        assertTrue(denied != null && denied >= 5, "五个被拒请求各写一条 VIEW_DENIED：" + denied);

        exitView();
    }

    // ---------------------------------------------------------------- 2. 脱敏矩阵（PLT-VIEW-010，默认规则）

    @Test
    @Order(2)
    void maskedValuesAcrossSearchAndSummaryForViewAndHospital() {
        String mrn = mrn();
        String doctor = register("demoa", demoaId, mrn);

        enterView(demoaId);
        String id = patientIdByMrn(viewToken, mrn);

        // 视角检索与概览：姓名 / 病案号只含脱敏值（前端不接收原值）
        Map<?, ?> row = searchRowByMrn(viewToken, mrn);
        assertEquals(SensitiveValueMasker.mask("name", NAME), row.get("name"), "检索行姓名脱敏");
        assertEquals(
                SensitiveValueMasker.mask("medicalRecordNo", mrn),
                row.get("patientNo"),
                "检索行病案号脱敏");
        Map<?, ?> summaryResponse =
                get("/api/hospital/followup/patients/" + id + "/summary", viewToken);
        assertEquals(0, codeOf(summaryResponse), "概览读放行：" + summaryResponse.get("message"));
        Map<?, ?> summary = data(summaryResponse);
        assertEquals(SensitiveValueMasker.mask("name", NAME), summary.get("name"), "概览姓名脱敏");
        assertEquals(
                SensitiveValueMasker.mask("medicalRecordNo", mrn),
                summary.get("patientNo"),
                "概览病案号脱敏");

        // 医院会话同接口同值（视角执行点只对 TENANT_VIEW 生效）
        Map<?, ?> doctorRow = searchRowByMrn(doctor, mrn);
        assertEquals(SensitiveValueMasker.mask("name", NAME), doctorRow.get("name"));
        assertEquals(SensitiveValueMasker.mask("medicalRecordNo", mrn), doctorRow.get("patientNo"));

        exitView();
    }

    // ---------------------------------------------------------------- 3.
    // 设盲矩阵与优先级（PLT-VIEW-011、009）

    @Test
    @Order(3)
    void blindedFieldAndTierPriorityTakeEffectImmediately() {
        String mrn = mrn();
        String doctor = register("demoa", demoaId, mrn);

        // FIELD 层：患者病案号设盲（同层更严：BLINDED > 默认 MASKED）
        insertRule(
                "VR-IT-E20T07-BLIND-MRN",
                "IT：患者病案号设盲（示例）",
                "FIELD",
                null,
                "followup",
                "patient",
                "medicalRecordNo",
                "BLINDED",
                "READONLY",
                false);
        // TENANT 层：demoa 姓名设盲（字段层脱敏默认应压过租户层设盲——字段 > 租户）
        insertRule(
                "VR-IT-E20T07-TENANT-NAME",
                "IT：demoa 患者姓名设盲（示例）",
                "TENANT",
                demoaId,
                "followup",
                "patient",
                "name",
                "BLINDED",
                "READONLY",
                false);
        try {
            enterView(demoaId);
            String id = patientIdByMrn(viewToken, mrn);
            Map<?, ?> row = searchRowByMrn(viewToken, mrn);
            assertEquals("已设盲", row.get("patientNo"), "命中设盲规则的字段接口不返回原值（PLT-VIEW-011）");
            assertEquals(
                    SensitiveValueMasker.mask("name", NAME),
                    row.get("name"),
                    "字段层脱敏默认压过租户层设盲（字段 > 租户，执行与预览同引擎）");
            Map<?, ?> summaryResponse =
                    get("/api/hospital/followup/patients/" + id + "/summary", viewToken);
            assertEquals(0, codeOf(summaryResponse));
            Map<?, ?> summary = data(summaryResponse);
            assertEquals("已设盲", summary.get("patientNo"), "概览病案号同样设盲");
            assertEquals(SensitiveValueMasker.mask("name", NAME), summary.get("name"));

            // 医院会话不受视角规则影响
            Map<?, ?> doctorRow = searchRowByMrn(doctor, mrn);
            assertEquals(
                    SensitiveValueMasker.mask("medicalRecordNo", mrn),
                    doctorRow.get("patientNo"),
                    "设盲只作用于视角会话");

            // 删规则即时生效：同会话下一次检索恢复脱敏值
            deleteRules("VR-IT-E20T07-BLIND-MRN", "VR-IT-E20T07-TENANT-NAME");
            Map<?, ?> restored = searchRowByMrn(viewToken, mrn);
            assertEquals(
                    SensitiveValueMasker.mask("medicalRecordNo", mrn),
                    restored.get("patientNo"),
                    "规则删除即时恢复脱敏值");
            exitView();
        } finally {
            deleteRules("VR-IT-E20T07-BLIND-MRN", "VR-IT-E20T07-TENANT-NAME");
        }
    }

    // ---------------------------------------------------------------- 4. 双租户隔离（AGENTS.md §6 双租户用例）

    @Test
    @Order(4)
    void tenantScopedRuleDoesNotLeakAcrossTenants() {
        String mrnA = mrn();
        String mrnB = mrn();
        register("demoa", demoaId, mrnA);
        register("demob", demobId, mrnB);

        try {
            // 带租户选择器的字段层设盲规则（同层与全局脱敏默认合并取更严 → BLINDED）：只应作用于 demoa
            insertRule(
                    "VR-IT-E20T07-SCOPE",
                    "IT：demoa 病案号设盲（示例）",
                    "FIELD",
                    demoaId,
                    "followup",
                    "patient",
                    "medicalRecordNo",
                    "BLINDED",
                    "READONLY",
                    false);

            // demoa 视角：租户选择器命中 → 同层更严合并为设盲
            enterView(demoaId);
            assertEquals(
                    "已设盲",
                    searchRowByMrn(viewToken, mrnA).get("patientNo"),
                    "demoa 规则对 demoa 视角生效");
            exitView();

            // demob 视角：demoa 的规则不外溢 → 仅全局脱敏默认命中，保留脱敏值
            enterView(demobId);
            assertEquals(
                    SensitiveValueMasker.mask("medicalRecordNo", mrnB),
                    searchRowByMrn(viewToken, mrnB).get("patientNo"),
                    "demoa 的规则不影响 demob 视角（双租户隔离）");
            exitView();

            // 两院医院会话不受视角规则影响（脱敏来自字段策略，与视角规则无关）
            assertEquals(
                    SensitiveValueMasker.mask("medicalRecordNo", mrnA),
                    searchRowByMrn(hospitalToken("demoa", "doctor.demo"), mrnA).get("patientNo"));
            assertEquals(
                    SensitiveValueMasker.mask("medicalRecordNo", mrnB),
                    searchRowByMrn(hospitalToken("demob", "doctor.demo"), mrnB).get("patientNo"));
        } finally {
            deleteRules("VR-IT-E20T07-SCOPE");
        }
    }

    // ---------------------------------------------------------------- 规则与造数辅助（平台库直连，自建自清）

    private void insertRule(
            String code,
            String name,
            String layer,
            Long tenantId,
            String module,
            String resource,
            String field,
            String visibility,
            String operation,
            boolean allowExport) {
        platformDirect()
                .update(
                        "INSERT INTO plt_view_rule (code, name, layer, tenant_id, module_code,"
                                + " resource, field, visibility, mask_rule, operation, allow_export,"
                                + " status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 'ACTIVE')",
                        code,
                        name,
                        layer,
                        tenantId,
                        module,
                        resource,
                        field,
                        visibility,
                        operation,
                        allowExport);
    }

    private void deleteRules(String... codes) {
        JdbcTemplate platform = platformDirect();
        for (String code : codes) {
            platform.update(
                    "DELETE FROM plt_view_rule_role WHERE rule_id IN"
                            + " (SELECT id FROM plt_view_rule WHERE code = ?)",
                    code);
            platform.update("DELETE FROM plt_view_rule WHERE code = ?", code);
        }
    }

    /** 医院账号（doctor.demo）登记患者并返回其令牌（全部虚构；沿用 E13-T04 造数口径；病区取该院 W-DEMO-1）。 */
    private String register(String tenantCode, long tenantId, String mrn) {
        String token = hospitalToken(tenantCode, "doctor.demo");
        Map<String, Object> body = registerBody(mrn);
        body.put("wardId", wardIdByCode(tenantCode, tenantId, "W-DEMO-1"));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者：" + created.get("message"));
        return token;
    }

    private Map<String, Object> registerBody(String mrn) {
        return new HashMap<>(
                Map.of(
                        "medicalRecordNo",
                        mrn,
                        "name",
                        NAME,
                        "gender",
                        "F",
                        "birthDate",
                        "1950-05-05",
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

    /** 按病案号精确检索并取唯一行（keyword 同时命中病案号盲索引）。 */
    private Map<?, ?> searchRowByMrn(String token, String mrn) {
        Map<?, ?> page =
                post("/api/hospital/followup/patients/search", token, Map.of("keyword", mrn));
        assertEquals(0, codeOf(page), "检索患者：" + page.get("message"));
        List<?> items = (List<?>) data(page).get("items");
        assertEquals(1, items.size(), "按病案号精确命中一名");
        return (Map<?, ?>) items.get(0);
    }

    private String patientIdByMrn(String token, String mrn) {
        return String.valueOf(searchRowByMrn(token, mrn).get("id"));
    }

    private long wardIdByCode(String tenantCode, long tenantId, String code) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    // ---------------------------------------------------------------- 视角会话进出（与 E20-T03 IT 同款链路）

    /** 进入指定租户的视角并换取视角令牌（E20-T01 / E20-T04 链路）。 */
    private void enterView(long tenantId) {
        enterViewAs("platform.admin", PLATFORM_PASSWORD, tenantId);
    }

    /** 以指定平台账号进入视角（场景一的「无代为权限」角色用）。 */
    private void enterViewAs(String username, String password, long tenantId) {
        String platform = loginToken(username, password);
        Map<?, ?> entered =
                post(
                        "/api/platform/tenant-view/sessions",
                        platform,
                        Map.of(
                                "tenantId",
                                tenantId,
                                "purpose",
                                "TROUBLESHOOT",
                                "note",
                                "验收用例验证（示例）"));
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

    private String hospitalToken(String tenantCode, String username) {
        Map<?, ?> login =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                tenantCode,
                                "username",
                                username,
                                "password",
                                HOSPITAL_PASSWORD));
        assertEquals(0, codeOf(login), "医院登录：" + login.get("message"));
        return (String) data(login).get("token");
    }

    private JdbcTemplate platformDirect() {
        return direct("crdp_platform");
    }

    private JdbcTemplate direct(String database) {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/" + database + params, "crdp_app", "DevOnly#app2026"));
    }

    @AfterAll
    static void cleanUp() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        for (String code :
                List.of(
                        "VR-IT-E20T07-EXPORT",
                        "VR-IT-E20T07-BLIND-MRN",
                        "VR-IT-E20T07-TENANT-NAME",
                        "VR-IT-E20T07-SCOPE")) {
            platform.update(
                    "DELETE FROM plt_view_rule_role WHERE rule_id IN"
                            + " (SELECT id FROM plt_view_rule WHERE code = ?)",
                    code);
            platform.update("DELETE FROM plt_view_rule WHERE code = ?", code);
        }
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

        for (String tenant : List.of("t_demoa", "t_demob")) {
            JdbcTemplate jdbc =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    base + "/" + tenant + params, "crdp_app", "DevOnly#app2026"));
            jdbc.update(
                    "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                            + " (SELECT id FROM pt_patient)");
            jdbc.update("DELETE FROM pt_patient WHERE legacy_no = ?", LEGACY);
            AuditTableMaintenance.delete(
                    jdbc,
                    "DELETE FROM aud_data_access_log WHERE action = 'BATCH_QUERY'"
                            + " AND description = '检索患者列表'");
        }
    }

    // ---------------------------------------------------------------- HTTP 辅助（与 E20-T03 IT 同款）

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
