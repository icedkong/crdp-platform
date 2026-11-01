package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.platform.menu.MenuRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E06-T06 / PLT-ROLE-006 有效权限计算的纯函数用例（tasks.csv 验收：多角色取并集）。
 *
 * <p>口径：用户最终权限 = 已开通模块 ∩ 平台允许租户管理员分配的范围 ∩ 用户全部角色权限的并集（platform-spec §5）。 角色并集由租户库 SQL（sys_role_menu
 * ∩ 启用角色）先行完成，这里验证三层交集与权限码去重；「变更即生效」的端到端链路见 {@code EffectivePermissionIT}。
 */
class EffectivePermissionServiceTest {

    /** 平台库医院端启用菜单（演示种子子集 + 一个未开通模块的菜单）。 */
    private static List<MenuRow> hospitalMenus() {
        return List.of(
                menu(10L, "base", null),
                menu(20L, "followup", null),
                menu(21L, "followup", "followup:patient:view"),
                menu(22L, "followup", "followup:task:view"),
                menu(23L, "followup", "followup:report:view"),
                menu(31L, "base", "tenant:user:view"),
                // 未开通模块 rct 的菜单：即使在角色授权里也不能生效
                menu(80L, "rct", "rct:crf:view"));
    }

    private static MenuRow menu(long id, String moduleCode, String permCode) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setSide("HOSPITAL");
        row.setType(permCode == null ? "MENU" : "BUTTON");
        row.setModuleCode(moduleCode);
        row.setTitle("菜单" + id + "（示例）");
        row.setPermCode(permCode);
        return row;
    }

    @Test
    void multiRoleGrantsUnionAcrossRoles() {
        // 角色 A：10、21、22；角色 B：23、31 —— 并集 10、21、22、23、31（并集已由租户库 SQL 完成，传入去重后的合集）
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        hospitalMenus(),
                        List.of(10L, 21L, 22L, 23L, 31L),
                        List.of("base", "followup"),
                        Set.of(10L, 20L, 21L, 22L, 23L, 31L));
        assertEquals(List.of(10L, 21L, 22L, 23L, 31L), granted.menuIds());
        assertTrue(granted.permissionCodes().contains("followup:patient:view"), "角色 A 的权限码");
        assertTrue(granted.permissionCodes().contains("followup:report:view"), "角色 B 的权限码");
        assertTrue(granted.permissionCodes().contains("tenant:user:view"), "角色 B 的基础模块权限码");
    }

    @Test
    void menusOfUnopenedModulesAreDropped() {
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        hospitalMenus(),
                        List.of(10L, 22L, 80L),
                        List.of("base"), // followup / rct 均未开通
                        null);
        assertEquals(List.of(10L), granted.menuIds(), "未开通模块的菜单不生效");
        assertFalse(granted.permissionCodes().contains("followup:task:view"));
        assertFalse(granted.permissionCodes().contains("rct:crf:view"));
    }

    @Test
    void menusOutsideAssignableScopeAreDropped() {
        // 22 不在可分配范围（平台已收回）：角色授权仍在租户库，但有效权限即时收缩
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        hospitalMenus(),
                        List.of(10L, 21L, 22L),
                        List.of("base", "followup"),
                        Set.of(10L, 21L));
        assertEquals(List.of(10L, 21L), granted.menuIds());
        assertFalse(granted.permissionCodes().contains("followup:task:view"));
    }

    @Test
    void disabledMenusNeverComeBack() {
        // 平台停用的菜单不会出现在 listMenus（启用集）里，授权残留自然失效
        List<MenuRow> enabledOnly =
                hospitalMenus().stream().filter(row -> row.getId() != 23L).toList();
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        enabledOnly, List.of(23L), List.of("base", "followup"), Set.of(23L));
        assertTrue(granted.menuIds().isEmpty());
        assertTrue(granted.permissionCodes().isEmpty());
    }

    @Test
    void permissionCodesAreDeduplicated() {
        // 两个角色都授权同一按钮（并集后重复）时权限码只保留一份
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        hospitalMenus(),
                        List.of(21L, 21L),
                        List.of("base", "followup"),
                        Set.of(21L));
        assertEquals(List.of("followup:patient:view"), granted.permissionCodes());
    }

    @Test
    void blankOrNullPermCodesAreIgnored() {
        // 目录 / 菜单行没有权限码，不应产生空串权限码
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        hospitalMenus(), List.of(10L, 20L), List.of("base", "followup"), null);
        assertEquals(List.of(10L, 20L), granted.menuIds());
        assertTrue(granted.permissionCodes().isEmpty());
    }

    @Test
    void platformSideSkipsAssignableIntersection() {
        // 平台端 assignable 传 null：不受租户可分配范围限制
        EffectivePermissionService.Granted granted =
                EffectivePermissionService.grant(
                        List.of(menu(50L, "base", "platform:tenant:view")),
                        List.of(50L),
                        List.of("base", "followup"),
                        null);
        assertEquals(List.of(50L), granted.menuIds());
        assertEquals(List.of("platform:tenant:view"), granted.permissionCodes());
    }
}
