package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.web.ApiException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E04-T05 / PLT-AUTH-001、007：图形验证码（连续失败 3 次后登录必须携带）。 Redis 只存 SHA-256
 * 摘要（不落明文，图片渲染后明文即刻丢弃）；验证不区分大小写； 一次一密：无论对错，验证后立即作废，前端需重新取图。
 */
class CaptchaStoreTest {

    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Duration> setTtls = new HashMap<>();
    private final AtomicLong expiryCalls = new AtomicLong();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SecurityProperties properties = new SecurityProperties();

    private CaptchaStore captchaStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        doAnswer(
                        inv -> {
                            store.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            setTtls.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(2, Duration.class));
                            return null;
                        })
                .when(ops)
                .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        when(ops.increment(anyString()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            long next = Long.parseLong(store.getOrDefault(key, "0")) + 1;
                            store.put(key, String.valueOf(next));
                            return next;
                        });
        doAnswer(
                        inv -> {
                            store.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());
        doAnswer(
                        inv -> {
                            expiryCalls.incrementAndGet();
                            return true;
                        })
                .when(redis)
                .expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        properties.setCaptchaTtlSeconds(300);
        captchaStore = new CaptchaStore(redis, properties);
    }

    @Test
    void issuedCaptchaCarriesAnImageAndANonEmptyId() {
        // clientIp 为 null 表示不做按 IP 限流（测试路径）
        CaptchaStore.CaptchaImage image = captchaStore.issue(null);

        assertNotNull(image.captchaId());
        assertTrue(image.image().startsWith("data:image/png;base64,"), "返回 PNG 的 data URL");
        assertTrue(image.image().length() > 100, "图片内容非空");
    }

    @Test
    void verifyAcceptsTheCodeIgnoringCase() {
        captchaStore.put("cap-1", "Ab3d");

        assertDoesNotThrow(() -> captchaStore.verify("cap-1", "aB3D"));
    }

    @Test
    void wrongOrUnknownCaptchaReturns40102() {
        captchaStore.put("cap-1", "Ab3d");

        ApiException wrong =
                assertThrows(ApiException.class, () -> captchaStore.verify("cap-1", "ZZ9Z"));
        assertEquals(401, wrong.httpStatus());
        assertEquals(40102, wrong.code());
        assertEquals("验证码错误或已过期", wrong.getMessage());

        ApiException unknown =
                assertThrows(ApiException.class, () -> captchaStore.verify("no-such", "Ab3d"));
        assertEquals(40102, unknown.code());
    }

    @Test
    void missingCaptchaArgumentsAreRejectedAsBadCode() {
        captchaStore.put("cap-1", "Ab3d");

        assertEquals(
                40102,
                assertThrows(ApiException.class, () -> captchaStore.verify(null, "Ab3d")).code());
        assertEquals(
                40102,
                assertThrows(ApiException.class, () -> captchaStore.verify("cap-1", null)).code());
        assertEquals(
                40102,
                assertThrows(ApiException.class, () -> captchaStore.verify("cap-1", "")).code());
    }

    @Test
    void captchaIsConsumedAfterOneVerificationAttempt() {
        captchaStore.put("cap-1", "Ab3d");
        assertThrows(ApiException.class, () -> captchaStore.verify("cap-1", "ZZ9Z"));

        // 输错一次即作废：即使随后输入正确码也不能再用（一次一密）
        ApiException second =
                assertThrows(ApiException.class, () -> captchaStore.verify("cap-1", "Ab3d"));
        assertEquals(40102, second.code());
    }

    @Test
    void redisHoldsOnlyTheDigestWithConfiguredTtl() {
        captchaStore.put("cap-1", "Ab3d");

        String value = store.get("crdp:captcha:code:cap-1");
        assertNotNull(value);
        assertNotEquals("Ab3d", value, "Redis 不存验证码明文");
        assertEquals(64, value.length(), "SHA-256 十六进制摘要长度");
        assertEquals(
                Duration.ofSeconds(300),
                setTtls.get("crdp:captcha:code:cap-1"),
                "验证码摘要的有效期为配置的 5 分钟");
    }

    @Test
    void repeatedIssuesFromTheSameIpAreLimitedPerHour() {
        properties.setCaptchaIpHourlyLimit(3);
        for (int i = 0; i < 3; i++) {
            captchaStore.issue("127.0.0.1");
        }
        ApiException limited =
                assertThrows(ApiException.class, () -> captchaStore.issue("127.0.0.1"));
        assertEquals(429, limited.httpStatus());
        assertEquals(42900, limited.code());

        // 限流按 IP 分桶，别的 IP 不受影响
        assertDoesNotThrow(() -> captchaStore.issue("10.0.0.8"));
    }
}
