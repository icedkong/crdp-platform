package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.patient.domain.PatientPlaintextSource;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E07-T05 / A-PAT-010、C-PAT-012 端到端（验收：默认脱敏；查看明文需权限并记审计）。
 *
 * <p>患者表随 E13-T01 建表，明文来源用 {@link PatientPlaintextSource} 测试桩（虚构患者 990001） 打通整条链：权限
 * followup:patient:plain（无权限 40300）、字段策略 MASKED 口径（VISIBLE / HIDDEN 40001）、 越范围 40400（不暴露存在）、模块关闭
 * 40301、频控 42900 + 安全告警 PLAINTEXT_ABUSE、每次成功调用一条 VIEW_PLAIN 数据访问审计（字段码与场景， 不记明文）。脱敏序列化本身见 {@code
 * PolicyMaskedSerializerTest} / {@code SensitiveFieldSanitizerTest}。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（ITR-PLAIN 角色、itr.plain.* 用户）， 全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientPlaintextIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    /** 桩内虚构患者：IT 直接编号（患者表 E13 前不存在，不与真实数据冲突）。 */
    private static final long PATIENT_IN_SCOPE = 990_001L;

    private static final long PATIENT_OUT_OF_SCOPE = 990_099L;

    /** 明文夹具全部虚构（AGENTS.md §5.5）：手机号为虚构测试号段，证件号为校验位有效的虚构演示号。 */
    private static final String PLAIN_NAME = "测试患者01（示例）";

    private static final String PLAIN_PHONE = "13800005678";

    private static final String PLAIN_ID_CARD = "110102199001015672";

    /** 明文来源桩（替代 E13 前的占位实现）：只虚构两名患者，其余一律 40400 口径。 */
    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        PatientPlaintextSource stubPatientPlaintextSource() {
            return patientId ->
                    patientId == PATIENT_IN_SCOPE
                            ? Optional.of(
                                    Map.of(
                                            "name", PLAIN_NAME,
                                            "phone", PLAIN_PHONE,
                                            "idCard", PLAIN_ID_CARD,
                                            "medicalRecordNo", "MR1000001", // 虚构演示病案号
                                            "address", "北京市东城区（示例街道）"))
                            : Optional.empty();
        }
    }

    private JdbcTemplate platform;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private com.crdp.server.platform.TenantModuleService moduleService;

    @BeforeAll
    void connectPlatform() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
    }

    @BeforeEach
    void cleanState() {
        runInTenant(() -> deleteItrRows(jdbc));
    }

    @AfterAll
    void cleanUp() {
        // 先恢复平台侧可分配范围（去掉本套件放开的 211），保持其他套件的基线不漂移
        restorePlainMenu();
        Long rateUserId =
                TenantContext.callAsTenant(
                        TENANT_ID, TENANT, () -> userId(jdbc, "itr.plain.rate.demo"));
        TenantContext.runAsTenant(TENANT_ID, TENANT, () -> deleteItrRows(jdbc));
        if (rateUserId != null) {
            // 频控窗口键不属于审计链表，直接清理（60 秒自然过期，这里显式清避免本套件重跑踩键）
            redis.delete("crdp:plain:reveal:" + TENANT_ID + ":" + rateUserId);
        }
        // 平台库告警行（非审计链表）：按本套件用户清理
        platform.update(
                "DELETE FROM plt_security_alert WHERE rule_code = 'PLAINTEXT_ABUSE'"
                        + " AND user_name LIKE 'itr.plain.%'");
    }

    private static void deleteItrRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                        + " WHERE u.username LIKE 'itr.plain.%'");
        jdbc.update(
                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                        + " WHERE r.code = 'ITR-PLAIN'");
        jdbc.update(
                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                        + " WHERE r.code = 'ITR-PLAIN'");
        jdbc.update(
                "DELETE fp FROM sys_role_field_policy fp JOIN sys_role r ON r.id = fp.role_id"
                        + " WHERE r.code = 'ITR-PLAIN'");
        jdbc.update("DELETE FROM sys_role WHERE code = 'ITR-PLAIN'");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.plain.%'");
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_data_access_log WHERE access_method = 'VIEW_PLAIN'"
                        + " AND object_id IN ('990001', '990099')");
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_log WHERE action = 'ACCESS_DENIED'"
                        + " AND detail LIKE '%plaintext%'");
    }

    // ---------------------------------------------------------------- 用例

    /** 正常：持 followup:patient:plain 查看脱敏字段明文；响应 no-store；审计一条 VIEW_PLAIN（不记明文）。 */
    @Test
    void permittedUserRevealsMaskedFieldsAndWritesDataAccessAudit() {
        String token = userWithPlainRole("itr.plain.main.demo", Map.of());

        ResponseEntity<Map> response =
                rest.postForEntity(
                        path(PATIENT_IN_SCOPE),
                        new HttpEntity<>(
                                Map.of("fields", List.of("name", "phone"), "scene", "detail"),
                                headers(token)),
                        Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(
                "no-store", response.getHeaders().getCacheControl(), "明文响应不得落任何缓存（patient.md §8）");
        Map<?, ?> body = response.getBody();
        assertEquals(0, code(body));
        Map<?, ?> values =
                data(body).get("values") == null ? Map.of() : (Map<?, ?>) data(body).get("values");
        assertEquals(PLAIN_NAME, values.get("name"));
        assertEquals(PLAIN_PHONE, values.get("phone"));
        assertEquals(2, values.size(), "只返回请求字段");
        assertEquals(60, ((Number) data(body).get("expiresInSeconds")).intValue());

        Map<String, Object> audit =
                runInTenant(
                        () ->
                                jdbc.queryForMap(
                                        "SELECT description, record_count, result, query_digest"
                                                + " FROM aud_data_access_log"
                                                + " WHERE access_method = 'VIEW_PLAIN'"
                                                + " AND object_id = '990001' AND result = 'SUCCESS'"
                                                + " ORDER BY id DESC LIMIT 1"));
        assertEquals("查看患者敏感字段明文：姓名、手机号（场景 detail）", audit.get("description"));
        assertEquals(2L, ((Number) audit.get("record_count")).longValue());
        assertFalse(audit.get("description").toString().contains(PLAIN_PHONE), "审计描述不得出现明文");
        assertTrue(
                audit.get("query_digest") == null || "".equals(audit.get("query_digest")),
                "查询摘要不落明文");
    }

    /** 无权限（doctor.demo 无按钮 211）：40300（前端隐藏不能代替后端校验）。 */
    @Test
    void userWithoutPlainPermissionGets40300() {
        String token = login("doctor.demo");

        assertEquals(
                40300,
                code(post(path(PATIENT_IN_SCOPE), token, Map.of("fields", List.of("name")))));
    }

    /** 越范围 / 不存在：40400（不暴露对象存在）并写 DENIED 数据访问审计。 */
    @Test
    void outOfScopePatientGets40400WithDeniedAudit() {
        String token = userWithPlainRole("itr.plain.scope.demo", Map.of());

        assertEquals(
                40400,
                code(post(path(PATIENT_OUT_OF_SCOPE), token, Map.of("fields", List.of("phone")))));

        Integer denied =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_data_access_log"
                                                + " WHERE access_method = 'VIEW_PLAIN'"
                                                + " AND object_id = '990099' AND result = 'DENIED'"
                                                + " AND error_code = 40400",
                                        Integer.class));
        assertTrue(denied != null && denied >= 1, "越范围明文查看应写 DENIED 审计");
    }

    /** 校验失败（40001）：目录外字段、场景非法；策略口径：VISIBLE 字段明文已随普通接口返回、HIDDEN 字段禁止输出。 */
    @Test
    void invalidFieldSceneOrPolicyReturns40001() {
        String token = userWithPlainRole("itr.plain.valid.demo", Map.of());

        assertEquals(
                40001,
                code(post(path(PATIENT_IN_SCOPE), token, Map.of("fields", List.of("nickname")))));
        assertEquals(
                40001,
                code(
                        post(
                                path(PATIENT_IN_SCOPE),
                                token,
                                Map.of("fields", List.of("name"), "scene", "export"))));
        assertEquals(40001, code(post(path(PATIENT_IN_SCOPE), token, Map.of("fields", List.of()))));

        // 策略覆盖：name VISIBLE（明文随普通接口返回）、address HIDDEN —— 都不走明文通道
        String overridden =
                userWithPlainRole(
                        "itr.plain.policy.demo", Map.of("name", "VISIBLE", "address", "HIDDEN"));
        assertEquals(
                40001,
                code(post(path(PATIENT_IN_SCOPE), overridden, Map.of("fields", List.of("name")))));
        assertEquals(
                40001,
                code(
                        post(
                                path(PATIENT_IN_SCOPE),
                                overridden,
                                Map.of("fields", List.of("address")))));
    }

    /**
     * 模块关闭（40301）：followup 经真实入口关闭（递增权限版本，PLT-MOD-006），在线会话下一次请求 重算 openedModules——明文接口随即
     * 40301；数据与角色保留，恢复即用。
     */
    @Test
    void closedFollowupModuleReturns40301() {
        String token = userWithPlainRole("itr.plain.module.demo", Map.of());
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));

            assertEquals(
                    40301,
                    code(post(path(PATIENT_IN_SCOPE), token, Map.of("fields", List.of("name")))));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    /** 频控（42900）：同一用户每分钟 ≤ 30 次；超限写 DENIED 审计并触发安全告警 PLAINTEXT_ABUSE（PLT-AUD-012）。 */
    @Test
    void thirtyFirstCallWithinAMinuteIsRejectedAndRaisesSecurityAlert() {
        String username = "itr.plain.rate.demo";
        String token = userWithPlainRole(username, Map.of());

        for (int i = 1; i <= 30; i++) {
            assertEquals(
                    0,
                    code(post(path(PATIENT_IN_SCOPE), token, Map.of("fields", List.of("name")))),
                    "第 " + i + " 次应放行");
        }
        assertEquals(
                42900,
                code(post(path(PATIENT_IN_SCOPE), token, Map.of("fields", List.of("name")))));

        Integer denied =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_data_access_log"
                                                + " WHERE access_method = 'VIEW_PLAIN'"
                                                + " AND result = 'DENIED' AND error_code = 42900"
                                                + " AND reason = 'RATE_LIMITED'",
                                        Integer.class));
        assertTrue(denied != null && denied >= 1, "频控拒绝应写 DENIED 审计");

        List<Map<String, Object>> alerts =
                platform.queryForList(
                        "SELECT detail, send_status, channel, recipient_name FROM"
                                + " plt_security_alert WHERE rule_code = 'PLAINTEXT_ABUSE'"
                                + " AND user_name = ?",
                        username);
        assertFalse(alerts.isEmpty(), "频控触发应写 PLAINTEXT_ABUSE 安全告警");
        for (Map<String, Object> alert : alerts) {
            assertEquals("PENDING", alert.get("send_status"), "站内信投递随 E09，先落 PENDING");
            assertEquals("INBOX", alert.get("channel"));
            assertNotNull(alert.get("recipient_name"), "接收人为启用的平台管理员");
            assertFalse(alert.get("detail").toString().contains(PLAIN_NAME), "告警摘要不含明文");
        }
    }

    // ---------------------------------------------------------------- 基础设施

    /**
     * 建持有按钮 211（followup:patient:plain）的角色并建用户登录，返回令牌；fieldPolicies 为本角色显式覆盖。 口令哈希复用种子医生（启动时已被
     * DemoPasswordInitializer 换成 Argon2id），测试用户密码同 Demo@2026。
     */
    private String userWithPlainRole(String username, Map<String, String> fieldPolicies) {
        runInTenant(
                () -> {
                    List<Long> existing =
                            jdbc.queryForList(
                                    "SELECT id FROM sys_role WHERE code = 'ITR-PLAIN'", Long.class);
                    long roleId;
                    if (existing.isEmpty()) {
                        jdbc.update(
                                "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                        + " VALUES ('ITR-PLAIN', 'IT 明文查看角色（示例）', 0, 'ENABLED',"
                                        + " 'CUSTOM')");
                        roleId =
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_role WHERE code = 'ITR-PLAIN'",
                                        Long.class);
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 211)",
                                roleId);
                        jdbc.update(
                                "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                roleId);
                    } else {
                        roleId = existing.get(0);
                    }
                    fieldPolicies.forEach(
                            (field, policy) ->
                                    jdbc.update(
                                            "INSERT INTO sys_role_field_policy (role_id, resource,"
                                                    + " field, policy) VALUES (?, 'patient', ?, ?)",
                                            roleId,
                                            field,
                                            policy));
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) SELECT ?, 'IT 明文用户（示例）',"
                                    + " password_hash, 'ENABLED', 0 FROM sys_user WHERE username ="
                                    + " 'doctor.demo'",
                            username);
                    long userId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = ?",
                                    Long.class,
                                    username);
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            userId,
                            roleId);
                });
        // followup:patient:plain 是敏感权限（plt_sensitive_perm）：平台先对本院放开菜单 211 才可分配
        // （PLT-SCOPE-004/008，经真实入口保存，写范围变更审计）
        releasePlainMenu();
        return login(username);
    }

    /**
     * 平台侧放开按钮 211（followup:patient:plain 是敏感权限，plt_sensitive_perm， 默认不进可分配范围）。 直接写 plt_tenant_scope
     * 覆盖行而不走 TenantScopeService.apply：apply 会写 SCOPE_UPDATE 平台审计， 其他套件按该事件做全量计数断言，测试脚手架不该往只追加审计里塞行；
     * 生产放开必须走真实入口（含敏感原因与审计）。
     */
    private void releasePlainMenu() {
        platform.update("DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id = 211");
        platform.update(
                "INSERT INTO plt_tenant_scope (tenant_id, menu_id, assignable, reason)"
                        + " VALUES (1, 211, 1, 'IT 明文查看用例放开（示例）')");
    }

    /** 恢复平台侧可分配范围基线（demoa 默认不含 211），并清掉早期调试经真实入口留下的痕迹。 */
    private void restorePlainMenu() {
        platform.update("DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id = 211");
        platform.update("DELETE FROM plt_scope_log WHERE reason LIKE 'IT 明文查看用例%'");
        // 早期调试曾走 TenantScopeService.apply（写 SCOPE_UPDATE 平台审计，只追加表），按本套件标记清理
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type = 'SCOPE_UPDATE'"
                        + " AND detail LIKE '%IT 明文查看用例%'");
    }

    private String login(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD));
        assertEquals(0, code(response), username + " 应能登录");
        return (String) data(response).get("token");
    }

    private static String path(long patientId) {
        return "/api/hospital/followup/patients/" + patientId + "/plaintext";
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return rest.exchange(
                        path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), Map.class)
                .getBody();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static Long userId(JdbcTemplate jdbc, String username) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
