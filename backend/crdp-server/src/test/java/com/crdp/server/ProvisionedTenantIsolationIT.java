package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.test.context.ActiveProfiles;

/**
 * E02-T09 租户隔离与开通编排自动化用例（PLT-TEN-007：A 的令牌访问 B 的数据必须失败）。与 E02-T05 的
 * TenantIsolationAcceptanceIT（种子租户 demoa/demob）互补，本用例覆盖“开通编排产出的新租户”：同一应用 进程内经向导 API 开通两个全新租户（真
 * MySQL），随后对这两个新租户跑完整隔离验收——
 *
 * <ol>
 *   <li>开通编排五步全部 SUCCESS、租户 TRIAL、库已建并迁移（开通编排可用的冒烟）；
 *   <li>新租户管理员在同一进程内直接登录（数据源热注册，无需重启，PLT-TEN-005）；
 *   <li>各令牌路由到各自新库；A 令牌在 B 子域名入口被拒（40100，E04-T02 会话与入口一致性）、 伪造租户头改不了路由（租户只来自令牌）；
 *   <li>跨租户凭证隔离：F1 的口令在 F2 编码下登录必须失败（40101），不泄露租户是否存在；
 *   <li>医院令牌进不了平台接口（40300）；
 *   <li>开通写入只落本库：F1 的管理员不在 F2 库、F2 的不在 F1 库，也不写入演示租户 demoa 的库 （local
 *       剖面给每个新库灌同一份演示种子，属预期；写隔离的数据层断言，业务写接口出现后按 AGENTS.md §6 双租户模板补 HTTP 级写用例）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（需 docker compose 的 MySQL 与 Redis，自 E02-T09 起在 CI 中运行）；
 * 自建自清（编码用后端生成的随机值，重跑不残留）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ProvisionedTenantIsolationIT {

    /** 两个新租户首个管理员用户名与口令都不同：跨租户凭证隔离与“写入只落本库”断言的前提。 */
    private static final String ADMIN_OF_A = "iso-admin-a";

    private static final String ADMIN_OF_B = "iso-admin-b";
    private static final String PASSWORD_OF_A = "Iso-One@2026";
    private static final String PASSWORD_OF_B = "Iso-Two@2026";

    private static JdbcTemplate root;
    private static JdbcTemplate platform;
    private static final List<String> createdCodes = new java.util.ArrayList<>();

    @Autowired private TestRestTemplate rest;

    @AfterAll
    static void cleanUp() {
        if (root == null || platform == null) {
            return; // 用例尚未连上数据库即失败时无可清理
        }
        for (String code : createdCodes) {
            root.execute("DROP DATABASE IF EXISTS t_" + code);
            root.execute("DROP USER IF EXISTS `t_" + code + "`@'%'");
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
    }

    @Test
    void freshlyProvisionedTenantsPassIsolationAcceptance() {
        connect();

        // 1. 开通编排（HTTP 向导入口）：两个全新租户，五步全部 SUCCESS
        String codeA = provision("虚构隔离验收医院甲", ADMIN_OF_A, PASSWORD_OF_A);
        String codeB = provision("虚构隔离验收医院乙", ADMIN_OF_B, PASSWORD_OF_B);
        for (String code : List.of(codeA, codeB)) {
            assertEquals(
                    "TRIAL",
                    platform.queryForObject(
                            "SELECT status FROM plt_tenant WHERE code = ?", String.class, code),
                    "新租户 " + code + " 应已开通到 TRIAL");
            assertEquals(
                    1,
                    root.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                            Integer.class,
                            "t_" + code),
                    "新租户库已建");
            assertFalse(
                    root.queryForList(
                                    "SELECT version FROM t_"
                                            + code
                                            + ".flyway_schema_history ORDER BY installed_rank",
                                    String.class)
                            .isEmpty(),
                    "新租户库已执行迁移");
        }

        // 2. 热注册：不开新进程，同一应用内新租户管理员即可登录（子域名识别 + 编码识别各一次）
        String tokenA = hospitalLogin(codeA, ADMIN_OF_A, PASSWORD_OF_A);
        String tokenB = loginBySubdomain(codeB, ADMIN_OF_B, PASSWORD_OF_B);
        assertNotNull(tokenA, "开通完成后应立即能以编码登录（数据源热注册）");
        assertNotNull(tokenB, "开通完成后应立即能以子域名登录（PLT-TEN-015）");

        // E04-T05 / PLT-AUTH-010：开通生成的初始密码首次登录强制改密——
        // 改密前除 me / 改密 / 登出 外一律 40305，改密后恢复可用（走产品真实流程）
        assertEquals(
                40305,
                codeOf(get("/api/hospital/hello", tokenA, null, null)),
                "初始密码未改前业务接口一律 40305");
        changeFirstLoginPassword(codeA, tokenA, PASSWORD_OF_A, "Iso-One@2027a");
        changeFirstLoginPassword(codeB, tokenB, PASSWORD_OF_B, "Iso-One@2027b");
        assertEquals("t_" + codeA, databaseOf(get("/api/hospital/hello", tokenA, null, null)));
        assertEquals("t_" + codeB, databaseOf(get("/api/hospital/hello", tokenB, null, null)));

        // 3. 跨租户路由劫持失败：A 令牌 + B 子域名被按未登录拒绝（E04-T02 会话与入口一致性，
        //    A 的令牌进不了 B 的入口）；A 令牌 + 自己的子域名仍路由 A 库；伪造租户头改不了路由
        assertEquals(
                40100,
                codeOf(get("/api/hospital/hello", tokenA, codeB + ".crdp.local", null)),
                "A 令牌在 B 子域名入口必须被拒（不暴露差异，统一 40100）");
        assertEquals(
                "t_" + codeA,
                databaseOf(get("/api/hospital/hello", tokenA, codeA + ".crdp.local", null)),
                "A 令牌在自己的子域名正常路由 A 库");
        assertEquals(
                "t_" + codeA,
                databaseOf(get("/api/hospital/hello", tokenA, null, codeB)),
                "租户只来自令牌");

        // 4. 跨租户凭证隔离：A 的账号在 B 编码下登录必须失败；未知租户同样统一拒绝
        assertEquals(
                40101,
                codeOf(
                        post(
                                loginBody(codeB, ADMIN_OF_A, PASSWORD_OF_A),
                                "/api/hospital/auth/login",
                                null,
                                null)),
                "F1 的账号只存在于 F1 库，在 F2 租户编码下不得通过");
        assertEquals(
                40101,
                codeOf(
                        post(
                                loginBody("nosuchtenant", ADMIN_OF_A, PASSWORD_OF_A),
                                "/api/hospital/auth/login",
                                null,
                                null)),
                "未知租户统一按凭证错误拒绝，不泄露租户存在性");

        // 5. 跨端拒绝：新租户的医院令牌进不了平台接口
        assertEquals(40300, codeOf(get("/api/platform/menus", tokenA, null, null)));

        // 6. 开通写入只落本库：local 剖面会向新库灌同一份演示种子（nurse/doctor.demo），
        //    但 A 的管理员绝不能出现在 B 库，反之亦然；也不得写入演示租户 demoa 的库
        assertTrue(
                root.queryForList("SELECT username FROM t_" + codeA + ".sys_user", String.class)
                        .contains(ADMIN_OF_A),
                "F1 管理员在 F1 库");
        assertFalse(
                root.queryForList("SELECT username FROM t_" + codeA + ".sys_user", String.class)
                        .contains(ADMIN_OF_B),
                "F2 管理员不得写入 F1 库");
        assertTrue(
                root.queryForList("SELECT username FROM t_" + codeB + ".sys_user", String.class)
                        .contains(ADMIN_OF_B),
                "F2 管理员在 F2 库");
        assertFalse(
                root.queryForList("SELECT username FROM t_" + codeB + ".sys_user", String.class)
                        .contains(ADMIN_OF_A),
                "F1 管理员不得写入 F2 库");
        assertEquals(
                0,
                root.queryForObject(
                        "SELECT COUNT(*) FROM t_demoa.sys_user WHERE username IN (?, ?)",
                        Integer.class,
                        ADMIN_OF_A,
                        ADMIN_OF_B),
                "开通 F1/F2 不得写入演示租户 demoa 的库");
    }

    /** 平台登录 → 向导开通一个新租户，返回生成的租户编码。 */
    @SuppressWarnings("unchecked")
    private String provision(String hospitalName, String adminUsername, String adminPassword) {
        Map<?, ?> response =
                post(
                        Map.of(
                                "name", hospitalName,
                                "adminUsername", adminUsername,
                                "adminDisplayName", hospitalName + "管理员",
                                "adminInitialPassword", adminPassword),
                        "/api/platform/tenants",
                        platformToken(),
                        null);
        assertEquals(0, codeOf(response), () -> String.valueOf(response));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        String code = data.get("code").toString();
        createdCodes.add(code);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertEquals(5, steps.size(), () -> steps.toString());
        assertTrue(
                steps.stream().allMatch(step -> "SUCCESS".equals(step.get("status"))),
                "开通五步必须全部成功: " + steps);
        return code;
    }

    private String platformToken() {
        return token(
                post(
                        Map.of("username", "platform.admin", "password", "Demo@2026"),
                        "/api/platform/auth/login",
                        null,
                        null));
    }

    private static Map<String, Object> loginBody(
            String tenantCode, String username, String password) {
        return Map.of("tenantCode", tenantCode, "username", username, "password", password);
    }

    /** E04-T05：开通管理员的首次登录改密（POST /auth/password，成功后当前会话继续可用）。 */
    private void changeFirstLoginPassword(
            String tenantCode, String token, String oldPassword, String newPassword) {
        Map<?, ?> response =
                post(
                        Map.of("oldPassword", oldPassword, "newPassword", newPassword),
                        "/api/hospital/auth/password",
                        token,
                        null);
        assertEquals(0, codeOf(response), () -> tenantCode + " 改密失败：" + response);
    }

    private String hospitalLogin(String tenantCode, String username, String password) {
        return token(
                post(
                        loginBody(tenantCode, username, password),
                        "/api/hospital/auth/login",
                        null,
                        null));
    }

    /** 子域名方式登录（PLT-AUTH-004 / PLT-TEN-015）：Host 带子域名，编码不填。 */
    private String loginBySubdomain(String tenantCode, String username, String password) {
        Map<?, ?> response =
                post(
                        Map.of("username", username, "password", password),
                        "/api/hospital/auth/login",
                        null,
                        tenantCode + ".crdp.local");
        return token(response);
    }

    private void connect() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:" + port + "/" + params,
                                "root",
                                "DevOnly#root2026"));
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:" + port + "/crdp_platform" + params,
                                "crdp_app",
                                "DevOnly#app2026"));
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> post(Map<String, Object> body, String path, String token, String host) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (host != null) {
            headers.set(HttpHeaders.HOST, host);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token, String host, String spoofedTenant) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (host != null) {
            headers.set(HttpHeaders.HOST, host);
        }
        if (spoofedTenant != null) {
            headers.set("X-Tenant-Code", spoofedTenant);
            headers.set("tenantCode", spoofedTenant);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static Object dataOf(Map<?, ?> response) {
        return response == null ? null : response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static String databaseOf(Map<?, ?> response) {
        Object data = dataOf(response);
        return data == null ? null : ((Map<String, Object>) data).get("database").toString();
    }

    private static int codeOf(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static String token(Map<?, ?> loginResponse) {
        Object data = dataOf(loginResponse);
        return data == null ? null : ((Map<?, ?>) data).get("token").toString();
    }
}
