package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.CaptchaStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.SmsCodeStore;
import com.crdp.framework.security.TwoFactorChallengeStore;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformAccountRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.server.platform.TenantScopeService;
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
 * E04-T02 / PLT-AUTH-001、PLT-AUTH-008：医院端账号密码登录成功下发不透明令牌， 会话保存在服务端，删除即吊销。 登录失败的各类原因返回同一 code 与
 * message（防探测）。 E04-T03 / PLT-AUTH-005：平台端独立登录，平台账号与医院账号分库校验、互不能在对方端登录。 E04-T05 /
 * PLT-AUTH-006、007、010：连续失败 3 次触发图形验证码、5 次锁定（42900 带剩余秒数）； 初始 / 重置 / 到期密码的会话带
 * mustChangePassword，双因素第二步换发令牌时保留该标记。
 */
class AuthServiceTest {

    private static final String TENANT_CODE = "demoa";
    private static final long TENANT_ID = 7L;
    private static final String USERNAME = "doctor.demo";
    private static final String HASH = "$argon2id$v=19$m=16384,t=2,p=1$demo";
    private static final String RIGHT_PASSWORD = "Demo@2026";
    private static final String WRONG_PASSWORD = "Wrong@2026";

    private static final String PLATFORM_USERNAME = "platform.admin";
    private static final long PLATFORM_ACCOUNT_ID = 1L;
    private static final String PLATFORM_HASH = "$argon2id$v=19$m=16384,t=2,p=1$platform";

    // E04-T04：演示手机号（虚构 1390000xxxx 段）与加密密文/盲索引的桩值
    private static final String PHONE = "13900000001";
    private static final String PHONE_CIPHER = "cipher-h:13900000001";
    private static final String PHONE_IDX = "idx-h:13900000001";
    private static final String PLATFORM_PHONE = "13900000003";
    private static final String PLATFORM_PHONE_CIPHER = "cipher-p:13900000003";

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PlatformMapper platformMapper = mock(PlatformMapper.class);
    private final TenantLookupCache tenantLookup = mock(TenantLookupCache.class);
    private final HospitalDirectory hospitalDirectory = mock(HospitalDirectory.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final CaptchaStore captchaStore = mock(CaptchaStore.class);
    private final java.util.Map<String, String> sentCodes = new java.util.HashMap<>();
    private final SmsCodeSender smsCodeSender = mock(SmsCodeSender.class);
    private final TenantScopeService scopeService = mock(TenantScopeService.class);

    private SessionStore sessionStore;
    private SecurityProperties properties;
    private TwoFactorChallengeStore challengeStore;
    private AuthService service;

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
                .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        when(ops.getAndDelete(anyString()))
                .thenAnswer(inv -> redisStore.remove(inv.getArgument(0, String.class)));
        when(ops.increment(anyString()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            long next = Long.parseLong(redisStore.getOrDefault(key, "0")) + 1;
                            redisStore.put(key, String.valueOf(next));
                            return next;
                        });
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
        // 既有平台端用例按「双因素关闭」的配置走（E09-T03 短信通道接入前 local/dev 的演示配置）；
        // 双因素开启的行为在下方 E04-T04 专组用例里显式打开覆盖。
        properties.setPlatformTwoFactorEnabled(false);
        sessionStore = new SessionStore(redis, new ObjectMapper(), properties);
        challengeStore =
                new TwoFactorChallengeStore(
                        redis,
                        new ObjectMapper(),
                        properties,
                        new com.crdp.framework.security.TokenGenerator());
        SmsCodeStore smsCodeStore = new SmsCodeStore(redis, properties);
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            sentCodes.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            return null;
                        })
                .when(smsCodeSender)
                .sendLoginCode(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        // 图形验证码桩：与真实 CaptchaStore 同口径——缺参即 40102，非空即通过
        doAnswer(
                        inv -> {
                            if (inv.getArgument(0, String.class) == null
                                    || inv.getArgument(1, String.class) == null) {
                                throw new ApiException(401, 40102, "验证码错误或已过期");
                            }
                            return null;
                        })
                .when(captchaStore)
                .verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        service =
                new AuthService(
                        platformMapper,
                        tenantLookup,
                        hospitalDirectory,
                        passwordEncoder,
                        sessionStore,
                        new com.crdp.framework.security.TokenGenerator(),
                        new com.crdp.framework.security.LoginRateLimiter(redis, properties),
                        auditLogger,
                        routing,
                        smsCodeStore,
                        challengeStore,
                        smsCodeSender,
                        cryptoService,
                        properties,
                        captchaStore,
                        new com.crdp.framework.security.PermissionVersionStore(redis),
                        scopeService,
                        new EffectivePermissionService(platformMapper, scopeService));

        when(tenantLookup.findByCode(TENANT_CODE))
                .thenReturn(
                        Optional.of(
                                new TenantRecord(
                                        TENANT_ID, TENANT_CODE, "虚构示例医院甲", "ENABLED", false)));
        when(routing.hasTenant(TENANT_ID)).thenReturn(true);
        when(platformMapper.openedModules(TENANT_ID)).thenReturn(List.of("base"));
        // E05-T07 / PLT-SCOPE：可分配范围交集（登录草稿会用；按需在用例里改写返回值）
        when(scopeService.assignableMenuIds(TENANT_ID))
                .thenAnswer(inv -> new java.util.HashSet<>(java.util.List.of(10L, 21L)));
        when(platformMapper.listMenus("HOSPITAL")).thenReturn(List.of());
        when(hospitalDirectory.find(USERNAME)).thenReturn(Optional.of(account("ENABLED", HASH)));
        when(passwordEncoder.matches(RIGHT_PASSWORD, HASH)).thenReturn(true);
        when(passwordEncoder.matches(WRONG_PASSWORD, HASH)).thenReturn(false);

        when(platformMapper.findAccount(PLATFORM_USERNAME))
                .thenReturn(platformAccount("ENABLED", PLATFORM_HASH));
        when(platformMapper.accountRoles(PLATFORM_ACCOUNT_ID))
                .thenReturn(List.of("PLATFORM_ADMIN"));
        when(platformMapper.accountMenuIds(PLATFORM_ACCOUNT_ID)).thenReturn(List.of(50L));
        when(platformMapper.listMenus("PLATFORM"))
                .thenReturn(List.of(platformMenu(50L, "platform:tenant:view", "base")));
        // E06-T06：平台端有效权限的模块全集来自 plt_module 登记（不再硬编码 base/followup）
        when(platformMapper.listModuleCodes()).thenReturn(List.of("base", "followup"));
        when(passwordEncoder.matches(RIGHT_PASSWORD, PLATFORM_HASH)).thenReturn(true);
        when(passwordEncoder.matches(WRONG_PASSWORD, PLATFORM_HASH)).thenReturn(false);

        // E04-T04：加解密与盲索引桩（真实实现在 EnvelopeCryptoService，另行覆盖）
        when(cryptoService.blindIndex("sys-user-phone", PHONE)).thenReturn(PHONE_IDX);
        when(cryptoService.decrypt(PHONE_CIPHER)).thenReturn(PHONE);
        when(cryptoService.decrypt(PLATFORM_PHONE_CIPHER)).thenReturn(PLATFORM_PHONE);
        when(hospitalDirectory.findByPhoneIdx(PHONE_IDX))
                .thenReturn(Optional.of(account("ENABLED", HASH)));
        when(hospitalDirectory.twoFactorRoles()).thenReturn(List.of());
    }

    private static HospitalAccount account(String status, String passwordHash) {
        return account(status, passwordHash, PHONE_CIPHER);
    }

    private static HospitalAccount account(String status, String passwordHash, String phone) {
        return account(status, passwordHash, phone, false, LocalDateTime.now().minusDays(1));
    }

    /** E04-T05：可指定强制改密标记与密码最后修改时间（有效期 90 天从此起算）。 */
    private static HospitalAccount account(
            String status,
            String passwordHash,
            String phone,
            boolean mustChangePassword,
            LocalDateTime passwordUpdatedAt) {
        return new HospitalAccount(
                100L,
                USERNAME,
                "演示医生",
                passwordHash,
                status,
                phone,
                mustChangePassword,
                passwordUpdatedAt,
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L, 20L),
                List.of(),
                ScopeSnapshot.allowAll());
    }

    private static PlatformAccountRow platformAccount(String status, String passwordHash) {
        return platformAccount(status, passwordHash, PLATFORM_PHONE_CIPHER);
    }

    private static PlatformAccountRow platformAccount(
            String status, String passwordHash, String phone) {
        return platformAccount(
                status, passwordHash, phone, false, LocalDateTime.now().minusDays(1));
    }

    private static PlatformAccountRow platformAccount(
            String status,
            String passwordHash,
            String phone,
            boolean mustChangePassword,
            LocalDateTime passwordUpdatedAt) {
        PlatformAccountRow row = new PlatformAccountRow();
        row.setId(PLATFORM_ACCOUNT_ID);
        row.setUsername(PLATFORM_USERNAME);
        row.setDisplayName("平台管理员（示例）");
        row.setPasswordHash(passwordHash);
        row.setStatus(status);
        row.setPhone(phone);
        row.setMustChangePassword(mustChangePassword);
        row.setPasswordUpdatedAt(passwordUpdatedAt);
        return row;
    }

    private static MenuRow platformMenu(long id, String permCode, String moduleCode) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setSide("PLATFORM");
        row.setType("MENU");
        row.setModuleCode(moduleCode);
        row.setTitle("租户管理（示例）");
        row.setPermCode(permCode);
        return row;
    }

    private ApiException loginRejected(String password) {
        return assertThrows(
                ApiException.class,
                () -> service.loginHospital(TENANT_CODE, USERNAME, password, null, null));
    }

    private static AuthService.CaptchaInput captcha() {
        return new AuthService.CaptchaInput("cap-1", "AB3D");
    }

    /** 发一次验证码并返回明文（模拟用户收到短信）。 */
    private String sentCodeAfterSend() {
        return sentCodeAfterSend(TENANT_CODE, PHONE);
    }

    private String sentCodeAfterSend(String tenantCode, String phone) {
        // 模拟 60 秒冷却到期（mock Redis 没有 TTL），否则连续用例会撞 42900
        redisStore.keySet().removeIf(key -> key.startsWith("crdp:sms:cd:"));
        service.sendHospitalLoginSms(tenantCode, phone);
        return sentCodes.get(phone);
    }

    @Test
    void successfulLoginIssuesAnOpaqueBearerTokenStoredServerSide() {
        AuthService.LoginResult result =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        assertEquals("Bearer", result.tokenType());
        // E04-T06 / PLT-AUTH-008：令牌有效期即空闲超时窗口（医院端 30 分钟，滑动续期），绝对时长 12 小时；
        // 签发与取剩余窗口之间可能跨秒，允许 1 秒内的误差
        assertTrue(
                result.expiresInSeconds() > Duration.ofMinutes(30).toSeconds() - 10
                        && result.expiresInSeconds() <= Duration.ofMinutes(30).toSeconds(),
                "expiresInSeconds 应为剩余空闲窗口（约 30 分钟），实际 " + result.expiresInSeconds());
        String token = result.token();
        assertNotNull(token);
        assertTrue(!token.contains("."), "不透明令牌不是 JWT（无分段点）");
        assertEquals(43, token.length(), "256 位随机值的 base64url 长度为 43");

        Optional<SessionPrincipal> session = sessionStore.find(token);
        assertTrue(session.isPresent(), "令牌必须能查到服务端会话");
        SessionPrincipal principal = session.get();
        assertTrue(principal.hospital());
        assertEquals(TENANT_ID, principal.tenantId());
        assertEquals(TENANT_CODE, principal.tenantCode());
        assertEquals(USERNAME, principal.username());
        assertEquals(1L, principal.permissionVersion(), "令牌内带权限版本号（PLT-PERM-010 预留）");
    }

    @Test
    void everyLoginIssuesAFreshToken() {
        String first =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null).token();
        String second =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null).token();
        assertNotEquals(first, second, "两次登录下发不同令牌");
        assertTrue(sessionStore.find(first).isPresent());
        assertTrue(sessionStore.find(second).isPresent());
    }

    @Test
    void unknownTenantWrongPasswordAndUnknownUserShareTheSameRejection() {
        when(tenantLookup.findByCode("nosuch")).thenReturn(Optional.empty());
        when(hospitalDirectory.find("nobody.demo")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(RIGHT_PASSWORD, null)).thenReturn(false);
        when(hospitalDirectory.find("ghost.demo"))
                .thenReturn(Optional.of(account("ENABLED", null)));

        ApiException unknownTenant =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        "nosuch", USERNAME, RIGHT_PASSWORD, null, null));
        ApiException wrongPassword = loginRejected(WRONG_PASSWORD);
        ApiException unknownUser =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        TENANT_CODE, "nobody.demo", RIGHT_PASSWORD, null, null));
        ApiException missingHash =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        TENANT_CODE, "ghost.demo", RIGHT_PASSWORD, null, null));

        for (ApiException ex : List.of(unknownTenant, wrongPassword, unknownUser, missingHash)) {
            assertEquals(401, ex.httpStatus());
            assertEquals(40101, ex.code());
            assertEquals("租户、账号或密码不正确", ex.getMessage(), "失败原因不外露（防探测）");
        }
        verify(auditLogger, org.mockito.Mockito.times(2)).loginAttempt(USERNAME, false, "rejected");
        verify(auditLogger).loginAttempt("nobody.demo", false, "rejected");
    }

    @Test
    void disabledAccountIsRejectedTheSameWay() {
        when(hospitalDirectory.find(USERNAME)).thenReturn(Optional.of(account("DISABLED", HASH)));
        ApiException ex = loginRejected(RIGHT_PASSWORD);
        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage());
    }

    @Test
    void suspendedTenantIsRejectedTheSameWay() {
        when(tenantLookup.findByCode(TENANT_CODE))
                .thenReturn(
                        Optional.of(
                                new TenantRecord(
                                        TENANT_ID, TENANT_CODE, "虚构示例医院甲", "SUSPENDED", false)));
        ApiException ex = loginRejected(RIGHT_PASSWORD);
        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage());
    }

    @Test
    void missingTenantDatasourceReturns50002() {
        when(routing.hasTenant(TENANT_ID)).thenReturn(false);
        ApiException ex = loginRejected(RIGHT_PASSWORD);
        assertEquals(500, ex.httpStatus());
        assertEquals(50002, ex.code());
    }

    // ---- E04-T05 / PLT-AUTH-007：图形验证码与锁定 ----

    @Test
    void failuresAnnounceRemainingAttemptsAndCaptchaRequirementInData() {
        ApiException first = loginRejected(WRONG_PASSWORD);
        assertEquals(40101, first.code());
        assertEquals(
                Map.of("captchaRequired", false, "remainingAttempts", 4),
                first.data(),
                "首次失败提示剩余次数，未到验证码阈值");

        loginRejected(WRONG_PASSWORD);
        ApiException third = loginRejected(WRONG_PASSWORD);
        assertEquals(40101, third.code());
        assertEquals(
                Map.of("captchaRequired", true, "remainingAttempts", 2),
                third.data(),
                "连续失败 3 次后要求图形验证码");
    }

    @Test
    void captchaIsRequiredAfterThreeFailuresAndMissingCaptchaReturns40102() {
        loginRejected(WRONG_PASSWORD);
        loginRejected(WRONG_PASSWORD);
        loginRejected(WRONG_PASSWORD);

        // 第 4 次尝试即使口令正确，不带验证码也直接 40102（不区分口令对错，防探测）
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null));
        assertEquals(40102, missing.code());

        // 带上验证码且口令正确：登录成功并清零失败计数（未达锁定阈值 5）
        AuthService.LoginResult ok =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, captcha(), null);
        assertNotNull(ok.token(), "验证码与口令都对时正常发令牌");

        ApiException afterSuccess = loginRejected(WRONG_PASSWORD);
        assertEquals(40101, afterSuccess.code(), "成功登录清零失败计数");
    }

    @Test
    void wrongCaptchaCountsTowardTheLockout() {
        for (int i = 0; i < 3; i++) {
            assertEquals(40101, loginRejected(WRONG_PASSWORD).code());
        }
        // 进入验证码模式后：不带验证码的尝试计 40102 并继续计入失败
        for (int i = 0; i < 2; i++) {
            ApiException missing =
                    assertThrows(
                            ApiException.class,
                            () ->
                                    service.loginHospital(
                                            TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null));
            assertEquals(40102, missing.code());
        }
        // 累计 5 次失败（锁定阈值）：即使验证码与口令都对也被锁定
        ApiException locked =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        TENANT_CODE, USERNAME, RIGHT_PASSWORD, captcha(), null));
        assertEquals(42900, locked.code(), "锁定后不发令牌");
    }

    @Test
    void repeatedFailuresLockAtFiveAttemptsForThirtyMinutes() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        for (int i = 0; i < 5; i++) {
            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () ->
                                    service.loginHospital(
                                            TENANT_CODE,
                                            USERNAME,
                                            WRONG_PASSWORD,
                                            captcha(),
                                            null));
            assertEquals(40101, ex.code(), "带上图形验证码后口令错误仍是 40101");
        }
        ApiException limited =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        TENANT_CODE, USERNAME, WRONG_PASSWORD, null, null));
        assertEquals(429, limited.httpStatus());
        assertEquals(42900, limited.code());
        assertEquals("失败次数过多，账号已锁定，请 30 分钟后再试或联系管理员解锁", limited.getMessage());
        assertEquals(
                Map.of("lockedSeconds", 1800L),
                limited.data(),
                "锁定剩余秒数放进 data（mock Redis 无 TTL，按整段时长兜底）");
    }

    @Test
    void successfulLoginClearsTheFailureCounterAndWritesAudit() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        loginRejected(WRONG_PASSWORD);
        loginRejected(WRONG_PASSWORD);
        when(passwordEncoder.matches(RIGHT_PASSWORD, HASH)).thenReturn(true);

        service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        verify(auditLogger).loginAttempt(USERNAME, true, "ok");
        ApiException again = loginRejected(WRONG_PASSWORD);
        assertEquals(40101, again.code(), "成功登录必须清零失败计数：不清零时计数已达上限，这里会是 42900");
    }

    // ---- E04-T05 / PLT-AUTH-010：初始 / 重置 / 到期密码强制改密 ----

    @Test
    void flaggedAccountGetsAMustChangePasswordSession() {
        when(hospitalDirectory.find(USERNAME))
                .thenReturn(
                        Optional.of(
                                account(
                                        "ENABLED",
                                        HASH,
                                        PHONE_CIPHER,
                                        true,
                                        LocalDateTime.now().minusDays(1))));

        String token =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null).token();

        SessionPrincipal principal = sessionStore.find(token).orElseThrow();
        assertTrue(principal.mustChangePassword(), "初始/重置密码的会话带强制改密标记");
        assertNotNull(principal.passwordExpiresAtEpochSecond());
    }

    @Test
    void expiredPasswordForcesAChangeEvenWithoutTheFlag() {
        when(hospitalDirectory.find(USERNAME))
                .thenReturn(
                        Optional.of(
                                account(
                                        "ENABLED",
                                        HASH,
                                        PHONE_CIPHER,
                                        false,
                                        LocalDateTime.now().minusDays(91))));

        String token =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null).token();

        assertTrue(
                sessionStore.find(token).orElseThrow().mustChangePassword(),
                "密码超过 90 天有效期，登录即强制改密（PLT-AUTH-006）");
    }

    @Test
    void freshPasswordSessionDoesNotRequireAChange() {
        String token =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null).token();
        SessionPrincipal principal = sessionStore.find(token).orElseThrow();
        assertFalseMustChange(principal);
        long expiresAt = principal.passwordExpiresAtEpochSecond();
        assertNotNull(expiresAt, "会话带密码到期时间，me 据此做到期前 7 天提醒");
        long now = java.time.Instant.now().getEpochSecond();
        // 演示账号 passwordUpdatedAt = 昨天，到期时间 ≈ 89 天后（= 最后改密时间 + 90 天）
        assertTrue(
                expiresAt - now > 88L * 86_400 && expiresAt - now <= 90L * 86_400,
                "到期时间 = 最后改密时间 + 90 天");
    }

    private static void assertFalseMustChange(SessionPrincipal principal) {
        assertTrue(!principal.mustChangePassword(), "正常会话不带强制改密标记");
    }

    @Test
    void platformFlaggedAccountAlsoGetsAMustChangePasswordSession() {
        when(platformMapper.findAccount(PLATFORM_USERNAME))
                .thenReturn(
                        platformAccount(
                                "ENABLED",
                                PLATFORM_HASH,
                                PLATFORM_PHONE_CIPHER,
                                true,
                                LocalDateTime.now().minusDays(1)));

        String token = service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null).token();

        assertTrue(sessionStore.find(token).orElseThrow().mustChangePassword());
    }

    // ---- E04-T02 基线 ----

    @Test
    void logoutRevokesTheTokenOnTheServerSide() {
        String token =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null).token();
        assertTrue(sessionStore.find(token).isPresent(), "登录后会话存在");

        service.logout(token);

        assertTrue(sessionStore.find(token).isEmpty(), "登出即服务端吊销，令牌立即失效");
    }

    @Test
    void auditLoginAttemptCarriesTheTenantContext() {
        service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(auditLogger)
                .loginAttempt(
                        org.mockito.ArgumentMatchers.eq(USERNAME),
                        org.mockito.ArgumentMatchers.eq(true),
                        message.capture());
        assertEquals("ok", message.getValue());
    }

    // ---- E04-T03 / PLT-AUTH-005：平台端独立登录，两端账号互不能在对方端登录 ----

    @Test
    void platformLoginIssuesAnOpaquePlatformSessionWithoutTenant() {
        AuthService.LoginResult result =
                service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null);

        assertEquals("Bearer", result.tokenType());
        // E04-T06 / PLT-AUTH-008：平台端空闲窗口 15 分钟（滑动续期），绝对时长 12 小时
        assertTrue(
                result.expiresInSeconds() > Duration.ofMinutes(15).toSeconds() - 10
                        && result.expiresInSeconds() <= Duration.ofMinutes(15).toSeconds(),
                "expiresInSeconds 应为剩余空闲窗口（约 15 分钟），实际 " + result.expiresInSeconds());
        String token = result.token();
        assertNotNull(token);
        assertEquals(43, token.length(), "256 位随机值的 base64url 长度为 43");

        Optional<SessionPrincipal> session = sessionStore.find(token);
        assertTrue(session.isPresent(), "平台会话同样保存在服务端");
        SessionPrincipal principal = session.get();
        assertTrue(principal.platform(), "平台登录得到平台会话");
        assertNull(principal.tenantId(), "平台会话不携带租户");
        assertNull(principal.tenantCode());
        assertNull(principal.tenantName());
        assertEquals(PLATFORM_USERNAME, principal.username());
        assertTrue(
                principal.permissionCodes().contains("platform:tenant:view"),
                "权限码来自平台菜单 ∩ 账号菜单 ∩ 已开通模块");
        verify(auditLogger).loginAttempt(PLATFORM_USERNAME, true, "ok");
    }

    @Test
    void platformLoginFailuresShareUniform40101() {
        when(platformMapper.findAccount("nobody")).thenReturn(null);
        when(platformMapper.findAccount("ghost.admin"))
                .thenReturn(platformAccount("ENABLED", null));
        when(platformMapper.findAccount("blocked.admin"))
                .thenReturn(platformAccount("DISABLED", PLATFORM_HASH));

        ApiException unknownUser =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform("nobody", RIGHT_PASSWORD, null, null));
        ApiException wrongPassword =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform(PLATFORM_USERNAME, WRONG_PASSWORD, null, null));
        ApiException missingHash =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform("ghost.admin", RIGHT_PASSWORD, null, null));
        ApiException disabled =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform("blocked.admin", RIGHT_PASSWORD, null, null));

        for (ApiException ex : List.of(unknownUser, wrongPassword, missingHash, disabled)) {
            assertEquals(401, ex.httpStatus());
            assertEquals(40101, ex.code());
            assertEquals("租户、账号或密码不正确", ex.getMessage(), "失败原因不外露（防探测）");
        }
    }

    @Test
    void hospitalAccountCannotLoginOnThePlatformSide() {
        // 医院端账号只存在于租户库，平台库查无此账号，统一 40101
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform(USERNAME, RIGHT_PASSWORD, null, null));
        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage());
    }

    @Test
    void platformAccountCannotLoginOnTheHospitalSide() {
        // 平台账号不在任何租户库中，医院端入口统一 40101
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospital(
                                        TENANT_CODE,
                                        PLATFORM_USERNAME,
                                        RIGHT_PASSWORD,
                                        null,
                                        null));
        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage());
    }

    @Test
    void platformAndHospitalLoginCountersAreIndependent() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        for (int i = 0; i < 5; i++) {
            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () ->
                                    service.loginHospital(
                                            TENANT_CODE,
                                            USERNAME,
                                            WRONG_PASSWORD,
                                            captcha(),
                                            null));
            assertEquals(40101, ex.code(), "带上验证码后仍是口令错误统一拒绝");
        }
        assertEquals(42900, loginRejected(WRONG_PASSWORD).code(), "医院端计数已达上限");

        // 两端失败计数按「端 + 账号」分桶：医院端锁死不影响平台端正常登录
        when(passwordEncoder.matches(RIGHT_PASSWORD, PLATFORM_HASH)).thenReturn(true);
        AuthService.LoginResult result =
                service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null);
        assertEquals("Bearer", result.tokenType());
    }

    // ---- E04-T04 / PLT-AUTH-002：医院端短信验证码登录 ----

    @Test
    void smsLoginWithBoundPhoneIssuesAnOpaqueToken() {
        AuthService.LoginResult result =
                service.loginHospitalWithSms(TENANT_CODE, PHONE, sentCodeAfterSend(), null);

        assertEquals("Bearer", result.tokenType());
        assertNotNull(result.token());
        assertEquals(43, result.token().length());
        assertNull(result.challengeId(), "短信登录不叠加第二因素，直接发令牌");

        Optional<SessionPrincipal> session = sessionStore.find(result.token());
        assertTrue(session.isPresent());
        assertEquals(USERNAME, session.get().username());
        assertEquals(TENANT_CODE, session.get().tenantCode());
        verify(auditLogger).loginAttempt(USERNAME, true, "ok");
    }

    @Test
    void smsLoginSendsCodeOnlyToTheRequestedPhone() {
        service.sendHospitalLoginSms(TENANT_CODE, PHONE);

        assertTrue(sentCodes.containsKey(PHONE), "验证码只发给请求的手机号");
        assertEquals(6, sentCodes.get(PHONE).length());
    }

    @Test
    void smsLoginResendsAreBlockedByCooldown() {
        service.sendHospitalLoginSms(TENANT_CODE, PHONE);

        ApiException second =
                assertThrows(
                        ApiException.class, () -> service.sendHospitalLoginSms(TENANT_CODE, PHONE));
        assertEquals(42900, second.code(), "60 秒内不能重发");
    }

    @Test
    void smsSendForUnknownTenantReturnsSuccessWithoutTouchingTheChannel() {
        when(tenantLookup.findByCode("nosuch")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.sendHospitalLoginSms("nosuch", PHONE));
        assertTrue(sentCodes.isEmpty(), "未知租户不签发也不发送，但对外不可区分");
    }

    @Test
    void smsLoginWithWrongCodeReturns40102BeforeAnyAccountLookup() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.loginHospitalWithSms(TENANT_CODE, PHONE, "000000", null));

        assertEquals(401, ex.httpStatus());
        assertEquals(40102, ex.code());
        assertEquals("验证码错误或已过期", ex.getMessage());
        org.mockito.Mockito.verifyNoInteractions(passwordEncoder);
    }

    @Test
    void smsLoginWithValidCodeButUnboundPhoneIsRejected40101() {
        when(hospitalDirectory.findByPhoneIdx(PHONE_IDX)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospitalWithSms(
                                        TENANT_CODE, PHONE, sentCodeAfterSend(), null));

        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage(), "未绑定手机号与账号密码失败同口径");
        verify(auditLogger).loginAttempt("139****0001", false, "rejected");
    }

    @Test
    void smsLoginForUnknownTenantRejectsBecauseNoCodeExistsForThatNamespace() {
        when(tenantLookup.findByCode("nosuch")).thenReturn(Optional.empty());
        // 验证码按「租户:手机号」隔离；未知租户从不签发（发送接口也不触达），所以任何码都判 40102。
        // 不构成探测：攻击者拿不到未知租户命名空间下的有效码。
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospitalWithSms(
                                        "nosuch", PHONE, sentCodeAfterSend(), null));

        assertEquals(40102, ex.code());
    }

    @Test
    void smsLoginCodesAreScopedPerTenant() {
        when(tenantLookup.findByCode("demob"))
                .thenReturn(
                        Optional.of(new TenantRecord(8L, "demob", "虚构示例医院乙", "ENABLED", false)));
        when(routing.hasTenant(8L)).thenReturn(true);
        when(cryptoService.blindIndex("sys-user-phone", PHONE)).thenReturn("idx-of-b");
        when(hospitalDirectory.findByPhoneIdx("idx-of-b")).thenReturn(Optional.empty());
        String code = sentCodeAfterSend(TENANT_CODE, PHONE);

        ApiException crossTenant =
                assertThrows(
                        ApiException.class,
                        () -> service.loginHospitalWithSms("demob", PHONE, code, null));

        assertEquals(40102, crossTenant.code(), "A 院签发的验证码不能在 B 院用");
    }

    @Test
    void smsLoginFailureCounterLocksAtTheLimitAndClearsOnSuccess() {
        when(hospitalDirectory.findByPhoneIdx(PHONE_IDX)).thenReturn(Optional.empty());
        for (int i = 0; i < 4; i++) {
            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () ->
                                    service.loginHospitalWithSms(
                                            TENANT_CODE, PHONE, sentCodeAfterSend(), null));
            assertEquals(40101, ex.code());
        }

        // 阈值 5：4 次后仍放行，本次成功并清零失败计数
        when(hospitalDirectory.findByPhoneIdx(PHONE_IDX))
                .thenReturn(Optional.of(account("ENABLED", HASH)));
        service.loginHospitalWithSms(TENANT_CODE, PHONE, sentCodeAfterSend(), null);

        when(hospitalDirectory.findByPhoneIdx(PHONE_IDX)).thenReturn(Optional.empty());
        ApiException afterSuccess =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.loginHospitalWithSms(
                                        TENANT_CODE, PHONE, sentCodeAfterSend(), null));
        assertEquals(40101, afterSuccess.code(), "成功登录清零失败计数：再次失败仍是 40101，而不是 42900");
    }

    // ---- E04-T04 / PLT-AUTH-003：双因素（平台强制、租户按角色配置） ----

    @Test
    void platformTwoFactorForcedReturnsChallengeInsteadOfToken() {
        properties.setPlatformTwoFactorEnabled(true);

        AuthService.LoginResult result =
                service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null);

        assertNull(result.token(), "密码第一步不发令牌");
        assertNull(result.tokenType());
        assertNotNull(result.challengeId());
        assertEquals(43, result.challengeId().length(), "challengeId 为 256 位不透明随机值");
        assertEquals(List.of("SMS"), result.methods());
        assertTrue(sentCodes.containsKey(PLATFORM_PHONE), "验证码发给绑定手机号");
        assertTrue(sessionStore.find(result.challengeId()).isEmpty(), "challengeId 不是会话令牌");
    }

    @Test
    void platformTwoFactorVerifyIssuesTheSessionToken() {
        properties.setPlatformTwoFactorEnabled(true);
        String challengeId =
                service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null).challengeId();

        AuthService.LoginResult verified =
                service.verifyTwoFactor(challengeId, sentCodes.get(PLATFORM_PHONE));

        assertEquals("Bearer", verified.tokenType());
        assertNotNull(verified.token());
        Optional<SessionPrincipal> session = sessionStore.find(verified.token());
        assertTrue(session.isPresent());
        assertTrue(session.get().platform(), "平台端双因素完成得到平台会话");
        assertEquals(PLATFORM_USERNAME, session.get().username());
        verify(auditLogger).loginAttempt(PLATFORM_USERNAME, true, "2fa-ok");
    }

    @Test
    void platformTwoFactorWrongCodeKeepsTheChallengeForRetry() {
        properties.setPlatformTwoFactorEnabled(true);
        String challengeId =
                service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null).challengeId();

        ApiException wrong =
                assertThrows(
                        ApiException.class, () -> service.verifyTwoFactor(challengeId, "000000"));
        assertEquals(40102, wrong.code(), "验证码错误不销毁 challenge，可重试");

        AuthService.LoginResult retry =
                service.verifyTwoFactor(challengeId, sentCodes.get(PLATFORM_PHONE));
        assertNotNull(retry.token(), "随后用正确验证码仍可完成登录");
    }

    @Test
    void platformTwoFactorChallengeIsSingleUse() {
        properties.setPlatformTwoFactorEnabled(true);
        String challengeId =
                service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null).challengeId();
        service.verifyTwoFactor(challengeId, sentCodes.get(PLATFORM_PHONE));

        ApiException replay =
                assertThrows(
                        ApiException.class,
                        () -> service.verifyTwoFactor(challengeId, sentCodes.get(PLATFORM_PHONE)));
        assertEquals(40100, replay.code(), "challenge 一次性使用，完成后即失效");
    }

    @Test
    void platformTwoFactorUnknownChallengeReturns40100() {
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.verifyTwoFactor("no-such", "123456"));
        assertEquals(401, ex.httpStatus());
        assertEquals(40100, ex.code());
    }

    @Test
    void platformAccountWithoutPhoneCannotPassWhenTwoFactorForced() {
        properties.setPlatformTwoFactorEnabled(true);
        when(platformMapper.findAccount(PLATFORM_USERNAME))
                .thenReturn(platformAccount("ENABLED", PLATFORM_HASH, null));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD, null, null));

        assertEquals(40101, ex.code(), "未绑定手机号与凭据失败同口径，不外露原因");
    }

    @Test
    void hospitalRoleConfiguredTwoFactorChallengesPasswordLogin() {
        when(hospitalDirectory.twoFactorRoles()).thenReturn(List.of("FOLLOWUP_DOCTOR"));

        AuthService.LoginResult first =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        assertNull(first.token());
        assertNotNull(first.challengeId(), "角色命中双因素时密码第一步只发 challenge");
        assertTrue(sentCodes.containsKey(PHONE));

        AuthService.LoginResult second =
                service.verifyTwoFactor(first.challengeId(), sentCodes.get(PHONE));
        assertNotNull(second.token());
        Optional<SessionPrincipal> session = sessionStore.find(second.token());
        assertTrue(session.isPresent());
        assertTrue(session.get().hospital());
        assertEquals(TENANT_CODE, session.get().tenantCode());
        verify(auditLogger).loginAttempt(USERNAME, true, "2fa-ok");
    }

    @Test
    void twoFactorStepKeepsTheMustChangePasswordFlag() {
        // E04-T05 / PLT-AUTH-010：初始密码 + 双因素两步登录，最终会话仍带强制改密标记
        when(hospitalDirectory.twoFactorRoles()).thenReturn(List.of("FOLLOWUP_DOCTOR"));
        when(hospitalDirectory.find(USERNAME))
                .thenReturn(
                        Optional.of(
                                account(
                                        "ENABLED",
                                        HASH,
                                        PHONE_CIPHER,
                                        true,
                                        LocalDateTime.now().minusDays(1))));

        String challengeId =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null)
                        .challengeId();
        AuthService.LoginResult second = service.verifyTwoFactor(challengeId, sentCodes.get(PHONE));

        assertTrue(
                sessionStore.find(second.token()).orElseThrow().mustChangePassword(),
                "双因素第二步换发的令牌保留强制改密标记");
    }

    @Test
    void hospitalRoleConfiguredTwoFactorWithoutPhoneIsRejected() {
        when(hospitalDirectory.twoFactorRoles()).thenReturn(List.of("FOLLOWUP_DOCTOR"));
        when(hospitalDirectory.find(USERNAME))
                .thenReturn(Optional.of(account("ENABLED", HASH, null)));

        ApiException ex = loginRejected(RIGHT_PASSWORD);

        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage());
    }

    @Test
    void hospitalRolesOutsideTheConfigLoginDirectly() {
        when(hospitalDirectory.twoFactorRoles()).thenReturn(List.of("TENANT_ADMIN"));

        AuthService.LoginResult result =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        assertNotNull(result.token(), "未命中配置角色的账号不触发双因素");
        assertNull(result.challengeId());
    }

    // ---- E04-T06 / PLT-AUTH-008、PLT-PERM-010：会话生命周期、单端登录、权限版本号 ----

    @Test
    void issuedSessionCarriesIpIdleWindowAndCurrentPermissionVersion() {
        AuthService.LoginResult result =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, "203.0.113.9");

        SessionPrincipal session = sessionStore.find(result.token()).orElseThrow();
        assertEquals("203.0.113.9", session.ip(), "会话记录登录 IP（在线用户列表展示）");
        assertEquals(
                properties.getSessionIdleSeconds(),
                session.idleSeconds(),
                "未配置租户加严时用平台默认空闲窗口（30 分钟）");
        assertEquals(1L, session.permissionVersion(), "登录会话带初始权限版本号");
        assertTrue(session.issuedAtEpochSecond() > 0, "会话带签发时间（绝对时长锚点）");
    }

    @Test
    void tenantCanOnlyShortenTheIdleWindow() {
        when(hospitalDirectory.sessionIdleOverrideSeconds()).thenReturn(10 * 60);

        AuthService.LoginResult result =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);
        assertEquals(
                600L, sessionStore.find(result.token()).orElseThrow().idleSeconds(), "租户加严取更短值");

        // 租户配置比平台默认更长时仍取平台默认（只能加严）
        when(hospitalDirectory.sessionIdleOverrideSeconds()).thenReturn(8 * 3600);
        AuthService.LoginResult second =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);
        assertEquals(
                properties.getSessionIdleSeconds(),
                sessionStore.find(second.token()).orElseThrow().idleSeconds(),
                "租户不能把空闲窗口改得比平台默认更长");
    }

    @Test
    void singleLoginKicksOlderSessionsOfTheSameAccount() {
        when(hospitalDirectory.singleLoginEnabled()).thenReturn(true);

        AuthService.LoginResult first =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);
        AuthService.LoginResult second =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        assertTrue(
                sessionStore.find(first.token()).isEmpty(), "单端登录：新登录使该账号旧会话全部下线（旧令牌下次请求 40100）");
        assertTrue(sessionStore.find(second.token()).isPresent(), "新会话保留");
    }

    @Test
    void multipleSessionsAreAllowedByDefault() {
        AuthService.LoginResult first =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);
        AuthService.LoginResult second =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        assertTrue(sessionStore.find(first.token()).isPresent(), "默认并发会话策略：多端登录共存");
        assertTrue(sessionStore.find(second.token()).isPresent());
    }

    @Test
    void loginStampsTheCurrentPermissionVersion() {
        // 权限已递增过的用户（例如角色刚调整过）重新登录：会话直接带新版本，无需再重算
        redisStore.put("crdp:permver:HOSPITAL:" + TENANT_ID + ":100", "4");

        AuthService.LoginResult result =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD, null, null);

        assertEquals(
                4L,
                sessionStore.find(result.token()).orElseThrow().permissionVersion(),
                "登录会话带当前权限版本号");
    }
}
