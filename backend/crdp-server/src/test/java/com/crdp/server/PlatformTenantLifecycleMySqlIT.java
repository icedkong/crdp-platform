package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.LocalDate;
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
 * E05-T02 端到端（验收：状态流转按规格，续期即恢复）：向导带到期设置建租户 → 状态人工流转（试用转正、试用不能直接停用、手动停用、停用即禁登录、恢复）→ 宽限期 /
 * 停用中续期立即恢复启用 → 到期设置维护与校验 → 审计（TENANT_STATUS / TENANT_EXPIRE）→ 无权限 40300。 门控
 * CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清。
 *
 * <p>租户 A 只做「停用后登录被拒」断言、不做成功登录（登录查找缓存 60 秒，正负都缓存，同一轮 IT 里先拒后成会被缓存掩盖）； 续期即恢复用租户 B 的接口断言覆盖。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformTenantLifecycleMySqlIT {

    private static final LocalDate TODAY = LocalDate.now();

    private JdbcTemplate root;
    private JdbcTemplate platform;
    private final List<String> createdCodes = new java.util.ArrayList<>();
    private boolean viewerCreated;

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;

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

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpMethod method, String url, HttpEntity<?> entity) {
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    /** 建租户（走完整开通编排）并登记清理；返回 tenantId。 */
    private long createTenant(String name, Map<String, Object> extra) {
        Map<String, Object> request = new java.util.HashMap<>(extra);
        request.put("name", name);
        request.putIfAbsent("adminUsername", "admin");
        request.putIfAbsent("adminDisplayName", name + "管理员");
        request.putIfAbsent("adminInitialPassword", "Init@2026#");
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(request, bearer(loginPlatform())));
        assertEquals(0, code(response), () -> String.valueOf(response));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        String codeOf = data.get("code").toString();
        createdCodes.add(codeOf);
        return ((Number) data.get("tenantId")).longValue();
    }

    private String codeOf(long tenantId) {
        return platform.queryForObject(
                "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private String statusInDb(long tenantId) {
        return platform.queryForObject(
                "SELECT status FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void wizardCreatesTenantWithExpirySettingsAndManualTransitionsFollowSpec() {
        connect();
        String token = loginPlatform();
        HttpHeaders headers = bearer(token);

        // 向导第 2 步带到期设置（PLT-TEN-011）：试用到期日 + 自定义提醒节点与宽限期
        long tenantA =
                createTenant(
                        "虚构示例医院己",
                        Map.of(
                                "expireType",
                                "FIXED",
                                "expireAt",
                                TODAY.plusDays(60).toString(),
                                "remindDays",
                                List.of(7, 30),
                                "graceDays",
                                20,
                                "graceReadOnly",
                                true));
        assertEquals("TRIAL", statusInDb(tenantA));
        String codeA = codeOf(tenantA);
        Map<String, Object> stored =
                platform.queryForMap(
                        "SELECT expire_type, expire_at, remind_days, grace_days, grace_read_only"
                                + " FROM plt_tenant WHERE id = ?",
                        tenantA);
        assertEquals("FIXED", stored.get("expire_type"));
        assertEquals(java.sql.Date.valueOf(TODAY.plusDays(60)), stored.get("expire_at"));
        assertEquals("30,7", stored.get("remind_days"), "提醒节点降序存储");
        assertEquals(20, ((Number) stored.get("grace_days")).intValue());
        assertEquals(1, ((Number) stored.get("grace_read_only")).intValue());

        // 向导到期校验：有到期日但缺到期日 → 40001 且不建租户
        Map<?, ?> bad =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(
                                Map.of(
                                        "name", "虚构示例医院庚",
                                        "expireType", "FIXED"),
                                headers));
        assertEquals(40001, code(bad));

        // 影响面（状态变更对话框）：无医院会话、fu_task 未建
        Map<?, ?> impact =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants/" + tenantA + "/status/impact",
                        new HttpEntity<>(headers));
        assertEquals(0, code(impact));
        Map<?, ?> impactData = (Map<?, ?>) impact.get("data");
        assertEquals("TRIAL", impactData.get("status"));
        assertEquals(0, ((Number) impactData.get("onlineUserCount")).intValue());
        assertNull(impactData.get("pendingTaskCount"), "随访任务表未建（E15），进行中任务数为空");

        // 状态图：试用不能直接停用；缺原因 40001
        Map<?, ?> premature =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantA + "/status",
                        new HttpEntity<>(Map.of("action", "DISABLE", "reason", "示例原因"), headers));
        assertEquals(40901, code(premature));
        Map<?, ?> noReason =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantA + "/status",
                        new HttpEntity<>(Map.of("action", "ENABLE"), headers));
        assertEquals(40001, code(noReason));

        // 转正：试用 → 启用（写 TENANT_STATUS 审计）
        Map<?, ?> converted =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantA + "/status",
                        new HttpEntity<>(
                                Map.of("action", "ENABLE", "reason", "正式签约（示例）"), headers));
        assertEquals(0, code(converted), () -> String.valueOf(converted));
        Map<?, ?> convertedData = (Map<?, ?>) converted.get("data");
        assertEquals("TRIAL", convertedData.get("previousStatus"));
        assertEquals("ENABLED", convertedData.get("status"));
        assertEquals("ENABLED", statusInDb(tenantA));
        String convertAudit =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_STATUS'"
                                + " AND target = 'plt_tenant:"
                                + codeA
                                + "'",
                        String.class);
        assertTrue(convertAudit.contains("TRIAL→ENABLED"), convertAudit);
        assertTrue(convertAudit.contains("正式签约（示例）"), "人工变更必须留原因");

        // 手动停用：启用 → 停用
        Map<?, ?> disabled =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantA + "/status",
                        new HttpEntity<>(
                                Map.of("action", "DISABLE", "reason", "欠费停用（示例）"), headers));
        assertEquals(0, code(disabled));
        assertEquals("DISABLED", ((Map<?, ?>) disabled.get("data")).get("status"));
        assertEquals("DISABLED", statusInDb(tenantA));

        // 停用即禁止登录（PLT-TEN-016 的登录闸门；会话失效是 E05-T03）：40101 不区分原因
        Map<?, ?> hospitalLogin =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/login",
                        new HttpEntity<>(
                                Map.of(
                                        "tenantCode", codeA,
                                        "username", "admin",
                                        "password", "Init@2026#"),
                                new HttpHeaders()));
        assertEquals(40101, code(hospitalLogin), "停用租户不得登录");

        // 恢复：停用 → 启用；到期设置不受状态流转影响
        Map<?, ?> restored =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantA + "/status",
                        new HttpEntity<>(Map.of("action", "ENABLE", "reason", "已结清（示例）"), headers));
        assertEquals(0, code(restored));
        assertEquals("ENABLED", statusInDb(tenantA));
        assertEquals(
                "30,7",
                platform.queryForObject(
                        "SELECT remind_days FROM plt_tenant WHERE id = ?", String.class, tenantA));
    }

    @Test
    void renewalRestoresGraceAndDisabledTenantsImmediately() {
        connect();
        String token = loginPlatform();
        HttpHeaders headers = bearer(token);

        long tenantB =
                createTenant(
                        "虚构示例医院辛",
                        Map.of(
                                "expireType",
                                "FIXED",
                                "expireAt",
                                TODAY.plusDays(90).toString(),
                                "remindDays",
                                List.of(30, 7, 1),
                                "graceDays",
                                15,
                                "graceReadOnly",
                                false));

        String codeB = codeOf(tenantB);
        // 宽限期由到期作业转入（E05-T03），这里直改库模拟，验证「续期即恢复」
        platform.update("UPDATE plt_tenant SET status = 'GRACE' WHERE id = ?", tenantB);
        Map<?, ?> renewedFromGrace =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + tenantB + "/expire",
                        new HttpEntity<>(
                                Map.of(
                                        "expireType", "FIXED",
                                        "expireAt", TODAY.plusDays(365).toString(),
                                        "reason", "续费一年（示例）"),
                                headers));
        assertEquals(0, code(renewedFromGrace), () -> String.valueOf(renewedFromGrace));
        Map<?, ?> graceData = (Map<?, ?>) renewedFromGrace.get("data");
        assertEquals("GRACE", graceData.get("previousStatus"));
        assertEquals("ENABLED", graceData.get("status"), "宽限期租户续期后立即恢复启用");
        assertEquals("ENABLED", statusInDb(tenantB));
        // 只改到期日：提醒节点与宽限期沿用原配置
        Map<?, ?> expiry = (Map<?, ?>) graceData.get("expiry");
        assertEquals(TODAY.plusDays(365).toString(), expiry.get("expireAt"));
        assertEquals(List.of(30, 7, 1), expiry.get("remindDays"));
        assertEquals(15, ((Number) expiry.get("graceDays")).intValue());
        String graceAudit =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_EXPIRE'"
                                + " AND target = 'plt_tenant:"
                                + codeB
                                + "'",
                        String.class);
        assertTrue(graceAudit.contains("GRACE→ENABLED"), graceAudit);
        assertTrue(graceAudit.contains("expire_at"), graceAudit);

        // 停用中续期同样立即恢复
        platform.update("UPDATE plt_tenant SET status = 'DISABLED' WHERE id = ?", tenantB);
        Map<?, ?> renewedFromDisabled =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + tenantB + "/expire",
                        new HttpEntity<>(
                                Map.of(
                                        "expireType", "LONG_TERM",
                                        "reason", "买断转长期（示例）"),
                                headers));
        assertEquals(0, code(renewedFromDisabled));
        Map<?, ?> disabledData = (Map<?, ?>) renewedFromDisabled.get("data");
        assertEquals("DISABLED", disabledData.get("previousStatus"));
        assertEquals("ENABLED", disabledData.get("status"), "停用租户续期后立即恢复启用");
        assertEquals("ENABLED", statusInDb(tenantB));

        // 转长期有效：到期日、提醒节点、宽限期一并清空
        Map<String, Object> longTerm =
                platform.queryForMap(
                        "SELECT expire_type, expire_at, remind_days, grace_days, grace_read_only"
                                + " FROM plt_tenant WHERE id = ?",
                        tenantB);
        assertEquals("LONG_TERM", longTerm.get("expire_type"));
        assertNull(longTerm.get("expire_at"));
        assertNull(longTerm.get("remind_days"));
        assertNull(longTerm.get("grace_days"));
        assertEquals(0, ((Number) longTerm.get("grace_read_only")).intValue());

        // 到期设置维护：改回有到期日，显式覆盖提醒节点与宽限期（PLT-TEN-011）
        Map<?, ?> adjusted =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + tenantB + "/expire",
                        new HttpEntity<>(
                                Map.of(
                                        "expireType",
                                        "FIXED",
                                        "expireAt",
                                        TODAY.plusDays(180).toString(),
                                        "remindDays",
                                        List.of(14, 14, 3),
                                        "graceDays",
                                        7,
                                        "graceReadOnly",
                                        true,
                                        "reason",
                                        "按合同调整（示例）"),
                                headers));
        assertEquals(0, code(adjusted));
        Map<?, ?> adjustedExpiry = (Map<?, ?>) ((Map<?, ?>) adjusted.get("data")).get("expiry");
        assertEquals(List.of(14, 3), adjustedExpiry.get("remindDays"), "提醒节点去重降序");
        assertEquals(7, ((Number) adjustedExpiry.get("graceDays")).intValue());
        assertEquals(true, adjustedExpiry.get("graceReadOnly"));

        // 校验：过去 / 当天到期日、宽限期越界、缺原因
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantB + "/expire",
                                new HttpEntity<>(
                                        Map.of(
                                                "expireType", "FIXED",
                                                "expireAt", TODAY.toString(),
                                                "reason", "当天不算未来"),
                                        headers))));
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantB + "/expire",
                                new HttpEntity<>(
                                        Map.of(
                                                "expireType",
                                                "FIXED",
                                                "expireAt",
                                                TODAY.plusDays(30).toString(),
                                                "graceDays",
                                                0,
                                                "reason",
                                                "宽限期下限"),
                                        headers))));
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantB + "/expire",
                                new HttpEntity<>(
                                        Map.of(
                                                "expireType",
                                                "FIXED",
                                                "expireAt",
                                                TODAY.plusDays(30).toString()),
                                        headers))));

        // 详情（PLT-TEN-009）带出到期设置；开通中的租户不能设置到期（走开通进度页）
        Map<?, ?> detail =
                body(HttpMethod.GET, "/api/platform/tenants/" + tenantB, new HttpEntity<>(headers));
        Map<?, ?> detailData = (Map<?, ?>) detail.get("data");
        assertEquals(List.of(14, 3), detailData.get("remindDays"));
        assertEquals(7, ((Number) detailData.get("graceDays")).intValue());
        assertEquals(true, detailData.get("graceReadOnly"));
        long provisioning = createTenant("虚构示例医院壬", Map.of());
        platform.update("UPDATE plt_tenant SET status = 'PROVISIONING' WHERE id = ?", provisioning);
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + provisioning + "/expire",
                                new HttpEntity<>(
                                        Map.of(
                                                "expireType", "LONG_TERM",
                                                "reason", "开通中不能设置"),
                                        headers))));
    }

    @Test
    void viewerWithoutStatusOrRenewPermissionIsRejected() {
        connect();

        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_VIEWER_IT', '平台只读（IT 示例）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.viewer.it', '平台只读（示例）', ?, 'ENABLED')",
                passwordEncoder.encode("Demo@2026"));
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.viewer.it' AND r.code = 'PLATFORM_VIEWER_IT'");
        // 只授予 platform:tenant:view（菜单 111），不含 119 状态 / 120 续期按钮
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 111 FROM plt_role r WHERE r.code = 'PLATFORM_VIEWER_IT'");
        viewerCreated = true;

        String viewerToken = loginPlatformAs("platform.viewer.it");
        HttpHeaders viewerHeaders = bearer(viewerToken);
        long demoaId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + demoaId + "/status/impact",
                                new HttpEntity<>(viewerHeaders))),
                "影响面是只读信息，platform:tenant:view 即可查看");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + demoaId + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "DISABLE", "reason", "无权（示例）"),
                                        viewerHeaders))),
                "无 platform:tenant:status 不得变更状态");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + demoaId + "/expire",
                                new HttpEntity<>(
                                        Map.of("expireType", "LONG_TERM", "reason", "无权（示例）"),
                                        viewerHeaders))),
                "无 platform:tenant:renew 不得设置到期");
        assertEquals(
                "ENABLED",
                platform.queryForObject(
                        "SELECT status FROM plt_tenant WHERE id = ?", String.class, demoaId),
                "被拒请求不得改状态");
        assertTrue(
                platform.queryForList(
                                "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_STATUS'"
                                        + " AND detail LIKE '%无权（示例）%'",
                                String.class)
                        .isEmpty(),
                "被拒操作不产生状态变更审计记录（DENIED 审计随 E08 审计切面统一落地）");
    }

    private String loginPlatformAs(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/platform/auth/login",
                                new HttpEntity<>(
                                        Map.of("username", username, "password", "Demo@2026"),
                                        headers),
                                Map.class)
                        .getBody();
        return ((Map<?, ?>) login.get("data")).get("token").toString();
    }
}
