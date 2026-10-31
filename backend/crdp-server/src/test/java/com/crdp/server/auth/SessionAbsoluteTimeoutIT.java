package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * E04-T10 / PLT-AUTH-008 绝对会话时长接口用例（完整应用 + 真实 MySQL/Redis，HTTP 走起）。
 *
 * <p>把 {@code crdp.security.session-absolute-seconds} 压到 6 秒（参数可配，默认 12 小时）， 空闲窗口放宽到 60 秒，在 HTTP
 * 链路上验证：绝对时长从登录起算、<b>不随活跃滑动</b>—— 会话一直活跃（每次请求都在空闲窗口内续期）也会在到点后 40100，须重新登录； 到点前最后一段活跃不受影响。空闲超时与滑动续期在
 * {@code SessionIdleTimeoutIT}（独立参数的上下文）。
 *
 * <p>CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。 等待时长都留了 ≥1.5 秒余量防 CI 抖动。全部为虚构演示账号。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.security.session-idle-seconds=60",
            "crdp.security.session-absolute-seconds=6"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class SessionAbsoluteTimeoutIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        deleteKeys(redis.keys("crdp:sess:*"));
        deleteKeys(redis.keys("crdp:login:fail:*"));
    }

    @Test
    void absoluteTimeoutEndsTheSessionDespiteContinuousActivity() throws InterruptedException {
        Map<?, ?> login = loginHospital();
        assertEquals(0, codeOf(login));
        String token = (String) dataOf(login).get("token");

        // 0–4.5 秒内持续活跃（每 1.5 秒一次，远小于 60 秒空闲窗口）：全部成功
        for (int tick = 1; tick <= 3; tick++) {
            Thread.sleep(1_500);
            assertEquals(
                    0,
                    codeOf(get("/api/hospital/auth/me", token)),
                    "t=" + (tick * 1.5) + "s 活跃请求应成功");
        }

        // 跨过绝对时长（6 秒）后：即使最后一次活跃就在 1.5 秒前（远未到空闲窗口），会话仍按绝对时长下线
        Thread.sleep(1_500 + 1_500);
        assertEquals(
                40100, codeOf(get("/api/hospital/auth/me", token)), "绝对时长从登录起算不随活跃滑动，到点必须重新登录");

        // 旧令牌作废后须重新登录换取新会话
        String freshToken = (String) dataOf(loginHospital()).get("token");
        assertEquals(0, codeOf(get("/api/hospital/auth/me", freshToken)), "重新登录可用");
    }

    // ---- helpers ----

    private Map<?, ?> loginHospital() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                        "/api/hospital/auth/login",
                        new HttpEntity<>(
                                Map.of(
                                        "tenantCode", TENANT,
                                        "username", "doctor.demo",
                                        "password", PASSWORD),
                                headers),
                        Map.class)
                .getBody();
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
