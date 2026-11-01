package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.tenant.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * E08-T01 / PLT-AUD-001~004 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连）。 验收原文：四类日志按规格字段写入。
 *
 * <ol>
 *   <li>登录日志（002）：成功（登录方式 / 租户识别方式 / 会话号 / IP / 终端 / 操作者）、失败（失败原因）、登出；
 *   <li>操作日志（001）：登录后的管理操作补全操作者四件套与请求上下文；被拒 40300 落 result=DENIED；
 *   <li>数据访问日志（003）：访问方式 / 记录数 / 查询条件摘要 / 文件哈希扩展字段；
 *   <li>平台访问日志（004）：只落平台库（租户视角记录，医院端不可见）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（标记列值），不碰种子数据。全部虚构演示账号。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuditWriteMySqlIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";
    private static final String VIEWER = "doctor.demo";
    private static final String IP_MARKER = "10.99";

    private JdbcTemplate platform;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogger auditLogger;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;

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

    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        platform.update("DELETE FROM aud_platform_access_log WHERE module = 'it-audit'");
        platform.update("DELETE FROM aud_login_log WHERE username LIKE 'it-audit%'");
        platform.update("DELETE FROM plt_audit_log WHERE action = 'IT_AUDIT_PROBE'");
        platform.update("DELETE FROM plt_audit_log WHERE detail = '状态探针（示例）'");
    }

    @BeforeEach
    void cleanState() {
        // 清失败计数与验证码桶，避免其他 IT 的失败计数把本类的登录锁死（与 LoginLockoutAutoUnlockIT 同法）
        java.util.Set<String> keys = redis.keys("crdp:login:fail:*");
        if (keys != null) {
            redis.delete(keys);
        }
        runInTenant(
                TENANT,
                () -> {
                    jdbc.update(
                            "DELETE FROM aud_login_log WHERE username IN (?, ?)", ADMIN, VIEWER);
                    jdbc.update(
                            "DELETE FROM aud_log WHERE action = 'ACCESS_DENIED'"
                                    + " OR action = 'IT_AUDIT_PROBE' OR module = 'it-audit'");
                    jdbc.update("DELETE FROM aud_data_access_log WHERE module = 'it-audit'");
                });
    }

    // ---------------------------------------------------------------- 登录日志（PLT-AUD-002）

    @Test
    void successfulLoginWritesSpecFieldsToTenantLoginLog() {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", ADMIN, "password", PASSWORD),
                        IP_MARKER + ".1.7");
        assertEquals(0, codeOf(response), "登录失败：" + response);

        Map<String, Object> row =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT * FROM aud_login_log WHERE username = ?"
                                                + " AND action = 'LOGIN' ORDER BY id DESC LIMIT 1",
                                        ADMIN));
        // 通用 + 扩展字段：结果、登录方式、租户识别方式、会话号、IP、终端、操作者
        assertEquals("SUCCESS", row.get("result"));
        assertEquals("PASSWORD", row.get("login_method"));
        assertEquals("INPUT", row.get("tenant_identify_method"));
        assertNotNull(row.get("session_id"), "会话号必填");
        assertEquals("HOSPITAL", row.get("user_type"));
        assertNotNull(row.get("user_id"));
        assertNotNull(row.get("display_name"));
        assertNotNull(row.get("role_codes"));
        assertTrue(String.valueOf(row.get("ip")).startsWith(IP_MARKER), "记录请求来源 IP");
        assertTrue(String.valueOf(row.get("user_agent")).contains("IT-AUDIT-UA"), "记录终端信息");
        assertNotNull(row.get("trace_id"), "traceId 必填");
    }

    @Test
    void failedLoginWritesFailReason() {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", ADMIN, "password", "Wrong@2026"),
                        IP_MARKER + ".1.8");
        assertEquals(40101, codeOf(response));

        Map<String, Object> row =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT * FROM aud_login_log WHERE username = ?"
                                                + " AND success = 0 ORDER BY id DESC LIMIT 1",
                                        ADMIN));
        assertEquals("FAIL", row.get("result"));
        assertEquals("bad-password", row.get("fail_reason"), "失败原因按规格落扩展字段");
    }

    @Test
    void logoutWritesLoginLog() {
        String token = hospitalToken(ADMIN);
        Map<?, ?> response = post("/api/hospital/auth/logout", token, Map.of(), null);
        assertEquals(0, codeOf(response));

        Integer count =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_login_log WHERE username = ?"
                                                + " AND action = 'LOGOUT' AND result = 'SUCCESS'",
                                        Integer.class,
                                        ADMIN));
        assertEquals(1, count, "登出事件落登录日志（PLT-AUD-002「登出」）");
    }

    // ---------------------------------------------------------------- 操作日志（PLT-AUD-001）

    @Test
    void operationAuditWritesSpecColumns() {
        // 直接探针验证列映射（HTTP 路径的操作者 / 请求上下文补全见 DENIED 用例）
        runInTenant(
                TENANT,
                () ->
                        auditLogger.append(
                                AuditEntry.operation("it-audit", "IT_AUDIT_PROBE")
                                        .objectType("sys_user")
                                        .objectId("1")
                                        .beforeJson("{\"status\":\"ENABLED\"}")
                                        .afterJson("{\"status\":\"DISABLED\"}")
                                        .build()));
        Map<String, Object> row =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT * FROM aud_log WHERE action = 'IT_AUDIT_PROBE'"
                                                + " ORDER BY id DESC LIMIT 1"));
        assertEquals("IT_AUDIT_PROBE", row.get("event_type"));
        assertEquals("SUCCESS", row.get("result"));
        assertEquals("it-audit", row.get("module"));
        assertEquals("sys_user", row.get("object_type"));
        assertEquals("1", row.get("object_id"));
        assertEquals("sys_user:1", row.get("target"), "target 兼容 M0 列（objectType:objectId）");
        assertEquals("{\"status\":\"ENABLED\"}", row.get("before_json"));
        assertEquals("{\"status\":\"DISABLED\"}", row.get("after_json"));
    }

    @Test
    void deniedRequestWritesDeniedOperationLog() {
        // doctor.demo 没有 tenant:user:view：40300 也要写审计（audit.md §1）
        String token = hospitalToken(VIEWER);
        Map<?, ?> response = get("/api/hospital/system/users", token, IP_MARKER + ".1.10");
        assertEquals(40300, codeOf(response));

        Map<String, Object> row =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT * FROM aud_log WHERE action = 'ACCESS_DENIED'"
                                                + " ORDER BY id DESC LIMIT 1"));
        assertEquals("DENIED", row.get("result"));
        assertEquals(40300, ((Number) row.get("error_code")).intValue());
        assertEquals("system", row.get("module"));
        assertEquals(VIEWER, row.get("user_name"));
        String description = String.valueOf(row.get("detail"));
        assertTrue(
                description.contains("GET") && description.contains("/api/hospital/system/users"),
                "描述记录方法与路径：" + description);
        assertTrue(String.valueOf(row.get("ip")).startsWith(IP_MARKER));
    }

    // ---------------------------------------------------------------- 数据访问日志（PLT-AUD-003）

    @Test
    void dataAccessAuditWritesExtensionColumns() {
        runInTenant(
                TENANT,
                () ->
                        auditLogger.append(
                                AuditEntry.dataAccess("it-audit", "VIEW_PLAIN")
                                        .description("查看患者手机号明文（示例）")
                                        .objectType("patient")
                                        .objectId("9001")
                                        .recordCount(1L)
                                        .queryDigest("id=9001")
                                        .build()));

        Map<String, Object> row =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT * FROM aud_data_access_log WHERE module ="
                                                + " 'it-audit' ORDER BY id DESC LIMIT 1"));
        assertEquals("VIEW_PLAIN", row.get("access_method"));
        assertEquals(1L, ((Number) row.get("record_count")).longValue());
        assertEquals("id=9001", row.get("query_digest"));
        assertEquals("patient", row.get("object_type"));
        assertEquals("9001", row.get("object_id"));
        assertEquals("SUCCESS", row.get("result"));
    }

    // ---------------------------------------------------------------- 平台访问日志（PLT-AUD-004）

    @Test
    void platformAccessAuditLandsOnlyInPlatformDatabase() {
        // 模拟平台账号在租户视角的访问（E20 之前的写入路径验证）：租户上下文里也要落平台库
        runInTenant(
                TENANT,
                () ->
                        auditLogger.append(
                                AuditEntry.platformAccess(1L, "it-audit", "PAGE_VIEW")
                                        .description("租户视角查看患者列表（示例）")
                                        .viewSessionId("vs-it-001")
                                        .page("/patient/list")
                                        .result(com.crdp.framework.audit.AuditResult.SUCCESS)
                                        .build()));

        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT * FROM aud_platform_access_log WHERE module = 'it-audit'"
                                + " ORDER BY id DESC LIMIT 1");
        assertEquals(1L, ((Number) row.get("tenant_id")).longValue());
        assertEquals("PAGE_VIEW", row.get("action"));
        assertEquals("vs-it-001", row.get("view_session_id"));
        assertEquals("/patient/list", row.get("page"));
        assertEquals("SUCCESS", row.get("result"));
        // 医院端不提供平台访问记录入口：租户库没有该表（tenantId=1 的 demoa 里查不到）
        Boolean tableExists =
                runInTenant(
                        TENANT,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) > 0 FROM information_schema.tables"
                                                + " WHERE table_schema = DATABASE()"
                                                + " AND table_name = 'aud_platform_access_log'",
                                        Boolean.class));
        assertEquals(Boolean.FALSE, tableExists, "租户库不得出现平台访问日志表");
    }

    @Test
    void platformOperationAuditCarriesSpecFields() {
        // 显式 tenantId（新口径）与 M0 target 反查（遗留 record 桥接）都能补 tenant_id
        runAsPlatform(
                () ->
                        auditLogger.append(
                                AuditEntry.operation("platform", "IT_AUDIT_PROBE")
                                        .tenantId(1L)
                                        .description("平台操作探针（示例）")
                                        .build()));
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT * FROM plt_audit_log WHERE action = 'IT_AUDIT_PROBE'"
                                + " ORDER BY id DESC LIMIT 1");
        assertEquals("IT_AUDIT_PROBE", row.get("event_type"));
        assertEquals("SUCCESS", row.get("result"));
        assertEquals("platform", row.get("module"));
        assertEquals(1L, ((Number) row.get("tenant_id")).longValue(), "PLT-TEN-022 按租户维度检索");

        runAsPlatform(
                () ->
                        auditLogger.record(
                                new com.crdp.framework.audit.AuditEvent(
                                        "TENANT_STATUS",
                                        1L,
                                        "UPDATE",
                                        "plt_tenant:demoa",
                                        "状态探针（示例）")));
        Map<String, Object> bridged =
                platform.queryForMap(
                        "SELECT * FROM plt_audit_log WHERE detail = '状态探针（示例）'"
                                + " ORDER BY id DESC LIMIT 1");
        assertEquals(
                1L,
                ((Number) bridged.get("tenant_id")).longValue(),
                "M0 target 反查补 tenant_id（PLT-TEN-022）");
        assertEquals("TENANT_STATUS", bridged.get("event_type"));
    }

    // ---------------------------------------------------------------- 工具（与既有 IT 同构）

    private String hospitalToken(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD),
                        null);
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body, String ip) {
        HttpHeaders headers = jsonHeaders(token, ip);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token, String ip) {
        HttpHeaders headers = jsonHeaders(token, ip);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private HttpHeaders jsonHeaders(String token, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.set("User-Agent", "IT-AUDIT-UA/1.0");
        if (ip != null) {
            headers.set("X-Forwarded-For", ip);
        }
        return headers;
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private void runAsPlatform(Runnable action) {
        TenantContext.runAsPlatform(action);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
