package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话是服务端状态（Redis），令牌只是查找键；删除键即吊销，下一次请求立即失效（E04-T02）。
 *
 * <p>E04-T05 / PLT-AUTH-006、010：save 时把令牌登记到「用户 → 令牌」索引（Set，随会话续期）， 修改或重置密码后可用 {@link
 * #revokeAllForUser} 让该账号的其他会话全部失效（规格：修改或重置密码后，该账号其他会话全部失效）， 当前会话保留。
 */
@Component
public class SessionStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SessionStore(
            StringRedisTemplate redis, ObjectMapper objectMapper, SecurityProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(properties.getSessionTtlHours());
    }

    public void save(SessionPrincipal principal) {
        try {
            String json = objectMapper.writeValueAsString(principal);
            redis.opsForValue().set(key(principal.token()), json, ttl);
            redis.opsForSet().add(userKey(principal), principal.token());
            redis.expire(userKey(principal), ttl);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    public Optional<SessionPrincipal> find(String token) {
        String json = redis.opsForValue().get(key(token));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SessionPrincipal.class));
        } catch (JsonProcessingException ex) {
            redis.delete(key(token));
            return Optional.empty();
        }
    }

    public void delete(String token) {
        redis.delete(key(token));
    }

    /** 吊销该账号除 keepToken 以外的全部会话（修改或重置密码后调用）。 返回被吊销的令牌数。 */
    public long revokeAllForUser(SessionPrincipal principal, String keepToken) {
        String userKey = userKey(principal);
        java.util.Set<String> members = redis.opsForSet().members(userKey);
        if (members == null || members.isEmpty()) {
            return 0;
        }
        List<String> tokens = members.stream().filter(java.util.Objects::nonNull).toList();
        long revoked = 0;
        for (String token : tokens) {
            if (token.equals(keepToken)) {
                continue;
            }
            redis.delete(key(token));
            redis.opsForSet().remove(userKey, token);
            revoked++;
        }
        return revoked;
    }

    public Duration ttl() {
        return ttl;
    }

    private static String key(String token) {
        return "crdp:sess:" + token;
    }

    /** 用户 → 令牌索引：端别 + 租户 + 用户唯一（医院账号与平台账号天然分开）。 */
    private static String userKey(SessionPrincipal principal) {
        String tenant = principal.tenantId() == null ? "0" : String.valueOf(principal.tenantId());
        return "crdp:sess:idx:" + principal.sessionType() + ":" + tenant + ":" + principal.userId();
    }
}
