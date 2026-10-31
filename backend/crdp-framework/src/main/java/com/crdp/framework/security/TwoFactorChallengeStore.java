package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * E04-T04 / PLT-AUTH-003：双因素第二步的 challenge（Redis）。密码第一步通过后不发会话令牌，只发一个 {@code
 * twoFactorChallengeTtlSeconds}（默认 5 分钟）有效、一次性的 challengeId； 第二步短信验证码通过后才换正式令牌。 challengeId
 * 与登录令牌同规格（256 位随机不透明值），取出即删，不重复使用。
 */
@Component
public class TwoFactorChallengeStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecurityProperties properties;
    private final TokenGenerator tokenGenerator;

    public TwoFactorChallengeStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            SecurityProperties properties,
            TokenGenerator tokenGenerator) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tokenGenerator = tokenGenerator;
    }

    /** 记住密码第一步已通过的账号与其完整会话草稿，返回 challengeId。 */
    public String create(String username, SessionPrincipal pendingPrincipal) {
        String challengeId = tokenGenerator.generate();
        PendingLogin pending = new PendingLogin(username, pendingPrincipal);
        try {
            redis.opsForValue()
                    .set(
                            key(challengeId),
                            objectMapper.writeValueAsString(pending),
                            Duration.ofSeconds(properties.getTwoFactorChallengeTtlSeconds()));
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
        return challengeId;
    }

    /** 只读查询（双因素第二步重试验证码时用，不消耗）。不存在或已过期返回 empty。 */
    public Optional<PendingLogin> find(String challengeId) {
        return read(challengeId, redis.opsForValue().get(key(challengeId)));
    }

    /** 取出并立即删除（一次性）。不存在或已过期返回 empty。 */
    public Optional<PendingLogin> consume(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        return read(challengeId, redis.opsForValue().getAndDelete(key(challengeId)));
    }

    private Optional<PendingLogin> read(String challengeId, String json) {
        if (challengeId == null || challengeId.isBlank() || json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PendingLogin.class));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    /** challenge 对应的暂挂登录：username 供审计，principal 是待签发的会话（token 为空，第二步通过时补）。 */
    public record PendingLogin(String username, SessionPrincipal principal) {}

    private static String key(String challengeId) {
        return "crdp:2fa:" + challengeId;
    }
}
