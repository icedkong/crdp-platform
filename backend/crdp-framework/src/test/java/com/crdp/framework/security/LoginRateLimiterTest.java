package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** E04-T02 / PLT-AUTH-001：登录失败按「端 + 租户编码 + 账号」计数，超过上限返回 42900。 锁定时长与图形验证码阈值属 E04-T05，这里只锁当前计数窗口。 */
class LoginRateLimiterTest {

    private final Map<String, Long> store = new HashMap<>();
    private final AtomicLong expiryCalls = new AtomicLong();
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
                            expiryCalls.incrementAndGet();
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
        properties.setLoginFailureLimit(8);
        properties.setLoginFailureWindowSeconds(600);
        limiter = new LoginRateLimiter(redis, properties);
    }

    @Test
    void checkPassesWhenNothingWasRecorded() {
        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
    }

    @Test
    void checkThrows42900OnceTheLimitIsReached() {
        for (int i = 0; i < 8; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        ApiException ex =
                assertThrows(ApiException.class, () -> limiter.check("H:demoa:doctor.demo"));
        assertEquals(429, ex.httpStatus());
        assertEquals(42900, ex.code());
        assertEquals("尝试次数过多，请稍后再试", ex.getMessage());
    }

    @Test
    void failuresAreCountedPerBucket() {
        for (int i = 0; i < 8; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        assertDoesNotThrow(() -> limiter.check("H:demob:doctor.demo"), "计数按租户 + 账号隔离");
    }

    @Test
    void windowIsSetOnlyOnTheFirstFailure() {
        limiter.recordFailure("H:demoa:doctor.demo");
        limiter.recordFailure("H:demoa:doctor.demo");
        assertEquals(1, expiryCalls.get(), "过期时间只在第一次失败时设置");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redis)
                .expire(key.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(600)));
        assertEquals("crdp:login:fail:H:demoa:doctor.demo", key.getValue());
    }

    @Test
    void clearResetsTheCounterAfterASuccessfulLogin() {
        limiter.recordFailure("H:demoa:doctor.demo");
        limiter.clear("H:demoa:doctor.demo");
        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
        verify(redis).delete("crdp:login:fail:H:demoa:doctor.demo");
    }

    @Test
    void checkBelowTheLimitStillPasses() {
        for (int i = 0; i < 7; i++) {
            limiter.recordFailure("H:demoa:doctor.demo");
        }
        assertDoesNotThrow(() -> limiter.check("H:demoa:doctor.demo"));
        verify(redis, never()).delete(anyString());
    }
}
