package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证令牌（Redis）。 密码再次验证通过后签发一个 5 分钟有效、绑定当前会话的 reauthToken； 敏感接口凭请求头
 * X-Reauth-Token 携带，缺失或不匹配一律 40304。 令牌与登录令牌同规格（256 位随机不透明值），窗口内可多次使用（导出提交后紧接着下载），
 * 但只对签发它的那个会话有效——登出或换会话后旧令牌自然失效。
 */
@SuppressWarnings("unchecked")
class ReauthStoreTest {

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private SecurityProperties properties;
    private ReauthStore store;

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

        properties = new SecurityProperties();
        store = new ReauthStore(redis, properties, new TokenGenerator());
    }

    private static SessionPrincipal principal(String token) {
        return new SessionPrincipal(
                token,
                "HOSPITAL",
                7L,
                "demoa",
                "演示医院 A",
                1L,
                "doctor.demo",
                "张医生（示例）",
                List.of("DOCTOR"),
                List.of(1L),
                List.of("followup:patient:view"),
                List.of("followup"),
                1,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    @Test
    void issueReturnsOpaqueTokenBoundToSession() {
        String token = store.issue(principal("session-token-1"));

        // 256 位随机不透明值：base64url 43 字符，不含会话材料
        assertEquals(43, token.length());
        assertNotEquals(store.issue(principal("session-token-1")), token, "每次签发都应不同");
    }

    @Test
    void issuedTokenIsValidForSameSessionWithinTtl() {
        SessionPrincipal session = principal("session-token-1");
        String token = store.issue(session);

        assertTrue(store.isValid(token, session), "同会话在有效期内应通过");
        assertTrue(store.isValid(token, session), "窗口内可多次使用（提交导出后紧接着下载）");
    }

    @Test
    void tokenIsInvalidForDifferentSession() {
        String token = store.issue(principal("session-token-1"));

        // 登出后重新登录得到新会话：旧 reauthToken 绑定旧会话号，不能带到新会话上用
        assertFalse(store.isValid(token, principal("session-token-2")));
    }

    @Test
    void missingOrUnknownTokenIsInvalid() {
        SessionPrincipal session = principal("session-token-1");
        store.issue(session);

        assertFalse(store.isValid(null, session));
        assertFalse(store.isValid("", session));
        assertFalse(store.isValid("forged-token", session));
    }

    @Test
    void issueUsesConfiguredTtl() {
        properties.setReauthTokenTtlSeconds(120);

        store.issue(principal("session-token-1"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis.opsForValue()).set(key.capture(), value.capture(), ttl.capture());
        assertTrue(key.getValue().startsWith("crdp:reauth:"), "Redis 键前缀");
        // 值存会话号（令牌哈希单向摘要），不存令牌本身
        assertEquals(SessionPrincipal.sessionIdOf("session-token-1"), value.getValue());
        assertEquals(Duration.ofSeconds(120), ttl.getValue(), "TTL 取配置的 reauth 有效期");
    }

    @Test
    void defaultTtlIsFiveMinutes() {
        assertEquals(300, properties.getReauthTokenTtlSeconds());
    }
}
