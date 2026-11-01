package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * E04-T02 / PLT-AUTH-001：登录失败按「端 + 租户编码 + 账号」计数。租户不存在、账号不存在、密码错误都计入，避免用返回差异探测租户。
 *
 * <p>E04-T05 / PLT-AUTH-007（等保参数表）：连续失败 {@code captchaThreshold}（默认 3）次后要求图形验证码； 连续失败 {@code
 * loginFailureLimit}（默认 5）次锁定 {@code loginLockSeconds}（默认 30 分钟），期间一律 42900， 响应 data
 * 带剩余锁定秒数；成功登录清零，管理员可解锁（{@link #unlock}）。计数、验证码、锁定共用一个桶。
 */
@Component
public class LoginRateLimiter {

    /** 锁定提示里展示的剩余时间（向上取整到分钟）。 */
    private static final String LOCK_MESSAGE = "失败次数过多，账号已锁定，请 %d 分钟后再试或联系管理员解锁";

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;

    public LoginRateLimiter(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 已锁定的桶直接拒绝，data 带剩余锁定秒数（mock Redis 无 TTL，测试里按整段时长返回）。 */
    public void check(String bucket) {
        int failures = failures(bucket);
        if (failures >= properties.getLoginFailureLimit()) {
            long lockedSeconds = lockedSeconds(bucket, failures);
            throw new ApiException(
                    429,
                    42900,
                    LOCK_MESSAGE.formatted(Math.max(1, (lockedSeconds + 59) / 60)),
                    Map.of("lockedSeconds", lockedSeconds));
        }
    }

    /** 连续失败达到阈值后，登录必须携带图形验证码（PLT-AUTH-001、007）。 */
    public boolean captchaRequired(String bucket) {
        return failures(bucket) >= properties.getCaptchaThreshold();
    }

    /** 距锁定还剩几次机会（未失败时等于锁定阈值），供 40101 响应提示。 */
    public int remainingAttempts(String bucket) {
        return Math.max(0, properties.getLoginFailureLimit() - failures(bucket));
    }

    /** 记一次失败。返回是否「本次失败恰好触发锁定」（E08-T01 / PLT-AUD-002 登录日志的 LOCK 事件）。 */
    public boolean recordFailure(String bucket) {
        String key = key(bucket);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return false;
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(properties.getLoginFailureWindowSeconds()));
            return false;
        }
        if (count >= properties.getLoginFailureLimit()) {
            // 达到锁定阈值：把同一键的 TTL 改写为锁定时长（重复触发以最后一次为准）
            redis.expire(key, Duration.ofSeconds(properties.getLoginLockSeconds()));
            return count == properties.getLoginFailureLimit();
        }
        return false;
    }

    /** 成功登录清零失败计数（验证码要求一并解除）。 */
    public void clear(String bucket) {
        redis.delete(key(bucket));
    }

    /** 管理员提前解锁（PLT-AUTH-007），与 {@link #clear} 同一存储。 */
    public void unlock(String bucket) {
        redis.delete(key(bucket));
    }

    private int failures(String bucket) {
        String value = redis.opsForValue().get(key(bucket));
        return value == null ? 0 : Integer.parseInt(value);
    }

    private long lockedSeconds(String bucket, int failures) {
        Long ttl = redis.getExpire(key(bucket), java.util.concurrent.TimeUnit.SECONDS);
        // 真实 Redis 返回剩余 TTL（正数；-1 无过期、-2 键不存在）；mock/异常情况按整段锁定时长兜底
        if (ttl == null || ttl <= 0) {
            return properties.getLoginLockSeconds();
        }
        return ttl;
    }

    private static String key(String bucket) {
        return "crdp:login:fail:" + bucket;
    }
}
