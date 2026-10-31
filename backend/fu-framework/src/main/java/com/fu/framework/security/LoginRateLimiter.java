package com.fu.framework.security;

import com.fu.framework.web.ApiException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 按租户编码 + 账号计数。租户不存在、账号不存在、密码错误都计入，避免用返回差异探测租户。 */
@Component
public class LoginRateLimiter {

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;

    public LoginRateLimiter(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(String bucket) {
        String value = redis.opsForValue().get(key(bucket));
        if (value == null) {
            return;
        }
        if (Integer.parseInt(value) >= properties.getLoginFailureLimit()) {
            throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
        }
    }

    public void recordFailure(String bucket) {
        String key = key(bucket);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(properties.getLoginFailureWindowSeconds()));
        }
    }

    public void clear(String bucket) {
        redis.delete(key(bucket));
    }

    private static String key(String bucket) {
        return "fu:login:fail:" + bucket;
    }
}
