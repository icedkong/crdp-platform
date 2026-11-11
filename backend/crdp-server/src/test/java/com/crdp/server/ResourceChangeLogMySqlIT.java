package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
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
 * E05-T10 端到端（验收：菜单、接口资源变更可查）：菜单维护与接口标记各产生一条变更记录， /api/platform/resource-logs 按类型 / 动作 /
 * 关键词可查；导出返回带水印的 CSV；无 platform:menu:view 权限 40300；条件非法 40001。 门控 CRDP_IT_MYSQL_PORT（docker compose
 * 的 MySQL 与 Redis），自建自清（IT 菜单、标记复原、变更记录与审计行）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ResourceChangeLogMySqlIT {

    private static JdbcTemplate platform;
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
        // 还原被标记的接口属性（与 PlatformPermResourcesMySqlIT 同一款清理）
        platform.update(
                "UPDATE plt_api_resource SET public_access = 1, audit_level = 'NONE',"
                        + " sensitive_level = 'NORMAL', action_type = 'QUERY'"
                        + " WHERE http_method = 'GET' AND path_pattern = '/api/platform/auth/codes'");
        platform.update(
                "DELETE rm FROM plt_role_menu rm JOIN plt_menu m ON m.id = rm.menu_id"
                        + " WHERE m.route_name = 'ItResourceLogPage'");
        platform.update("DELETE FROM plt_menu WHERE route_name = 'ItResourceLogPage'");
        platform.update(
                "DELETE FROM plt_resource_change_log WHERE resource_ident = 'ItResourceLogPage'"
                        + " OR resource_ident = '/api/platform/auth/codes' AND action = 'UPDATE'"
                        + " AND detail LIKE '%标记接口属性%'");
        if (itAccountCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.resourcelog.viewer.it'");
            platform.update(
                    "DELETE FROM plt_account WHERE username = 'platform.resourcelog.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_RESOURCELOG_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_RESOURCELOG_VIEWER_IT'");
        }
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE detail LIKE '%ItResourceLogPage%'"
                        + " OR detail LIKE '%资源变更记录%'");
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
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object value = response == null ? null : response.get("code");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    // ---------------------------------------------------------------- 验收：菜单、接口资源变更可查

    @Test
    void menuAndApiChangesAreQueryable() {
        connect();
        String token = login("platform.admin");

        // 菜单新建 → CREATE / PAGE
        Map<?, ?> created =
                body(
                        HttpMethod.POST,
                        "/api/platform/menus",
                        new HttpEntity<>(
                                Map.of(
                                        "side", "PLATFORM",
                                        "parentId", "110",
                                        "type", "MENU",
                                        "moduleCode", "base",
                                        "title", "IT资源记录演示（示例）",
                                        "routeName", "ItResourceLogPage",
                                        "path", "/platform/it-resource-log",
                                        "component", "platform/api/index"),
                                bearer(token)));
        assertEquals(0, code(created), "平台管理员可新建菜单");
        long menuId = ((Number) ((Map<?, ?>) created.get("data")).get("id")).longValue();

        Map<?, ?> menuLogs =
                body(
                        HttpMethod.GET,
                        "/api/platform/resource-logs?resourceType=MENU&keyword=ItResourceLogPage",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(menuLogs));
        List<Map<String, Object>> menuItems =
                (List<Map<String, Object>>) ((Map<?, ?>) menuLogs.get("data")).get("items");
        assertTrue(
                menuItems.stream()
                        .anyMatch(
                                row ->
                                        "CREATE".equals(row.get("action"))
                                                && "PAGE".equals(row.get("channel"))
                                                && "ItResourceLogPage"
                                                        .equals(row.get("resourceIdent"))),
                "菜单新建写入变更记录（CREATE / PAGE）");

        // 接口标记 → UPDATE / PAGE（与 E05-T06 同一标记口）
        Long codesApiId =
                platform.queryForObject(
                        "SELECT id FROM plt_api_resource WHERE http_method = 'GET'"
                                + " AND path_pattern = '/api/platform/auth/codes'",
                        Long.class);
        assertNotNull(codesApiId, "启动扫描已登记 auth/codes");
        Map<?, ?> marked =
                body(
                        HttpMethod.PUT,
                        "/api/platform/apis/" + codesApiId,
                        new HttpEntity<>(Map.of("auditLevel", "OPERATION"), bearer(token)));
        assertEquals(0, code(marked), "标记审计级别成功");

        Map<?, ?> apiLogs =
                body(
                        HttpMethod.GET,
                        "/api/platform/resource-logs?resourceType=API&keyword=/api/platform/auth/codes",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(apiLogs));
        List<Map<String, Object>> apiItems =
                (List<Map<String, Object>>) ((Map<?, ?>) apiLogs.get("data")).get("items");
        assertTrue(
                apiItems.stream()
                        .anyMatch(
                                row ->
                                        "UPDATE".equals(row.get("action"))
                                                && "PAGE".equals(row.get("channel"))
                                                && String.valueOf(row.get("detail"))
                                                        .contains("审计级别 NONE → OPERATION")),
                "接口标记写入变更记录（UPDATE / PAGE，含前后值）");

        // 菜单删除 → DELETE / PAGE
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.DELETE,
                                "/api/platform/menus/" + menuId,
                                new HttpEntity<>(bearer(token)))),
                "删除新建的菜单");
        Map<?, ?> deleteLogs =
                body(
                        HttpMethod.GET,
                        "/api/platform/resource-logs?resourceType=MENU&action=DELETE&keyword=ItResourceLogPage",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(deleteLogs));
        List<Map<String, Object>> deleteItems =
                (List<Map<String, Object>>) ((Map<?, ?>) deleteLogs.get("data")).get("items");
        assertTrue(
                deleteItems.stream()
                        .anyMatch(
                                row ->
                                        "DELETE".equals(row.get("action"))
                                                && "ItResourceLogPage"
                                                        .equals(row.get("resourceIdent"))),
                "菜单删除写入变更记录（DELETE）");
    }

    // ---------------------------------------------------------------- 导出

    @Test
    void exportReturnsWatermarkedCsv() {
        connect();
        String token = login("platform.admin");
        org.springframework.http.ResponseEntity<byte[]> file =
                rest.exchange(
                        URI.create(
                                "/api/platform/resource-logs/export?resourceType=MENU&keyword=ItResourceLogPage"),
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(token)),
                        byte[].class);
        assertEquals(200, file.getStatusCode().value());
        assertTrue(
                file.getHeaders().getContentType().toString().startsWith("text/csv"), "导出返回 CSV");
        assertTrue(
                file.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("attachment"),
                "导出为附件下载");
        byte[] body = file.getBody();
        assertNotNull(body);
        assertEquals(0xEF, body[0] & 0xFF, "CSV 带 UTF-8 BOM");
        String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("水印"), "首列水印");
        assertTrue(csv.contains("平台资源变更记录"), "水印机构");
    }

    // ---------------------------------------------------------------- 权限与校验

    @Test
    void deniedWithoutMenuViewPermission() {
        connect();
        String token = viewerWithoutMenuView();
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/resource-logs",
                                new HttpEntity<>(bearer(token)))),
                "无 platform:menu:view 权限的账号被拒（40300）");
    }

    @Test
    void rejectsInvalidResourceType() {
        connect();
        String token = login("platform.admin");
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/resource-logs?resourceType=BOGUS",
                                new HttpEntity<>(bearer(token)))),
                "未知资源类型 40001");
    }

    // ---------------------------------------------------------------- 辅助

    /** 只授模块目录（platform:module:view）的 IT 账号——无 platform:menu:view。 */
    private String viewerWithoutMenuView() {
        String hash = passwordEncoder.encode("Demo@2026");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.resourcelog.viewer.it', '模块查看员（示例）', ?, 'ENABLED')",
                hash);
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_RESOURCELOG_VIEWER_IT', '模块查看（IT）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.resourcelog.viewer.it' AND r.code ="
                        + " 'PLATFORM_RESOURCELOG_VIEWER_IT'");
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, m.id FROM plt_role r, plt_menu m"
                        + " WHERE r.code = 'PLATFORM_RESOURCELOG_VIEWER_IT' AND m.id = 112");
        itAccountCreated = true;
        return login("platform.resourcelog.viewer.it");
    }
}
