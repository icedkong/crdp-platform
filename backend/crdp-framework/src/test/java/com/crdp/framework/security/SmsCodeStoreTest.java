package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.web.ApiException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E04-T04 / PLT-AUTH-002、PLT-AUTH-003：登录短信验证码的签发与校验。 安全策略参数（platform-spec 第 7 章）：6 位数字、有效期 5 分钟、输错
 * 5 次失效、重发间隔 60 秒、同一手机号每日 10 次。
 */
@SuppressWarnings("unchecked")
class SmsCodeStoreTest {

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SecurityProperties properties = new SecurityProperties();

    private SmsCodeStore store;

    @BeforeEach
    void setUp() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString()))
                .thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        doAnswer(
                        inv -> {
                            redisStore.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            return null;
                        })
                .when(ops)
                .set(anyString(), anyString(), any(Duration.class));
        when(ops.increment(anyString()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            long next = Long.parseLong(redisStore.getOrDefault(key, "0")) + 1;
                            redisStore.put(key, String.valueOf(next));
                            return next;
                        });
        // expire 只设置 TTL，不动计数器的值
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        doAnswer(
                        inv -> {
                            redisStore.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());

        store = new SmsCodeStore(redis, properties);
    }

    private void expireCooldown() {
        redisStore.keySet().removeIf(key -> key.contains(":cd:"));
    }

    @Test
    void issuedCodeIsSixDigitsAndLivesForFiveMinutes() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        String code = store.issue("login:demoa:13900000001", "13900000001");

        assertEquals(6, code.length(), "6 位数字验证码");
        code.chars().forEach(c -> assertEquals(true, Character.isDigit(c), "只允许数字"));
        verify(redis.opsForValue(), times(2))
                .set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(300), ttlCaptor.getAllValues().get(0), "验证码 5 分钟有效（验收标准）");
        assertNotEquals(code, valueCaptor.getValue(), "Redis 只存验证码摘要，不落明文（AGENTS.md 第 5 节）");
    }

    @Test
    void resendWithinSixtySecondsIsRejectedWith42900() {
        store.issue("login:demoa:13900000001", "13900000001");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> store.issue("login:demoa:13900000001", "13900000001"));

        assertEquals(429, ex.httpStatus());
        assertEquals(42900, ex.code());
        assertEquals("尝试次数过多，请稍后再试", ex.getMessage());
    }

    @Test
    void resendIsAllowedAgainAfterCooldownExpires() {
        String first = store.issue("login:demoa:13900000001", "13900000001");
        expireCooldown();

        String second = store.issue("login:demoa:13900000001", "13900000001");

        assertNotEquals(first, second, "冷却到期后可重发，且换新码");
    }

    @Test
    void dailyLimitPerPhoneIsTen() {
        for (int i = 0; i < 10; i++) {
            expireCooldown();
            store.issue("login:demoa:13900000001", "13900000001");
        }

        expireCooldown();
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> store.issue("login:demoa:13900000001", "13900000001"));

        assertEquals(42900, ex.code(), "同一手机号每日最多 10 条");
    }

    @Test
    void correctCodeVerifiesAndIsConsumed() {
        String code = store.issue("login:demoa:13900000001", "13900000001");

        store.verify("login:demoa:13900000001", code);

        ApiException again =
                assertThrows(
                        ApiException.class, () -> store.verify("login:demoa:13900000001", code));
        assertEquals(40102, again.code(), "验证码一次性使用，校验成功即作废");
    }

    @Test
    void wrongCodeReturns40102ButDoesNotConsumeTheRightCode() {
        String code = store.issue("login:demoa:13900000001", "13900000001");

        ApiException wrong =
                assertThrows(
                        ApiException.class,
                        () -> store.verify("login:demoa:13900000001", "000000"));

        assertEquals(401, wrong.httpStatus());
        assertEquals(40102, wrong.code());
        assertEquals("验证码错误或已过期", wrong.getMessage());
        store.verify("login:demoa:13900000001", code);
    }

    @Test
    void fiveWrongAttemptsInvalidateTheCode() {
        String code = store.issue("login:demoa:13900000001", "13900000001");

        for (int i = 0; i < 4; i++) {
            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () -> store.verify("login:demoa:13900000001", "000000"));
            assertEquals(40102, ex.code());
        }

        ApiException fifth =
                assertThrows(
                        ApiException.class,
                        () -> store.verify("login:demoa:13900000001", "000000"));
        assertEquals(42900, fifth.code(), "输错 5 次后验证码失效并限流");

        ApiException afterInvalidate =
                assertThrows(
                        ApiException.class, () -> store.verify("login:demoa:13900000001", code));
        assertEquals(40102, afterInvalidate.code(), "失效后正确验证码也不能再用");
    }

    @Test
    void missingCodeVerifiesAs40102WithoutRevealingExistence() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> store.verify("login:demoa:13900000001", "123456"));
        assertEquals(40102, ex.code(), "不存在（含已过期）的验证码与错误验证码同码，防探测");
    }

    @Test
    void identifiersAreIsolatedFromEachOther() {
        String code = store.issue("login:demoa:13900000001", "13900000001");
        expireCooldown();
        store.issue("login:demob:13900000001", "13900000001");

        ApiException crossTenant =
                assertThrows(
                        ApiException.class, () -> store.verify("login:demob:13900000001", code));
        assertEquals(40102, crossTenant.code(), "验证码按命名空间隔离，租户 A 的码不能在租户 B 用");
    }

    @Test
    void cooldownAndDailyLimitAreCountedPerPhoneAcrossNamespaces() {
        store.issue("login:demoa:13900000001", "13900000001");

        ApiException otherNamespace =
                assertThrows(
                        ApiException.class, () -> store.issue("2fa:challenge-1", "13900000001"));
        assertEquals(42900, otherNamespace.code(), "冷却按手机号计，跨命名空间同样受限");
    }

    @Test
    void ttlAndDailyLimitComeFromProperties() {
        properties.setSmsCodeTtlSeconds(600);
        properties.setSmsResendCooldownSeconds(120);
        properties.setSmsDailyLimitPerPhone(2);
        store = new SmsCodeStore(redis, properties);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        store.issue("login:demoa:13900000001", "13900000001");
        verify(redis.opsForValue(), times(2)).set(anyString(), anyString(), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(600), ttlCaptor.getAllValues().get(0), "有效期可配置");

        expireCooldown();
        store.issue("login:demoa:13900000001", "13900000001");
        expireCooldown();
        ApiException limited =
                assertThrows(
                        ApiException.class,
                        () -> store.issue("login:demoa:13900000001", "13900000001"));
        assertEquals(42900, limited.code(), "每日上限可配置");
    }
}
