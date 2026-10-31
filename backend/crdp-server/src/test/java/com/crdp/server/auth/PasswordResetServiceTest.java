package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.SmsCodeStore;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.tenant.TenantRecord;
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
 * E04-T07 / PLT-AUTH-011：忘记密码（医院端）。 绑定手机号收验证码 → 验证通过后重置； 防探测（未知租户 / 未知账号 / 未绑定手机号与验证码错误同口径 40102）；
 * 重置验证码与登录验证码分命名空间（登录码不能重置密码）； 成功后全部会话失效、清失败计数、写审计（不落新旧口令）。
 */
class PasswordResetServiceTest {

    private static final String TENANT_CODE = "demoa";
    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 100L;
    private static final String USERNAME = "doctor.demo";
    private static final String OLD_PASSWORD = "Old@2026";
    private static final String NEW_PASSWORD = "New@2027x";
    private static final String OLD_HASH = "$argon2id$v=19$m=16384,t=2,p=1$old";
    private static final String NEW_HASH = "$argon2id$v=19$m=16384,t=2,p=1$new";
    private static final String HISTORY_HASH = "$argon2id$v=19$m=16384,t=2,p=1$hist";
    private static final String PHONE = "13900000001";
    private static final String PHONE_CIPHER = "cipher-h:13900000001";

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TenantLookupCache tenantLookup = mock(TenantLookupCache.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final HospitalDirectory hospitalDirectory = mock(HospitalDirectory.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final Map<String, String> sentCodes = new HashMap<>();
    private final SmsCodeSender smsCodeSender = mock(SmsCodeSender.class);

    private SecurityProperties properties;
    private SessionStore sessionStore;
    private SmsCodeStore smsCodeStore;
    private PasswordResetService service;

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
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
                mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        Map<String, java.util.Set<String>> redisSets = new HashMap<>();
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
        smsCodeStore = new SmsCodeStore(redis, properties);
        doAnswer(
                        inv -> {
                            sentCodes.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            return null;
                        })
                .when(smsCodeSender)
                .sendPasswordResetCode(anyString(), anyString());
        service =
                new PasswordResetService(
                        tenantLookup,
                        routing,
                        hospitalDirectory,
                        passwordEncoder,
                        sessionStore,
                        rateLimiter,
                        auditLogger,
                        properties,
                        smsCodeStore,
                        smsCodeSender,
                        cryptoService);

        when(tenantLookup.findByCode(TENANT_CODE))
                .thenReturn(
                        Optional.of(
                                new TenantRecord(TENANT_ID, TENANT_CODE, "虚构示例医院甲", "ENABLED")));
        when(routing.hasTenant(TENANT_ID)).thenReturn(true);
        when(hospitalDirectory.find(USERNAME))
                .thenReturn(Optional.of(account("ENABLED", OLD_HASH, PHONE_CIPHER)));
        when(hospitalDirectory.passwordMinLengthOverride()).thenReturn(0);
        when(hospitalDirectory.passwordHistory(USER_ID, 4)).thenReturn(List.of(HISTORY_HASH));
        when(cryptoService.decrypt(PHONE_CIPHER)).thenReturn(PHONE);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.matches(NEW_PASSWORD, HISTORY_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
    }

    private static HospitalAccount account(String status, String hash, String phone) {
        return new HospitalAccount(
                USER_ID,
                USERNAME,
                "演示医生",
                hash,
                status,
                phone,
                false,
                LocalDateTime.now().minusDays(1),
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L),
                List.of(),
                ScopeSnapshot.allowAll());
    }

    /** 发一次重置验证码并返回明文（模拟用户收到短信；先清冷却，mock Redis 没有 TTL）。 */
    private String sentResetCode() {
        redisStore.keySet().removeIf(key -> key.startsWith("crdp:sms:cd:"));
        service.requestHospitalReset(TENANT_CODE, USERNAME);
        return sentCodes.get(PHONE);
    }

    // ---- 第一步：发送重置验证码 ----

    @Test
    void requestSendsCodeToTheBoundPhoneOnly() {
        service.requestHospitalReset(TENANT_CODE, USERNAME);

        assertTrue(
                sentCodes.get(PHONE) != null && sentCodes.get(PHONE).matches("\\d{6}"),
                "验证码为 6 位数字并发到绑定手机号");
        verify(smsCodeSender).sendPasswordResetCode(eq(PHONE), anyString());
    }

    @Test
    void requestIsSilentForUnknownTenantUnknownUserDisabledOrUnboundPhone() {
        when(tenantLookup.findByCode("nosuch")).thenReturn(Optional.empty());
        when(hospitalDirectory.find("ghost.demo")).thenReturn(Optional.empty());
        when(hospitalDirectory.find("off.demo"))
                .thenReturn(Optional.of(account("DISABLED", OLD_HASH, PHONE_CIPHER)));
        when(hospitalDirectory.find("nophone.demo"))
                .thenReturn(Optional.of(account("ENABLED", OLD_HASH, null)));

        assertDoesNotThrow(() -> service.requestHospitalReset("nosuch", USERNAME));
        assertDoesNotThrow(() -> service.requestHospitalReset(TENANT_CODE, "ghost.demo"));
        assertDoesNotThrow(() -> service.requestHospitalReset(TENANT_CODE, "off.demo"));
        assertDoesNotThrow(() -> service.requestHospitalReset(TENANT_CODE, "nophone.demo"));

        assertTrue(sentCodes.isEmpty(), "以上情形一律不触达短信通道，也不给调用方可区分的返回");
    }

    // ---- 第二步：验证并重置 ----

    @Test
    void resetWritesNewHashHistoryAndAudits() {
        service.resetHospitalPassword(TENANT_CODE, USERNAME, sentResetCode(), NEW_PASSWORD);

        verify(hospitalDirectory)
                .changePassword(eq(USER_ID), eq(NEW_HASH), any(LocalDateTime.class));
        verify(hospitalDirectory).recordPasswordHistory(USER_ID, OLD_HASH);
        verify(rateLimiter).clear("HR:demoa:doctor.demo");

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).record(event.capture());
        assertEquals("USER_PASSWORD_RESET", event.getValue().eventType());
        assertEquals("sys_user:doctor.demo", event.getValue().target());
        assertEquals(USER_ID, event.getValue().actorId());
        assertTrue(
                event.getValue().detail().contains("会话"),
                "审计说明会话已全部失效，实际：" + event.getValue().detail());
        assertTrue(
                !event.getValue().detail().contains(OLD_PASSWORD)
                        && !event.getValue().detail().contains(NEW_PASSWORD)
                        && !event.getValue().detail().contains(OLD_HASH)
                        && !event.getValue().detail().contains(NEW_HASH),
                "审计不落新旧口令与哈希");
    }

    @Test
    void resetRevokesEverySessionOfTheAccount() {
        // 重置是免登录流程：该账号现有会话（含别处登录的）必须全部失效，没有「当前会话」可保留
        sessionStore.save(hospitalSession("token-1"));
        sessionStore.save(hospitalSession("token-2"));

        service.resetHospitalPassword(TENANT_CODE, USERNAME, sentResetCode(), NEW_PASSWORD);

        assertTrue(sessionStore.find("token-1").isEmpty(), "会话 1 失效");
        assertTrue(sessionStore.find("token-2").isEmpty(), "会话 2 失效");
    }

    @Test
    void wrongCodeIsRejected40102AndNothingIsWritten() {
        String code = sentResetCode();

        ApiException wrong =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, USERNAME, "000000", NEW_PASSWORD));

        assertEquals(401, wrong.httpStatus());
        assertEquals(40102, wrong.code());
        assertEquals("验证码错误或已过期", wrong.getMessage());
        verify(hospitalDirectory, never())
                .changePassword(any(Long.class).longValue(), anyString(), any(LocalDateTime.class));
        verify(auditLogger, never()).record(any(AuditEvent.class));
        // 被拒绝的重置尝试也落登录日志（AGENTS.md 第 5 节：被拒绝的操作写审计）
        verify(auditLogger).loginAttempt(eq(USERNAME), eq(false), anyString());
    }

    @Test
    void unknownAccountIsRejectedWithTheSameCodeAsWrongCode() {
        when(hospitalDirectory.find("ghost.demo")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, "ghost.demo", "123456", NEW_PASSWORD));

        assertEquals(40102, ex.code());
        assertEquals("验证码错误或已过期", ex.getMessage(), "未知账号与验证码错误同口径（防探测）");
    }

    @Test
    void disabledAccountOrUnboundPhoneIsRejectedTheSameWay() {
        when(hospitalDirectory.find("off.demo"))
                .thenReturn(Optional.of(account("DISABLED", OLD_HASH, PHONE_CIPHER)));
        when(hospitalDirectory.find("nophone.demo"))
                .thenReturn(Optional.of(account("ENABLED", OLD_HASH, null)));

        assertEquals(
                40102,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.resetHospitalPassword(
                                                TENANT_CODE, "off.demo", "123456", NEW_PASSWORD))
                        .code());
        assertEquals(
                40102,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.resetHospitalPassword(
                                                TENANT_CODE,
                                                "nophone.demo",
                                                "123456",
                                                NEW_PASSWORD))
                        .code());
    }

    @Test
    void unknownTenantIsRejectedWithoutRevealingExistence() {
        when(tenantLookup.findByCode("nosuch")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        "nosuch", USERNAME, "123456", NEW_PASSWORD));

        assertEquals(40102, ex.code());
        verify(auditLogger).loginAttempt(eq(USERNAME), eq(false), anyString());
    }

    @Test
    void loginPurposeCodeCannotResetPassword() {
        // 登录验证码（login: 命名空间）不能用于重置密码：钓鱼拿到的登录码不能接管账号
        redisStore.keySet().removeIf(key -> key.startsWith("crdp:sms:cd:"));
        String loginCode = smsCodeStore.issue("login:" + TENANT_CODE + ":" + PHONE, PHONE);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, USERNAME, loginCode, NEW_PASSWORD));

        assertEquals(40102, ex.code());
        verify(hospitalDirectory, never())
                .changePassword(any(Long.class).longValue(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void resetCodeIsConsumedAfterSuccess() {
        String code = sentResetCode();
        service.resetHospitalPassword(TENANT_CODE, USERNAME, code, NEW_PASSWORD);

        ApiException replay =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, USERNAME, code, "Again@2027x"));

        assertEquals(40102, replay.code(), "验证码一次一用，重放被拒");
    }

    @Test
    void weakPasswordIsRejected42200WithAllViolations() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, USERNAME, sentResetCode(), "abc123"));

        assertEquals(422, ex.httpStatus());
        assertEquals(42200, ex.code());
        assertTrue(
                ex.getMessage().contains("长度至少 8 位") && ex.getMessage().contains("至少 3 类"),
                "列明全部不满足项，实际：" + ex.getMessage());
        verify(hospitalDirectory, never())
                .changePassword(any(Long.class).longValue(), anyString(), any(LocalDateTime.class));

        // 包含账号也要报
        ApiException containsAccount =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, USERNAME, sentResetCode(), "xDoctor.Demo26"));
        assertTrue(
                containsAccount.getMessage().contains("不能包含账号"),
                "实际：" + containsAccount.getMessage());
    }

    @Test
    void tenantMinLengthTighteningAppliesToReset() {
        when(hospitalDirectory.passwordMinLengthOverride()).thenReturn(12);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resetHospitalPassword(
                                        TENANT_CODE, USERNAME, sentResetCode(), "Ab@1234567z"));

        assertEquals(42200, ex.code());
        assertTrue(ex.getMessage().contains("长度至少 12 位"), "实际：" + ex.getMessage());
    }

    @Test
    void reusingCurrentOrRecentPasswordIsRejected42200() {
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches("Hist@2026", HISTORY_HASH)).thenReturn(true);

        assertEquals(
                42200,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.resetHospitalPassword(
                                                TENANT_CODE,
                                                USERNAME,
                                                sentResetCode(),
                                                OLD_PASSWORD))
                        .code());
        assertEquals(
                42200,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.resetHospitalPassword(
                                                TENANT_CODE,
                                                USERNAME,
                                                sentResetCode(),
                                                "Hist@2026"))
                        .code());
    }

    /** 医院端会话（登录态的用户在别处的会话）。 */
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
                        false,
                        null);
        sessionStore.save(principal);
        return principal;
    }
}
