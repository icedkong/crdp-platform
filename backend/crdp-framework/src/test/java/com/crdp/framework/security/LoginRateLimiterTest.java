package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.web.ApiException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E04-T02 / PLT-AUTH-001：登录失败按「端 + 租户编码 + 账号」计数。 E04-T05 / PLT-AUTH-007（等保参数表）：连续失败 3 次触发图形验证码、连续失败
 * 5 次锁定 30 分钟，管理员可解锁； 成功登录清零。计数、验证码、锁定共用一个桶。
 */
class LoginRateLimiterTest {

    private final Map<String, Long> store = new HashMap<>();
    private final Map<String, Duration> expiries = new HashMap<>();
    private final AtomicReference<Duration> lastExpiry = new AtomicReference<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SecurityProperties properties = new SecurityProperties();

    private LoginRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString()))
                .thenAnswer(
                        inv -> {
                            Long value = store.get(inv.getArgument(0, String.class));
                            return value == null ? null : String.valueOf(value);
                        });
        when(ops.increment(anyString()))
                .thenAnswer(inv -> store.merge(inv.getArgument(0, String.class), 1L, Long::sum));
        doAnswer(
                        inv -> {
                            lastExpiry.set(inv.getArgument(1, Duration.class));
                            expiries.put(inv.getArgument(0, String.class), lastExpiry.get());
                            return true;
                        })
                .when(redis)
                .expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        doAnswer(
                        inv -> {
                            store.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());
        properties.setLoginFailureLimit(5);
        properties.setLoginFailureWindowSeconds(600);
        properties.setLoginLockSeconds(1800);
        properties.setCaptchaThreshold(3);
        limiter = new LoginRateLimiter(redis, properties);
    }

    @Test
    void checkPassesWhenNothingWasRecorded() {
        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
    }

    @Test
    void checkThrows42900OnceTheLockThresholdIsReached() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        ApiException ex =
                assertThrows(ApiException.class, () -> limiter.check("H:demoa:doctor.demo"));
        assertEquals(429, ex.httpStatus());
        assertEquals(42900, ex.code());
        assertEquals("失败次数过多，账号已锁定，请 30 分钟后再试或联系管理员解锁", ex.getMessage());
        assertEquals(java.util.Map.of("lockedSeconds", 1800L), ex.data(), "锁定剩余时间放进响应 data 供前端展示");
    }

    @Test
    void lockWindowIsThirtyMinutesAndCountingWindowIsTen() {
        limiter.recordFailure("H:demoa:doctor.demo");
        assertEquals(Duration.ofSeconds(600), expiries.get("crdp:login:fail:H:demoa:doctor.demo"));

        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        // 第 5 次失败把同一键的 TTL 改写为锁定时长 30 分钟
        assertEquals(Duration.ofSeconds(1800), lastExpiry.get());
    }

    @Test
    void failuresAreCountedPerBucket() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        assertDoesNotThrow(() -> limiter.check("H:demob:doctor.demo"), "计数按租户 + 账号隔离");
    }

    @Test
    void checkBelowTheLockThresholdStillPasses() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void captchaIsRequiredAfterThreeConsecutiveFailures() {
        assertFalse(limiter.captchaRequired("H:demoa:doctor.demo"));
        limiter.recordFailure("H:demoa:doctor.demo");
        limiter.recordFailure("H:demoa:doctor.demo");
        assertFalse(limiter.captchaRequired("H:demoa:doctor.demo"), "2 次失败还不要求验证码");

        limiter.recordFailure("H:demoa:doctor.demo");
        assertTrue(limiter.captchaRequired("H:demoa:doctor.demo"), "连续失败 3 次触发图形验证码");
    }

    @Test
    void remainingAttemptsCountDownToTheLockThreshold() {
        assertEquals(5, limiter.remainingAttempts("H:demoa:doctor.demo"));
        limiter.recordFailure("H:demoa:doctor.demo");
        assertEquals(4, limiter.remainingAttempts("H:demoa:doctor.demo"));
    }

    @Test
    void unlockClearsTheBucketForTheAdministrator() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        assertThrows(ApiException.class, () -> limiter.check("H:demoa:doctor.demo"));

        limiter.unlock("H:demoa:doctor.demo");

        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
        assertFalse(limiter.captchaRequired("H:demoa:doctor.demo"));
        verify(redis).delete("crdp:login:fail:H:demoa:doctor.demo");
    }

    @Test
    void clearResetsTheCounterAfterASuccessfulLogin() {
        limiter.recordFailure("H:demoa:doctor.demo");
        limiter.clear("H:demoa:doctor.demo");
        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
        verify(redis).delete("crdp:login:fail:H:demoa:doctor.demo");
    }

    @Test
    void windowIsSetOnlyOnTheFirstFailureBeforeLockout() {
        limiter.recordFailure("H:demoa:doctor.demo");
        limiter.recordFailure("H:demoa:doctor.demo");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redis)
                .expire(key.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(600)));
        assertEquals("crdp:login:fail:H:demoa:doctor.demo", key.getValue());
    }
}
