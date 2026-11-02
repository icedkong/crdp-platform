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
 * E05-T04 端到端（验收：只登记「随访」模块，启动时同步）：应用启动（含平台库 Flyway）后 plt_module 的 followup 行已带功能 /
 * 迁移版本与同步时间，plt_module_version 有版本记录；平台端查看目录（含菜单、权限码、配置项、版本 历史）、手动同步幂等、维护展示信息与上下架（写 MODULE_UPDATE
 * 审计、基础模块不可下架）、无编辑权限 40300。 门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（followup
 * 展示信息复原、IT 账号与审计清除）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PlatformModuleRegistryMySqlIT {

    private static JdbcTemplate platform;
    private static boolean viewerCreated;

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 上下文（含平台库 Flyway 与启动注册同步）启动后才能查目录，故在每个用例开头惰性连接。 */
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
        // 复原 followup 的平台端展示信息为种子值（版本列与版本记录是系统注册的，保留）
        platform.update(
                "UPDATE plt_module SET name = '随访',"
                        + " description = '医院 A、B、C 共用的随访模块，差异由模板和功能开关承担',"
                        + " status = 'ON' WHERE code = 'followup'");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN ('MODULE_UPDATE', 'MODULE_SYNC')"
                        + " AND target = 'plt_module:followup'");
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.module.viewer.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.module.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_MODULE_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_MODULE_VIEWER_IT'");
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
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    @Test
    void startupSyncRegistersFollowupWithVersions() {
        connect();
        // 验收：启动时同步——上下文启动后 followup 已带版本与同步时间
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT feature_version, migration_version, depends_on, synced_at, status"
                                + " FROM plt_module WHERE code = 'followup'");
        assertEquals("0.1.0", row.get("feature_version"));
        assertEquals(
                "22",
                row.get("migration_version").toString(),
                "迁移版本取 db/tenant/followup 最大脚本版本 V22（E09-T04）");
        assertEquals("base", row.get("depends_on"));
        assertNotNull(row.get("synced_at"), "启动同步应刷新 synced_at");
        assertEquals("ON", row.get("status"), "注册后默认上架");

        Map<String, Object> version =
                platform.queryForMap(
                        "SELECT feature_version, migration_version, change_note FROM"
                                + " plt_module_version WHERE module_code = 'followup'"
                                + " ORDER BY id DESC LIMIT 1");
        assertEquals("0.1.0", version.get("feature_version"));
        assertEquals("22", version.get("migration_version").toString());
        assertTrue(
                String.valueOf(version.get("change_note")).contains("随访"),
                "版本记录含变更说明：" + version.get("change_note"));

        // 只登记随访：rct 等后续模块不出现在目录
        Integer rct =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_module WHERE code = 'rct'", Integer.class);
        assertEquals(0, rct, "rct 首期不登记");
    }

    @Test
    void catalogShowsMenusPermissionsConfigItemsAndVersions() {
        connect();
        assertEquals(
                40100,
                code(body(HttpMethod.GET, "/api/platform/modules", HttpEntity.EMPTY)),
                "无令牌不得查看模块目录");

        String token = login("platform.admin");
        Map<?, ?> response =
                body(HttpMethod.GET, "/api/platform/modules", new HttpEntity<>(bearer(token)));
        assertEquals(0, code(response), () -> String.valueOf(response));

        List<Map<String, Object>> modules = (List<Map<String, Object>>) response.get("data");
        Map<String, Object> followup =
                modules.stream()
                        .filter(item -> "followup".equals(item.get("code")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("随访", followup.get("name"));
        assertEquals(Boolean.FALSE, followup.get("baseModule"));
        assertEquals("ON", followup.get("status"));
        assertEquals(List.of("base"), followup.get("dependsOn"));
        assertFalse(((List<?>) followup.get("menus")).isEmpty(), "目录应含菜单清单");
        assertTrue(
                ((List<?>) followup.get("menus")).size()
                        >= ((List<?>) followup.get("permissions")).size(),
                "按钮与菜单都挂 perm_code，权限码清单不多于菜单清单");
        assertTrue(
                ((List<String>) followup.get("permissions")).contains("followup:patient:view"),
                "权限码清单应含菜单权限码");
        List<Map<String, Object>> configItems =
                (List<Map<String, Object>>) followup.get("configItems");
        assertEquals(1, configItems.size(), "配置项来自模块定义");
        assertEquals("defaultNotifyChannel", configItems.get(0).get("code"));
        List<Map<String, Object>> versions = (List<Map<String, Object>>) followup.get("versions");
        assertFalse(versions.isEmpty(), "版本历史来自 plt_module_version");

        Map<String, Object> base =
                modules.stream()
                        .filter(item -> "base".equals(item.get("code")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(Boolean.TRUE, base.get("baseModule"), "平台基础是必选基础模块");

        // 不存在的模块维护 40400
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/rct",
                                new HttpEntity<>(Map.of("name", "RCT"), bearer(token)))));
    }

    @Test
    void manualSyncIsIdempotent() {
        connect();
        String token = login("platform.admin");

        long versionsBefore =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_module_version WHERE module_code = 'followup'",
                        Long.class);
        Map<?, ?> synced =
                body(
                        HttpMethod.POST,
                        "/api/platform/modules/sync",
                        new HttpEntity<>(bearer(token)));
        assertEquals(0, code(synced), () -> String.valueOf(synced));
        List<Map<String, Object>> outcomes =
                (List<Map<String, Object>>) ((Map<?, ?>) synced.get("data")).get("outcomes");
        assertEquals(1, outcomes.size(), "验收：只登记随访一个模块");
        assertEquals("followup", outcomes.get(0).get("code"));
        assertEquals("UNCHANGED", outcomes.get(0).get("action"), "重复同步不产生新版本记录");

        long versionsAfter =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_module_version WHERE module_code = 'followup'",
                        Long.class);
        assertEquals(versionsBefore, versionsAfter, "同步幂等：无版本变化不写 plt_module_version");
    }

    @Test
    void updateMaintainsDisplayInfoAndShelfStatusWithAudit() {
        connect();
        String token = login("platform.admin");
        HttpHeaders headers = bearer(token);

        Map<?, ?> renamed =
                body(
                        HttpMethod.PUT,
                        "/api/platform/modules/followup",
                        new HttpEntity<>(
                                Map.of(
                                        "name", "随访（演示）",
                                        "description", "演示说明（示例）",
                                        "status", "OFF"),
                                headers));
        assertEquals(0, code(renamed), () -> String.valueOf(renamed));
        Map<?, ?> renamedData = (Map<?, ?>) renamed.get("data");
        assertEquals("随访（演示）", renamedData.get("name"));
        assertEquals("OFF", renamedData.get("status"), "下架后不能再被新开通（PLT-MOD-001）");

        String audit =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'MODULE_UPDATE'"
                                + " AND target = 'plt_module:followup' ORDER BY id DESC LIMIT 1",
                        String.class);
        assertNotNull(audit, "维护展示信息与上下架必须写审计");
        assertTrue(audit.contains("ON→OFF"), audit);

        // 基础模块必选，不能下架
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/base",
                                new HttpEntity<>(
                                        Map.of("name", "平台基础", "status", "OFF"), headers))));

        // 校验失败
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/followup",
                                new HttpEntity<>(Map.of("name", " ", "status", "ON"), headers))));
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/followup",
                                new HttpEntity<>(
                                        Map.of("name", "随访", "status", "GONE"), headers))));

        // 用例内复原（JUnit 方法顺序不定，避免影响目录断言；@AfterAll 再兜底）
        platform.update(
                "UPDATE plt_module SET name = '随访',"
                        + " description = '医院 A、B、C 共用的随访模块，差异由模板和功能开关承担',"
                        + " status = 'ON' WHERE code = 'followup'");
    }

    @Test
    void viewerWithoutEditPermissionIsRejected() {
        connect();
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_MODULE_VIEWER_IT', '模块只读（IT 示例）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.module.viewer.it', '模块只读（示例）', ?, 'ENABLED')",
                passwordEncoder.encode("Demo@2026"));
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.module.viewer.it'"
                        + " AND r.code = 'PLATFORM_MODULE_VIEWER_IT'");
        // 只授模块目录查看（菜单 112 的 perm_code 即 platform:module:view）
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 112 FROM plt_role r WHERE r.code = 'PLATFORM_MODULE_VIEWER_IT'");
        viewerCreated = true;

        String token = login("platform.module.viewer.it");

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/modules",
                                new HttpEntity<>(bearer(token)))),
                "只读角色有 platform:module:view，可查看目录");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/modules/sync",
                                new HttpEntity<>(bearer(token)))),
                "无 platform:module:edit 不得触发同步");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/modules/followup",
                                new HttpEntity<>(
                                        Map.of("name", "随访", "status", "ON"), bearer(token)))),
                "无 platform:module:edit 不得维护模块信息");
    }
}
