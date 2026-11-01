package com.crdp.followup.patient.app;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 明文查看频控（E07-T05，docs/api/patient.md §5：同一用户每分钟 ≤ 30 次，超出 42900 并写安全告警 PLT-AUD-012）。
 *
 * <p>Redis 固定窗口计数（与 {@code LoginRateLimiter} 同一存储模式）：键 {@code crdp:plain:reveal:{租户}:{用户}}， 首次计数置
 * 60 秒过期。计数器不可用时<b>不放行</b>（fail closed——明文查看宁可拒，审计与告警另有兜底）。
 */
@Component
public class PlaintextRateLimiter {

    /** 每分钟上限（patient.md §5「推断，待确认」，先用文档建议值，产品定论后只改这里）。 */
    static final int LIMIT_PER_MINUTE = 30;

    private static final Logger log = LoggerFactory.getLogger(PlaintextRateLimiter.class);

    private final StringRedisTemplate redis;

    public PlaintextRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 本次查看是否放行；tenantId / userId 缺失（异常上下文）或 Redis 不可用一律拒绝。 */
    public boolean tryAcquire(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return false;
        }
        String key = "crdp:plain:reveal:" + tenantId + ":" + userId;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return false;
            }
            if (count == 1L) {
                redis.expire(key, Duration.ofSeconds(60));
            }
            return count <= LIMIT_PER_MINUTE;
        } catch (RuntimeException failure) {
            log.error("明文查看频控计数失败（fail closed 拒绝）tenantId={} userId={}", tenantId, userId, failure);
            return false;
        }
    }
}
