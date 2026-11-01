package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E07-T05 明文查看频控（docs/api/patient.md §5：同一用户每分钟 ≤ 30 次，超出 42900）： Redis 固定窗口计数，与 {@code
 * LoginRateLimiter} 同一存储模式。全部虚构数据。
 */
class PlaintextRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);

    private final PlaintextRateLimiter limiter = new PlaintextRateLimiter(redis);

    @BeforeEach
    void wire() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void allowsUpToThirtyPerMinuteWindow() {
        for (long count = 1; count <= 30; count++) {
            when(values.increment(anyString())).thenReturn(count);
            assertTrue(limiter.tryAcquire(1L, 9L), "第 " + count + " 次应放行");
        }
    }

    @Test
    void deniesThirtyFirstCall() {
        when(values.increment(anyString())).thenReturn(31L);

        assertFalse(limiter.tryAcquire(1L, 9L));
    }

    @Test
    void firstCallSetsWindowExpiry() {
        when(values.increment("crdp:plain:reveal:1:9")).thenReturn(1L);

        limiter.tryAcquire(1L, 9L);

        verify(redis).expire(eq("crdp:plain:reveal:1:9"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void redisFailureFailsClosed() {
        when(values.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertFalse(limiter.tryAcquire(1L, 9L), "计数器不可用时不放行（fail closed，审计仍兜底）");
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
