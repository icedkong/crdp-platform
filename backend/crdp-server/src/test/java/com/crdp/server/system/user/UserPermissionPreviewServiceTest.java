package com.crdp.server.system.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.platform.menu.MenuRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** E06-T09 / PLT-ORG-009、A-WARD-009：权限预览纯函数（菜单树装配、来源角色分组、 数据范围与可见病区归并）的单测。 */
class UserPermissionPreviewServiceTest {

    private static MenuRow menu(long id, long parentId, String type, String title, String perm) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setParentId(parentId);
        row.setType(type);
        row.setModuleCode("followup");
        row.setTitle(title);
        row.setPermCode(perm);
        row.setSortNo(10);
        return row;
    }

    @Test
    void buildsMenuTreeWithButtonsAndSourceRoles() {
        List<MenuRow> platformMenus =
                List.of(
                        menu(20, 0, "DIR", "随访", null),
                        menu(21, 20, "MENU", "患者档案", "followup:patient:view"),
                        menu(211, 21, "BUTTON", "查看明文", "followup:patient:plain"),
                        menu(22, 20, "MENU", "随访任务", "followup:task:view"),
                        menu(31, 0, "MENU", "用户管理", "tenant:user:view"));
        Map<Long, List<String>> sources =
                Map.of(
                        20L, List.of("随访医生"),
                        21L, List.of("随访医生", "IT 示例角色"),
                        22L, List.of("随访医生"),
                        211L, List.of("IT 示例角色"));

        List<UserPermissionPreviewService.MenuPreviewNode> tree =
                UserPermissionPreviewService.menuTree(
                        platformMenus, Set.of(20L, 21L, 22L, 211L), sources);

        assertEquals(1, tree.size(), "只有「随访」目录生效（31 未授权）：" + tree.size());
        UserPermissionPreviewService.MenuPreviewNode dir = tree.get(0);
        assertEquals("随访", dir.title());
        assertEquals(2, dir.children().size(), "目录下有患者档案与随访任务");
        UserPermissionPreviewService.MenuPreviewNode patient =
                dir.children().stream()
                        .filter(node -> "患者档案".equals(node.title()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of("随访医生", "IT 示例角色"), patient.sourceRoles(), "多角色来源按角色序去重合并");
        assertEquals(1, patient.children().size(), "按钮挂在菜单下");
        assertEquals("查看明文", patient.children().get(0).title());
        assertEquals("followup:patient:plain", patient.children().get(0).permCode());
        assertEquals(List.of("IT 示例角色"), patient.children().get(0).sourceRoles(), "按钮级来源角色");
    }

    @Test
    void wardScopeOnlyWhenWardTypeEffectiveAndNotAll() {
        // 无 WARD 范围类型：即使本人负责病区也不展示（快照同口径：范围由角色触发）
        assertFalse(
                UserPermissionPreviewService.wardsEffective(false, List.of("RESPONSIBLE_PATIENT")),
                "无 WARD 类型不触发病区清单");
        // 任一角色 ALL：全院可见，不列病区（A-WARD-007）
        assertFalse(UserPermissionPreviewService.wardsEffective(true, List.of("WARD")));
        // WARD 生效
        assertTrue(UserPermissionPreviewService.wardsEffective(false, List.of("WARD")));
    }

    @Test
    void deptScopeTriggeredByDeptLikeTypes() {
        assertTrue(
                UserPermissionPreviewService.deptsEffective(false, List.of("RESPONSIBLE_DEPT")),
                "RESPONSIBLE_DEPT 触发负责科室清单");
        assertTrue(UserPermissionPreviewService.deptsEffective(false, List.of("DEPT")));
        assertTrue(UserPermissionPreviewService.deptsEffective(false, List.of("CUSTOM_DEPT")));
        assertFalse(
                UserPermissionPreviewService.deptsEffective(false, List.of("WARD")),
                "WARD 不触发科室清单");
        assertFalse(UserPermissionPreviewService.deptsEffective(true, List.of("DEPT")));
    }

    @Test
    void groupsSourceRolesByScopeTypeKeepingStableOrder() {
        List<UserPermissionPreviewService.RoleScopeRow> rows =
                List.of(
                        new UserPermissionPreviewService.RoleScopeRow(2, "IT 角色乙", "WARD"),
                        new UserPermissionPreviewService.RoleScopeRow(1, "随访医生", "WARD"),
                        new UserPermissionPreviewService.RoleScopeRow(1, "随访医生", "SELF"),
                        new UserPermissionPreviewService.RoleScopeRow(3, "IT 角色丙", "WARD"));
        Map<String, List<String>> grouped = UserPermissionPreviewService.scopeSources(rows);
        assertEquals(List.of("随访医生", "IT 角色乙", "IT 角色丙"), grouped.get("WARD"), "按角色序合并来源");
        assertEquals(List.of("随访医生"), grouped.get("SELF"));
    }

    // ------------------------------------------------ E07-T04 / PLT-PERM-009：字段策略合并预览

    /** 无任何覆盖：五位敏感字段按目录展示，全部回落平台默认 MASKED、无来源角色。 */
    @Test
    void fieldPolicyViewsWithoutOverridesFallBackToDefaults() {
        List<UserPermissionPreviewService.FieldPolicyView> fields =
                UserPermissionPreviewService.fieldPolicyViews(List.of());

        assertEquals(
                List.of("name", "idCard", "phone", "medicalRecordNo", "address"),
                fields.stream().map(UserPermissionPreviewService.FieldPolicyView::field).toList(),
                "目录字段与顺序");
        for (UserPermissionPreviewService.FieldPolicyView field : fields) {
            assertEquals("MASKED", field.defaultPolicy());
            assertEquals("MASKED", field.policy(), field.field() + " 未覆盖回落默认");
            assertFalse(field.overridden());
            assertTrue(field.sourceRoles().isEmpty(), "默认策略没有来源角色");
        }
    }

    /** E07-T04 验收：隐藏、脱敏、明文按角色合并（取最宽松），sourceRoles 只列授出生效策略的角色。 */
    @Test
    void fieldPolicyViewsMergeMostPermissiveWithWinnerSourceRoles() {
        List<UserPermissionPreviewService.RoleFieldPolicyRow> rows =
                List.of(
                        // 姓名三角色混合：甲收 HIDDEN、乙脱敏、丙放开 → 明文，来源只列丙
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                1, "IT 角色甲", "patient", "name", "HIDDEN"),
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                2, "IT 角色乙", "patient", "name", "MASKED"),
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                3, "IT 角色丙", "patient", "name", "VISIBLE"),
                        // 住址只有甲收紧 HIDDEN → 生效 HIDDEN（未覆盖的角色不注入默认参与合并）
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                1, "IT 角色甲", "patient", "address", "HIDDEN"),
                        // 病案号两个角色都放开 VISIBLE → 去重列两个来源
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                2, "IT 角色乙", "patient", "medicalRecordNo", "VISIBLE"),
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                3, "IT 角色丙", "patient", "medicalRecordNo", "VISIBLE"),
                        // 手机号显式 MASKED（与默认同值，但仍是覆盖）
                        new UserPermissionPreviewService.RoleFieldPolicyRow(
                                1, "IT 角色甲", "patient", "phone", "MASKED"));

        List<UserPermissionPreviewService.FieldPolicyView> fields =
                UserPermissionPreviewService.fieldPolicyViews(rows);

        UserPermissionPreviewService.FieldPolicyView name = byField(fields, "name");
        assertEquals("VISIBLE", name.policy(), "隐藏 + 脱敏 + 明文 → 明文（叠加授权取最宽松）");
        assertTrue(name.overridden());
        assertEquals(List.of("IT 角色丙"), name.sourceRoles(), "只列授出生效策略的角色");

        UserPermissionPreviewService.FieldPolicyView address = byField(fields, "address");
        assertEquals("HIDDEN", address.policy(), "唯一显式覆盖生效");
        assertEquals(List.of("IT 角色甲"), address.sourceRoles());

        UserPermissionPreviewService.FieldPolicyView recordNo = byField(fields, "medicalRecordNo");
        assertEquals("VISIBLE", recordNo.policy());
        assertEquals(List.of("IT 角色乙", "IT 角色丙"), recordNo.sourceRoles(), "同策略多角色按角色序去重");

        UserPermissionPreviewService.FieldPolicyView phone = byField(fields, "phone");
        assertEquals("MASKED", phone.policy());
        assertTrue(phone.overridden(), "显式 MASKED 虽与默认同值，仍是覆盖");

        UserPermissionPreviewService.FieldPolicyView idCard = byField(fields, "idCard");
        assertEquals("MASKED", idCard.policy(), "无覆盖字段回落默认");
        assertFalse(idCard.overridden());
        assertTrue(idCard.sourceRoles().isEmpty());
    }

    /** 防御脏数据：目录外字段与非法策略行不进预览、不参与合并。 */
    @Test
    void fieldPolicyViewsIgnoreDirtyRows() {
        List<UserPermissionPreviewService.FieldPolicyView> fields =
                UserPermissionPreviewService.fieldPolicyViews(
                        List.of(
                                new UserPermissionPreviewService.RoleFieldPolicyRow(
                                        1, "IT 角色甲", "patient", "nickname", "VISIBLE"),
                                new UserPermissionPreviewService.RoleFieldPolicyRow(
                                        1, "IT 角色甲", "patient", "name", "PLAIN")));
        assertEquals("MASKED", byField(fields, "name").policy(), "非法策略行忽略，回落默认");
        assertTrue(fields.stream().noneMatch(field -> "nickname".equals(field.field())));
    }

    private UserPermissionPreviewService.FieldPolicyView byField(
            List<UserPermissionPreviewService.FieldPolicyView> fields, String field) {
        return fields.stream().filter(item -> field.equals(item.field())).findFirst().orElseThrow();
    }
}
