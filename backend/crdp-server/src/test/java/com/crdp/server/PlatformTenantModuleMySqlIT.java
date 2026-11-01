package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E05-T05 端到端（验收原文：开通后菜单可见，关闭后接口拒绝）。场景按序：
 *
 * <ol>
 *   <li>关闭 followup → 医院端在线会话下一次请求即被拒（40301，/followup/ping 走 @RequireModule）且菜单收回；
 *   <li>重新开通 → 同一会话菜单恢复、接口放行（租户库角色授权保留，重开自动恢复）；
 *   <li>依赖校验：依赖未开通时开通 40901、被已开通模块依赖时关闭 40901（含依赖说明）；
 *   <li>模块级配置：默认取模块定义、租户覆盖、未知配置项 40001、未开通 40901；
 *   <li>开通记录（PLT-MOD-009）与权限控制（无 platform:tenant:module:open 40300）；
 *   <li>新建向导带 modules：base + followup 落 OPEN、配置初始化、菜单可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（恢复模块状态、删除本 IT 的 开通记录 / 审计 /
 * 配置、临时模块与角色、向导租户库）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTenantModuleMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long TENANT_A = 1L; // demoa，种子已开通 followup
    private static final long TENANT_B = 2L; // demob

    private static JdbcTemplate platform;
    private static JdbcTemplate root;
    private static boolean viewerCreated;
    private static String wizardCode;

    @Autowired private TestRestTemplate rest;

    private static JdbcTemplate connect() {
        if (platform == null) {
            String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
            String params = "?useSSL=false&allowPublicKeyRetrieval=true";
            platform =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/crdp_platform" + params,
                                    "crdp_app",
                                    "DevOnly#app2026"));
            root =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/" + params, "root", "DevOnly#root2026"));
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        // 两个演示租户的 followup / base 恢复 OPEN（V16 已回填迁移版本与开通时间）
        platform.update(
                "UPDATE plt_tenant_module SET status = 'OPEN', closed_at = NULL, reason = NULL,"
                        + " operator_id = NULL WHERE module_code IN ('base', 'followup')");
        // 本 IT 产生的开通记录、配置与审计
        platform.update("DELETE FROM plt_tenant_module_log");
        platform.update("DELETE FROM plt_tenant_module_config");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('MODULE_OPEN', 'MODULE_CLOSE', 'MODULE_CONFIG')");
        // 临时依赖模块 rct（IT 内插入）
        platform.update("DELETE FROM plt_tenant_module WHERE module_code = 'rct'");
        platform.update("DELETE FROM plt_module WHERE code = 'rct'");
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.module.viewer2.it'");
            platform.update(
                    "DELETE FROM plt_account WHERE username = 'platform.module.viewer2.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_TENANT_MODULE_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_TENANT_MODULE_VIEWER_IT'");
        }
        if (wizardCode != null) {
            root.execute("DROP DATABASE IF EXISTS t_" + wizardCode);
            root.execute("DROP USER IF EXISTS `t_" + wizardCode + "`@'%'");
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, wizardCode);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
    }

    // ---------------------------------------------------------------- 基础设施

    private String loginHospital(String tenantCode) {
        return token(
                post(
                        Map.of(
                                "tenantCode", tenantCode,
                                "username", "nurse.demo",
                                "password", DEMO_PASSWORD),
                        "/api/hospital/auth/login",
                        null));
    }

    private String loginPlatform(String username) {
        return token(
                post(
                        Map.of("username", username, "password", DEMO_PASSWORD),
                        "/api/platform/auth/login",
                        null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return (Map<String, Object>)
                rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return (Map<String, Object>)
                rest.exchange(URI.create(path), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object value = response == null ? null : response.get("code");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    private static String token(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? null : ((Map<?, ?>) data).get("token").toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataItems(Map<?, ?> response) {
        return (List<Map<String, Object>>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<?, ?> response) {
        return (Map<String, Object>) response.get("data");
    }

    private static String moduleStatus(Map<?, ?> modulesView, String code) {
        List<Map<String, Object>> modules = (List<Map<String, Object>>) modulesView.get("modules");
        return modules.stream()
                .filter(m -> code.equals(m.get("code")))
                .map(m -> String.valueOf(m.get("status")))
                .findFirst()
                .orElse(null);
    }

    private static List<String> menuTitles(Map<?, ?> menusResponse) {
        return dataItems(menusResponse).stream().flatMap(node -> flatten(node).stream()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> flatten(Map<String, Object> node) {
        List<String> titles = new java.util.ArrayList<>();
        Object meta = node.get("meta");
        if (meta instanceof Map<?, ?> metaMap && metaMap.get("title") != null) {
            titles.add(String.valueOf(metaMap.get("title")));
        }
        Object childNodes = node.get("children");
        if (childNodes instanceof List<?> list) {
            for (Object child : list) {
                titles.addAll(flatten((Map<String, Object>) child));
            }
        }
        return titles;
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    void closeThenOpenDrivesMenusAndInterfaceImmediately() {
        connect();
        // 前置：followup 对 demoa 已开通（种子）
        assertEquals(
                "OPEN",
                moduleStatus(getModules(loginPlatform("platform.admin"), TENANT_A), "followup"));

        String hospitalToken = loginHospital("demoa");
        // 开通态：菜单可见 + 模块接口放行
        List<String> before =
                menuTitles(request(HttpMethod.GET, "/api/hospital/menus", hospitalToken, null));
        assertTrue(before.contains("随访"), "开通后菜单应可见：" + before);
        assertEquals(
                0,
                code(request(HttpMethod.GET, "/api/hospital/followup/ping", hospitalToken, null)));

        // 关闭：影响评估 → 原因必填 → 收回菜单与接口
        String platformToken = loginPlatform("platform.admin");
        Map<String, Object> impact =
                data(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/"
                                        + TENANT_A
                                        + "/modules/followup/close-impact",
                                platformToken,
                                null));
        assertEquals("followup", impact.get("moduleCode"));
        assertTrue(((List<?>) impact.get("dependentModules")).isEmpty());

        Map<String, Object> close =
                post(
                        Map.of("reason", "合同暂停（示例）"),
                        "/api/platform/tenants/" + TENANT_A + "/modules/followup/close",
                        platformToken);
        assertEquals(0, code(close));
        assertEquals("CLOSED", data(close).get("status"));
        assertEquals("OPEN", data(close).get("previousStatus"));

        // 同一在线会话：下一次请求即被拒（权限版本递增触发会话重算）且菜单收回
        assertEquals(
                40301,
                code(request(HttpMethod.GET, "/api/hospital/followup/ping", hospitalToken, null)),
                "关闭后模块接口应立即拒绝（40301）");
        List<String> closed =
                menuTitles(request(HttpMethod.GET, "/api/hospital/menus", hospitalToken, null));
        assertFalse(closed.contains("随访"), "关闭后菜单应收回：" + closed);
        assertTrue(closed.contains("工作台"), "基础模块菜单不受影响：" + closed);

        // 关闭后的痕迹：状态、关闭时间与原因；数据保留（租户库角色授权不动）
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_module WHERE tenant_id = ?"
                                + " AND module_code = 'followup' AND status = 'CLOSED'"
                                + " AND closed_at IS NOT NULL AND reason = '合同暂停（示例）'",
                        Integer.class,
                        TENANT_A));
        assertEquals(
                1,
                root.queryForObject(
                        "SELECT COUNT(*) FROM t_demoa.sys_role_menu rm JOIN t_demoa.sys_role r ON"
                                + " r.id = rm.role_id WHERE r.code = 'TENANT_ADMIN' AND rm.menu_id = 21",
                        Integer.class),
                "关闭不删数据：租户库角色授权保留（重新开通自动恢复）");
        // 已关闭再查影响面 → 40901
        assertEquals(
                40901,
                code(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/"
                                        + TENANT_A
                                        + "/modules/followup/close-impact",
                                platformToken,
                                null)));

        // 重新开通：接口放行、菜单恢复（授权保留自动恢复）
        Map<String, Object> reopen =
                post(
                        Map.of("reason", "续签（示例）"),
                        "/api/platform/tenants/" + TENANT_A + "/modules/followup/open",
                        platformToken);
        assertEquals(0, code(reopen));
        assertEquals("OPEN", data(reopen).get("status"));
        assertEquals("CLOSED", data(reopen).get("previousStatus"));
        assertEquals(
                0,
                code(request(HttpMethod.GET, "/api/hospital/followup/ping", hospitalToken, null)));
        List<String> reopened =
                menuTitles(request(HttpMethod.GET, "/api/hospital/menus", hospitalToken, null));
        assertTrue(reopened.contains("随访"), "重新开通后菜单自动恢复：" + reopened);
    }

    @Test
    @Order(2)
    void openAndCloseValidateDependencies() {
        connect();
        String token = loginPlatform("platform.admin");

        // 基础模块必选不可关闭
        assertEquals(
                40901,
                code(
                        post(
                                Map.of("reason", "误操作（示例）"),
                                "/api/platform/tenants/" + TENANT_B + "/modules/base/close",
                                token)));

        // 依赖未开通：直接把 base 置 CLOSED（IT 内），开通 followup 应 40901 并带依赖说明
        platform.update(
                "UPDATE plt_tenant_module SET status = 'CLOSED' WHERE tenant_id = ? AND module_code = 'base'",
                TENANT_B);
        platform.update(
                "UPDATE plt_tenant_module SET status = 'CLOSED' WHERE tenant_id = ? AND module_code = 'followup'",
                TENANT_B);
        Map<String, Object> blocked =
                post(
                        Map.of(),
                        "/api/platform/tenants/" + TENANT_B + "/modules/followup/open",
                        token);
        assertEquals(40901, code(blocked));
        assertTrue(
                String.valueOf(blocked.get("message")).contains("base"),
                blocked.get("message").toString());
        assertEquals(
                "CLOSED", moduleStatus(getModules(token, TENANT_B), "followup"), "依赖校验失败不应改状态");

        // 恢复 base 后可开通
        platform.update(
                "UPDATE plt_tenant_module SET status = 'OPEN' WHERE tenant_id = ? AND module_code = 'base'",
                TENANT_B);
        assertEquals(
                0,
                code(
                        post(
                                Map.of(),
                                "/api/platform/tenants/" + TENANT_B + "/modules/followup/open",
                                token)));

        // 被已开通模块依赖时不能关闭：插入临时 rct 模块（依赖 followup）并开通
        platform.update(
                "INSERT INTO plt_module (code, name, description, base_module, status,"
                        + " feature_version, migration_version, depends_on)"
                        + " VALUES ('rct', 'RCT 随访（IT 临时）', '依赖校验用例', 0, 'ON', '0.1.0', '', 'followup')");
        platform.update(
                "INSERT INTO plt_tenant_module (tenant_id, module_code, status) VALUES (?, 'rct', 'OPEN')",
                TENANT_B);
        Map<String, Object> dependent =
                post(
                        Map.of("reason", "误关（示例）"),
                        "/api/platform/tenants/" + TENANT_B + "/modules/followup/close",
                        token);
        assertEquals(40901, code(dependent));
        assertTrue(
                String.valueOf(dependent.get("message")).contains("rct"),
                dependent.get("message").toString());
        // 影响面同样列出依赖方
        Map<String, Object> impact =
                data(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/"
                                        + TENANT_B
                                        + "/modules/followup/close-impact",
                                token,
                                null));
        assertEquals(List.of("rct"), impact.get("dependentModules"));
        // 关掉依赖方后即可关闭
        platform.update(
                "UPDATE plt_tenant_module SET status = 'CLOSED' WHERE tenant_id = ? AND module_code = 'rct'",
                TENANT_B);
        assertEquals(
                0,
                code(
                        post(
                                Map.of("reason", "暂停（示例）"),
                                "/api/platform/tenants/" + TENANT_B + "/modules/followup/close",
                                token)));
    }

    @Test
    @Order(3)
    void moduleConfigUsesDefinitionDefaultsAndTenantOverrides() {
        connect();
        String token = loginPlatform("platform.admin");

        // 开通时初始化默认值（Order(2) 里 demob 的 followup 已关，先重开）
        assertEquals(
                0,
                code(
                        post(
                                Map.of(),
                                "/api/platform/tenants/" + TENANT_B + "/modules/followup/open",
                                token)));
        Map<String, Object> config =
                data(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + TENANT_B + "/modules/followup/config",
                                token,
                                null));
        List<Map<String, Object>> items = (List<Map<String, Object>>) config.get("items");
        assertEquals(1, items.size());
        assertEquals("defaultNotifyChannel", items.get(0).get("code"));
        assertEquals("站内信", items.get(0).get("defaultValue"));
        assertEquals("站内信", items.get(0).get("effectiveValue"));

        // 租户覆盖：effective 取覆盖值；未知配置项 40001；未开通 40901
        Map<String, Object> updated =
                request(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + TENANT_B + "/modules/followup/config",
                        token,
                        Map.of("values", Map.of("defaultNotifyChannel", "短信（示例）")));
        assertEquals(0, code(updated));
        assertEquals(
                "短信（示例）",
                ((List<Map<String, Object>>) data(updated).get("items"))
                        .get(0)
                        .get("effectiveValue"));

        Map<String, Object> unknown =
                request(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + TENANT_B + "/modules/followup/config",
                        token,
                        Map.of("values", Map.of("noSuchItem", "x")));
        assertEquals(40001, code(unknown));

        assertEquals(
                0,
                code(
                        post(
                                Map.of("reason", "配置用例关闭（示例）"),
                                "/api/platform/tenants/" + TENANT_B + "/modules/followup/close",
                                token)));
        Map<String, Object> closedConfig =
                request(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + TENANT_B + "/modules/followup/config",
                        token,
                        Map.of("values", Map.of("defaultNotifyChannel", "站内信")));
        assertEquals(40901, code(closedConfig));
    }

    @Test
    @Order(4)
    void openLogsRecordOperationsAndPermissionIsEnforced() {
        connect();
        String token = loginPlatform("platform.admin");

        // PLT-MOD-009：倒序（最新在前），reason / detail 留痕
        Map<String, Object> logs =
                request(
                        HttpMethod.GET,
                        "/api/platform/modules/followup/open-logs?tenantId=" + TENANT_B,
                        token,
                        null);
        assertEquals(0, code(logs));
        List<Map<String, Object>> entries = dataItems(logs);
        assertFalse(entries.isEmpty());
        assertTrue(
                entries.stream()
                        .anyMatch(e -> "CLOSE".equals(e.get("action")) && e.get("reason") != null),
                "应有带原因的关闭记录：" + entries);
        assertEquals("demob", entries.get(0).get("tenantCode"));
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(
                    ((Number) entries.get(i).get("id")).longValue()
                            < ((Number) entries.get(i - 1).get("id")).longValue(),
                    "开通记录应倒序（最新在前）");
        }

        // 权限：只读角色（platform:tenant:view）能看模块视图，开通 / 关闭 40300
        createViewer();
        String viewer = loginPlatform("platform.module.viewer2.it");
        assertEquals(
                0,
                code(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + TENANT_A + "/modules",
                                viewer,
                                null)));
        assertEquals(
                40300,
                code(
                        post(
                                Map.of(),
                                "/api/platform/tenants/" + TENANT_A + "/modules/followup/close",
                                viewer)));
        assertEquals(
                40300,
                code(
                        post(
                                Map.of(),
                                "/api/platform/tenants/" + TENANT_A + "/modules/followup/open",
                                viewer)));
    }

    @Test
    @Order(5)
    void wizardOpensSelectedModulesWithConfigAndMenus() {
        connect();
        String token = loginPlatform("platform.admin");
        wizardCode =
                "tmodu"
                        + Long.toString(System.currentTimeMillis(), 36)
                                .toLowerCase()
                                .replaceAll("[^a-z0-9]", "");

        Map<String, Object> created =
                post(
                        Map.of(
                                "name", "虚构模块向导医院",
                                "code", wizardCode,
                                "adminUsername", "wizard.admin",
                                "adminDisplayName", "向导管理员（示例）",
                                "adminInitialPassword", "Init@2026#",
                                "modules", List.of("followup")),
                        "/api/platform/tenants",
                        token);
        assertEquals(0, code(created), () -> "向导开通失败：" + created);
        long tenantId = ((Number) data(created).get("tenantId")).longValue();

        // base + followup 都在册且 OPEN；配置默认值已初始化；开通记录留痕
        Map<String, Object> modules = getModules(token, tenantId);
        assertEquals("OPEN", moduleStatus(modules, "base"));
        assertEquals("OPEN", moduleStatus(modules, "followup"));
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_module_config WHERE tenant_id = ?"
                                + " AND module_code = 'followup' AND config_code = 'defaultNotifyChannel'"
                                + " AND config_value = '站内信'",
                        Integer.class,
                        tenantId));
        assertEquals(
                2,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_module_log WHERE tenant_id = ? AND action = 'OPEN'",
                        Integer.class,
                        tenantId),
                "base 与 followup 各一条开通记录");

        // 新租户管理员登录即见随访菜单（TENANT_ADMIN 已随开通获得模块菜单）。
        // 首次登录强制改密（E04-T05）：改密成功后当前会话继续可用。
        String adminToken = loginHospital2(wizardCode, "wizard.admin", "Init@2026#");
        assertNotNull(adminToken);
        assertEquals(
                0,
                code(
                        post(
                                Map.of("oldPassword", "Init@2026#", "newPassword", "Changed@2026#"),
                                "/api/hospital/auth/password",
                                adminToken)));
        List<String> titles =
                menuTitles(request(HttpMethod.GET, "/api/hospital/menus", adminToken, null));
        assertTrue(titles.contains("随访"), "向导勾选的模块菜单应可见：" + titles);
        assertEquals(
                0, code(request(HttpMethod.GET, "/api/hospital/followup/ping", adminToken, null)));
    }

    private Map<String, Object> getModules(String token, long tenantId) {
        return data(
                request(
                        HttpMethod.GET,
                        "/api/platform/tenants/" + tenantId + "/modules",
                        token,
                        null));
    }

    private String loginHospital2(String tenantCode, String username, String password) {
        Map<?, ?> response =
                post(
                        Map.of(
                                "tenantCode",
                                tenantCode,
                                "username",
                                username,
                                "password",
                                password),
                        "/api/hospital/auth/login",
                        null);
        return response.get("data") == null ? null : token(response);
    }

    private void createViewer() {
        if (viewerCreated) {
            return;
        }
        String passwordHash =
                new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
                                16, 32, 1, 65536, 3)
                        .encode(DEMO_PASSWORD);
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_TENANT_MODULE_VIEWER_IT', '租户模块只读（IT）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 111 FROM plt_role r WHERE r.code = 'PLATFORM_TENANT_MODULE_VIEWER_IT'");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.module.viewer2.it', '模块只读（IT 示例）', ?, 'ENABLED')",
                passwordHash);
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.module.viewer2.it'"
                        + " AND r.code = 'PLATFORM_TENANT_MODULE_VIEWER_IT'");
        viewerCreated = true;
    }
}
