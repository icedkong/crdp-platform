package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E05-T11 端到端（验收：可向全部或指定租户发布）：面向全部租户的公告两家演示医院都可见； 定向公告只有目标租户可见（双租户用例，AGENTS
 * §6）；撤下后医院端即刻不可见；发布与撤下写平台审计； 无 platform:notice:edit 权限 40300；校验失败 40001；未知租户 40400；平台账号调医院端公告接口
 * 40300（端别隔离）。 自建自清（IT 公告行、审计行与无权限账号）。门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformAnnouncementMySqlIT {

    private static final String ALL_TITLE = "IT 全员公告（示例）";
    private static final String SCOPED_TITLE = "IT 定向公告（示例）";

    private static JdbcTemplate platform;
    private static final List<Long> createdAnnouncementIds = new ArrayList<>();
    private static boolean itAccountCreated;

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;

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
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        for (Long id : createdAnnouncementIds) {
            platform.update("DELETE FROM plt_announcement_tenant WHERE announcement_id = ?", id);
            platform.update("DELETE FROM plt_announcement WHERE id = ?", id);
        }
        if (itAccountCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.announcement.viewer.it'");
            platform.update(
                    "DELETE FROM plt_account WHERE username = 'platform.announcement.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_ANNOUNCEMENT_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_ANNOUNCEMENT_VIEWER_IT'");
        }
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE detail LIKE '%"
                        + ALL_TITLE
                        + "%' OR detail LIKE '%"
                        + SCOPED_TITLE
                        + "%'");
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

    private String hospitalLogin(String tenantCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/hospital/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "tenantCode", tenantCode,
                                                "username", "nurse.demo",
                                                "password", "Demo@2026"),
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
        Object value = response == null ? null : response.get("code");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hospitalItems(Map<?, ?> response) {
        return (List<Map<String, Object>>) response.get("data");
    }

    private static long rememberId(Map<?, ?> publishResponse) {
        long id = ((Number) ((Map<?, ?>) publishResponse.get("data")).get("id")).longValue();
        createdAnnouncementIds.add(id);
        return id;
    }

    // ------------------------------------------------- 验收：可向全部或指定租户发布

    @Test
    @Order(1)
    void allTenantAnnouncementVisibleToBothTenants() {
        connect();
        String token = login("platform.admin");
        Map<?, ?> published =
                body(
                        HttpMethod.POST,
                        "/api/platform/announcements",
                        new HttpEntity<>(
                                Map.of(
                                        "title", ALL_TITLE,
                                        "content", "平台将于本周六 22:00–24:00 升级维护（示例）",
                                        "scope", "ALL"),
                                bearer(token)));
        assertEquals(0, code(published), "平台管理员可发布全员公告");
        long id = rememberId(published);
        assertEquals("全部租户", ((Map<?, ?>) published.get("data")).get("scopeText"));

        for (String tenantCode : List.of("demoa", "demob")) {
            Map<?, ?> view =
                    body(
                            HttpMethod.GET,
                            "/api/hospital/announcements",
                            new HttpEntity<>(bearer(hospitalLogin(tenantCode))));
            assertEquals(0, code(view), tenantCode + " 登录即可读取公告");
            assertTrue(
                    hospitalItems(view).stream()
                            .anyMatch(item -> ((Number) item.get("id")).longValue() == id),
                    "全员公告对 " + tenantCode + " 可见");
        }
    }

    @Test
    @Order(2)
    void scopedAnnouncementVisibleOnlyToTargetTenant() {
        connect();
        String token = login("platform.admin");
        Long demoaId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = 'demoa'", Long.class);
        Long demobId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = 'demob'", Long.class);
        assertNotNull(demoaId);
        assertNotNull(demobId);

        Map<?, ?> published =
                body(
                        HttpMethod.POST,
                        "/api/platform/announcements",
                        new HttpEntity<>(
                                Map.of(
                                        "title",
                                        SCOPED_TITLE,
                                        "content",
                                        "仅演示医院 A 的定向说明（示例）",
                                        "scope",
                                        "TENANTS",
                                        "tenantIds",
                                        List.of(demoaId)),
                                bearer(token)));
        assertEquals(0, code(published), "可发布定向公告");
        long id = rememberId(published);
        List<Map<String, Object>> tenants =
                (List<Map<String, Object>>) ((Map<?, ?>) published.get("data")).get("tenants");
        assertEquals(1, tenants.size(), "定向名单只含目标租户");
        assertEquals("demoa", tenants.get(0).get("tenantCode"));

        Map<?, ?> demoaView =
                body(
                        HttpMethod.GET,
                        "/api/hospital/announcements",
                        new HttpEntity<>(bearer(hospitalLogin("demoa"))));
        assertTrue(
                hospitalItems(demoaView).stream()
                        .anyMatch(item -> ((Number) item.get("id")).longValue() == id),
                "定向公告对目标租户 demoa 可见");

        Map<?, ?> demobView =
                body(
                        HttpMethod.GET,
                        "/api/hospital/announcements",
                        new HttpEntity<>(bearer(hospitalLogin("demob"))));
        assertTrue(
                hospitalItems(demobView).stream()
                        .noneMatch(item -> ((Number) item.get("id")).longValue() == id),
                "定向公告对非目标租户 demob 不可见（双租户用例）");
    }

    @Test
    @Order(3)
    void revokeHidesFromTenantsAndWritesAudit() {
        connect();
        String token = login("platform.admin");
        Map<?, ?> published =
                body(
                        HttpMethod.POST,
                        "/api/platform/announcements",
                        new HttpEntity<>(
                                Map.of(
                                        "title", "IT 撤下公告（示例）",
                                        "content", "发布后立即撤下（示例）",
                                        "scope", "ALL"),
                                bearer(token)));
        assertEquals(0, code(published));
        long id = rememberId(published);

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/announcements/" + id + "/revoke",
                                new HttpEntity<>(bearer(token)))),
                "平台管理员可撤下公告");
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/announcements/" + id + "/revoke",
                                new HttpEntity<>(bearer(token)))),
                "重复撤下 40901");

        Map<?, ?> view =
                body(
                        HttpMethod.GET,
                        "/api/hospital/announcements",
                        new HttpEntity<>(bearer(hospitalLogin("demoa"))));
        assertTrue(
                hospitalItems(view).stream()
                        .noneMatch(item -> ((Number) item.get("id")).longValue() == id),
                "撤下后医院端即刻不可见");

        assertEquals(
                2,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_audit_log WHERE event_type IN"
                                + " ('ANNOUNCEMENT_PUBLISH','ANNOUNCEMENT_REVOKE')"
                                + " AND target = ?",
                        Integer.class,
                        "plt_announcement:" + id),
                "发布与撤下各写一条平台审计");
    }

    // ------------------------------------------------- 权限、端别与校验

    @Test
    @Order(4)
    void deniedWithoutNoticeEditPermission() {
        connect();
        String token = viewerWithoutNoticeEdit();
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/announcements",
                                new HttpEntity<>(bearer(token)))),
                "无 platform:notice:edit 权限的账号被拒（40300）");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/announcements",
                                new HttpEntity<>(
                                        Map.of(
                                                "title", "无权发布（示例）",
                                                "content", "（示例）",
                                                "scope", "ALL"),
                                        bearer(token)))),
                "无权发布（40300）");
    }

    @Test
    @Order(5)
    void platformTokenCannotCallHospitalEndpoint() {
        connect();
        String token = login("platform.admin");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/hospital/announcements",
                                new HttpEntity<>(bearer(token)))),
                "平台账号调医院端公告接口 40300（端别隔离）");
    }

    @Test
    @Order(6)
    void rejectsInvalidPublishRequests() {
        connect();
        String token = login("platform.admin");
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/announcements",
                                new HttpEntity<>(
                                        Map.of("title", " ", "content", "（示例）", "scope", "ALL"),
                                        bearer(token)))),
                "空标题 40001");
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/announcements",
                                new HttpEntity<>(
                                        Map.of(
                                                "title", "缺范围（示例）",
                                                "content", "（示例）"),
                                        bearer(token)))),
                "缺发布范围 40001");
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/announcements",
                                new HttpEntity<>(
                                        Map.of(
                                                "title", "未知租户（示例）",
                                                "content", "（示例）",
                                                "scope", "TENANTS",
                                                "tenantIds", List.of(999_999L)),
                                        bearer(token)))),
                "定向到未知租户 40400");
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/announcements/999999",
                                new HttpEntity<>(bearer(token)))),
                "未知公告详情 40400");
    }

    // ---------------------------------------------------------------- 辅助

    /** 只授模块目录（platform:module:view）的 IT 账号——无 platform:notice:edit。 */
    private String viewerWithoutNoticeEdit() {
        String hash = passwordEncoder.encode("Demo@2026");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.announcement.viewer.it', '模块查看员（示例）', ?, 'ENABLED')",
                hash);
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_ANNOUNCEMENT_VIEWER_IT', '模块查看（IT）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.announcement.viewer.it' AND r.code ="
                        + " 'PLATFORM_ANNOUNCEMENT_VIEWER_IT'");
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, m.id FROM plt_role r, plt_menu m"
                        + " WHERE r.code = 'PLATFORM_ANNOUNCEMENT_VIEWER_IT' AND m.id = 112");
        itAccountCreated = true;
        return login("platform.announcement.viewer.it");
    }
}
