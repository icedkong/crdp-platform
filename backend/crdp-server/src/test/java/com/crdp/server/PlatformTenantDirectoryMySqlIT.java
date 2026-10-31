package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
 * E05-T01 端到端（验收：按名称、状态检索；修改留记录）：平台端登录 → 列表按名称/编码、状态、到期日、模块检索 → 详情 → 信息维护（电话加密落库、写 TENANT_UPDATE
 * 审计）→ 无编辑权限 40300。门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis）；自 E02-T09 起随 CI
 * 运行；自建自清（演示租户 demoa 的列复原、IT 账号与审计记录清除）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PlatformTenantDirectoryMySqlIT {

    private static JdbcTemplate platform;
    private static long demoaId;
    private static boolean viewerCreated;

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 上下文（含平台库 Flyway）启动后才能查种子租户，故在每个用例开头惰性连接。 */
    private static JdbcTemplate connect() {
        if (platform == null) {
            platform =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    "jdbc:mysql://127.0.0.1:"
                                            + System.getenv("CRDP_IT_MYSQL_PORT")
                                            + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                    "crdp_app",
                                    "DevOnly#app2026"));
            demoaId =
                    platform.queryForObject(
                            "SELECT id FROM plt_tenant WHERE code = 'demoa'", Long.class);
            assertNotNull(demoaId, "种子租户 demoa 应存在");
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        // 复原演示租户的维护列（种子态为空），名称与状态不动
        platform.update(
                "UPDATE plt_tenant SET short_name = NULL, credit_code = NULL, org_type = NULL,"
                        + " region = NULL, contact_name = NULL, contact_phone = NULL,"
                        + " contact_email = NULL, contract_no = NULL, remark = NULL,"
                        + " updated_at = NULL WHERE id = ?",
                demoaId);
        platform.update(
                "DELETE FROM plt_audit_log WHERE event_type = 'TENANT_UPDATE' AND target = ?",
                "plt_tenant:demoa");
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

    private String login(String username) {
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

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpMethod method, String url, HttpEntity<?> entity) {
        // 用已编码的 URI，避免 RestTemplate 把 %-编码再编一次（中文关键词会被翻成 %25E4…）
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    @Test
    void listSearchesByNameStatusExpiryAndModule() {
        connect();
        // 未登录拒绝
        assertEquals(
                40100,
                code(body(HttpMethod.GET, "/api/platform/tenants", HttpEntity.EMPTY)),
                "无令牌不得查看租户列表");

        String token = login("platform.admin");

        // 全量：种子租户都在，模块与用户数随行展示（PLT-TEN-001）
        Map<?, ?> all =
                body(HttpMethod.GET, "/api/platform/tenants", new HttpEntity<>(bearer(token)));
        assertEquals(0, code(all));
        Map<?, ?> allData = (Map<?, ?>) all.get("data");
        assertTrue(((Number) allData.get("total")).longValue() >= 2, "应包含两个种子租户");
        Map<String, Object> demoa =
                ((List<Map<String, Object>>) allData.get("items"))
                        .stream()
                                .filter(item -> "demoa".equals(item.get("code")))
                                .findFirst()
                                .orElseThrow();
        List<Map<String, Object>> modules = (List<Map<String, Object>>) demoa.get("modules");
        assertTrue(
                modules.stream().anyMatch(m -> "followup".equals(m.get("code"))),
                "已开通模块应含随访：" + modules);
        assertTrue(modules.stream().anyMatch(m -> "平台基础".equals(m.get("name"))));
        assertNotNull(demoa.get("userCount"), "租户库可达时用户数应返回");
        assertEquals("LONG_TERM", demoa.get("expireType"));
        assertNull(demoa.get("expireAt"), "长期有效租户无到期日");

        // 按名称/编码关键词：只命中乙
        String keyword = URLEncoder.encode("乙", StandardCharsets.UTF_8);
        Map<?, ?> byKeyword =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?keyword=" + keyword,
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(byKeyword));
        Map<?, ?> keywordData = (Map<?, ?>) byKeyword.get("data");
        assertEquals(1, ((Number) keywordData.get("total")).intValue());
        assertEquals(
                "demob", ((List<Map<String, Object>>) keywordData.get("items")).get(0).get("code"));

        // 按编码关键词同样命中
        Map<?, ?> byCode =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?keyword=demoa",
                        new HttpEntity<>(bearer(token)));
        assertEquals(
                1,
                ((Number) ((Map<?, ?>) byCode.get("data")).get("total")).intValue(),
                "按编码检索应命中 demoa");

        // 按状态
        Map<?, ?> enabled =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?status=ENABLED",
                        new HttpEntity<>(bearer(token)));
        assertTrue(((Number) ((Map<?, ?>) enabled.get("data")).get("total")).longValue() >= 2);
        Map<?, ?> disabled =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?status=DISABLED",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, ((Number) ((Map<?, ?>) disabled.get("data")).get("total")).intValue());

        // 按到期日：种子租户均为长期有效，不命中
        Map<?, ?> expiring =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?expireBefore=2099-12-31",
                        new HttpEntity<>(bearer(token)));
        assertEquals(
                0,
                ((Number) ((Map<?, ?>) expiring.get("data")).get("total")).intValue(),
                "长期有效租户不进到期日筛选");

        // 按模块
        Map<?, ?> followup =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?moduleCode=followup",
                        new HttpEntity<>(bearer(token)));
        assertTrue(
                ((List<Map<String, Object>>) ((Map<?, ?>) followup.get("data")).get("items"))
                        .stream().anyMatch(item -> "demoa".equals(item.get("code"))));

        // 非法入参
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants?status=GONE",
                                new HttpEntity<>(bearer(token)))));
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants?expireBefore=2099/12/31",
                                new HttpEntity<>(bearer(token)))));
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants?sort=password",
                                new HttpEntity<>(bearer(token)))));

        // 分页：pageSize=1 时 total 不变、items 只有 1 条
        Map<?, ?> paged =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?pageNo=1&pageSize=1",
                        new HttpEntity<>(bearer(token)));
        Map<?, ?> pagedData = (Map<?, ?>) paged.get("data");
        assertEquals(1, ((List<?>) pagedData.get("items")).size());
        assertEquals(1, ((Number) pagedData.get("pageSize")).intValue());
        assertTrue(((Number) pagedData.get("total")).longValue() >= 2);
    }

    @Test
    void updateMaintainsInfoWritesAuditAndEncryptsPhone() {
        connect();
        String token = login("platform.admin");
        HttpHeaders headers = bearer(token);

        // 详情（PLT-TEN-009）
        Map<?, ?> before =
                body(HttpMethod.GET, "/api/platform/tenants/" + demoaId, new HttpEntity<>(headers));
        assertEquals(0, code(before));
        assertEquals("demoa", ((Map<?, ?>) before.get("data")).get("code"));

        // 维护：基本信息、联系人、备注
        Map<?, ?> updated =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + demoaId,
                        new HttpEntity<>(
                                Map.of(
                                        "name", "虚构示例医院甲",
                                        "shortName", "示例甲院",
                                        "creditCode", "DEMOUSCC0000001X",
                                        "orgType", "三级甲等（示例）",
                                        "region", "示例市示例区",
                                        "contactName", "李经理（示例）",
                                        "contactPhone", "13900000002",
                                        "contactEmail", "lee@example.com",
                                        "contractNo", "DEMO-HT-2026-001",
                                        "remark", "续保联系人已换（示例）"),
                                headers));
        assertEquals(0, code(updated), () -> String.valueOf(updated));
        assertEquals("示例甲院", ((Map<?, ?>) updated.get("data")).get("shortName"));

        // 电话加密落库（密文不含明文），修改时间与审计记录（修改留记录）
        String storedPhone =
                platform.queryForObject(
                        "SELECT contact_phone FROM plt_tenant WHERE id = ?", String.class, demoaId);
        assertNotNull(storedPhone);
        assertFalse(storedPhone.contains("13900000002"), "电话必须以密文存储");
        assertNotNull(
                platform.queryForObject(
                        "SELECT updated_at FROM plt_tenant WHERE id = ?",
                        java.sql.Timestamp.class,
                        demoaId),
                "修改后应留更新时间");

        String auditDetail =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_UPDATE'"
                                + " AND target = 'plt_tenant:demoa' ORDER BY id DESC LIMIT 1",
                        String.class);
        assertNotNull(auditDetail, "信息维护必须写审计记录");
        assertTrue(auditDetail.contains("contact_phone"), auditDetail);
        assertFalse(auditDetail.contains("13900000002"), "审计不得落电话明文");

        // 详情能解回明文（编辑场景），列表不返回电话
        Map<?, ?> after =
                body(HttpMethod.GET, "/api/platform/tenants/" + demoaId, new HttpEntity<>(headers));
        assertEquals("13900000002", ((Map<?, ?>) after.get("data")).get("contactPhone"));
        Map<?, ?> list =
                body(
                        HttpMethod.GET,
                        "/api/platform/tenants?keyword=demoa",
                        new HttpEntity<>(headers));
        assertFalse(
                ((List<Map<String, Object>>) ((Map<?, ?>) list.get("data")).get("items"))
                        .get(0)
                        .containsKey("contactPhone"),
                "列表不返回电话（脱敏要求，PLT-TEN-009）");

        // 医院名称全平台唯一
        Map<?, ?> duplicate =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + demoaId,
                        new HttpEntity<>(
                                Map.of("name", "虚构示例医院乙", "contactPhone", "13900000002"), headers));
        assertEquals(40901, code(duplicate));

        // 校验失败与不存在
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + demoaId,
                                new HttpEntity<>(
                                        Map.of("name", " ", "contactPhone", "13900000002"),
                                        headers))));
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/999999",
                                new HttpEntity<>(headers))));
    }

    @Test
    void viewerWithoutEditPermissionIsRejected() {
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
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 111 FROM plt_role r WHERE r.code = 'PLATFORM_VIEWER_IT'");
        viewerCreated = true;

        String token = login("platform.viewer.it");

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants",
                                new HttpEntity<>(bearer(token)))),
                "只读角色有 platform:tenant:view，可查看列表");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + demoaId,
                                new HttpEntity<>(
                                        Map.of("name", "虚构示例医院甲", "contactPhone", "13900000002"),
                                        bearer(token)))),
                "无 platform:tenant:edit 不得维护租户信息");
    }
}
