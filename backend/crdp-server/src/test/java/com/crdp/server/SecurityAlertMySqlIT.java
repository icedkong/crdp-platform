package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E08-T05 / PLT-AUD-012 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连）。 验收原文：触发规则后站内信告警。
 *
 * <ol>
 *   <li>异常登录：连续失败达到阈值锁定（LOCK 登录日志）→ 平台库 plt_security_alert 出现 ABNORMAL_LOGIN
 *       站内信派发行（channel=INBOX、PENDING，接收人 = 启用的 PLATFORM_ADMIN）；
 *   <li>批量导出：DATA_ACCESS/EXPORT（E08-T04 提交导出的审计点）→ BATCH_EXPORT 派发行；
 *   <li>权限变更：ROLE_* 操作 → PERMISSION_CHANGE 派发行（平台访问 VIEW_* 写入侧随 E20-T01，规则已就绪）。
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
class SecurityAlertMySqlIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";
    private static final String PROBE_MODULE = "it-alert";
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
        // plt_security_alert 非审计链表（无只读触发器），普通 DELETE 即可；按本类标记清理
        platform.update("DELETE FROM plt_security_alert WHERE module = ?", PROBE_MODULE);
        platform.update(
                "DELETE FROM plt_security_alert WHERE rule_code = 'ABNORMAL_LOGIN'"
                        + " AND user_name = ?",
                ADMIN);
        runInTenant(
                TENANT,
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_login_log WHERE username = ?", ADMIN);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_data_access_log WHERE module = ?", PROBE_MODULE);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE module = ?", PROBE_MODULE);
                });
    }

    @BeforeEach
    void cleanState() {
        // 清失败计数与验证码桶，避免其他 IT 的失败计数把本类的登录锁死（与 AuditWriteMySqlIT 同法）
        java.util.Set<String> keys = redis.keys("crdp:login:fail:*");
        if (keys != null) {
            redis.delete(keys);
        }
        java.util.Set<String> captchaKeys = redis.keys("crdp:captcha:*");
        if (captchaKeys != null) {
            redis.delete(captchaKeys);
        }
        platform.update("DELETE FROM plt_security_alert WHERE module = ?", PROBE_MODULE);
        platform.update(
                "DELETE FROM plt_security_alert WHERE rule_code = 'ABNORMAL_LOGIN' AND user_name = ?",
                ADMIN);
    }

    // ---------------------------------------------------------------- 异常登录（验收主链路，HTTP 端到端）

    @Test
    void lockoutRaisesAbnormalLoginInboxAlertForPlatformAdmins() {
        // 连续失败 3 次后要求验证码：第 4、5 次带正确验证码 + 错口令，走 rejected() 计失败，
        // 第 5 次触发锁定并写 LOCK 登录日志（E08-T01 的异常登录事件源）；第 6 次尝试被 42900 拒绝。
        // 验证码摘要直接注入 Redis（CaptchaStore 同口径）
        for (int i = 0; i < 3; i++) {
            assertEquals(40101, codeOf(login(ADMIN, "Wrong@2026", null, null)));
        }
        for (int i = 0; i < 2; i++) {
            String captchaId = java.util.UUID.randomUUID().toString();
            redis.opsForValue()
                    .set(
                            "crdp:captcha:code:" + captchaId,
                            sha256Hex("ITQ7"),
                            java.time.Duration.ofMinutes(5));
            Map<?, ?> response = login(ADMIN, "Wrong@2026", captchaId, "ITQ7");
            assertEquals(40101, codeOf(response), "带正确验证码的错口令按 40101 计失败：" + response);
        }
        assertEquals(42900, codeOf(login(ADMIN, PASSWORD, null, null)), "锁定期间一律拒绝");

        // 触发规则后站内信告警：平台库出现 ABNORMAL_LOGIN 派发行（INBOX、PENDING，接收人含种子 platform.admin）
        Integer notices =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_security_alert"
                                + " WHERE rule_code = 'ABNORMAL_LOGIN' AND user_name = ?"
                                + " AND channel = 'INBOX' AND send_status = 'PENDING'",
                        Integer.class,
                        ADMIN);
        assertTrue(notices != null && notices >= 1, "异常登录告警至少派发给一名平台管理员");
        Integer toSeedAdmin =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_security_alert s"
                                + " JOIN plt_account a ON a.id = s.recipient_id"
                                + " WHERE s.rule_code = 'ABNORMAL_LOGIN' AND s.user_name = ?"
                                + " AND a.username = 'platform.admin'",
                        Integer.class,
                        ADMIN);
        assertTrue(toSeedAdmin != null && toSeedAdmin >= 1, "种子平台管理员 platform.admin 应收到告警");
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT * FROM plt_security_alert"
                                + " WHERE rule_code = 'ABNORMAL_LOGIN' AND user_name = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        ADMIN);
        assertEquals("LOCK", String.valueOf(row.get("action")), "告警记录触发动作 LOCK（账号锁定）");
        assertTrue(
                String.valueOf(row.get("detail")).contains(ADMIN), "摘要包含触发账号：" + row.get("detail"));
        assertTrue(
                ((Number) row.get("tenant_id")).longValue() > 0, "登录日志不带 tenant_id，租户取自触发时的租户上下文");
    }

    // ---------------------------------------------------------------- 批量导出 / 权限变更（审计点探针，与 E08-T04
    // 相同写入路径）

    @Test
    void exportAccessRaisesBatchExportInboxAlert() {
        runInTenant(
                TENANT,
                () ->
                        auditLogger.append(
                                AuditEntry.dataAccess("it-alert", "EXPORT")
                                        .tenantId(tenantIdOf(TENANT))
                                        .description("租户管理员导出审计日志（免审批）：操作日志")
                                        .objectType("AUDIT_EXPORT")
                                        .objectId("33")
                                        .result(com.crdp.framework.audit.AuditResult.SUCCESS)
                                        .build()));

        Integer notices =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_security_alert WHERE rule_code = 'BATCH_EXPORT'"
                                + " AND module = ? AND channel = 'INBOX' AND send_status = 'PENDING'",
                        Integer.class,
                        PROBE_MODULE);
        assertTrue(notices != null && notices >= 1, "批量导出应实时告警给平台管理员");
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT * FROM plt_security_alert WHERE rule_code = 'BATCH_EXPORT'"
                                + " AND module = ? ORDER BY id DESC LIMIT 1",
                        PROBE_MODULE);
        assertEquals("EXPORT", String.valueOf(row.get("action")));
        assertEquals("AUDIT_EXPORT", String.valueOf(row.get("object_type")));
        assertEquals("33", String.valueOf(row.get("object_id")));
    }

    @Test
    void permissionChangeRaisesAlertButPlainOperationDoesNot() {
        runInTenant(
                TENANT,
                () -> {
                    auditLogger.append(
                            AuditEntry.operation("it-alert", "ROLE_MEMBERS")
                                    .description("调整角色成员（示例）")
                                    .objectType("sys_role")
                                    .objectId("9")
                                    .result(com.crdp.framework.audit.AuditResult.SUCCESS)
                                    .build());
                    auditLogger.append(
                            AuditEntry.operation("it-alert", "PARAM_UPDATE")
                                    .description("普通参数变更（示例）")
                                    .result(com.crdp.framework.audit.AuditResult.SUCCESS)
                                    .build());
                });

        Integer permissionAlerts =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_security_alert"
                                + " WHERE rule_code = 'PERMISSION_CHANGE' AND module = ?",
                        Integer.class,
                        PROBE_MODULE);
        assertTrue(permissionAlerts != null && permissionAlerts >= 1, "权限变更应告警");
        Integer plainAlerts =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_security_alert WHERE module = ?"
                                + " AND rule_code <> 'PERMISSION_CHANGE'",
                        Integer.class,
                        PROBE_MODULE);
        assertEquals(0, plainAlerts, "普通操作不触发安全告警");
    }

    // ---------------------------------------------------------------- 工具（与既有 IT 同构）

    private Map<?, ?> login(
            String username, String password, String captchaId, String captchaCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "IT-ALERT-UA/1.0");
        headers.set("X-Forwarded-For", IP_MARKER + ".9.5");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("tenantCode", TENANT);
        body.put("username", username);
        body.put("password", password);
        if (captchaId != null) {
            body.put("captchaId", captchaId);
            body.put("captchaCode", captchaCode);
        }
        return rest.postForEntity(
                        "/api/hospital/auth/login", new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    /** CaptchaStore 同口径：Redis 存大写验证码的 SHA-256 摘要。 */
    private static String sha256Hex(String code) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            code.toUpperCase(java.util.Locale.ROOT)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
