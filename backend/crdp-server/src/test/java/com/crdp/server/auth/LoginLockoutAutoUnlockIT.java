package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

/**
 * E04-T10 / PLT-AUTH-007 锁定接口用例（完整应用 + 真实 MySQL/Redis，HTTP 走起）。
 *
 * <p>把 {@code crdp.security.login-lock-seconds} 压到 3 秒（参数可配，默认 30 分钟），在 HTTP 链路上验证： 失败计数桶按「端 + 租户编码
 * + 账号」隔离——demoa 锁死的账号既不影响同院他人（E04-T09 已证）， 也不影响 demob 的同名账号（双租户负例，AGENTS.md 第 6
 * 节）；锁定期满自动解锁，无需管理员介入（OQ-39： 平台账号暂无解锁界面，靠的就是这条）。 阶梯本身（3 次验证码墙 → 5 次锁定、正确口令不放行、管理员提前解锁）在
 * PasswordAndLockoutIT / AuthSecurityHardeningIT。
 *
 * <p>CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。 全部为虚构演示账号。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "crdp.security.login-lock-seconds=3"})
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class LoginLockoutAutoUnlockIT {

    private static final String TENANT = "demoa";
    private static final String OTHER_TENANT = "demob";
    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        // HttpClient 5 收到 429 会自动重发请求（把 42900 掩盖成后续响应），锁定用例要看服务端第一个响应
        rest.getRestTemplate()
                .setRequestFactory(
                        new HttpComponentsClientHttpRequestFactory(
                                HttpClients.custom().disableAutomaticRetries().build()));
        // 真实 Redis：清两院的失败计数与图形验证码，避免用例之间互相锁定
        deleteKeys(redis.keys("crdp:login:fail:*"));
        deleteKeys(redis.keys("crdp:captcha:*"));
    }

    @Test
    void lockoutIsScopedPerTenantAndExpiresAutomatically() throws InterruptedException {
        // 1. demoa 的 doctor.demo 连续失败 5 次：3 次口令错误（40101，第 3 次起要图形验证码），
        //    第 4、5 次不带验证码（40102，同样计失败）；第 5 次失败已置锁，后续请求一律 42900
        for (int i = 0; i < 3; i++) {
            Map<?, ?> wrong = login(TENANT, "doctor.demo", "Wrong@2026");
            assertEquals(40101, codeOf(wrong), "第 " + (i + 1) + " 次口令错误");
        }
        assertEquals(40102, codeOf(login(TENANT, "doctor.demo", "Wrong@2026")), "验证码墙内缺验证码");
        assertEquals(40102, codeOf(login(TENANT, "doctor.demo", PASSWORD)), "第 5 次失败仍按验证码错误拒绝");
        Map<?, ?> locked = login(TENANT, "doctor.demo", PASSWORD);
        assertEquals(42900, codeOf(locked), "连续失败 5 次锁定后的下一次请求 42900");
        long lockedSeconds = ((Number) dataOf(locked).get("lockedSeconds")).longValue();
        assertTrue(lockedSeconds > 0 && lockedSeconds <= 3, "锁定时长按配置（3 秒），实际 " + lockedSeconds);

        // 2. 锁定只作用于「demoa + doctor.demo」这个桶：demob 的同名账号用正确口令照常登录
        //    （失败计数按「端 + 租户编码 + 账号」分桶，AGENTS.md 第 6 节双租户用例）
        assertEquals(0, codeOf(login(OTHER_TENANT, "doctor.demo", PASSWORD)), "他院同名账号不受影响");

        // 3. 锁定期满自动解锁：无需管理员，正确口令直接登录成功（OQ-39 场景）
        Thread.sleep(3500 + 1000);
        Map<?, ?> unlocked = login(TENANT, "doctor.demo", PASSWORD);
        assertEquals(0, codeOf(unlocked), "锁定期满自动解锁：message=" + unlocked.get("message"));
    }

    @Test
    void platformLoginLockoutMirror() throws InterruptedException {
        // 平台端同构：连续失败 5 次锁定 42900（P: 桶），期满自动解锁；与医院端 H: 桶互不影响
        for (int i = 0; i < 3; i++) {
            assertEquals(
                    40101,
                    codeOf(platformLogin("platform.admin", "Wrong@2026")),
                    "平台端第 " + (i + 1) + " 次失败");
        }
        assertEquals(
                40102, codeOf(platformLogin("platform.admin", "Wrong@2026")), "平台端同样 3 次后要图形验证码");
        assertEquals(40102, codeOf(platformLogin("platform.admin", PASSWORD)), "第 5 次失败仍按验证码错误拒绝");
        assertEquals(42900, codeOf(platformLogin("platform.admin", PASSWORD)), "锁定后的下一次请求 42900");

        // 医院端桶不受平台端锁定影响（端别分桶）
        assertEquals(0, codeOf(login(TENANT, "nurse.demo", PASSWORD)));

        Thread.sleep(3500 + 1000);
        assertEquals(0, codeOf(platformLogin("platform.admin", PASSWORD)), "平台端锁定期满自动解锁");
    }

    // ---- helpers ----

    private Map<?, ?> login(String tenantCode, String username, String password) {
        return post(
                "/api/hospital/auth/login",
                Map.of("tenantCode", tenantCode, "username", username, "password", password));
    }

    private Map<?, ?> platformLogin(String username, String password) {
        return post("/api/platform/auth/login", Map.of("username", username, "password", password));
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
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
