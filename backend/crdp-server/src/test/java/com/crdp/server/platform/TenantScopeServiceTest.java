package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import com.crdp.platform.menu.MenuRow;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E05-T07（PLT-SCOPE-001..006、008）单元测试：可分配范围生效值的推导（base 固定 ∪ 模板默认按覆盖调整 ∩ 已开通模块）、保存校验（授权范围子集、base
 * 不可收回、敏感放开原因必填）与前后差异计算。 收回后租户角色同步收回（DELETE sys_role_menu + 权限版本递增 + 会话交集）的端到端行为由 {@code
 * PlatformTenantScopeMySqlIT} 验证。
 */
class TenantScopeServiceTest {

    // 菜单（与种子 V100 同构：base 固定 10/30/31，followup 业务 20/21/22/23 + 敏感按钮 211/212）
    private static final MenuRow WORKBENCH = menu(10L, 0L, "MENU", "base", "工作台", null);
    private static final MenuRow SYSTEM_DIR = menu(30L, 0L, "DIR", "base", "系统管理", null);
    private static final MenuRow USER_MGMT =
            menu(31L, 30L, "MENU", "base", "用户管理", "tenant:user:view");
    private static final MenuRow FOLLOWUP_DIR = menu(20L, 0L, "DIR", "followup", "随访", null);
    private static final MenuRow PATIENT =
            menu(21L, 20L, "MENU", "followup", "患者档案", "followup:patient:view");
    private static final MenuRow TASK =
            menu(22L, 20L, "MENU", "followup", "随访任务", "followup:task:view");
    private static final MenuRow REPORT =
            menu(23L, 20L, "MENU", "followup", "报告单", "followup:report:view");
    private static final MenuRow PLAIN =
            menu(211L, 21L, "BUTTON", "followup", "查看明文", "followup:patient:plain");
    private static final MenuRow EXPORT =
            menu(212L, 21L, "BUTTON", "followup", "导出患者", "followup:patient:export");

    private static final Set<String> SENSITIVE =
            new LinkedHashSet<>(List.of("followup:patient:plain", "followup:patient:export"));

    private static MenuRow menu(
            long id, long parentId, String type, String module, String title, String permCode) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setParentId(parentId);
        row.setSide("HOSPITAL");
        row.setType(type);
        row.setModuleCode(module);
        row.setTitle(title);
        row.setPermCode(permCode);
        return row;
    }

    private static TenantScopeService.ScopeModel model(
            Set<String> openedModules, Set<Long> template, Map<Long, Boolean> overrides) {
        List<MenuRow> menus =
                List.of(
                        WORKBENCH,
                        SYSTEM_DIR,
                        USER_MGMT,
                        FOLLOWUP_DIR,
                        PATIENT,
                        TASK,
                        REPORT,
                        PLAIN,
                        EXPORT);
        return TenantScopeService.ScopeModel.of(
                menus, openedModules, template, SENSITIVE, overrides);
    }

    // ---------------------------------------------------------------- 生效值推导（PLT-SCOPE-001、002、004）

    @Test
    void effectiveAssignableFollowsTemplateDefaults() {
        Set<Long> template = new LinkedHashSet<>(List.of(20L, 21L, 22L, 23L));
        Set<Long> effective =
                TenantScopeService.effectiveAssignable(
                        model(
                                new LinkedHashSet<>(List.of("base", "followup")),
                                template,
                                Map.of()));
        // base 固定能力 + 模板默认项；敏感按钮（211/212）不在模板也默认不可分配
        assertEquals(Set.of(10L, 30L, 31L, 20L, 21L, 22L, 23L), effective);
    }

    @Test
    void effectiveAssignableShrinksWithClosedModule() {
        Set<Long> template = new LinkedHashSet<>(List.of(20L, 21L, 22L, 23L));
        // followup 关闭：授权范围自动收缩，业务项全部不可分配（PLT-SCOPE-001）
        Set<Long> effective =
                TenantScopeService.effectiveAssignable(
                        model(new LinkedHashSet<>(List.of("base")), template, Map.of(22L, true)));
        assertEquals(Set.of(10L, 30L, 31L), effective);
    }

    @Test
    void overridesAdjustDefaultsBothWays() {
        Set<Long> template = new LinkedHashSet<>(List.of(20L, 21L, 22L, 23L));
        Map<Long, Boolean> overrides = new LinkedHashMap<>();
        overrides.put(22L, false); // 收回模板默认项
        overrides.put(211L, true); // 放开敏感项
        Set<Long> effective =
                TenantScopeService.effectiveAssignable(
                        model(
                                new LinkedHashSet<>(List.of("base", "followup")),
                                template,
                                overrides));
        assertFalse(effective.contains(22L), "覆盖收回模板默认项");
        assertTrue(effective.contains(211L), "覆盖放开敏感项");
        assertTrue(effective.contains(21L), "未覆盖项沿用模板默认");
    }

    // ---------------------------------------------------------------- 保存校验（PLT-SCOPE-002、004、008）

    private static final Set<Long> DEFAULT_BEFORE = Set.of(10L, 30L, 31L, 20L, 21L, 22L, 23L);

    private static TenantScopeService.ScopeUpdateRequest request(
            List<Long> menuIds, Map<Long, String> sensitiveReasons, String reason) {
        return new TenantScopeService.ScopeUpdateRequest(menuIds, sensitiveReasons, reason);
    }

    @Test
    void validateRejectsMissingOrOverlongReason() {
        TenantScopeService.ScopeModel model =
                model(new LinkedHashSet<>(List.of("base", "followup")), Set.of(), Map.of());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantScopeService.validateUpdate(
                                                request(List.of(10L), Map.of(), " "),
                                                model,
                                                DEFAULT_BEFORE))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantScopeService.validateUpdate(
                                                request(List.of(10L), Map.of(), "长".repeat(201)),
                                                model,
                                                DEFAULT_BEFORE))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantScopeService.validateUpdate(
                                                request(null, Map.of(), "调整（示例）"),
                                                model,
                                                DEFAULT_BEFORE))
                        .code());
    }

    @Test
    void validateRejectsMenusOutsideAuthorizationScope() {
        TenantScopeService.ScopeModel model =
                model(new LinkedHashSet<>(List.of("base", "followup")), Set.of(), Map.of());
        // 未知菜单 / 按钮（不在医院端启用菜单清单）
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantScopeService.validateUpdate(
                                                request(
                                                        List.of(10L, 30L, 31L, 999L),
                                                        Map.of(),
                                                        "调整（示例）"),
                                                model,
                                                DEFAULT_BEFORE))
                        .code());
        // 未开通模块的项不能进入可分配范围
        TenantScopeService.ScopeModel closed =
                model(new LinkedHashSet<>(List.of("base")), Set.of(), Map.of());
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantScopeService.validateUpdate(
                                        request(List.of(10L, 30L, 31L, 22L), Map.of(), "调整（示例）"),
                                        closed,
                                        Set.of(10L, 30L, 31L)));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("未开通"), ex.getMessage());
    }

    @Test
    void validateKeepsBaseModuleFixedCapability() {
        TenantScopeService.ScopeModel model =
                model(new LinkedHashSet<>(List.of("base", "followup")), Set.of(), Map.of());
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantScopeService.validateUpdate(
                                        request(
                                                // 漏掉 31（用户管理，base 固定能力）
                                                List.of(10L, 30L, 20L, 21L, 22L, 23L),
                                                Map.of(),
                                                "调整（示例）"),
                                        model,
                                        DEFAULT_BEFORE));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("固定能力"), ex.getMessage());
    }

    @Test
    void validateRequiresReasonForNewSensitiveRelease() {
        TenantScopeService.ScopeModel model =
                model(new LinkedHashSet<>(List.of("base", "followup")), Set.of(), Map.of());
        List<Long> withPlain = List.of(10L, 30L, 31L, 20L, 21L, 22L, 23L, 211L);
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantScopeService.validateUpdate(
                                        request(withPlain, Map.of(), "调整（示例）"),
                                        model,
                                        DEFAULT_BEFORE));
        assertEquals(40001, missing.code());
        assertTrue(missing.getMessage().contains("查看明文"), missing.getMessage());
        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantScopeService.validateUpdate(
                                        request(withPlain, Map.of(211L, "  "), "调整（示例）"),
                                        model,
                                        DEFAULT_BEFORE));
        assertEquals(40001, blank.code());
        // 已放开的敏感项保持勾选不需要重新填原因（沿用已登记原因）
        Set<Long> beforeWithPlain = new LinkedHashSet<>(DEFAULT_BEFORE);
        beforeWithPlain.add(211L);
        assertEquals(
                new LinkedHashSet<>(withPlain),
                TenantScopeService.validateUpdate(
                        request(withPlain, Map.of(), "调整（示例）"), model, beforeWithPlain));
    }

    @Test
    void validateAcceptsNormalShrinkAndRelease() {
        TenantScopeService.ScopeModel model =
                model(new LinkedHashSet<>(List.of("base", "followup")), Set.of(), Map.of());
        // 收回 22 + 放开 211（带原因）
        Set<Long> desired =
                TenantScopeService.validateUpdate(
                        request(
                                List.of(10L, 30L, 31L, 20L, 21L, 23L, 211L),
                                Map.of(211L, "科研协作需要（示例）"),
                                "按合同调整（示例）"),
                        model,
                        DEFAULT_BEFORE);
        assertEquals(Set.of(10L, 30L, 31L, 20L, 21L, 23L, 211L), desired);
    }

    // ---------------------------------------------------------------- 前后差异（PLT-SCOPE-005）

    @Test
    void diffListsRemovedAndAddedInStableOrder() {
        TenantScopeService.Diff diff =
                TenantScopeService.diff(
                        Set.of(10L, 30L, 31L, 21L, 22L, 23L),
                        Set.of(31L, 10L, 30L, 23L, 21L, 211L));
        assertEquals(List.of(22L), diff.removed());
        assertEquals(List.of(211L), diff.added());
    }
}
