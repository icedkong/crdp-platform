package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * E02-T05 隔离验收自动化（PLT-TEN-007：A 的令牌访问 B 的数据必须失败）。启动完整应用（local 剖面 即真实 MySQL + Redis），走 HTTP
 * 验证：令牌决定租户（Host、伪造头都劫持不了路由）、跨端拒绝、 无令牌与登出后的令牌拒绝。CRDP_IT_MYSQL_PORT 存在时运行（需 docker compose 的 MySQL 与
 * Redis）； CI 无基础设施自动跳过。跨租户"写"隔离随业务写接口出现后按 AGENTS.md 第 6 节的双租户用例模板补充。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class TenantIsolationAcceptanceIT {

    @Autowired private TestRestTemplate rest;

    private String hospitalToken(String tenantCode) {
        Map<String, Object> body =
                Map.of(
                        "tenantCode", tenantCode,
                        "username", "nurse.demo",
                        "password", "Demo@2026");
        Map<?, ?> response = post(body, "/api/hospital/auth/login", null, null);
        return token(response);
    }

    private String platformToken() {
        Map<String, Object> body = Map.of("username", "platform.admin", "password", "Demo@2026");
        return token(post(body, "/api/platform/auth/login", null, null));
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

    @SuppressWarnings("unchecked")
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

    @Test
    void tenantIsolationAcceptance() {
        String tokenOfA = hospitalToken("demoa");
        String tokenOfB = hospitalToken("demob");
        String platform = platformToken();
        assertNotNull(tokenOfA);
        assertNotNull(tokenOfB);
        assertNotNull(platform);

        // 1. 各自令牌路由到各自租户库
        assertEquals("t_demoa", databaseOf(get("/api/hospital/hello", tokenOfA, null, null)));
        assertEquals("t_demob", databaseOf(get("/api/hospital/hello", tokenOfB, null, null)));

        // 2. A 的令牌 + B 的子域名：仍是 A 的库——Host 劫持不了已认证路由（A 访问不到 B 的数据）
        assertEquals(
                "t_demoa",
                databaseOf(get("/api/hospital/hello", tokenOfA, "demob.crdp.local", null)));

        // 3. A 的令牌 + 伪造租户头：仍是 A 的库——租户只来自令牌
        assertEquals("t_demoa", databaseOf(get("/api/hospital/hello", tokenOfA, null, "demob")));

        // 4. 跨端拒绝：医院令牌进不了平台接口，平台令牌进不了医院接口
        assertEquals(40300, codeOf(get("/api/platform/menus", tokenOfA, null, null)));
        assertEquals(40300, codeOf(get("/api/hospital/hello", platform, null, null)));

        // 5. 无令牌拒绝
        assertEquals(40100, codeOf(get("/api/hospital/menus", null, null, null)));

        // 6. 登出后令牌立即失效
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenOfA);
        rest.exchange(
                "/api/hospital/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertEquals(40100, codeOf(get("/api/hospital/menus", tokenOfA, null, null)));
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
