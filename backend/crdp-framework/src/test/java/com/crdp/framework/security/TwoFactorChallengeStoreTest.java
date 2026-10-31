package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E04-T04 / PLT-AUTH-003：双因素第二步的 challenge。密码第一步通过后不发票据， 只发一个 5 分钟有效、一次性的
 * challengeId；第二步验证短信验证码通过后才换会话令牌。
 */
@SuppressWarnings("unchecked")
class TwoFactorChallengeStoreTest {

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private TwoFactorChallengeStore store;

    @BeforeEach
    void setUp() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString()))
                .thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        when(ops.getAndDelete(anyString()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            return redisStore.remove(key);
                        });
        doAnswer(
                        inv -> {
                            redisStore.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            return null;
                        })
                .when(ops)
                .set(anyString(), anyString(), any(Duration.class));
        doAnswer(
                        inv -> {
                            redisStore.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());

        SecurityProperties properties = new SecurityProperties();
        store =
                new TwoFactorChallengeStore(
                        redis, new ObjectMapper(), properties, new TokenGenerator());
    }

    private SessionPrincipal samplePrincipal() {
        return new SessionPrincipal(
                null,
                "HOSPITAL",
                7L,
                "demoa",
                "虚构示例医院甲",
                100L,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L),
                List.of("followup:patient:view"),
                List.of("base"),
                1L,
                ScopeSnapshot.allowAll());
    }

    @Test
    void challengeRoundTripsThePendingPrincipal() {
        SessionPrincipal principal = samplePrincipal();

        String challengeId = store.create("doctor.demo", principal);
        TwoFactorChallengeStore.PendingLogin pending = store.consume(challengeId).orElseThrow();

        assertEquals("doctor.demo", pending.username());
        assertEquals("demoa", pending.principal().tenantCode());
        assertTrue(pending.principal().hospital());
    }

    @Test
    void challengeIsSingleUse() {
        String challengeId = store.create("doctor.demo", samplePrincipal());

        assertTrue(store.consume(challengeId).isPresent(), "第一次取出成功");
        assertTrue(store.consume(challengeId).isEmpty(), "challenge 一次性使用，取出即删");
    }

    @Test
    void unknownChallengeIsEmpty() {
        assertTrue(store.consume("no-such-challenge").isEmpty());
    }

    @Test
    void challengeLivesForFiveMinutes() {
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);

        store.create("doctor.demo", samplePrincipal());

        verify(redis.opsForValue()).set(anyString(), anyString(), ttl.capture());
        assertEquals(Duration.ofSeconds(300), ttl.getValue(), "challenge 5 分钟有效");
    }

    @Test
    void challengeIdIsAnOpaque256BitValue() {
        String challengeId = store.create("doctor.demo", samplePrincipal());
        assertEquals(43, challengeId.length(), "与登录令牌同规格的不透明随机值");
    }

    @Test
    void ttlComesFromProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setTwoFactorChallengeTtlSeconds(120);
        store =
                new TwoFactorChallengeStore(
                        redis, new ObjectMapper(), properties, new TokenGenerator());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);

        store.create("doctor.demo", samplePrincipal());

        verify(redis.opsForValue()).set(anyString(), anyString(), ttl.capture());
        assertEquals(Duration.ofSeconds(120), ttl.getValue());
    }
}
