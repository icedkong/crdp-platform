package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.ReauthStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.TokenGenerator;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformAccountRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.time.Duration;
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
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证（两端同构）。 会话内密码再次验证通过 → 签发 5 分钟、绑定当前会话的 reauthToken； 密码错误 42200
 * 且计入登录失败桶（复用锁定：连续 5 次锁 30 分钟）；成功清零失败计数；通过 / 失败各写登录日志（action=REAUTH）。
 */
@SuppressWarnings("unchecked")
class ReauthServiceTest {

    private static final String TENANT_CODE = "demoa";
    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 100L;
    private static final String USERNAME = "doctor.demo";
    private static final String PASSWORD = "Demo@2026";
    private static final String HASH = "$argon2id$v=19$m=16384,t=2,p=1$demo";

    private static final long PLATFORM_ID = 1L;
    private static final String PLATFORM_USERNAME = "platform.admin";
    private static final String PLATFORM_HASH = "$argon2id$v=19$m=16384,t=2,p=1$platform";

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HospitalDirectory hospitalDirectory = mock(HospitalDirectory.class);
    private final PlatformMapper platformMapper = mock(PlatformMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);

    private SecurityProperties properties;
    private ReauthStore reauthStore;
    private ReauthService service;

    @BeforeEach
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

        properties = new SecurityProperties();
        reauthStore = new ReauthStore(redis, properties, new TokenGenerator());
        service =
                new ReauthService(
                        hospitalDirectory,
                        platformMapper,
                        passwordEncoder,
                        rateLimiter,
                        reauthStore,
                        auditLogger,
                        properties);
    }

    private static SessionPrincipal hospitalPrincipal() {
        return new SessionPrincipal(
                "hospital-session-token",
                "HOSPITAL",
                TENANT_ID,
                TENANT_CODE,
                "演示医院 A",
                USER_ID,
                USERNAME,
                "张医生（示例）",
                List.of("NURSE"),
                List.of(1L),
                List.of("tenant:audit:export"),
                List.of(),
                1,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private static SessionPrincipal platformPrincipal() {
        return new SessionPrincipal(
                "platform-session-token",
                "PLATFORM",
                null,
                null,
                null,
                PLATFORM_ID,
                PLATFORM_USERNAME,
                "平台演示管理员",
                List.of("PLATFORM_ADMIN"),
                List.of(1L),
                List.of("platform:audit:export"),
                List.of(),
                1,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private void accountWithHash(String hash) {
        when(hospitalDirectory.findById(USER_ID))
                .thenReturn(
                        Optional.of(
                                new HospitalAccount(
                                        USER_ID,
                                        USERNAME,
                                        "张医生（示例）",
                                        hash,
                                        "ENABLED",
                                        null,
                                        false,
                                        null,
                                        List.of("NURSE"),
                                        List.of(1L),
                                        List.of("tenant:audit:export"),
                                        ScopeSnapshot.allowAll())));
        when(passwordEncoder.matches(PASSWORD, hash)).thenReturn(true);
    }

    // ---------------------------------------------------------------- 医院端

    @Test
    void hospitalReauthWithCorrectPasswordIssuesSessionBoundToken() {
        accountWithHash(HASH);
        when(rateLimiter.remainingAttempts(anyString())).thenReturn(4);

        ReauthService.ReauthResult result = service.reauthHospital(hospitalPrincipal(), PASSWORD);

        assertEquals(300, result.expiresInSeconds(), "有效期与配置一致（5 分钟）");
        assertEquals(43, result.reauthToken().length(), "256 位随机不透明值（base64url 43 字符）");
        // 签发的令牌对当前会话立即可用；换个会话不可用（防令牌搬运）
        assertTrue(reauthStore.isValid(result.reauthToken(), hospitalPrincipal()));
        assertEquals(
                false,
                reauthStore.isValid(
                        result.reauthToken(),
                        new SessionPrincipal(
                                "another-session",
                                "HOSPITAL",
                                TENANT_ID,
                                TENANT_CODE,
                                "演示医院 A",
                                USER_ID,
                                USERNAME,
                                "张医生（示例）",
                                List.of("NURSE"),
                                List.of(1L),
                                List.of(),
                                List.of(),
                                1,
                                ScopeSnapshot.allowAll(),
                                false,
                                null)));
        verify(rateLimiter).clear("H:" + TENANT_CODE + ":" + USERNAME);
        // 通过写登录日志（action=REAUTH，PLT-AUD-002 登录事件口径）
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogger).append(entry.capture());
        assertEquals("REAUTH", entry.getValue().action());
        assertEquals("PASSWORD", entry.getValue().loginMethod());
    }

    @Test
    void wrongPasswordIs42200AndCountsIntoLoginFailureBucket() {
        accountWithHash(HASH);
        when(passwordEncoder.matches("Wrong@2026", HASH)).thenReturn(false);
        when(rateLimiter.remainingAttempts(anyString())).thenReturn(3);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reauthHospital(hospitalPrincipal(), "Wrong@2026"));

        assertEquals(42200, ex.code());
        assertEquals("密码不正确", ex.getMessage());
        assertEquals(3, ((Map<?, ?>) ex.data()).get("remainingAttempts"));
        String bucket = "H:" + TENANT_CODE + ":" + USERNAME;
        verify(rateLimiter).recordFailure(bucket);
        // 失败写登录日志（failReason=reauth-bad-password），不签发令牌
        verify(auditLogger).append(any(AuditEntry.class));
        assertEquals(0, redisStore.size(), "失败不得签发任何 reauthToken");
    }

    @Test
    void lockedAccountCannotReauthEvenWithCorrectPassword() {
        accountWithHash(HASH);
        doThrow(new ApiException(429, 42900, "尝试过于频繁，请稍后再试", Map.of("lockedSeconds", 300)))
                .when(rateLimiter)
                .check(anyString());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reauthHospital(hospitalPrincipal(), PASSWORD));

        // 复用登录锁定桶：锁定期间正确密码也进不来（42900），与登录同一口径
        assertEquals(42900, ex.code());
    }

    @Test
    void deletedAccountMidSessionIsRejectedLikeWrongPassword() {
        when(hospitalDirectory.findById(USER_ID)).thenReturn(Optional.empty());
        when(rateLimiter.remainingAttempts(anyString())).thenReturn(4);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reauthHospital(hospitalPrincipal(), PASSWORD));

        assertEquals(42200, ex.code(), "不区分账号消失与密码错误（不外露存在性）");
        verify(rateLimiter).recordFailure(anyString());
    }

    // ---------------------------------------------------------------- 平台端

    @Test
    void platformReauthIssuesTokenAndAudits() {
        PlatformAccountRow account = new PlatformAccountRow();
        account.setPasswordHash(PLATFORM_HASH);
        when(platformMapper.findAccount(PLATFORM_USERNAME)).thenReturn(account);
        when(passwordEncoder.matches(PASSWORD, PLATFORM_HASH)).thenReturn(true);

        ReauthService.ReauthResult result = service.reauthPlatform(platformPrincipal(), PASSWORD);

        assertEquals(300, result.expiresInSeconds());
        assertTrue(reauthStore.isValid(result.reauthToken(), platformPrincipal()));
        verify(rateLimiter).clear("P:" + PLATFORM_USERNAME);
        verify(auditLogger).append(any(AuditEntry.class));
    }

    @Test
    void platformWrongPasswordIs42200AndCounts() {
        PlatformAccountRow account = new PlatformAccountRow();
        account.setPasswordHash(PLATFORM_HASH);
        when(platformMapper.findAccount(PLATFORM_USERNAME)).thenReturn(account);
        when(passwordEncoder.matches("Wrong@2026", PLATFORM_HASH)).thenReturn(false);
        when(rateLimiter.remainingAttempts(anyString())).thenReturn(4);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.reauthPlatform(platformPrincipal(), "Wrong@2026"));

        assertEquals(42200, ex.code());
        verify(rateLimiter).recordFailure("P:" + PLATFORM_USERNAME);
    }
}
