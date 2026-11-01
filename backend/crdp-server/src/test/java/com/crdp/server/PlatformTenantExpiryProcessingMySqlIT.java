package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.server.platform.TenantExpiryService;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E05-T03 端到端（验收：到期前提醒，宽限期满自动停用）：提醒节点登记与幂等 → 试用 / 启用到期的自动转宽限 → 宽限期登录与会话不受影响、 只读租户写请求 40306 →
 * 宽限期满自动停用（吊销在线会话、开停用时段）→ 续期 / 人工恢复关段 → 悬停时段接口与无权限 40300。 门控 CRDP_IT_MYSQL_PORT（docker compose 的
 * MySQL 与 Redis），自建自清。
 *
 * <p>到期日只允许未来（E05-T02 校验），因此「已到期 / 宽限中」的初态用直改平台库模拟（与 E05-T02 IT 同一做法）； 租户查找缓存 60
 * 秒正负都缓存，故停用后的「新登录被拒」不做断言（登录闸门属 E05-T02，已覆盖），本 IT 以存量会话 40100 断言「在线会话立即失效」。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformTenantExpiryProcessingMySqlIT {

    // 服务端按北京时间校验「到期日必须晚于当天」（Asia/Shanghai）；CI 在 UTC 16:00–24:00（北京次日凌晨）
    // 运行时 LocalDate.now() 比 Beijing 慢一天，plusDays(1) 会撞上「等于当天」被 40001 拒绝。
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantExpiryService expiryService;

    private JdbcTemplate root;
    private JdbcTemplate platform;
    private final List<String> createdCodes = new java.util.ArrayList<>();
    private boolean viewerCreated;

    private void connect() {
        if (platform != null) {
            return;
        }
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                "DevOnly#root2026"));
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
        for (String code : createdCodes) {
            root.execute("DROP DATABASE IF EXISTS t_" + code);
            root.execute("DROP USER IF EXISTS `t_" + code + "`@'%'");
            platform.update("DELETE FROM plt_audit_log WHERE target = ?", "plt_tenant:" + code);
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_tenant_expire_notice WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_suspension WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.viewer.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_VIEWER_IT'");
        }
    }

    // ---------------------------------------------------------------- 请求辅助

    private String loginPlatform() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/platform/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "username",
                                                "platform.admin",
                                                "password",
                                                "Demo@2026"),
                                        headers),
                                Map.class)
                        .getBody();
        return ((Map<?, ?>) login.get("data")).get("token").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpMethod method, String url, HttpEntity<?> entity) {
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object value = response == null ? null : response.get("code");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    private HttpHeaders platformJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** 建租户（走完整开通编排，管理员 admin / Init@2026#）并登记清理；返回 tenantId。 */
    private long createTenant(String name, Map<String, Object> extra) {
        Map<String, Object> request = new HashMap<>(extra);
        request.put("name", name);
        request.putIfAbsent("adminUsername", "admin");
        request.putIfAbsent("adminDisplayName", name + "管理员");
        request.putIfAbsent("adminInitialPassword", "Init@2026#");
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(request, platformJson(loginPlatform())));
        assertEquals(0, code(response), () -> String.valueOf(response));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        createdCodes.add(data.get("code").toString());
        return ((Number) data.get("tenantId")).longValue();
    }

    /** 医院端登录并返回 Bearer 头；随后立即改掉初始密码，解除 40305 强制改密对写请求断言的干扰。 */
    private HttpHeaders hospitalSession(String tenantCode, String marker) {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/login",
                        new HttpEntity<>(
                                Map.of(
                                        "tenantCode",
                                        tenantCode,
                                        "username",
                                        "admin",
                                        "password",
                                        "Init@2026#"),
                                json));
        assertEquals(0, code(login), () -> String.valueOf(login));
        String token = ((Map<?, ?>) login.get("data")).get("token").toString();
        HttpHeaders auth = new HttpHeaders();
        auth.setContentType(MediaType.APPLICATION_JSON);
        auth.setBearerAuth(token);
        Map<?, ?> changed =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/password",
                        new HttpEntity<>(
                                Map.of(
                                        "oldPassword",
                                        "Init@2026#",
                                        "newPassword",
                                        "Renew@2026#" + marker),
                                auth));
        assertEquals(0, code(changed), () -> String.valueOf(changed));
        return auth;
    }

    // ---------------------------------------------------------------- 库内辅助

    private String statusInDb(long tenantId) {
        return platform.queryForObject(
                "SELECT status FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private String codeOf(long tenantId) {
        return platform.queryForObject(
                "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private int noticeCount(long tenantId) {
        return platform.queryForObject(
                "SELECT COUNT(*) FROM plt_tenant_expire_notice WHERE tenant_id = ?",
                Integer.class,
                tenantId);
    }

    private Map<String, Object> openSuspension(long tenantId) {
        return platform.queryForMap(
                "SELECT cause, reason, restored_at, restore_cause FROM plt_tenant_suspension"
                        + " WHERE tenant_id = ? ORDER BY disabled_at DESC LIMIT 1",
                tenantId);
    }

    // ---------------------------------------------------------------- 用例

    /** PLT-TEN-012：到期前按节点提醒（登记派发行，E09 通道接入后消费），同节点不重发；长期有效不提醒。 */
    @Test
    void remindersAreRecordedPerNodeAndIdempotent() {
        connect();
        long reminded =
                createTenant(
                        "虚构示例医院辛",
                        Map.of("expireType", "FIXED", "expireAt", TODAY.plusDays(30).toString()));
        long longTerm = createTenant("虚构示例医院壬", Map.of());

        expiryService.runForDate(TODAY);

        // 默认节点 30,7,1：今天是 30 天节点 → 租户管理员（admin）+ 平台负责人（PLATFORM_ADMIN）各至少一条
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT recipient_type, days_left, remind_date, channels, send_status"
                                + " FROM plt_tenant_expire_notice WHERE tenant_id = ?",
                        reminded);
        assertTrue(rows.size() >= 2, "接收人含租户管理员与平台负责人，实际 " + rows.size());
        assertTrue(
                rows.stream().anyMatch(row -> "TENANT_ADMIN".equals(row.get("recipient_type"))),
                "含租户管理员");
        assertTrue(
                rows.stream().anyMatch(row -> "PLATFORM_OWNER".equals(row.get("recipient_type"))),
                "含平台负责人");
        for (Map<String, Object> row : rows) {
            assertEquals(30, ((Number) row.get("days_left")).intValue());
            assertEquals(java.sql.Date.valueOf(TODAY), row.get("remind_date"));
            assertEquals("INBOX,SMS", row.get("channels"));
            assertEquals("PENDING", row.get("send_status"));
        }
        assertEquals(0, noticeCount(longTerm), "长期有效租户不提醒");

        // 当天重复执行（幂等重跑）：不产生重复派发行
        int before = noticeCount(reminded);
        expiryService.runForDate(TODAY);
        assertEquals(before, noticeCount(reminded));
    }

    /** PLT-TEN-012、016：试用到期自动转宽限（可登录、可写、会话保留）→ 宽限期满自动停用（会话立即失效、开停用时段）→ 续期关段。 */
    @Test
    void trialExpiryEntersGraceThenAutoDisablesAndRenewalClosesSuspension() {
        connect();
        String token = loginPlatform();
        long tenantB =
                createTenant(
                        "虚构示例医院癸",
                        Map.of(
                                "expireType",
                                "FIXED",
                                "expireAt",
                                TODAY.plusDays(1).toString(),
                                "graceDays",
                                2,
                                "remindDays",
                                List.of(7)));
        HttpHeaders hospital = hospitalSession(codeOf(tenantB), "B");
        assertEquals(
                0,
                code(body(HttpMethod.GET, "/api/hospital/auth/me", new HttpEntity<>(hospital))),
                "停用前的医院会话正常");

        // 模拟已到期（到期日只允许未来）：到期昨天，宽限 2 天 → 宽限覆盖 [昨天, 明天)
        platform.update(
                "UPDATE plt_tenant SET expire_at = ? WHERE id = ?",
                java.sql.Date.valueOf(TODAY.minusDays(1)),
                tenantB);

        expiryService.runForDate(TODAY);
        assertEquals("GRACE", statusInDb(tenantB), "到期当天（补跑含已过期）自动进入宽限期");
        assertEquals(
                0,
                code(body(HttpMethod.GET, "/api/hospital/auth/me", new HttpEntity<>(hospital))),
                "宽限期默认正常使用：存量会话不失效");
        assertNotEquals(
                40306,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/hospital/followup/patients",
                                new HttpEntity<>(Map.of(), hospital))),
                "未配置只读的宽限租户写请求不被 40306 拦截");
        assertEquals(
                0,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_suspension WHERE tenant_id = ?",
                        Integer.class,
                        tenantB),
                "宽限期不开停用时段");
        String graceAudit =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_STATUS'"
                                + " AND target = ? AND detail LIKE '%→GRACE%'",
                        String.class, "plt_tenant:" + codeOf(tenantB));
        assertTrue(graceAudit.contains("试用到期自动"), "自动流转写平台审计：" + graceAudit);

        // 宽限期满（明天 = 到期日 + 2）：自动停用
        expiryService.runForDate(TODAY.plusDays(1));
        assertEquals("DISABLED", statusInDb(tenantB), "宽限期满自动停用");
        assertEquals(
                40100,
                code(body(HttpMethod.GET, "/api/hospital/auth/me", new HttpEntity<>(hospital))),
                "停用后在线会话立即失效（PLT-TEN-016）");
        Map<String, Object> suspension = openSuspension(tenantB);
        assertEquals("AUTO_GRACE_END", suspension.get("cause"));
        assertNull(suspension.get("restored_at"), "停用时段开段，恢复后关闭");
        assertTrue(String.valueOf(suspension.get("reason")).contains("宽限期结束未续期"), "自动停用原因可追溯");

        // 悬停时段接口（PLT-TEN-017 底座）
        Map<?, ?> listed =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants/" + tenantB + "/suspensions",
                        new HttpEntity<>(platformJson(token)));
        assertEquals(0, code(listed));
        assertEquals(1, ((List<?>) listed.get("data")).size());

        // 续期即恢复（PLT-TEN-013）：关停用时段，恢复途径 RENEWAL
        Map<?, ?> renewed =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + tenantB + "/expire",
                        new HttpEntity<>(
                                Map.of(
                                        "expireType",
                                        "FIXED",
                                        "expireAt",
                                        TODAY.plusDays(90).toString(),
                                        "reason",
                                        "虚构示例续期"),
                                platformJson(token)));
        assertEquals(0, code(renewed), () -> String.valueOf(renewed));
        assertEquals("ENABLED", statusInDb(tenantB));
        Map<String, Object> closed = openSuspension(tenantB);
        assertNotNull(closed.get("restored_at"), "恢复后停用时段关闭（停用补偿的推导依据）");
        assertEquals("RENEWAL", closed.get("restore_cause"));
    }

    /** PLT-TEN-011、012：启用态服务到期转宽限；配置只读的宽限租户写 40306、读照常；tenant-state 供续费提示。 */
    @Test
    void enabledExpiryEntersGraceAndReadOnlyTenantCannotWrite() {
        connect();
        String token = loginPlatform();
        long tenantC =
                createTenant(
                        "虚构示例医院子",
                        Map.of(
                                "expireType",
                                "FIXED",
                                "expireAt",
                                TODAY.plusDays(60).toString(),
                                "graceDays",
                                5,
                                "graceReadOnly",
                                true,
                                "remindDays",
                                List.of(7)));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantC + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "ENABLE", "reason", "虚构示例转正"),
                                        platformJson(token)))),
                "转正后进入启用态");
        HttpHeaders hospital = hospitalSession(codeOf(tenantC), "C");

        platform.update(
                "UPDATE plt_tenant SET expire_at = ? WHERE id = ?",
                java.sql.Date.valueOf(TODAY),
                tenantC);
        expiryService.runForDate(TODAY);
        assertEquals("GRACE", statusInDb(tenantC), "启用态服务到期自动转宽限");

        assertEquals(
                0,
                code(body(HttpMethod.GET, "/api/hospital/auth/me", new HttpEntity<>(hospital))),
                "宽限期允许登录与会话存续");
        assertEquals(
                40306,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/hospital/followup/patients",
                                new HttpEntity<>(Map.of(), hospital))),
                "配置只读的宽限租户：写请求 40306");
        assertEquals(
                40306,
                code(
                        body(
                                HttpMethod.DELETE,
                                "/api/hospital/followup/tasks/1",
                                new HttpEntity<>(hospital))),
                "删除同样被拦截");
        Map<?, ?> state =
                body(HttpMethod.GET, "/api/hospital/auth/tenant-state", new HttpEntity<>(hospital));
        assertEquals(0, code(state));
        Map<?, ?> stateData = (Map<?, ?>) state.get("data");
        assertEquals("GRACE", stateData.get("status"));
        assertEquals(true, stateData.get("graceReadOnly"));
        assertEquals(0, ((Number) stateData.get("daysLeft")).intValue(), "到期当天 daysLeft=0");
        assertEquals(TODAY.toString(), stateData.get("expireAt"));
    }

    /** PLT-TEN-016、017：人工停用同样开段 + 吊销会话；人工启用恢复关段（MANUAL_ENABLE）；无权限查悬停时段 40300。 */
    @Test
    void manualDisableOpensSuspensionAndManualEnableClosesIt() {
        connect();
        String token = loginPlatform();
        long tenantF =
                createTenant(
                        "虚构示例医院丑",
                        Map.of("expireType", "FIXED", "expireAt", TODAY.plusDays(60).toString()));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantF + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "ENABLE", "reason", "虚构示例转正"),
                                        platformJson(token)))),
                "转正");
        HttpHeaders hospital = hospitalSession(codeOf(tenantF), "F");

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantF + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "DISABLE", "reason", "虚构示例人工停用"),
                                        platformJson(token)))),
                "人工停用");
        assertEquals(
                40100,
                code(body(HttpMethod.GET, "/api/hospital/auth/me", new HttpEntity<>(hospital))),
                "人工停用同样吊销在线会话");
        assertEquals("MANUAL", openSuspension(tenantF).get("cause"));

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantF + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "ENABLE", "reason", "虚构示例恢复"),
                                        platformJson(token)))),
                "人工启用恢复");
        Map<String, Object> closed = openSuspension(tenantF);
        assertNotNull(closed.get("restored_at"));
        assertEquals("MANUAL_ENABLE", closed.get("restore_cause"));

        // 无 platform:tenant:view 的平台账号：悬停时段 40300
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_VIEWER_IT', '平台无权限（IT 示例）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.viewer.it', '平台无权限（示例）', ?, 'ENABLED')",
                passwordEncoder.encode("Demo@2026"));
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.viewer.it' AND r.code = 'PLATFORM_VIEWER_IT'");
        viewerCreated = true;
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> viewerLogin =
                body(
                        HttpMethod.POST,
                        "/api/platform/auth/login",
                        new HttpEntity<>(
                                Map.of("username", "platform.viewer.it", "password", "Demo@2026"),
                                json));
        assertEquals(0, code(viewerLogin));
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + tenantF + "/suspensions",
                                new HttpEntity<>(
                                        platformJson(
                                                ((Map<?, ?>) viewerLogin.get("data"))
                                                        .get("token")
                                                        .toString())))),
                "无 platform:tenant:view 权限的账号被拒");
    }
}
