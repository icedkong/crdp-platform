package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话是服务端状态（Redis），令牌只是查找键；删除键即吊销，下一次请求立即失效（E04-T02）。
 *
 * <p>E04-T05 / PLT-AUTH-006、010：save 时把令牌登记到「用户 → 令牌」索引（Set，随会话续期）， 修改或重置密码后可用 {@link
 * #revokeAllForUser} 让该账号的其他会话全部失效（规格：修改或重置密码后，该账号其他会话全部失效）， 当前会话保留。
 *
 * <p>E04-T06 / PLT-AUTH-008、009：会话生命周期与在线会话管理。
 *
 * <ul>
 *   <li>空闲超时（医院端默认 30 分钟、平台端 15 分钟，按登录时快照存进会话）：超过未活跃即失效；
 *   <li>绝对会话时长（默认 12 小时，SecurityProperties）：从登录起算不滑动，到时重新登录；
 *   <li>滑动续期：每个已认证请求 {@link #touch} 刷新最后活跃时间并按剩余窗口续 Redis TTL；
 *   <li>「用户 → 令牌」「租户 → 令牌」两级索引：本人在线会话、管理员在线用户列表与强制下线都走索引， 只暴露 {@link SessionPrincipal#sessionIdOf
 *       单向推导的会话号}，不外露令牌。
 * </ul>
 */
@Component
public class SessionStore {

    /** 兜底上限：Redis TTL 不能超过 2^31-1 秒，未启用绝对时长时按 30 天封顶。 */
    private static final long MAX_TTL_SECONDS = 30L * 24 * 3600;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecurityProperties properties;

    public SessionStore(
            StringRedisTemplate redis, ObjectMapper objectMapper, SecurityProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 新会话入库（或续期）：按「剩余空闲窗口 / 剩余绝对时长」的较小值设置 Redis TTL。 */
    public void save(SessionPrincipal principal) {
        Duration ttl = effectiveTtl(principal, Instant.now().getEpochSecond());
        if (ttl.isZero() || ttl.isNegative()) {
            // 已过期的会话不再落库，直接清掉（防御：绝对时长到时后的旧写入）
            evict(principal.token(), principal);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(principal);
            redis.opsForValue().set(key(principal.token()), json, ttl);
            redis.opsForSet().add(userKey(principal), principal.token());
            redis.expire(userKey(principal), ttl);
            if (principal.hospital()) {
                redis.opsForSet().add(tenantKey(principal.tenantId()), principal.token());
                redis.expire(tenantKey(principal.tenantId()), ttl);
            }
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    /** 按令牌取会话；空闲或绝对超时的会话按不存在处理并清理（下一次请求 40100）。 */
    public Optional<SessionPrincipal> find(String token) {
        String json = redis.opsForValue().get(key(token));
        if (json == null) {
            return Optional.empty();
        }
        SessionPrincipal principal;
        try {
            principal = objectMapper.readValue(json, SessionPrincipal.class);
        } catch (JsonProcessingException ex) {
            redis.delete(key(token));
            return Optional.empty();
        }
        if (expired(principal, Instant.now().getEpochSecond())) {
            evict(token, principal);
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    /** 滑动续期：刷新最后活跃时间并重写会话（Redis TTL 随剩余窗口续期）。 */
    public SessionPrincipal touch(SessionPrincipal principal) {
        SessionPrincipal refreshed = principal.refreshedAt(Instant.now().getEpochSecond());
        save(refreshed);
        return refreshed;
    }

    /** 删除会话（登出、强制下线、权限重算时账号已失效）。同时清理两级索引。 */
    public void delete(String token) {
        evict(token, readQuietly(token).orElse(null));
    }

    /** 吊销该账号除 keepToken 以外的全部会话（修改或重置密码后调用；单端登录时新登录顶掉旧会话）。 返回被吊销的令牌数。 */
    public long revokeAllForUser(SessionPrincipal principal, String keepToken) {
        Set<String> members = redis.opsForSet().members(userKey(principal));
        if (members == null || members.isEmpty()) {
            return 0;
        }
        List<String> tokens = members.stream().filter(java.util.Objects::nonNull).toList();
        long revoked = 0;
        for (String token : tokens) {
            if (token.equals(keepToken)) {
                continue;
            }
            SessionPrincipal member = readQuietly(token).orElse(null);
            evict(token, member);
            revoked++;
        }
        return revoked;
    }

    /** 本人在线会话（PLT-ORG-011「在线会话」），按登录时间升序。 */
    public List<SessionPrincipal> listForUser(SessionPrincipal principal) {
        return collect(userKey(principal));
    }

    /** 某租户的全部在线会话（PLT-AUTH-009 在线用户），按登录时间升序；索引里的死条目顺手修剪。 */
    public List<SessionPrincipal> listForTenant(long tenantId) {
        return collect(tenantKey(tenantId));
    }

    /**
     * 吊销某租户的全部在线会话（PLT-TEN-016 停用处理：在线会话立即失效）。 人工停用与宽限期满自动停用（E05-T03）都走这里；平台侧会话与租户视角会话不在 「租户 →
     * 令牌」索引里，不受影响（平台仍可进入租户视角排障）。返回被吊销的会话数。
     */
    public long revokeAllForTenant(long tenantId) {
        long revoked = 0;
        for (SessionPrincipal session : listForTenant(tenantId)) {
            delete(session.token());
            revoked++;
        }
        return revoked;
    }

    /** 会话剩余有效期（取剩余空闲窗口与剩余绝对时长中的较小值）；已过期返回零。 */
    public Duration effectiveTtl(SessionPrincipal principal) {
        return effectiveTtl(principal, Instant.now().getEpochSecond());
    }

    private Duration effectiveTtl(SessionPrincipal principal, long now) {
        long idleRemain =
                principal.idleSeconds() > 0
                        ? principal.idleSeconds() - (now - principal.lastActiveAtEpochSecond())
                        : Long.MAX_VALUE;
        long absolute = properties.getSessionAbsoluteSeconds();
        long absoluteRemain =
                absolute > 0 ? absolute - (now - principal.issuedAtEpochSecond()) : Long.MAX_VALUE;
        long remain = Math.min(idleRemain, absoluteRemain);
        if (remain <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(Math.min(remain, MAX_TTL_SECONDS));
    }

    private boolean expired(SessionPrincipal principal, long now) {
        if (principal.idleSeconds() > 0
                && now - principal.lastActiveAtEpochSecond() >= principal.idleSeconds()) {
            return true;
        }
        long absolute = properties.getSessionAbsoluteSeconds();
        return absolute > 0 && now - principal.issuedAtEpochSecond() >= absolute;
    }

    private List<SessionPrincipal> collect(String indexKey) {
        Set<String> members = redis.opsForSet().members(indexKey);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<SessionPrincipal> sessions = new ArrayList<>();
        for (String token : members) {
            if (token == null) {
                continue;
            }
            // find 顺带做空闲 / 绝对超时判定：已过期的会话不出现在列表里
            Optional<SessionPrincipal> session = find(token);
            if (session.isEmpty()) {
                redis.opsForSet().remove(indexKey, token);
                continue;
            }
            sessions.add(session.get());
        }
        sessions.sort(Comparator.comparingLong(SessionPrincipal::issuedAtEpochSecond));
        return sessions;
    }

    private Optional<SessionPrincipal> readQuietly(String token) {
        String json = redis.opsForValue().get(key(token));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SessionPrincipal.class));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    private void evict(String token, SessionPrincipal principal) {
        if (token == null) {
            return;
        }
        redis.delete(key(token));
        if (principal == null) {
            return;
        }
        redis.opsForSet().remove(userKey(principal), token);
        if (principal.hospital()) {
            redis.opsForSet().remove(tenantKey(principal.tenantId()), token);
        }
    }

    private static String key(String token) {
        return "crdp:sess:" + token;
    }

    /** 用户 → 令牌索引：端别 + 租户 + 用户唯一（医院账号与平台账号天然分开）。 */
    private static String userKey(SessionPrincipal principal) {
        String tenant = principal.tenantId() == null ? "0" : String.valueOf(principal.tenantId());
        return "crdp:sess:idx:" + principal.sessionType() + ":" + tenant + ":" + principal.userId();
    }

    /** 租户 → 令牌索引：在线用户列表与强制下线按租户检索（只存医院会话，平台端不做全员会话管理）。 */
    private static String tenantKey(long tenantId) {
        return "crdp:sess:tenant:" + tenantId;
    }
}
