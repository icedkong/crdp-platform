package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/** E04-T02 / PLT-AUTH-008：会话是服务端状态（Redis），令牌只是查找键。 删除键即吊销，下一次请求立即失效。 */
class SessionStoreTest {

    private final Map<String, String> store = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private SessionStore sessionStore;
    private SecurityProperties properties;

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
                            return null;
                        })
                .when(ops)
                .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        doAnswer(
                        inv -> {
                            store.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());
        properties = new SecurityProperties();
        properties.setSessionTtlHours(8);
        sessionStore = new SessionStore(redis, new ObjectMapper(), properties);
    }

    private static SessionPrincipal principal(String token) {
        return new SessionPrincipal(
                token,
                "HOSPITAL",
                7L,
                "demoa",
                "虚构示例医院甲",
                100L,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L, 20L),
                List.of("followup:patient:view"),
                List.of("base"),
                1L,
                ScopeSnapshot.allowAll());
    }

    @Test
    void savedSessionRoundTripsThroughTheStore() {
        SessionPrincipal expected = principal("token-1");
        sessionStore.save(expected);
        var found = sessionStore.find("token-1");
        assertTrue(found.isPresent(), "保存后的会话应能按令牌取回");
        assertEquals(expected, found.get(), "会话内容序列化往返后应保持一致");
    }

    @Test
    void saveWritesWithTheConfiguredTtl() {
        sessionStore.save(principal("token-1"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis.opsForValue())
                .set(
                        org.mockito.ArgumentMatchers.eq("crdp:sess:token-1"),
                        anyString(),
                        ttl.capture());
        assertEquals(Duration.ofHours(8), ttl.getValue(), "会话应按配置的 TTL 写入");
        assertEquals(Duration.ofHours(8), sessionStore.ttl());
    }

    @Test
    void deletingTheSessionRevokesTheTokenImmediately() {
        sessionStore.save(principal("token-1"));
        sessionStore.delete("token-1");
        assertTrue(sessionStore.find("token-1").isEmpty(), "删除会话后令牌应立即失效（服务端吊销）");
    }

    @Test
    void unknownTokenHasNoSession() {
        assertTrue(sessionStore.find("never-issued").isEmpty(), "未签发过的令牌查不到会话");
    }

    @Test
    void corruptedEntryIsDroppedInsteadOfFailingTheRequest() {
        store.put("crdp:sess:broken", "{not-json");
        assertTrue(sessionStore.find("broken").isEmpty(), "损坏的会话条目应按未登录处理");
        verify(redis).delete("crdp:sess:broken");
    }
}
