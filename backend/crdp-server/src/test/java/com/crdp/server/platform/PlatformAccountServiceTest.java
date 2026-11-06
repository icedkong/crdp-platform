package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.PasswordPolicy;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.PlatformAccountService.AccountModel;
import com.crdp.server.platform.PlatformAccountService.AccountSaveRequest;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * E06-T04（PLT-ROLE-001）单元测试：平台账号字段校验（账号名 / 手机号 / 初始密码 / 角色绑定）、 「平台管理员至少保留 2
 * 个启用账号」的模拟校验（platform-spec §1，已确认）、临时密码策略与手机号脱敏。
 */
class PlatformAccountServiceTest {

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final SessionStore sessionStore = mock(SessionStore.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final SecurityProperties properties = new SecurityProperties();
    private final PermissionVersionStore permissionVersions = mock(PermissionVersionStore.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final TenantViewService tenantView = mock(TenantViewService.class);

    private PlatformAccountService service;

    @BeforeEach
    void setUp() {
        TenantContext.usePlatform();
        service =
                new PlatformAccountService(
                        platform,
                        passwordEncoder,
                        cryptoService,
                        sessionStore,
                        rateLimiter,
                        properties,
                        permissionVersions,
                        audit,
                        tenantView);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private static AccountSaveRequest createRequest(
            String username, String phone, String password, List<Long> roleIds) {
        return new AccountSaveRequest(username, "示例姓名", phone, password, roleIds, null);
    }

    // ---------------------------------------------------------------- 字段校验

    @Test
    void createRejectsBlankOrUppercaseUsername() {
        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        createRequest("", "13900000001", "Abcdef!1", List.of(2L))));
        assertEquals(40001, blank.code());
        assertTrue(blank.getMessage().contains("账号名必填"), blank.getMessage());

        ApiException uppercase =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        createRequest(
                                                "Admin01",
                                                "13900000001",
                                                "Abcdef!1",
                                                List.of(2L))));
        assertEquals(40001, uppercase.code());
    }

    @Test
    void createRejectsDuplicateUsername() {
        lenient()
                .when(
                        platform.queryForObject(
                                contains("FROM plt_account WHERE username"),
                                eq(Integer.class),
                                eq("platform.admin")))
                .thenReturn(1);
        ApiException duplicate =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        createRequest(
                                                "platform.admin",
                                                "13900000001",
                                                "Abcdef!1",
                                                List.of(2L))));
        assertEquals(40901, duplicate.code());
    }

    @Test
    void phoneIsMandatoryAndValidated() {
        ApiException missing =
                assertThrows(
                        ApiException.class, () -> PlatformAccountService.requirePhone(null, null));
        assertEquals(40001, missing.code());
        assertTrue(missing.getMessage().contains("双因素"), missing.getMessage());

        ApiException blank =
                assertThrows(
                        ApiException.class, () -> PlatformAccountService.requirePhone("  ", null));
        assertEquals(40001, blank.code());

        ApiException badFormat =
                assertThrows(
                        ApiException.class,
                        () -> PlatformAccountService.requirePhone("123456", null));
        assertEquals(40001, badFormat.code());

        assertEquals("13900001234", PlatformAccountService.requirePhone(" 13900001234 ", null));
    }

    @Test
    void initialPasswordFollowsPolicy() {
        ApiException weak =
                assertThrows(
                        ApiException.class,
                        () -> service.validateInitialPassword("short", "ops001"));
        assertEquals(42200, weak.code());

        ApiException containsUsername =
                assertThrows(
                        ApiException.class,
                        () -> service.validateInitialPassword("Xops001!x", "ops001"));
        assertEquals(42200, containsUsername.code());

        service.validateInitialPassword("Str0ng!Pass", "ops001");
    }

    @Test
    void rolesAreRequired() {
        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.validateRoles(
                                        new AccountModel(
                                                7L, "acct", "ENABLED", Set.of(), Set.of(), Set.of(),
                                                Set.of(), "ENABLED")));
        assertEquals(40001, empty.code());
        assertTrue(empty.getMessage().contains("至少选择一个"), empty.getMessage());
    }

    // ---------------------------------------------------------------- 管理员 ≥2 保护

    @Test
    void adminGuardBlocksWhenFewerThanTwoRemain() {
        // 保存后：本账号停用且不再持有平台管理员；库内其余启用平台管理员只有 1 个 → 总数不足 2 → 拒绝
        when(platform.queryForObject(
                        contains("COUNT(DISTINCT ar.account_id)"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(1);
        AccountModel model =
                new AccountModel(
                        7L,
                        "impl001",
                        "ENABLED",
                        Set.of(1L),
                        Set.of("PLATFORM_ADMIN"),
                        Set.of(2L),
                        Set.of("IMPLEMENTATION"),
                        "DISABLED");
        ApiException rejected =
                assertThrows(
                        ApiException.class,
                        () -> service.ensureEnoughEnabledAdmins(model, "impl001"));
        assertEquals(40901, rejected.code());
        assertTrue(rejected.getMessage().contains("至少保留 2"), rejected.getMessage());
    }

    @Test
    void adminGuardPassesWithTwoRemainingAdmins() {
        when(platform.queryForObject(
                        contains("COUNT(DISTINCT ar.account_id)"),
                        eq(Integer.class),
                        any(Object[].class)))
                .thenReturn(2);
        AccountModel model =
                new AccountModel(
                        7L,
                        "impl001",
                        "ENABLED",
                        Set.of(1L),
                        Set.of("PLATFORM_ADMIN"),
                        Set.of(1L),
                        Set.of("PLATFORM_ADMIN"),
                        "ENABLED");
        service.ensureEnoughEnabledAdmins(model, "impl001");
    }

    // ---------------------------------------------------------------- 临时密码与脱敏

    @Test
    void temporaryPasswordMeetsPolicyAndAvoidsUsername() {
        for (int round = 0; round < 50; round++) {
            String password =
                    PlatformAccountService.generateTemporaryPassword(
                            "ops001", new Random(1000 + round));
            assertEquals(PlatformAccountService.TEMP_PASSWORD_LENGTH, password.length());
            assertTrue(
                    PasswordPolicy.violations(password, "ops001", 8, 3).isEmpty(),
                    "临时密码应满足平台策略：" + password);
        }
        // 同一种子可复现（便于问题排查），不同种子不相同
        assertEquals(
                PlatformAccountService.generateTemporaryPassword("ops001", new Random(42)),
                PlatformAccountService.generateTemporaryPassword("ops001", new Random(42)));
        assertNotEquals(
                PlatformAccountService.generateTemporaryPassword("ops001", new Random(42)),
                PlatformAccountService.generateTemporaryPassword("ops001", new Random(43)));
    }

    @Test
    void phoneMaskedForViews() {
        assertEquals("139****1234", PlatformAccountService.maskedPhone("13900001234"));
        assertNull(PlatformAccountService.maskedPhone(null));
        assertEquals("****", PlatformAccountService.maskedPhone("123"));
    }
}
