package com.crdp.server.system.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.crdp.server.system.user.UserAccountAdminService.StatusChange;
import com.crdp.server.system.user.UserAccountAdminService.UserCreateRequest;
import com.crdp.server.system.user.UserAccountAdminService.UserUpdateRequest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * E06-T07（PLT-ORG-006、008、A-WARD-002、C-WARD-003）单元测试：字段校验、状态流转（停用 / 启用 / 离职、
 * 最后一名租户管理员保护）、模板角色不可直接分配、负责病区 / 负责科室与未知引用 40400、 临时密码策略、重置密码副作用（会话失效 + 解锁 + 审计）。 真实 MySQL
 * 的端到端（HTTP + 租户隔离 + 审计 + 权限）由 {@code UserManagementIT} 覆盖。
 */
class UserAccountAdminServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 11L;
    private static final long DEPT_ID = 101L;
    private static final long WARD_ID = 201L;
    private static final long ROLE_ID = 31L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final SecurityProperties securityProperties = new SecurityProperties();
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PermissionVersionStore permissionVersions = mock(PermissionVersionStore.class);
    private final com.crdp.system.event.OrgUserEvents events =
            mock(com.crdp.system.event.OrgUserEvents.class);

    private UserAccountAdminService service;

    @BeforeEach
    void setUp() {
        service =
                new UserAccountAdminService(
                        jdbc,
                        crypto,
                        passwordEncoder,
                        sessions,
                        rateLimiter,
                        securityProperties,
                        audit,
                        permissionVersions,
                        events);
        TenantContext.useTenant(TENANT_ID, "demoa");
        stubUserRow(userRow("ENABLED"));
        stubRoles(List.of(Map.of("id", ROLE_ID, "code", "FOLLOWUP_DOCTOR", "role_type", "CUSTOM")));
        stubDepts(List.of(Map.of("id", DEPT_ID, "name", "虚构心内科")));
        stubWards(List.of(Map.of("id", WARD_ID, "code", "W-DEMO-1", "name", "虚构病区一")));
        stubAdminRole(false);
        stubEnabledAdminCount(2);
        lenient().when(crypto.encrypt(anyString(), anyString())).thenReturn("cipher");
        lenient().when(crypto.blindIndex(anyString(), anyString())).thenReturn("idx");
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("argon2-hash");
        lenient()
                .when(
                        jdbc.queryForList(
                                contains("WHERE username ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        lenient()
                .when(
                        jdbc.queryForList(
                                contains("phone_idx ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 字段校验（纯函数）

    @Test
    void usernameIsRequiredWellFormedAndBounded() {
        assertEquals("nurse.demo", UserAccountAdminService.normalizeUsername(" nurse.demo "));
        for (String bad : new String[] {null, "", "   ", "张三", "有 空格", "a".repeat(65)}) {
            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () -> UserAccountAdminService.normalizeUsername(bad));
            assertEquals(40001, ex.code());
        }
    }

    @Test
    void displayNameRequiredStaffNoOptional() {
        ApiException empty =
                assertThrows(
                        ApiException.class, () -> UserAccountAdminService.requireDisplayName(" "));
        assertEquals(40001, empty.code());
        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () -> UserAccountAdminService.requireDisplayName("长".repeat(65)));
        assertEquals(40001, tooLong.code());
        assertEquals("工号 007（示例）", UserAccountAdminService.optionalStaffNo(" 工号 007（示例） "));
        assertNull(UserAccountAdminService.optionalStaffNo(null));
        assertNull(UserAccountAdminService.optionalStaffNo("  "));
        ApiException longStaffNo =
                assertThrows(
                        ApiException.class,
                        () -> UserAccountAdminService.optionalStaffNo("a".repeat(65)));
        assertEquals(40001, longStaffNo.code());
    }

    @Test
    void phoneMustBeMainlandMobileAndMaskedForDisplay() {
        assertEquals("13900000002", UserAccountAdminService.requirePhone("13900000002"));
        ApiException bad =
                assertThrows(
                        ApiException.class, () -> UserAccountAdminService.requirePhone("12345"));
        assertEquals(40001, bad.code());
        assertEquals("139****0002", UserAccountAdminService.maskedPhone("13900000002"));
        assertEquals("****", UserAccountAdminService.maskedPhone(null));
    }

    // ---------------------------------------------------------------- 状态流转（PLT-ORG-006）

    @Test
    void statusActionParsesToTargetStatus() {
        assertEquals(
                UserAccountAdminService.STATUS_DISABLED,
                UserAccountAdminService.targetStatus(new StatusChange("DISABLE", "轮岗（示例）")));
        assertEquals(
                UserAccountAdminService.STATUS_ENABLED,
                UserAccountAdminService.targetStatus(new StatusChange("enable", null)));
        assertEquals(
                UserAccountAdminService.STATUS_RESIGNED,
                UserAccountAdminService.targetStatus(new StatusChange("RESIGN", "离职")));
        ApiException bad =
                assertThrows(
                        ApiException.class,
                        () -> UserAccountAdminService.targetStatus(new StatusChange("DROP", "")));
        assertEquals(40001, bad.code());
        ApiException longReason =
                assertThrows(
                        ApiException.class,
                        () ->
                                UserAccountAdminService.targetStatus(
                                        new StatusChange("DISABLE", "长".repeat(201))));
        assertEquals(40001, longReason.code());
    }

    @Test
    void resignIsTerminalAndTransitionsAreGuarded() {
        ApiException resignThenEnable =
                assertThrows(
                        ApiException.class,
                        () ->
                                UserAccountAdminService.ensureTransition(
                                        UserAccountAdminService.STATUS_RESIGNED,
                                        UserAccountAdminService.STATUS_ENABLED));
        assertEquals(40901, resignThenEnable.code());
        assertTrue(resignThenEnable.getMessage().contains("离职"), resignThenEnable.getMessage());
        ApiException disabledThenResign =
                assertThrows(
                        ApiException.class,
                        () ->
                                UserAccountAdminService.ensureTransition(
                                        UserAccountAdminService.STATUS_DISABLED,
                                        UserAccountAdminService.STATUS_RESIGNED));
        assertEquals(40901, disabledThenResign.code());
        ApiException repeat =
                assertThrows(
                        ApiException.class,
                        () ->
                                UserAccountAdminService.ensureTransition(
                                        UserAccountAdminService.STATUS_ENABLED,
                                        UserAccountAdminService.STATUS_ENABLED));
        assertEquals(40901, repeat.code());
        // 合法流转：启用 → 停用 / 离职，停用 → 启用
        UserAccountAdminService.ensureTransition(
                UserAccountAdminService.STATUS_ENABLED, UserAccountAdminService.STATUS_DISABLED);
        UserAccountAdminService.ensureTransition(
                UserAccountAdminService.STATUS_DISABLED, UserAccountAdminService.STATUS_ENABLED);
        UserAccountAdminService.ensureTransition(
                UserAccountAdminService.STATUS_ENABLED, UserAccountAdminService.STATUS_RESIGNED);
    }

    @Test
    void lastEnabledTenantAdminCannotBeDisabledOrResigned() {
        stubAdminRole(true);
        stubEnabledAdminCount(1);
        ApiException disable =
                assertThrows(
                        ApiException.class,
                        () -> service.changeStatus(USER_ID, new StatusChange("DISABLE", "测试（示例）")));
        assertEquals(40901, disable.code());
        ApiException resign =
                assertThrows(
                        ApiException.class,
                        () -> service.changeStatus(USER_ID, new StatusChange("RESIGN", "测试（示例）")));
        assertEquals(40901, resign.code());
        verify(jdbc, never())
                .update(contains("UPDATE sys_user SET status"), anyString(), anyLong());
    }

    @Test
    void disablingUserRevokesSessionsBumpsVersionAndWritesAudit() {
        service.changeStatus(USER_ID, new StatusChange("DISABLE", "长期休假（示例）"));
        verify(sessions)
                .revokeAllForUser(argThat(p -> p != null && p.userId() == USER_ID), eq(null));
        verify(jdbc).update(contains("UPDATE sys_user SET status"), eq("DISABLED"), eq(USER_ID));
        verify(permissionVersions).bump(eq("HOSPITAL"), eq(TENANT_ID), eq(USER_ID));
        verify(audit).record(argThat(e -> "USER_STATUS".equals(e.eventType())));
    }

    // ---------------------------------------------------------------- 新建（PLT-ORG-006 新建用户流程）

    @Test
    void createRequiresRoleMainDeptAndUniqueUsernameAndPhone() {
        ApiException noRole =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "nurse.new",
                                                "示例护士",
                                                null,
                                                "13900000003",
                                                DEPT_ID,
                                                List.of(),
                                                null,
                                                null)));
        assertEquals(40001, noRole.code());
        ApiException noDept =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new UserCreateRequest(
                                                "nurse.new",
                                                "示例护士",
                                                null,
                                                "13900000003",
                                                null,
                                                List.of(ROLE_ID),
                                                null,
                                                null)));
        assertEquals(40001, noDept.code());

        when(jdbc.queryForList(contains("WHERE username ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(USER_ID));
        ApiException duplicateUsername =
                assertThrows(ApiException.class, () -> service.create(validCreateRequest()));
        assertEquals(40902, duplicateUsername.code());

        when(jdbc.queryForList(contains("WHERE username ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("phone_idx ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(99L));
        ApiException duplicatePhone =
                assertThrows(ApiException.class, () -> service.create(validCreateRequest()));
        assertEquals(40902, duplicatePhone.code());
    }

    @Test
    void createRejectsTemplateRoleAndUnknownWardOrDeptRefs() {
        stubRoles(
                List.of(
                        Map.of(
                                "id",
                                ROLE_ID,
                                "code",
                                "FOLLOWUP_NURSE",
                                "name",
                                "随访员",
                                "role_type",
                                "TEMPLATE")));
        ApiException template =
                assertThrows(ApiException.class, () -> service.create(validCreateRequest()));
        assertEquals(40901, template.code());
        assertTrue(template.getMessage().contains("不能直接分配给用户"), template.getMessage());

        stubRoles(List.of(Map.of("id", ROLE_ID, "code", "FOLLOWUP_DOCTOR", "role_type", "CUSTOM")));
        stubWards(List.of());
        ApiException unknownWard =
                assertThrows(ApiException.class, () -> service.create(validCreateRequest()));
        assertEquals(40400, unknownWard.code());

        stubWards(List.of(Map.of("id", WARD_ID, "code", "W-DEMO-1", "name", "虚构病区一")));
        stubDepts(List.of());
        ApiException unknownDept =
                assertThrows(ApiException.class, () -> service.create(validCreateRequest()));
        assertEquals(40400, unknownDept.code());
    }

    @Test
    void createStoresWardsDeptScopesAndReturnsTemporaryPasswordOnce() {
        var created = service.create(validCreateRequest());
        assertEquals(USER_ID, created.user().id());
        assertNotNull(created.temporaryPassword());
        assertTrue(created.temporaryPassword().length() >= 16);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(contains("INSERT INTO sys_user_ward"), anyLong(), eq(WARD_ID));
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(contains("INSERT INTO sys_user_dept_scope"), anyLong(), eq(DEPT_ID));
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(contains("INSERT INTO sys_user_role"), anyLong(), eq(ROLE_ID));
        // 初始密码：强制改密 + 初始态启用；审计不落临时密码
        verify(jdbc)
                .update(
                        contains("must_change_password"),
                        eq("nurse.new"),
                        eq("示例护士"),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any());
        verify(audit)
                .record(
                        argThat(
                                e ->
                                        "USER_CREATE".equals(e.eventType())
                                                && !e.detail()
                                                        .contains(created.temporaryPassword())));
    }

    // ---------------------------------------------------------------- 编辑（负责病区 /
    // 负责科室全量覆盖，A-WARD-002 / C-WARD-003）

    @Test
    void updateReplacesWardAndDeptScopeSetsAndBumpsPermissionVersion() {
        UserUpdateRequest request =
                new UserUpdateRequest(
                        "示例医生",
                        "8001",
                        null,
                        DEPT_ID,
                        List.of(ROLE_ID),
                        List.of(WARD_ID),
                        List.of(DEPT_ID));
        var detail = service.update(USER_ID, request);
        assertEquals(USER_ID, detail.id());
        verify(jdbc).update(contains("DELETE FROM sys_user_ward"), eq(USER_ID));
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(contains("INSERT INTO sys_user_ward"), eq(USER_ID), eq(WARD_ID));
        verify(jdbc).update(contains("DELETE FROM sys_user_dept_scope"), eq(USER_ID));
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .update(contains("INSERT INTO sys_user_dept_scope"), eq(USER_ID), eq(DEPT_ID));
        verify(permissionVersions).bump(eq("HOSPITAL"), eq(TENANT_ID), eq(USER_ID));
        // 手机号未提供 → 不改绑
        verify(jdbc, never()).update(contains("phone_idx ="), any(), any(), any(), any());
    }

    @Test
    void updateRephoneChecksTenantUniqueness() {
        when(jdbc.queryForList(contains("phone_idx ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(99L));
        ApiException taken =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        USER_ID,
                                        new UserUpdateRequest(
                                                "示例医生",
                                                null,
                                                "13900000009",
                                                DEPT_ID,
                                                List.of(ROLE_ID),
                                                null,
                                                null)));
        assertEquals(40902, taken.code());
    }

    @Test
    void updateUnknownUserIs40400() {
        when(jdbc.queryForList(contains("FROM sys_user u"), any(Object[].class)))
                .thenReturn(List.of());
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        USER_ID,
                                        new UserUpdateRequest(
                                                "示例医生",
                                                null,
                                                null,
                                                DEPT_ID,
                                                List.of(ROLE_ID),
                                                null,
                                                null)));
        assertEquals(40400, ex.code());
    }

    @Test
    void updateCannotStripLastTenantAdminRole() {
        stubAdminRole(true);
        stubEnabledAdminCount(1);
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        USER_ID,
                                        new UserUpdateRequest(
                                                "示例管理员",
                                                null,
                                                null,
                                                DEPT_ID,
                                                List.of(ROLE_ID),
                                                null,
                                                null)));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("租户管理员"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 重置密码（PLT-ORG-008）

    @Test
    void resetPasswordReturnsTemporaryOnceAndRevokesSessions() {
        var result = service.resetPassword(USER_ID);
        assertEquals(USER_ID, result.id());
        assertNotNull(result.temporaryPassword());
        assertTrue(result.temporaryPassword().length() >= 16);
        verify(jdbc)
                .update(
                        contains("must_change_password = 1"),
                        eq("argon2-hash"),
                        any(),
                        eq(USER_ID));
        verify(jdbc)
                .update(contains("INSERT INTO sys_password_history"), eq(USER_ID), eq("old-hash"));
        verify(sessions)
                .revokeAllForUser(argThat(p -> p != null && p.userId() == USER_ID), eq(null));
        verify(rateLimiter).unlock(eq("H:demoa:doctor.demo"));
        verify(permissionVersions, never()).bump(anyString(), anyLong(), anyLong());
        verify(audit).record(argThat(e -> "USER_RESET_PASSWORD".equals(e.eventType())));
    }

    @Test
    void temporaryPasswordSatisfiesPolicyAndAvoidsUsername() {
        Random fixed = new Random(42);
        for (int i = 0; i < 30; i++) {
            String password =
                    UserAccountAdminService.generateTemporaryPassword("doctor.demo", 16, fixed);
            assertTrue(password.length() >= 16);
            assertTrue(
                    PasswordPolicy.violations(password, "doctor.demo", 16, 3).isEmpty(),
                    "临时密码必须满足密码策略：" + password);
        }
    }

    // ---------------------------------------------------------------- 辅助

    private UserCreateRequest validCreateRequest() {
        return new UserCreateRequest(
                "nurse.new",
                "示例护士",
                null,
                "13900000003",
                DEPT_ID,
                List.of(ROLE_ID),
                List.of(WARD_ID),
                List.of(DEPT_ID));
    }

    private Map<String, Object> userRow(String status) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", USER_ID);
        row.put("username", "doctor.demo");
        row.put("display_name", "示例医生");
        row.put("staff_no", "8001");
        row.put("phone", "cipher");
        row.put("main_dept_id", DEPT_ID);
        row.put("main_dept_name", "虚构心内科");
        row.put("status", status);
        row.put("password_hash", "old-hash");
        return row;
    }

    private void stubUserRow(Map<String, Object> row) {
        when(jdbc.queryForList(contains("FROM sys_user u"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private void stubRoles(List<Map<String, Object>> roles) {
        lenient()
                .when(jdbc.queryForList(contains("FROM sys_role WHERE"), any(Object[].class)))
                .thenReturn(roles);
    }

    private void stubDepts(List<Map<String, Object>> depts) {
        lenient()
                .when(jdbc.queryForList(contains("FROM sys_dept WHERE"), any(Object[].class)))
                .thenReturn(depts);
    }

    private void stubWards(List<Map<String, Object>> wards) {
        lenient()
                .when(jdbc.queryForList(contains("FROM ward WHERE"), any(Object[].class)))
                .thenReturn(wards);
    }

    /** 用户是否挂内置 TENANT_ADMIN 角色。 */
    private void stubAdminRole(boolean member) {
        lenient()
                .when(
                        jdbc.queryForObject(
                                contains("WHERE ur.user_id = ?"),
                                eq(Integer.class),
                                any(Object[].class)))
                .thenReturn(member ? 1 : 0);
    }

    /** 本院启用中的租户管理员人数。 */
    private void stubEnabledAdminCount(int count) {
        lenient()
                .when(jdbc.queryForObject(contains("u.status = 'ENABLED'"), eq(Integer.class)))
                .thenReturn(count);
    }
}
