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
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E04-T02 / PLT-AUTH-008：会话是服务端状态（Redis），令牌只是查找键。 删除键即吊销，下一次请求立即失效。 E04-T05 /
 * PLT-AUTH-006、010：修改或重置密码后，该账号其他会话全部失效（revokeAllForUser），当前会话保留。 E04-T06 /
 * PLT-AUTH-008、009：空闲超时、绝对时长、滑动续期，本人 / 租户两级在线会话索引，踢线只按会话号定位。
 */
class SessionStoreTest {

    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Set<String>> sets = new HashMap<>();
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

        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        doAnswer(
                        inv -> {
                            sets.computeIfAbsent(
                                            inv.getArgument(0, String.class), k -> new HashSet<>())
                                    .add(inv.getArgument(1, String.class));
                            return 1L;
                        })
                .when(setOps)
                .add(anyString(), anyString());
        when(setOps.members(anyString()))
                .thenAnswer(
                        inv -> {
                            Set<String> members = sets.get(inv.getArgument(0, String.class));
                            return members == null ? Set.of() : Set.copyOf(members);
                        });
        doAnswer(
                        inv -> {
                            Set<String> members = sets.get(inv.getArgument(0, String.class));
                            if (members != null) {
                                members.remove(inv.getArgument(1, String.class));
                            }
                            return 1L;
                        })
                .when(setOps)
                .remove(anyString(), anyString());
        doAnswer(inv -> true)
                .when(redis)
                .expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        doAnswer(
                        inv -> {
                            store.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());

        properties = new SecurityProperties();
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
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    /** 指定生命周期的会话（E04-T06）：空闲窗口与两个时间戳可控。 */
    private static SessionPrincipal principal(
            String token, long issuedAt, long lastActiveAt, long idleSeconds) {
        SessionPrincipal base = principal(token);
        return new SessionPrincipal(
                base.token(),
                base.sessionType(),
                base.tenantId(),
                base.tenantCode(),
                base.tenantName(),
                base.userId(),
                base.username(),
                base.displayName(),
                base.roles(),
                base.menuIds(),
                base.permissionCodes(),
                base.openedModules(),
                base.permissionVersion(),
                base.scope(),
                base.mustChangePassword(),
                base.passwordExpiresAtEpochSecond(),
                "203.0.113.9",
                issuedAt,
                lastActiveAt,
                idleSeconds);
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
    void lifecycleFieldsRoundTripThroughTheStore() {
        long now = now();
        SessionPrincipal expected = principal("token-1", now - 3_600L, now - 60L, 1800L);
        sessionStore.save(expected);
        var found = sessionStore.find("token-1");
        assertTrue(found.isPresent());
        assertEquals("203.0.113.9", found.get().ip(), "登录 IP 随会话保存");
        assertEquals(now - 3_600L, found.get().issuedAtEpochSecond(), "签发时间随会话保存");
        assertEquals(now - 60L, found.get().lastActiveAtEpochSecond(), "最后活跃时间随会话保存");
        assertEquals(1800L, found.get().idleSeconds(), "空闲窗口随会话保存");
        assertEquals(
                SessionPrincipal.sessionIdOf("token-1"),
                found.get().sessionId(),
                "会话号由令牌单向推导，稳定不变");
    }

    @Test
    void saveWritesWithTheRemainingIdleWindow() {
        SessionPrincipal active = principal("token-1", now(), now(), 1800L);
        sessionStore.save(active);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis.opsForValue())
                .set(
                        org.mockito.ArgumentMatchers.eq("crdp:sess:token-1"),
                        anyString(),
                        ttl.capture());
        assertTrue(
                ttl.getValue().compareTo(Duration.ofSeconds(1800)) <= 0
                        && ttl.getValue().compareTo(Duration.ofMinutes(29)) > 0,
                "Redis TTL 应按剩余空闲窗口（≤30 分钟）设置，实际 " + ttl.getValue());
    }

    @Test
    void ttlIsCappedByTheRemainingAbsoluteWindow() {
        // 空闲窗口很大，但绝对时长只剩 5 分钟：TTL 取两者较小值
        long now = now();
        SessionPrincipal session = principal("token-1", now - (43200 - 300), now, 86400L);
        sessionStore.save(session);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis.opsForValue())
                .set(
                        org.mockito.ArgumentMatchers.eq("crdp:sess:token-1"),
                        anyString(),
                        ttl.capture());
        assertTrue(
                ttl.getValue().compareTo(Duration.ofSeconds(300)) <= 0,
                "TTL 不得超过剩余绝对时长（12 小时 - 已用 11 小时 55 分），实际 " + ttl.getValue());
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

    // ---- E04-T06 / PLT-AUTH-008：空闲超时、绝对时长、滑动续期 ----

    @Test
    void idleExpiredSessionIsTreatedAsLoggedOut() {
        // 空闲窗口 60 秒，最后活跃在 61 秒前：即使 Redis 键还在也按失效处理
        long now = now();
        sessionStore.save(principal("token-1", now - 120, now - 61, 60L));
        assertTrue(sessionStore.find("token-1").isEmpty(), "空闲超时的会话按未登录处理");
        assertTrue(sessionStore.listForUser(principal("token-1")).isEmpty(), "空闲超时的会话不出现在在线列表");
    }

    @Test
    void absoluteExpiredSessionIsTreatedAsLoggedOut() {
        long now = now();
        // 绝对时长 12 小时，签发于 13 小时前；最后活跃刚刚发生（未空闲超时）
        sessionStore.save(principal("token-1", now - 46800, now, 46800L));
        assertTrue(sessionStore.find("token-1").isEmpty(), "绝对会话时长到点后必须重新登录");
    }

    @Test
    void touchExtendsTheIdleWindow() {
        long now = now();
        SessionPrincipal session = principal("token-1", now - 1700, now - 1700, 1800L);
        sessionStore.save(session);
        SessionPrincipal touched = sessionStore.touch(session);
        assertTrue(sessionStore.find("token-1").isPresent(), "续期后的会话仍可取回");
        assertTrue(
                touched.lastActiveAtEpochSecond() >= session.lastActiveAtEpochSecond(),
                "滑动续期应推进最后活跃时间");
        assertTrue(touched.issuedAtEpochSecond() == session.issuedAtEpochSecond(), "绝对时长锚点不随续期变化");
    }

    // ---- E04-T05：修改密码后其他会话全部失效（PLT-AUTH-006、010） ----

    @Test
    void revokeAllForUserKillsOtherSessionsButKeepsTheCurrentOne() {
        sessionStore.save(principal("token-a"));
        sessionStore.save(principal("token-b"));
        sessionStore.save(principal("token-c"));

        long revoked = sessionStore.revokeAllForUser(principal("token-a"), "token-a");

        assertEquals(2, revoked, "除当前会话外的两个会话被吊销");
        assertTrue(sessionStore.find("token-a").isPresent(), "当前会话保留");
        assertTrue(sessionStore.find("token-b").isEmpty(), "其他会话失效");
        assertTrue(sessionStore.find("token-c").isEmpty(), "其他会话失效");
    }

    @Test
    void revokeIsScopedToTheSameUserBucket() {
        sessionStore.save(principal("token-a"));
        // 另一个用户的会话不在同一索引里（端别 + 租户 + 用户分桶）
        SessionPrincipal other =
                new SessionPrincipal(
                        "token-x",
                        "HOSPITAL",
                        7L,
                        "demoa",
                        "虚构示例医院甲",
                        200L,
                        "nurse.demo",
                        "演示护士",
                        List.of("TENANT_ADMIN"),
                        List.of(10L),
                        List.of("tenant:user:view"),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        sessionStore.save(other);

        assertEquals(1, sessionStore.revokeAllForUser(other, "token-y"));

        assertTrue(sessionStore.find("token-a").isPresent(), "其他用户的会话不受影响");
        assertTrue(sessionStore.find("token-x").isEmpty(), "目标用户的其他会话失效");
    }

    // ---- E04-T06 / PLT-AUTH-008、009：在线会话索引 ----

    @Test
    void listForUserReturnsOwnSessionsIncludingRevokedExclusions() {
        sessionStore.save(principal("token-a"));
        sessionStore.save(principal("token-b"));
        sessionStore.delete("token-b");

        List<SessionPrincipal> sessions = sessionStore.listForUser(principal("token-a"));

        assertEquals(1, sessions.size(), "被下线的会话不再出现在本人在线列表里");
        assertEquals("token-a", sessions.get(0).token());
    }

    @Test
    void listForTenantReturnsEveryHospitalSessionOfTheTenantOnly() {
        sessionStore.save(principal("token-a"));
        sessionStore.save(principal("token-b"));
        // 另一租户的会话不进本院索引
        SessionPrincipal otherTenant =
                new SessionPrincipal(
                        "token-y",
                        "HOSPITAL",
                        8L,
                        "demob",
                        "虚构示例医院乙",
                        100L,
                        "doctor.demo",
                        "演示医生",
                        List.of("FOLLOWUP_DOCTOR"),
                        List.of(10L),
                        List.of("followup:patient:view"),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        sessionStore.save(otherTenant);
        // 平台会话也不进租户索引
        SessionPrincipal platform =
                new SessionPrincipal(
                        "token-p",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        1L,
                        "platform.admin",
                        "平台管理员（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of("platform:tenant:view"),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        sessionStore.save(platform);

        List<SessionPrincipal> sessions = sessionStore.listForTenant(7L);

        assertEquals(2, sessions.size(), "本院两个在线会话；其他租户与平台会话不在本院在线用户里");
        assertTrue(sessions.stream().allMatch(p -> p.tenantId() == 7L), "在线用户列表只含本院会话");
    }

    @Test
    void revokingASessionRemovesItFromTheTenantIndex() {
        sessionStore.save(principal("token-a"));
        sessionStore.delete("token-a");
        assertTrue(sessionStore.listForTenant(7L).isEmpty(), "下线后租户在线索引同步清理");
    }

    // ---- E04-T06 / PLT-PERM-010：权限版本落后时按新授权重建会话内容 ----

    @Test
    void withFreshGrantsKeepsTokenAndLifecycleButTakesNewPermissions() {
        long now = now();
        SessionPrincipal stale = principal("token-1", now - 600, now - 60, 1800L);
        SessionPrincipal draft =
                new SessionPrincipal(
                        "draft-token",
                        "HOSPITAL",
                        7L,
                        "demoa",
                        "虚构示例医院甲",
                        100L,
                        "doctor.demo",
                        "演示医生",
                        List.of("FOLLOWUP_DOCTOR"),
                        List.of(10L),
                        List.of("followup:patient:view", "tenant:session:view"),
                        List.of("base"),
                        2L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null,
                        "198.51.100.7",
                        now,
                        now,
                        60L);

        SessionPrincipal refreshed = stale.withFreshGrants(draft);

        assertEquals("token-1", refreshed.token(), "重算保留令牌");
        assertEquals(now - 600, refreshed.issuedAtEpochSecond(), "重算保留登录时间（绝对时长锚点）");
        assertEquals(now - 60, refreshed.lastActiveAtEpochSecond(), "重算保留最后活跃时间");
        assertEquals(1800L, refreshed.idleSeconds(), "重算保留空闲窗口");
        assertEquals("203.0.113.9", refreshed.ip(), "重算保留登录 IP");
        assertEquals(2L, refreshed.permissionVersion(), "权限版本取新值");
        assertEquals(
                List.of("followup:patient:view", "tenant:session:view"),
                refreshed.permissionCodes(),
                "权限码按新授权计算");
    }

    // ---- E20-T01 / PLT-VIEW-004：会话专属绝对时长（租户视角单次最长 2 小时） ----

    @Test
    void perSessionAbsoluteSecondsCapsTheViewSessionAtTwoHours() {
        long now = now();
        // 视角会话：签发 1 小时前、30 秒前活跃、空闲 15 分钟、专属绝对时长 2 小时（全局配置 12 小时）
        SessionPrincipal view =
                principalWithAbsolute("view-token", now - 3_600L, now - 30L, 900L, 7_200L);
        sessionStore.save(view);
        assertTrue(sessionStore.find("view-token").isPresent(), "2 小时未到、15 分钟内活跃：仍有效");
        long ttl = sessionStore.effectiveTtl(sessionStore.find("view-token").get()).toSeconds();
        assertTrue(ttl > 0 && ttl <= 7_200L - 3_600L, "剩余有效期按 2 小时上限而非全局 12 小时计算，实际 " + ttl);

        // 签发 2 小时前（未到全局 12 小时、也不空闲）：专属上限到时即失效
        SessionPrincipal expired =
                principalWithAbsolute("view-token-2", now - 7_200L, now - 30L, 900L, 7_200L);
        sessionStore.save(expired);
        assertTrue(sessionStore.find("view-token-2").isEmpty(), "单次 2 小时到时，会话自动退出（40100）");
    }

    @Test
    void zeroAbsoluteSecondsKeepsTheGlobalConfiguredLimit() {
        long now = now();
        // 普通会话（absoluteSeconds=0）：仍按部署配置的全局绝对时长（默认 12 小时）
        SessionPrincipal platform =
                principalWithAbsolute("platform-token", now - 7_200L, now - 30L, 900L, 0L);
        sessionStore.save(platform);
        assertTrue(
                sessionStore.find("platform-token").isPresent(),
                "absoluteSeconds=0 时 2 小时不封顶，按全局 12 小时");
        assertEquals(900L, sessionStore.find("platform-token").get().idleSeconds());
        assertEquals(0L, sessionStore.find("platform-token").get().absoluteSeconds());
    }

    private static SessionPrincipal principalWithAbsolute(
            String token,
            long issuedAt,
            long lastActiveAt,
            long idleSeconds,
            long absoluteSeconds) {
        SessionPrincipal base = principal(token);
        return new SessionPrincipal(
                base.token(),
                base.sessionType(),
                base.tenantId(),
                base.tenantCode(),
                base.tenantName(),
                base.userId(),
                base.username(),
                base.displayName(),
                base.roles(),
                base.menuIds(),
                base.permissionCodes(),
                base.openedModules(),
                base.permissionVersion(),
                base.scope(),
                base.mustChangePassword(),
                base.passwordExpiresAtEpochSecond(),
                "203.0.113.9",
                issuedAt,
                lastActiveAt,
                idleSeconds,
                absoluteSeconds,
                base.fieldPolicies());
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
