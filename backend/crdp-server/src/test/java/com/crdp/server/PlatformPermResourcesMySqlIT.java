package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * E05-T06 端到端（验收：未登记接口默认拒绝）：启动扫描把全部接口登记进 plt_api_resource，登记册生效—— 未登记路径
 * 40307；公共接口（登录、me、menus）登录即可访问；绑定权限码的接口无权 40300； 平台端标记接口属性后闸门即时按新标记拒绝；菜单树 / 维护 / 清单同步走通。 门控
 * CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（IT 账号、角色、菜单、审计与接口标记复原）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PlatformPermResourcesMySqlIT {

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
        // 还原被标记的接口属性（/api/platform/auth/codes 是扫描登记的公共接口）
        platform.update(
                "UPDATE plt_api_resource SET public_access = 1, audit_level = 'NONE',"
                        + " sensitive_level = 'NORMAL', action_type = 'QUERY'"
                        + " WHERE http_method = 'GET' AND path_pattern = '/api/platform/auth/codes'");
        // 删除本 IT 建的菜单（含软删行，避免权限码唯一键占位）
        platform.update(
                "DELETE rm FROM plt_role_menu rm JOIN plt_menu m ON m.id = rm.menu_id"
                        + " WHERE m.route_name = 'ItDemoPage' OR m.perm_code = 'base:itdemo:act'");
        platform.update(
                "DELETE FROM plt_menu WHERE route_name = 'ItDemoPage' OR perm_code ="
                        + " 'base:itdemo:act'");
        if (itAccountCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.menu.viewer.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.menu.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_MENU_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_MENU_VIEWER_IT'");
        }
        platform.update(
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('MENU_CREATE', 'MENU_UPDATE', 'MENU_DELETE', 'MENU_SYNC', 'API_UPDATE')");
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

    // ---------------------------------------------------------------- 启动登记（PLT-PERM-004）

    @Test
    void startupScanRegistersControllerEndpoints() {
        connect();
        String permOf =
                platform.queryForObject(
                        "SELECT perm_code FROM plt_api_resource WHERE http_method = 'GET'"
                                + " AND path_pattern = '/api/platform/menus/tree'",
                        String.class);
        assertEquals("platform:menu:view", permOf, "菜单树接口按注解登记权限码");
        assertEquals(
                "base",
                platform.queryForObject(
                        "SELECT module_code FROM plt_api_resource WHERE http_method = 'GET'"
                                + " AND path_pattern = '/api/platform/menus/tree'",
                        String.class),
                "platform 前缀不是登记模块 → base");
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT public_access FROM plt_api_resource WHERE http_method = 'GET'"
                                + " AND path_pattern = '/api/platform/auth/me'",
                        Integer.class),
                "@PublicApi 接口登记为登录即可访问");
        assertEquals(
                "followup",
                platform.queryForObject(
                        "SELECT module_code FROM plt_api_resource WHERE http_method = 'GET'"
                                + " AND path_pattern = '/api/hospital/followup/ping'",
                        String.class),
                "权限码前段是登记模块 → 该模块");
        assertNotNull(
                platform.queryForObject(
                        "SELECT synced_at FROM plt_api_resource WHERE path_pattern ="
                                + " '/api/platform/menus/tree'",
                        java.time.LocalDateTime.class),
                "登记刷新同步时间");
        // 默认拒绝的不变量：登记行要么公共、要么绑定权限码
        Integer dangling =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_api_resource WHERE public_access = 0"
                                + " AND (perm_code IS NULL OR perm_code = '')",
                        Integer.class);
        assertEquals(0, dangling, "不存在「既非公共又未绑定权限码」的登记行");
    }

    // ---------------------------------------------------------------- 验收：未登记接口默认拒绝

    @Test
    void unregisteredEndpointIsDeniedEvenWhenAuthenticated() {
        connect();
        String token = login("platform.admin");
        assertEquals(
                40307,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/definitely/not/registered",
                                new HttpEntity<>(bearer(token)))),
                "验收标准：未登记接口默认拒绝（40307）");
        assertEquals(
                40307,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/hospital/followup/unknown",
                                new HttpEntity<>(bearer(token)))),
                "医院端同样默认拒绝");
    }

    @Test
    void publicEndpointsAreReachableAfterLogin() {
        connect();
        String token = login("platform.admin");
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/auth/me",
                                new HttpEntity<>(bearer(token)))),
                "me 公共");
        assertEquals(
                0,
                code(body(HttpMethod.GET, "/api/platform/menus", new HttpEntity<>(bearer(token)))),
                "菜单下发公共");
    }

    @Test
    void permBoundEndpointRejectsSessionWithoutCode() {
        connect();
        // 只给查看权的 IT 角色：能看树，不能维护（40300）
        String token = viewerLogin();
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/menus/tree?side=PLATFORM",
                                new HttpEntity<>(bearer(token)))),
                "持 platform:menu:view 可看菜单树");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/menus/113",
                                new HttpEntity<>(
                                        Map.of("title", "菜单资源", "status", "ENABLED"),
                                        bearer(token)))),
                "无 platform:menu:edit 维护被拒（40300）");
    }

    // ---------------------------------------------------------------- 标记接口属性 → 闸门即时生效

    @Test
    void markingApiUpdatesGuardImmediately() {
        connect();
        String token = login("platform.admin");
        Long id =
                platform.queryForObject(
                        "SELECT id FROM plt_api_resource WHERE http_method = 'GET'"
                                + " AND path_pattern = '/api/platform/auth/codes'",
                        Long.class);
        // 取消公共：没有权限码的接口立刻被默认拒绝
        Map<?, ?> marked =
                body(
                        HttpMethod.PUT,
                        "/api/platform/apis/" + id,
                        new HttpEntity<>(
                                Map.of("publicAccess", false, "permCode", "base:code:view"),
                                bearer(token)));
        assertEquals(0, code(marked), () -> String.valueOf(marked));
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/auth/codes",
                                new HttpEntity<>(bearer(token)))),
                "绑定权限码后会话没持有 → 40300（闸门已按新标记生效）");
        // 恢复公共：立刻放行
        Map<?, ?> restored =
                body(
                        HttpMethod.PUT,
                        "/api/platform/apis/" + id,
                        new HttpEntity<>(
                                Map.of("publicAccess", true, "permCode", ""), bearer(token)));
        assertEquals(0, code(restored), () -> String.valueOf(restored));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/auth/codes",
                                new HttpEntity<>(bearer(token)))),
                "恢复公共后放行");
    }

    // ---------------------------------------------------------------- 菜单树、维护与清单同步

    @Test
    void menuTreeIncludesButtonsForBothSides() {
        connect();
        String token = login("platform.admin");
        Map<?, ?> platformTree =
                body(
                        HttpMethod.GET,
                        "/api/platform/menus/tree?side=PLATFORM",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(platformTree));
        assertTrue(
                flatten((List<Map<String, Object>>) platformTree.get("data")).stream()
                        .anyMatch(node -> "BUTTON".equals(node.get("type"))),
                "平台端树含按钮（如 platform:menu:edit）");
        Map<?, ?> hospitalTree =
                body(
                        HttpMethod.GET,
                        "/api/platform/menus/tree?side=HOSPITAL",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(hospitalTree));
        assertTrue(
                flatten((List<Map<String, Object>>) hospitalTree.get("data")).stream()
                        .anyMatch(node -> "followup:patient:plain".equals(node.get("permCode"))),
                "医院端树含随访按钮");
        // OQ-11 定论 B：组织架构菜单权限码对齐规格（V103 改名），旧码不得再出现
        assertTrue(
                flatten((List<Map<String, Object>>) hospitalTree.get("data")).stream()
                        .anyMatch(node -> "tenant:org:view".equals(node.get("permCode"))),
                "组织架构菜单用规格码 tenant:org:view");
        assertFalse(
                flatten((List<Map<String, Object>>) hospitalTree.get("data")).stream()
                        .anyMatch(node -> "tenant:dept:view".equals(node.get("permCode"))),
                "旧码 tenant:dept:view 已由 V103 改名，不得再出现");
    }

    @Test
    void menuCrudValidatesAndWrites() {
        connect();
        String token = login("platform.admin");
        // 缺权限码的按钮：40001
        Map<?, ?> bad =
                body(
                        HttpMethod.POST,
                        "/api/platform/menus",
                        new HttpEntity<>(
                                Map.of(
                                        "side", "PLATFORM",
                                        "parentId", "113",
                                        "type", "BUTTON",
                                        "moduleCode", "base",
                                        "title", "IT 按钮"),
                                bearer(token)));
        assertEquals(40001, code(bad), () -> String.valueOf(bad));
        // 未登记模块：40001（PLT-PERM-002）
        Map<?, ?> badModule =
                body(
                        HttpMethod.POST,
                        "/api/platform/menus",
                        new HttpEntity<>(
                                Map.of(
                                        "side", "PLATFORM",
                                        "parentId", "0",
                                        "type", "DIR",
                                        "moduleCode", "rct",
                                        "title", "研究",
                                        "routeName", "RctIt",
                                        "path", "/rct-it"),
                                bearer(token)));
        assertEquals(40001, code(badModule), "每个菜单必须归属已登记模块");
        // 正常新建：菜单 + 按钮
        Map<?, ?> menu =
                body(
                        HttpMethod.POST,
                        "/api/platform/menus",
                        new HttpEntity<>(
                                Map.of(
                                        "side", "PLATFORM",
                                        "parentId", "110",
                                        "type", "MENU",
                                        "moduleCode", "base",
                                        "title", "IT 演示页（示例）",
                                        "routeName", "ItDemoPage",
                                        "path", "/platform/it-demo",
                                        "component", "platform/module/index",
                                        "sortNo", 99),
                                bearer(token)));
        assertEquals(0, code(menu), () -> String.valueOf(menu));
        long menuId = ((Number) ((Map<?, ?>) menu.get("data")).get("id")).longValue();
        assertTrue(menuId > 0, "自增主键已回填");
        Map<?, ?> button =
                body(
                        HttpMethod.POST,
                        "/api/platform/menus",
                        new HttpEntity<>(
                                Map.of(
                                        "side", "PLATFORM",
                                        "parentId", String.valueOf(menuId),
                                        "type", "BUTTON",
                                        "moduleCode", "base",
                                        "title", "IT 演示按钮（示例）",
                                        "permCode", "base:itdemo:act",
                                        "hidden", true),
                                bearer(token)));
        assertEquals(0, code(button), () -> String.valueOf(button));
        // 权限码重复：40001
        Map<?, ?> dup =
                body(
                        HttpMethod.POST,
                        "/api/platform/menus",
                        new HttpEntity<>(
                                Map.of(
                                        "side", "PLATFORM",
                                        "parentId", String.valueOf(menuId),
                                        "type", "BUTTON",
                                        "moduleCode", "base",
                                        "title", "重复按钮",
                                        "permCode", "base:itdemo:act"),
                                bearer(token)));
        assertEquals(40001, code(dup), "按钮权限码全局唯一");
        // 有子项不能删
        Map<?, ?> hasChildren =
                body(
                        HttpMethod.DELETE,
                        "/api/platform/menus/" + menuId,
                        new HttpEntity<>(bearer(token)));
        assertEquals(40001, code(hasChildren));
        // 删按钮 → 删菜单（软删）
        long buttonId = ((Number) ((Map<?, ?>) button.get("data")).get("id")).longValue();
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.DELETE,
                                "/api/platform/menus/" + buttonId,
                                new HttpEntity<>(bearer(token)))));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.DELETE,
                                "/api/platform/menus/" + menuId,
                                new HttpEntity<>(bearer(token)))));
        assertEquals(
                "DELETED",
                platform.queryForObject(
                        "SELECT status FROM plt_menu WHERE id = " + menuId, String.class),
                "删除为软删（行保留）");
        assertTrue(
                0
                        >= platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_role_menu WHERE menu_id = " + menuId,
                                Integer.class),
                "平台角色授予已移除");
    }

    @Test
    void menuManifestSyncDiffIsEmptyAgainstSeedAndAppliesNoOp() {
        connect();
        String token = login("platform.admin");
        Map<?, ?> diff =
                body(
                        HttpMethod.GET,
                        "/api/platform/menus/sync/diff",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(diff), () -> String.valueOf(diff));
        assertTrue(
                Boolean.TRUE.equals(((Map<?, ?>) diff.get("data")).get("empty")),
                () -> "种子与随包清单一致，不应有差异：" + diff.get("data"));
        Map<?, ?> apply =
                body(HttpMethod.POST, "/api/platform/menus/sync", new HttpEntity<>(bearer(token)));
        assertEquals(0, code(apply), () -> String.valueOf(apply));
        Map<?, ?> result = (Map<?, ?>) apply.get("data");
        assertEquals(0, ((Number) result.get("added")).intValue());
        assertEquals(0, ((Number) result.get("changed")).intValue());
        assertEquals(0, ((Number) result.get("offline")).intValue());
    }

    @Test
    void apiListFiltersAndManualSyncAreIdempotent() {
        connect();
        String token = login("platform.admin");
        Map<?, ?> all = body(HttpMethod.GET, "/api/platform/apis", new HttpEntity<>(bearer(token)));
        assertEquals(0, code(all));
        assertFalse(((List<?>) all.get("data")).isEmpty(), "登记清单非空");
        Map<?, ?> unbound =
                body(
                        HttpMethod.GET,
                        "/api/platform/apis?unbound=true",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(unbound));
        assertTrue(
                ((List<?>) unbound.get("data"))
                        .stream()
                                .allMatch(
                                        row ->
                                                ((Map<?, ?>) row).get("permCode") == null
                                                        || ((Map<?, ?>) row)
                                                                .get("permCode")
                                                                .toString()
                                                                .isBlank()),
                "unbound 只看未绑定");
        Map<?, ?> followup =
                body(
                        HttpMethod.GET,
                        "/api/platform/apis?moduleCode=followup",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(followup));
        assertTrue(
                ((List<?>) followup.get("data"))
                        .stream()
                                .allMatch(
                                        row ->
                                                "followup"
                                                        .equals(
                                                                ((Map<?, ?>) row)
                                                                        .get("moduleCode"))));
        // 手动同步幂等：再跑一次没有 REGISTERED / UPDATED / REMOVED
        Map<?, ?> sync =
                body(HttpMethod.POST, "/api/platform/apis/sync", new HttpEntity<>(bearer(token)));
        assertEquals(0, code(sync), () -> String.valueOf(sync));
        assertTrue(
                ((List<?>) ((Map<?, ?>) sync.get("data")).get("outcomes"))
                        .stream()
                                .allMatch(
                                        outcome ->
                                                "UNCHANGED"
                                                        .equals(
                                                                ((Map<?, ?>) outcome)
                                                                        .get("action"))),
                "重复同步无变化");
    }

    // ---------------------------------------------------------------- 辅助

    private static List<Map<String, Object>> flatten(List<Map<String, Object>> nodes) {
        List<Map<String, Object>> flat = new java.util.ArrayList<>();
        for (Map<String, Object> node : nodes) {
            flat.add(node);
            Object children = node.get("children");
            if (children instanceof List<?> list && !list.isEmpty()) {
                flat.addAll(flatten((List<Map<String, Object>>) list));
            }
        }
        return flat;
    }

    /** 只授 platform:menu:view 的 IT 账号（无编辑权）。 */
    private String viewerLogin() {
        String hash = passwordEncoder.encode("Demo@2026");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.menu.viewer.it', '菜单查看员（示例）', ?, 'ENABLED')",
                hash);
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_MENU_VIEWER_IT', '菜单查看（IT）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.menu.viewer.it' AND r.code ="
                        + " 'PLATFORM_MENU_VIEWER_IT'");
        // 只授菜单 113（platform:menu:view）
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, m.id FROM plt_role r, plt_menu m"
                        + " WHERE r.code = 'PLATFORM_MENU_VIEWER_IT' AND m.id = 113");
        itAccountCreated = true;
        return login("platform.menu.viewer.it");
    }
}
