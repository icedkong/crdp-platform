package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * E04-T10 / PLT-AUTH-008 会话空闲超时接口用例（完整应用 + 真实 MySQL/Redis，HTTP 走起）。
 *
 * <p>把 {@code crdp.security.session-idle-seconds} 与 {@code platform-session-idle-seconds} 压到 5 秒
 * （参数可配，默认医院端 30 分钟 / 平台端 15 分钟），在 HTTP 链路上验证： 登录响应的 {@code expiresInSeconds} 即剩余空闲窗口；超时未活跃 →
 * 会话删除、40100； 每个已认证请求滑动续期（持续活跃的会话活得比初始窗口久，停下后按最后一次活跃计时）； 平台端独立计超时。绝对超时（不随活跃滑动）在 {@code
 * SessionAbsoluteTimeoutIT}（独立参数的上下文）。
 *
 * <p>CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。 等待时长都留了 ≥3 秒余量防 CI 抖动（滑动用例 2 秒一击，窗口 5
 * 秒——共享 runner 上单次请求往返 + GC 停顿可超 1 秒，2026-11 CI 曾以 3 秒窗口 / 2 秒节拍出现过 1 秒余量被吃满的误报）。全部为虚构演示账号。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.security.session-idle-seconds=5",
            "crdp.security.platform-session-idle-seconds=5",
            "crdp.security.session-absolute-seconds=3600"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class SessionIdleTimeoutIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        // 真实 Redis：清会话，保证 expiresInSeconds 与会话存活断言只针对本用例签发的会话
        deleteKeys(redis.keys("crdp:sess:*"));
        deleteKeys(redis.keys("crdp:login:fail:*"));
    }

    @Test
    void idleExpiryEndsTheSessionWith40100() throws InterruptedException {
        Map<?, ?> login = loginHospital();
        assertEquals(0, codeOf(login));
        long expiresInSeconds = ((Number) dataOf(login).get("expiresInSeconds")).longValue();
        assertTrue(
                expiresInSeconds > 0 && expiresInSeconds <= 5,
                "expiresInSeconds 即剩余空闲窗口（本上下文 5 秒），实际 " + expiresInSeconds);

        String token = (String) dataOf(login).get("token");
        assertEquals(0, codeOf(get("/api/hospital/auth/me", token)), "刚登录的会话可用");

        Thread.sleep(5_500 + 1_500);
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", token)), "空闲超时后按未认证拒绝");
    }

    @Test
    void authenticatedRequestsSlideTheIdleWindow() throws InterruptedException {
        String token = (String) dataOf(loginHospital()).get("token");

        // 每 2 秒活跃一次（< 5 秒空闲窗口，留 ≥3 秒余量防 CI 抖动）：t=2、4、6 的请求各自成功并续期
        for (int tick = 2; tick <= 6; tick += 2) {
            Thread.sleep(2_000);
            assertEquals(
                    0,
                    codeOf(get("/api/hospital/auth/me", token)),
                    "t=" + tick + "s 的活跃请求应成功（窗口已滑动）");
        }
        // t≈6.5s：距登录已超过初始 5 秒窗口，但距最后活跃只有 0.5 秒——会话仍活着
        assertEquals(0, codeOf(get("/api/hospital/auth/me", token)), "滑动续期让会话活过初始窗口");

        // 停止活跃：从最后一次活跃起按空闲窗口计时，超时即 40100
        Thread.sleep(5_500 + 1_500);
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", token)), "停止活跃后按空闲超时下线");
    }

    @Test
    void platformSessionIdleTimeoutIsIndependent() throws InterruptedException {
        Map<?, ?> login = loginPlatform();
        assertEquals(0, codeOf(login));
        long expiresInSeconds = ((Number) dataOf(login).get("expiresInSeconds")).longValue();
        assertTrue(
                expiresInSeconds > 0 && expiresInSeconds <= 5,
                "平台端空闲窗口（本上下文 5 秒），实际 " + expiresInSeconds);

        String platformToken = (String) dataOf(login).get("token");
        Thread.sleep(5_500 + 1_500);
        assertEquals(40100, codeOf(get("/api/platform/auth/me", platformToken)), "平台会话同样按空闲超时下线");

        // 两端会话独立计超时：期间活跃过的医院会话不受平台会话过期影响
        String hospitalToken = (String) dataOf(loginHospital()).get("token");
        Thread.sleep(1_000);
        assertEquals(0, codeOf(get("/api/hospital/auth/me", hospitalToken)), "医院会话独立存活");
    }

    // ---- helpers ----

    private Map<?, ?> loginHospital() {
        return post(
                "/api/hospital/auth/login",
                Map.of("tenantCode", TENANT, "username", "doctor.demo", "password", PASSWORD));
    }

    private Map<?, ?> loginPlatform() {
        return post(
                "/api/platform/auth/login",
                Map.of("username", "platform.admin", "password", PASSWORD));
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<?, ?>) response.get("data");
    }

    private void deleteKeys(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
