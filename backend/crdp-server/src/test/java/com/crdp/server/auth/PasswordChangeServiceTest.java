package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * E04-T05 / PLT-AUTH-006、010：自助改密（两端同构）。 校验顺序：原密码 → 策略（平台下限 + 租户加严最小长度）→ 历史密码（最近 5 次）；
 * 通过后清强制改密标记、刷新会话、其他会话全部失效、清登录失败计数、写审计（不落新旧口令）。
 */
class PasswordChangeServiceTest {

    private static final String TENANT_CODE = "demoa";
    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 100L;
    private static final String USERNAME = "doctor.demo";
    private static final String OLD_PASSWORD = "Old@2026";
    private static final String NEW_PASSWORD = "New@2027x";
    private static final String OLD_HASH = "$argon2id$v=19$m=16384,t=2,p=1$old";
    private static final String NEW_HASH = "$argon2id$v=19$m=16384,t=2,p=1$new";
    private static final String HISTORY_HASH = "$argon2id$v=19$m=16384,t=2,p=1$hist";

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HospitalDirectory hospitalDirectory = mock(HospitalDirectory.class);
    private final PlatformMapper platformMapper = mock(PlatformMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);

    private SecurityProperties properties;
    private SessionStore sessionStore;
    private PasswordChangeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
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
        doAnswer(
                        inv -> {
                            redisStore.remove(inv.getArgument(0, String.class));
                            return true;
                        })
                .when(redis)
                .delete(anyString());
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
                mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        java.util.Map<String, java.util.Set<String>> redisSets = new HashMap<>();
        doAnswer(
                        inv -> {
                            redisSets
                                    .computeIfAbsent(
                                            inv.getArgument(0, String.class),
                                            k -> new java.util.HashSet<>())
                                    .add(inv.getArgument(1, String.class));
                            return 1L;
                        })
                .when(setOps)
                .add(anyString(), anyString());
        when(setOps.members(anyString()))
                .thenAnswer(
                        inv -> {
                            java.util.Set<String> members =
                                    redisSets.get(inv.getArgument(0, String.class));
                            return members == null
                                    ? java.util.Set.of()
                                    : java.util.Set.copyOf(members);
                        });
        doAnswer(
                        inv -> {
                            java.util.Set<String> members =
                                    redisSets.get(inv.getArgument(0, String.class));
                            if (members != null) {
                                members.remove(inv.getArgument(1, String.class));
                            }
                            return 1L;
                        })
                .when(setOps)
                .remove(anyString(), anyString());
        properties = new SecurityProperties();
        sessionStore = new SessionStore(redis, new ObjectMapper(), properties);
        service =
                new PasswordChangeService(
                        hospitalDirectory,
                        platformMapper,
                        passwordEncoder,
                        sessionStore,
                        rateLimiter,
                        auditLogger,
                        properties);

        when(hospitalDirectory.findById(USER_ID))
                .thenReturn(Optional.of(hospitalAccount(OLD_HASH)));
        when(hospitalDirectory.passwordMinLengthOverride()).thenReturn(0);
        when(hospitalDirectory.passwordHistory(USER_ID, 4)).thenReturn(List.of(HISTORY_HASH));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.matches(NEW_PASSWORD, HISTORY_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
    }

    private static HospitalAccount hospitalAccount(String passwordHash) {
        return new HospitalAccount(
                USER_ID,
                USERNAME,
                "演示医生",
                passwordHash,
                "ENABLED",
                null,
                true,
                LocalDateTime.now().minusDays(1),
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L),
                List.of(),
                ScopeSnapshot.allowAll());
    }

    /** 医院端会话（强制改密中）：当前令牌在会话存储里。 */
    private SessionPrincipal hospitalSession(String token) {
        SessionPrincipal principal =
                new SessionPrincipal(
                        token,
                        "HOSPITAL",
                        TENANT_ID,
                        TENANT_CODE,
                        "虚构示例医院甲",
                        USER_ID,
                        USERNAME,
                        "演示医生",
                        List.of("FOLLOWUP_DOCTOR"),
                        List.of(10L),
                        List.of(),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        true,
                        null);
        sessionStore.save(principal);
        return principal;
    }

    @Test
    void hospitalChangeUpdatesHashClearsTheFlagAndRefreshesTheSession() {
        SessionPrincipal principal = hospitalSession("token-1");

        service.changeHospital(principal, OLD_PASSWORD, NEW_PASSWORD);

        verify(hospitalDirectory)
                .changePassword(eq(USER_ID), eq(NEW_HASH), any(LocalDateTime.class));
        verify(hospitalDirectory).recordPasswordHistory(USER_ID, OLD_HASH);
        SessionPrincipal refreshed = sessionStore.find("token-1").orElseThrow();
        assertFalse(refreshed.mustChangePassword(), "改密成功后当前会话解除强制改密标记");
        assertTrue(
                refreshed.passwordExpiresAtEpochSecond() != null
                        && refreshed.passwordExpiresAtEpochSecond()
                                > java.time.Instant.now().getEpochSecond(),
                "会话刷新出新的密码到期时间（90 天）");
        verify(rateLimiter).clear("H:demoa:doctor.demo");
    }

    @Test
    void hospitalChangeRevokesOtherSessionsButKeepsTheCurrentOne() {
        SessionPrincipal principal = hospitalSession("token-1");
        // 同一账号的另一个会话（改密后必须全部失效，规格：修改密码后其他会话失效）
        SessionPrincipal other = principal.withToken("token-2");
        sessionStore.save(other);

        service.changeHospital(principal, OLD_PASSWORD, NEW_PASSWORD);

        assertTrue(sessionStore.find("token-1").isPresent(), "当前会话保留");
        assertTrue(sessionStore.find("token-2").isEmpty(), "其他会话失效");
    }

    @Test
    void wrongOldPasswordIsRejected42200() {
        when(passwordEncoder.matches("bad-old", OLD_HASH)).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeHospital(
                                        hospitalSession("t"), "bad-old", NEW_PASSWORD));

        assertEquals(422, ex.httpStatus());
        assertEquals(42200, ex.code());
        assertEquals("原密码不正确", ex.getMessage());
        verify(hospitalDirectory, never())
                .changePassword(any(Long.class).longValue(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void policyViolationsAreReportedTogether() {
        // 7 位、一类字符：长度与复杂度两条同时报出
        ApiException shortAndSimple =
                assertThrows(
                        ApiException.class,
                        () -> service.changeHospital(hospitalSession("t"), OLD_PASSWORD, "abc123"));
        assertEquals(42200, shortAndSimple.code());
        assertTrue(
                shortAndSimple.getMessage().contains("长度至少 8 位"),
                () -> "实际：" + shortAndSimple.getMessage());
        assertTrue(
                shortAndSimple.getMessage().contains("至少 3 类"),
                () -> "实际：" + shortAndSimple.getMessage());

        // 包含账号（长度达标、两类字符）：报「不能包含账号」
        ApiException containsAccount =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeHospital(
                                        hospitalSession("t"), OLD_PASSWORD, "XyDoctor.Demo26"));
        assertTrue(
                containsAccount.getMessage().contains("不能包含账号"),
                () -> "实际：" + containsAccount.getMessage());
    }

    @Test
    void tenantCanTightenTheMinimumLengthBeyondThePlatformFloor() {
        when(hospitalDirectory.passwordMinLengthOverride()).thenReturn(12);
        // 11 位且其余合规：平台下限 8 通过，租户加严到 12 后被拒
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeHospital(
                                        hospitalSession("t"), OLD_PASSWORD, "Ab@1234567z"));

        assertEquals(42200, ex.code());
        assertTrue(ex.getMessage().contains("长度至少 12 位"), () -> "实际：" + ex.getMessage());
    }

    @Test
    void reusingTheCurrentOrRecentPasswordIsRejected() {
        // 与当前密码相同
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        ApiException current =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeHospital(
                                        hospitalSession("t"), OLD_PASSWORD, OLD_PASSWORD));
        assertEquals(42200, current.code());
        assertEquals("新密码不能与最近 5 次使用过的密码相同", current.getMessage());

        // 与历史密码相同
        when(passwordEncoder.matches("Hist@2026", HISTORY_HASH)).thenReturn(true);
        ApiException history =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeHospital(
                                        hospitalSession("t"), OLD_PASSWORD, "Hist@2026"));
        assertEquals(42200, history.code());
    }

    @Test
    void hospitalChangeWritesAuditWithoutAnyPasswordMaterial() {
        service.changeHospital(hospitalSession("token-1"), OLD_PASSWORD, NEW_PASSWORD);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).record(event.capture());
        assertEquals("USER_PASSWORD_CHANGE", event.getValue().eventType());
        assertEquals("sys_user:doctor.demo", event.getValue().target());
        assertFalse(
                event.getValue().detail().contains(OLD_PASSWORD)
                        || event.getValue().detail().contains(NEW_PASSWORD)
                        || event.getValue().detail().contains(OLD_HASH),
                "审计不落新旧口令与哈希");
    }

    // ---- 平台端（plt_account 同构） ----

    @Test
    void platformChangeUpdatesHashAndRefreshesTheSession() {
        when(platformMapper.findAccount("platform.admin")).thenReturn(platformRow(OLD_HASH));
        when(platformMapper.accountPasswordHistory(1L, 4)).thenReturn(List.of());
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        SessionPrincipal principal = platformSession("ptoken-1");

        service.changePlatform(principal, OLD_PASSWORD, NEW_PASSWORD);

        verify(platformMapper)
                .updateAccountPassword(eq(1L), eq(NEW_HASH), any(LocalDateTime.class));
        verify(platformMapper).insertAccountPasswordHistory(1L, OLD_HASH);
        assertFalse(sessionStore.find("ptoken-1").orElseThrow().mustChangePassword());
        verify(rateLimiter).clear("P:platform.admin");
    }

    @Test
    void platformChangeWithWrongOldPasswordIsRejected42200() {
        when(platformMapper.findAccount("platform.admin")).thenReturn(platformRow(OLD_HASH));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changePlatform(
                                        platformSession("pt"), "bad-old", NEW_PASSWORD));

        assertEquals(42200, ex.code());
        assertEquals("原密码不正确", ex.getMessage());
    }

    private static com.crdp.platform.infra.mapper.PlatformAccountRow platformRow(String hash) {
        com.crdp.platform.infra.mapper.PlatformAccountRow row =
                new com.crdp.platform.infra.mapper.PlatformAccountRow();
        row.setId(1L);
        row.setUsername("platform.admin");
        row.setDisplayName("平台管理员（示例）");
        row.setPasswordHash(hash);
        row.setStatus("ENABLED");
        row.setMustChangePassword(true);
        row.setPasswordUpdatedAt(LocalDateTime.now().minusDays(1));
        return row;
    }

    private SessionPrincipal platformSession(String token) {
        SessionPrincipal principal =
                new SessionPrincipal(
                        token,
                        "PLATFORM",
                        null,
                        null,
                        null,
                        1L,
                        "platform.admin",
                        "平台管理员（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of(),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        true,
                        null);
        sessionStore.save(principal);
        return principal;
    }
}
