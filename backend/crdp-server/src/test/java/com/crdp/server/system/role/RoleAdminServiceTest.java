package com.crdp.server.system.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantScopeService;
import com.crdp.server.system.role.RoleAdminService.CopyRequest;
import com.crdp.server.system.role.RoleAdminService.FieldPolicyItem;
import com.crdp.server.system.role.RoleAdminService.RoleFieldPoliciesView;
import com.crdp.server.system.role.RoleAdminService.RoleSave;
import com.crdp.server.system.role.RoleAdminService.ScopeSave;
import com.crdp.server.system.role.RoleAdminService.StatusChange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E06-T05（PLT-ORG-004、005）单元测试：角色保存校验（编码 / 名称 / 说明 / 范围类型）、内置与模板角色的保护、 菜单可分配范围边界与成员规则。真实 MySQL
 * 的端到端（HTTP + 租户隔离 + 审计）由 {@code RoleManagementMySqlIT} 覆盖。
 */
class RoleAdminServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long BUILT_IN_ROLE_ID = 5L;
    private static final long TEMPLATE_ROLE_ID = 21L;
    private static final long CUSTOM_ROLE_ID = 31L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantScopeService tenantScopes = mock(TenantScopeService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PermissionVersionStore permissionVersions = mock(PermissionVersionStore.class);

    private RoleAdminService service;

    @BeforeEach
    void setUp() {
        service = new RoleAdminService(jdbc, tenantScopes, audit, permissionVersions);
        TenantContext.useTenant(TENANT_ID, "demoa");
        lenient().when(tenantScopes.assignableMenuIds(TENANT_ID)).thenReturn(Set.of(20L, 21L, 22L));
        lenient()
                .when(
                        jdbc.queryForList(
                                contains("SELECT id, username, display_name, status FROM sys_user"),
                                any(Object[].class)))
                .thenReturn(List.of());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 保存校验（纯函数）

    @Test
    void roleCodeIsRequiredWellFormedAndBounded() {
        assertEquals("ward_nurse", RoleAdminService.normalizeCode(" ward_nurse "));
        for (String bad : new String[] {null, "", "   ", "随访员", "有 空格", "a".repeat(65)}) {
            ApiException ex =
                    assertThrows(ApiException.class, () -> RoleAdminService.normalizeCode(bad));
            assertEquals(40001, ex.code());
        }
    }

    @Test
    void roleNameAndDescriptionAreValidated() {
        assertEquals("病区随访护士", RoleAdminService.requireName(" 病区随访护士 "));
        assertNull(RoleAdminService.optionalDescription(null));
        assertNull(RoleAdminService.optionalDescription("  "));
        assertEquals("说明（示例）", RoleAdminService.optionalDescription(" 说明（示例） "));
        ApiException longName =
                assertThrows(
                        ApiException.class, () -> RoleAdminService.requireName("长".repeat(65)));
        assertEquals(40001, longName.code());
        ApiException longDesc =
                assertThrows(
                        ApiException.class,
                        () -> RoleAdminService.optionalDescription("长".repeat(201)));
        assertEquals(40001, longDesc.code());
    }

    @Test
    void statusActionAndReasonAreValidated() {
        assertTrue(RoleAdminService.parseDisable(new StatusChange("DISABLE", "调整（示例）")));
        assertEquals("", RoleAdminService.reasonSuffix(new StatusChange("DISABLE", null)));
        assertEquals(
                "，原因：合并科室（示例）",
                RoleAdminService.reasonSuffix(new StatusChange("DISABLE", " 合并科室（示例） ")));
        ApiException bad =
                assertThrows(
                        ApiException.class,
                        () -> RoleAdminService.parseDisable(new StatusChange("PAUSE", null)));
        assertEquals(40001, bad.code());
        ApiException longReason =
                assertThrows(
                        ApiException.class,
                        () ->
                                RoleAdminService.reasonSuffix(
                                        new StatusChange("DISABLE", "长".repeat(201))));
        assertEquals(40001, longReason.code());
    }

    @Test
    void idListsDedupeAndRejectInvalidEntries() {
        assertEquals(
                List.of(1L, 2L),
                List.copyOf(RoleAdminService.normalizeIds(List.of(1L, 2L, 1L), "menuIds")));
        // 空清单合法（清空菜单 / 成员；租户管理员的空成员在业务规则里单独拒绝）
        assertEquals(List.of(), List.copyOf(RoleAdminService.normalizeIds(List.of(), "menuIds")));
        ApiException missing =
                assertThrows(
                        ApiException.class, () -> RoleAdminService.normalizeIds(null, "menuIds"));
        assertEquals(40001, missing.code());
        ApiException negative =
                assertThrows(
                        ApiException.class,
                        () -> RoleAdminService.normalizeIds(List.of(1L, 0L), "menuIds"));
        assertEquals(40001, negative.code());
    }

    // ---------------------------------------------------------------- 内置 /
    // 模板角色保护（PLT-ROLE-004、005）

    @Test
    void builtInRoleCannotBeDisabledEditedOrDeleted() {
        stubRole(
                BUILT_IN_ROLE_ID,
                "TENANT_ADMIN",
                "租户管理员",
                RoleAdminService.TYPE_BUILT_IN,
                "ENABLED");

        ApiException disable =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeStatus(
                                        BUILT_IN_ROLE_ID, new StatusChange("DISABLE", "测试（示例）")));
        assertEquals(40901, disable.code());
        assertTrue(disable.getMessage().contains("内置角色不可停用"), disable.getMessage());

        ApiException edit =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        BUILT_IN_ROLE_ID,
                                        new RoleSave("TENANT_ADMIN", "改名（示例）", null)));
        assertEquals(40901, edit.code());
        assertTrue(edit.getMessage().contains("内置角色不可修改权限"), edit.getMessage());

        ApiException delete =
                assertThrows(ApiException.class, () -> service.delete(BUILT_IN_ROLE_ID));
        assertEquals(40901, delete.code());
        assertTrue(delete.getMessage().contains("内置角色不可删除"), delete.getMessage());
    }

    @Test
    void templateRoleIsReadOnlyAndNotDirectlyAssignable() {
        stubRole(
                TEMPLATE_ROLE_ID,
                "FOLLOWUP_NURSE",
                "随访员",
                RoleAdminService.TYPE_TEMPLATE,
                "ENABLED");

        ApiException edit =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMenus(TEMPLATE_ROLE_ID, List.of(21L)));
        assertEquals(40901, edit.code());
        assertTrue(edit.getMessage().contains("模板角色只读"), edit.getMessage());

        ApiException scope =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateScope(
                                        TEMPLATE_ROLE_ID,
                                        new ScopeSave(List.of("ALL"), List.of())));
        assertEquals(40901, scope.code());

        ApiException members =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMembers(TEMPLATE_ROLE_ID, List.of(9L)));
        assertEquals(40901, members.code());
        assertTrue(members.getMessage().contains("模板角色不能直接分配"), members.getMessage());

        ApiException disable =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeStatus(
                                        TEMPLATE_ROLE_ID, new StatusChange("DISABLE", null)));
        assertEquals(40901, disable.code());
    }

    @Test
    void copyFromTemplateRejectsNonTemplateRoles() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.copyFromTemplate(
                                        CUSTOM_ROLE_ID,
                                        new CopyRequest("IT_COPY", "副本（示例）", null)));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("不是模板角色"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 菜单与数据范围校验

    @Test
    void updateMenusRejectsIdsOutsideAssignableScope() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");
        when(jdbc.queryForList(
                        eq(
                                "SELECT id FROM plt_menu WHERE side = 'HOSPITAL' AND status = 'ENABLED'"),
                        eq(Long.class)))
                .thenReturn(List.of(20L, 21L, 22L, 23L));
        // 23 在医院端菜单里但不在可分配范围（E05-T07 收回），21 可分配
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMenus(CUSTOM_ROLE_ID, List.of(21L, 23L)));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("不在本院可分配范围内"), ex.getMessage());
        verify(jdbc, never()).update(contains("DELETE FROM sys_role_menu"), eq(CUSTOM_ROLE_ID));
    }

    @Test
    void updateScopeValidatesTypesAndCustomDeptRequirement() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");

        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateScope(
                                        CUSTOM_ROLE_ID,
                                        new ScopeSave(List.of("HOSPITAL"), List.of())));
        assertEquals(40001, unknown.code());
        assertTrue(unknown.getMessage().contains("未知的数据范围类型"), unknown.getMessage());

        ApiException emptyTypes =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateScope(
                                        CUSTOM_ROLE_ID, new ScopeSave(List.of(), List.of())));
        assertEquals(40001, emptyTypes.code());

        ApiException customWithoutDept =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateScope(
                                        CUSTOM_ROLE_ID,
                                        new ScopeSave(
                                                List.of(RoleAdminService.SCOPE_CUSTOM_DEPT),
                                                List.of())));
        assertEquals(40001, customWithoutDept.code());
        assertTrue(
                customWithoutDept.getMessage().contains("自定义科室"), customWithoutDept.getMessage());
    }

    /** E07-T03 / PLT-PERM-008：RESEARCH_PROJECT 是可分配的范围类型（角色多选取并集）； 项目成员关系经扩展点取数，空实现下该分支不放宽任何数据。 */
    @Test
    void researchProjectScopeTypeIsAssignable() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");

        service.updateScope(CUSTOM_ROLE_ID, new ScopeSave(List.of("RESEARCH_PROJECT"), List.of()));

        verify(jdbc)
                .update(
                        eq("INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)"),
                        eq(CUSTOM_ROLE_ID),
                        eq("RESEARCH_PROJECT"));
    }

    @Test
    void customScopeRejectsUnknownDepts() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");
        when(jdbc.queryForList(
                        contains("FROM sys_dept WHERE id IN"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateScope(
                                        CUSTOM_ROLE_ID,
                                        new ScopeSave(
                                                List.of(RoleAdminService.SCOPE_CUSTOM_DEPT),
                                                List.of(77L))));
        assertEquals(40400, ex.code());
        assertTrue(ex.getMessage().contains("科室不存在"), ex.getMessage());
    }

    @Test
    void updateMembersRejectsNonEnabledUsers() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", 9L);
        user.put("username", "nurse.demo");
        user.put("display_name", "演示护士");
        user.put("status", "DISABLED");
        when(jdbc.queryForList(contains("FROM sys_user WHERE id IN"), any(Object[].class)))
                .thenReturn(List.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMembers(CUSTOM_ROLE_ID, List.of(9L)));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("不是启用状态"), ex.getMessage());
    }

    @Test
    void tenantAdminCannotLoseAllEnabledMembers() {
        stubRole(
                BUILT_IN_ROLE_ID,
                "TENANT_ADMIN",
                "租户管理员",
                RoleAdminService.TYPE_BUILT_IN,
                "ENABLED");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMembers(BUILT_IN_ROLE_ID, List.of()));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains("最后一名"), ex.getMessage());
    }

    @Test
    void createRejectsDuplicateCodeWith40902() {
        when(jdbc.queryForList(
                        eq("SELECT id FROM sys_role WHERE code = ?"), eq(Long.class), eq("IT_DUP")))
                .thenReturn(List.of(99L));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(new RoleSave("IT_DUP", "重复（示例）", null)));
        assertEquals(40902, ex.code());
    }

    // ---------------------------------------------------------------- 字段策略（E07-T06 / PLT-PERM-009）

    @Test
    void updateFieldPoliciesValidatesEntriesAgainstCatalog() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");

        ApiException unknownField =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateFieldPolicies(
                                        CUSTOM_ROLE_ID,
                                        List.of(
                                                new FieldPolicyItem(
                                                        "patient", "nickname", "VISIBLE"))));
        assertEquals(40001, unknownField.code());
        assertTrue(unknownField.getMessage().contains("不在敏感字段清单"), unknownField.getMessage());

        ApiException badPolicy =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateFieldPolicies(
                                        CUSTOM_ROLE_ID,
                                        List.of(new FieldPolicyItem("patient", "name", "PLAIN"))));
        assertEquals(40001, badPolicy.code());
        assertTrue(badPolicy.getMessage().contains("字段策略只允许"), badPolicy.getMessage());

        ApiException duplicated =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateFieldPolicies(
                                        CUSTOM_ROLE_ID,
                                        List.of(
                                                new FieldPolicyItem("patient", "name", "VISIBLE"),
                                                new FieldPolicyItem("patient", "name", "HIDDEN"))));
        assertEquals(40001, duplicated.code());

        ApiException nullList =
                assertThrows(
                        ApiException.class,
                        () -> service.updateFieldPolicies(CUSTOM_ROLE_ID, null));
        assertEquals(40001, nullList.code());
    }

    @Test
    void updateFieldPoliciesRejectsBuiltInAndTemplateRoles() {
        stubRole(
                BUILT_IN_ROLE_ID,
                "TENANT_ADMIN",
                "租户管理员",
                RoleAdminService.TYPE_BUILT_IN,
                "ENABLED");
        ApiException builtIn =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateFieldPolicies(
                                        BUILT_IN_ROLE_ID,
                                        List.of(
                                                new FieldPolicyItem(
                                                        "patient", "name", "VISIBLE"))));
        assertEquals(40901, builtIn.code());

        stubRole(
                TEMPLATE_ROLE_ID,
                "FOLLOWUP_NURSE",
                "随访员",
                RoleAdminService.TYPE_TEMPLATE,
                "ENABLED");
        ApiException template =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateFieldPolicies(
                                        TEMPLATE_ROLE_ID,
                                        List.of(
                                                new FieldPolicyItem(
                                                        "patient", "name", "VISIBLE"))));
        assertEquals(40901, template.code());
        assertTrue(template.getMessage().contains("模板角色只读"), template.getMessage());
    }

    @Test
    void updateFieldPoliciesStoresOverlayAuditsAndBumpsMembers() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");
        lenient()
                .when(jdbc.queryForList(contains("FROM sys_role_field_policy"), eq(CUSTOM_ROLE_ID)))
                .thenReturn(List.of());

        RoleAdminService.RoleView view =
                service.updateFieldPolicies(
                        CUSTOM_ROLE_ID,
                        List.of(
                                new FieldPolicyItem("patient", "name", "VISIBLE"),
                                new FieldPolicyItem("patient", "phone", "HIDDEN")));

        assertEquals("IT_ROLE", view.code());
        verify(jdbc).update(contains("DELETE FROM sys_role_field_policy"), eq(CUSTOM_ROLE_ID));
        verify(jdbc)
                .update(
                        contains("INSERT INTO sys_role_field_policy"),
                        eq(CUSTOM_ROLE_ID),
                        eq("patient"),
                        eq("name"),
                        eq("VISIBLE"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO sys_role_field_policy"),
                        eq(CUSTOM_ROLE_ID),
                        eq("patient"),
                        eq("phone"),
                        eq("HIDDEN"));
        verify(audit)
                .append(
                        argThat(
                                entry ->
                                        entry != null
                                                && "ROLE_FIELD_POLICY".equals(entry.eventType())));
    }

    @Test
    void fieldPoliciesViewFallsBackToPlatformDefault() {
        stubRole(CUSTOM_ROLE_ID, "IT_ROLE", "示例角色", RoleAdminService.TYPE_CUSTOM, "ENABLED");
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("resource", "patient");
        override.put("field", "name");
        override.put("policy", "VISIBLE");
        when(jdbc.queryForList(contains("FROM sys_role_field_policy"), eq(CUSTOM_ROLE_ID)))
                .thenReturn(List.of(override));

        RoleFieldPoliciesView view = service.fieldPoliciesView(CUSTOM_ROLE_ID);

        assertEquals("IT_ROLE", view.role().code());
        // 覆盖项：显式行；其余字段回落平台默认（MASKED），不视为覆盖
        var name =
                view.fields().stream()
                        .filter(f -> "name".equals(f.field()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("VISIBLE", name.policy());
        assertTrue(name.overridden());
        var phone =
                view.fields().stream()
                        .filter(f -> "phone".equals(f.field()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("MASKED", phone.policy());
        assertFalse(phone.overridden());
        assertEquals("MASKED", phone.defaultPolicy());
        // 敏感字段清单（platform-spec §8 默认视角规则：姓名、证件号、手机号、住址、病历号）
        assertTrue(view.fields().size() >= 5, "敏感字段目录应至少包含五位患者身份字段");
    }

    // ---------------------------------------------------------------- 桩

    private void stubRole(long id, String code, String name, String roleType, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("code", code);
        row.put("name", name);
        row.put("role_type", roleType);
        row.put("module_code", "BUILT_IN".equals(roleType) ? null : "followup");
        row.put("description", "说明（示例）");
        row.put("status", status);
        row.put("built_in", RoleAdminService.TYPE_BUILT_IN.equals(roleType) ? 1 : 0);
        when(jdbc.queryForList(contains("FROM sys_role WHERE id = ?"), eq(id)))
                .thenReturn(List.of(row));
        lenient().when(jdbc.queryForList(contains("GROUP BY role_id"))).thenReturn(List.of());
        lenient()
                .when(
                        jdbc.queryForList(
                                eq("SELECT id FROM sys_role WHERE code = ?"),
                                eq(Long.class),
                                eq(code)))
                .thenReturn(List.of(id));
    }
}
