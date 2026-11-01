package com.crdp.framework.security;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证令牌（Redis）。
 *
 * <p>敏感操作（明文查看、导出、下载、重置密码等）执行前要求用户再次验证身份：{@code POST /auth/reauth} 用密码换一个 {@code
 * reauthTokenTtlSeconds}（默认 5 分钟）有效的 reauthToken，敏感接口凭请求头 {@code X-Reauth-Token} 携带，
 * 由拦截器校验，缺失或不匹配一律 40304（docs/api/auth.md §2、docs/api/README.md 错误码表）。
 *
 * <p>令牌与登录令牌同规格（256 位随机不透明值，base64url 43 字符）。窗口内可多次使用（提交导出后紧接着下载明文相关文件）， 不做一次性消费；
 * 但只对签发它的那个会话有效——Redis 里存的是会话号（令牌 SHA-256 单向摘要）， 登出 / 被下线 / 重新登录后旧 reauthToken 自然失效，无需显式吊销。
 */
@Component
public class ReauthStore {

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;
    private final TokenGenerator tokenGenerator;

    public ReauthStore(
            StringRedisTemplate redis,
            SecurityProperties properties,
            TokenGenerator tokenGenerator) {
        this.redis = redis;
        this.properties = properties;
        this.tokenGenerator = tokenGenerator;
    }

    /** 密码再次验证通过后签发 reauthToken（绑定当前会话号，TTL = 配置有效期）。 */
    public String issue(SessionPrincipal principal) {
        String reauthToken = tokenGenerator.generate();
        redis.opsForValue()
                .set(
                        key(reauthToken),
                        principal.sessionId(),
                        Duration.ofSeconds(properties.getReauthTokenTtlSeconds()));
        return reauthToken;
    }

    /** 校验请求头携带的 reauthToken：存在、未过期且绑定当前会话才放行。 有效期内可重复校验（不消费）。 */
    public boolean isValid(String reauthToken, SessionPrincipal principal) {
        if (reauthToken == null || reauthToken.isBlank()) {
            return false;
        }
        return Optional.ofNullable(redis.opsForValue().get(key(reauthToken)))
                .filter(principal.sessionId()::equals)
                .isPresent();
    }

    private static String key(String reauthToken) {
        return "crdp:reauth:" + reauthToken;
    }
}
