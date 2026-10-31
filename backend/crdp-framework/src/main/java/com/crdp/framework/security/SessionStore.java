package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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
            redis.opsForValue()
                    .set(key(principal.token()), objectMapper.writeValueAsString(principal), ttl);
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

    public Duration ttl() {
        return ttl;
    }

    private static String key(String token) {
        return "crdp:sess:" + token;
    }
}
