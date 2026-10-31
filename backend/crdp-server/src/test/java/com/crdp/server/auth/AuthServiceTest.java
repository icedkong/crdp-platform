package com.crdp.server.auth;

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
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformAccountRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * E04-T02 / PLT-AUTH-001、PLT-AUTH-008：医院端账号密码登录成功下发不透明令牌， 会话保存在服务端，删除即吊销。 登录失败的各类原因返回同一 code 与
 * message（防探测）。锁定阈值与图形验证码属 E04-T05。 E04-T03 / PLT-AUTH-005：平台端独立登录，平台账号与医院账号分库校验、互不能在对方端登录。
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

    private final Map<String, String> redisStore = new HashMap<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PlatformMapper platformMapper = mock(PlatformMapper.class);
    private final TenantLookupCache tenantLookup = mock(TenantLookupCache.class);
    private final HospitalDirectory hospitalDirectory = mock(HospitalDirectory.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);

    private SessionStore sessionStore;
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

        SecurityProperties properties = new SecurityProperties();
        properties.setSessionTtlHours(8);
        sessionStore = new SessionStore(redis, new ObjectMapper(), properties);
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
                        routing);

        when(tenantLookup.findByCode(TENANT_CODE))
                .thenReturn(
                        Optional.of(
                                new TenantRecord(TENANT_ID, TENANT_CODE, "虚构示例医院甲", "ENABLED")));
        when(routing.hasTenant(TENANT_ID)).thenReturn(true);
        when(platformMapper.openedModules(TENANT_ID)).thenReturn(List.of("base"));
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
        when(passwordEncoder.matches(RIGHT_PASSWORD, PLATFORM_HASH)).thenReturn(true);
        when(passwordEncoder.matches(WRONG_PASSWORD, PLATFORM_HASH)).thenReturn(false);
    }

    private static HospitalAccount account(String status, String passwordHash) {
        return new HospitalAccount(
                100L,
                USERNAME,
                "演示医生",
                passwordHash,
                status,
                List.of("FOLLOWUP_DOCTOR"),
                List.of(10L, 20L),
                List.of(),
                ScopeSnapshot.allowAll());
    }

    private static PlatformAccountRow platformAccount(String status, String passwordHash) {
        PlatformAccountRow row = new PlatformAccountRow();
        row.setId(PLATFORM_ACCOUNT_ID);
        row.setUsername(PLATFORM_USERNAME);
        row.setDisplayName("平台管理员（示例）");
        row.setPasswordHash(passwordHash);
        row.setStatus(status);
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
                ApiException.class, () -> service.loginHospital(TENANT_CODE, USERNAME, password));
    }

    @Test
    void successfulLoginIssuesAnOpaqueBearerTokenStoredServerSide() {
        AuthService.LoginResult result =
                service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD);

        assertEquals("Bearer", result.tokenType());
        assertEquals(Duration.ofHours(8).toSeconds(), result.expiresInSeconds());
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
        String first = service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD).token();
        String second = service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD).token();
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
                        () -> service.loginHospital("nosuch", USERNAME, RIGHT_PASSWORD));
        ApiException wrongPassword = loginRejected(WRONG_PASSWORD);
        ApiException unknownUser =
                assertThrows(
                        ApiException.class,
                        () -> service.loginHospital(TENANT_CODE, "nobody.demo", RIGHT_PASSWORD));
        ApiException missingHash =
                assertThrows(
                        ApiException.class,
                        () -> service.loginHospital(TENANT_CODE, "ghost.demo", RIGHT_PASSWORD));

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
                                new TenantRecord(TENANT_ID, TENANT_CODE, "虚构示例医院甲", "SUSPENDED")));
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

    @Test
    void repeatedFailuresEventuallyReturn42900() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        for (int i = 0; i < 8; i++) {
            assertEquals(40101, loginRejected(WRONG_PASSWORD).code());
        }
        ApiException limited = loginRejected(WRONG_PASSWORD);
        assertEquals(429, limited.httpStatus());
        assertEquals(42900, limited.code());
    }

    @Test
    void successfulLoginClearsTheFailureCounterAndWritesAudit() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        for (int i = 0; i < 7; i++) {
            assertEquals(40101, loginRejected(WRONG_PASSWORD).code());
        }
        when(passwordEncoder.matches(RIGHT_PASSWORD, HASH)).thenReturn(true);

        service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD);

        verify(auditLogger).loginAttempt(USERNAME, true, "ok");
        ApiException again = loginRejected(WRONG_PASSWORD);
        assertEquals(40101, again.code(), "成功登录必须清零失败计数：不清零时计数已达上限，这里会是 42900");
    }

    @Test
    void logoutRevokesTheTokenOnTheServerSide() {
        String token = service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD).token();
        assertTrue(sessionStore.find(token).isPresent(), "登录后会话存在");

        service.logout(token);

        assertTrue(sessionStore.find(token).isEmpty(), "登出即服务端吊销，令牌立即失效");
    }

    @Test
    void auditLoginAttemptCarriesTheTenantContext() {
        service.loginHospital(TENANT_CODE, USERNAME, RIGHT_PASSWORD);
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
        AuthService.LoginResult result = service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD);

        assertEquals("Bearer", result.tokenType());
        assertEquals(Duration.ofHours(8).toSeconds(), result.expiresInSeconds());
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
                        ApiException.class, () -> service.loginPlatform("nobody", RIGHT_PASSWORD));
        ApiException wrongPassword =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform(PLATFORM_USERNAME, WRONG_PASSWORD));
        ApiException missingHash =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform("ghost.admin", RIGHT_PASSWORD));
        ApiException disabled =
                assertThrows(
                        ApiException.class,
                        () -> service.loginPlatform("blocked.admin", RIGHT_PASSWORD));

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
                        ApiException.class, () -> service.loginPlatform(USERNAME, RIGHT_PASSWORD));
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
                                        TENANT_CODE, PLATFORM_USERNAME, RIGHT_PASSWORD));
        assertEquals(40101, ex.code());
        assertEquals("租户、账号或密码不正确", ex.getMessage());
    }

    @Test
    void platformAndHospitalLoginCountersAreIndependent() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        for (int i = 0; i < 8; i++) {
            assertEquals(40101, loginRejected(WRONG_PASSWORD).code());
        }
        assertEquals(42900, loginRejected(WRONG_PASSWORD).code(), "医院端计数已达上限");

        // 两端失败计数按「端 + 账号」分桶：医院端锁死不影响平台端正常登录
        when(passwordEncoder.matches(RIGHT_PASSWORD, PLATFORM_HASH)).thenReturn(true);
        AuthService.LoginResult result = service.loginPlatform(PLATFORM_USERNAME, RIGHT_PASSWORD);
        assertEquals("Bearer", result.tokenType());
    }
}
