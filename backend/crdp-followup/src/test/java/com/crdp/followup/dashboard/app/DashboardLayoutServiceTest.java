package com.crdp.followup.dashboard.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.app.DashboardLayoutService.DashboardLayoutView;
import com.crdp.followup.dashboard.app.DashboardLayoutService.LayoutCardView;
import com.crdp.followup.dashboard.app.DashboardLayoutService.RoleCardSave;
import com.crdp.followup.dashboard.app.DashboardLayoutService.RoleLayoutsView;
import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardLayoutMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E19-T06 / A-DASH-013、A-DASH-014 布局服务单测（tasks.csv 验收「按角色配置后即生效」的解析与约束）：
 *
 * <ul>
 *   <li>未配置角色用默认布局（A 默认不含节点随访率——default_visible=0，B / C 经角色配置开启，A 规格 §14）；
 *   <li>角色配置决定显示与顺序（验收「随访员只勾选任务区与指标卡，任务区排在第一」）；多角色取并集、 顺序取第一个已配置布局的角色（按角色创建序，OQ-111），其余卡片排在后面；
 *   <li>目录 ∩ 功能开关 ∩ 权限：配置不放宽权限（node_follow_rate 关闭、无卡片权限时该卡不出现）；
 *   <li>用户隐藏 / 显示（P1）：只能在角色允许且 allowUserHide 的卡片内隐藏；恢复默认 = 空清单全量覆盖；
 *   <li>配置页读取（默认 / 已配置视图）与保存校验（未知卡片 40001、顺序重复 40001、角色不存在 40400）、 恢复默认（DELETE）。
 * </ul>
 *
 * <p>端到端（角色配置后下一次 GET 即生效、双租户隔离、40300）在 DashboardLayoutIT 覆盖；本测锁定合并算法 与校验。全部虚构数据。
 */
class DashboardLayoutServiceTest {

    private static final String PERM = "followup:dashboard:view";

    private final DashboardLayoutMapper layouts = mock(DashboardLayoutMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final DashboardLayoutService service =
            new DashboardLayoutService(layouts, features, audit);

    @BeforeEach
    void seedCatalog() {
        // 目录种子（followup V77）：A 默认布局，node_rate 默认不显示且受 node_follow_rate 开关
        lenient()
                .when(layouts.findCards())
                .thenReturn(
                        List.of(
                                card("kpi", 10, true, null),
                                card("trend", 20, true, null),
                                card("task_board", 30, true, null),
                                card("task_table", 40, true, null),
                                card("yearly_rate", 50, true, null),
                                card("node_rate", 60, false, "node_follow_rate"),
                                card("baseline_completeness", 70, true, null),
                                card("followup_completeness", 71, true, null)));
        lenient().when(features.findEnabled(any())).thenReturn(true);
        lenient().when(layouts.findRoleIdsByCodes(anyList())).thenReturn(List.of());
        lenient().when(layouts.findRoleLayouts(anyList())).thenReturn(List.of());
        lenient().when(layouts.findUserPrefs(anyLong())).thenReturn(List.of());
        lenient().when(layouts.findRoleOptions()).thenReturn(List.of());
        lenient().when(layouts.findRoleLayout(anyLong())).thenReturn(List.of());
        lenient().when(layouts.findRoleId(101L)).thenReturn(101L);
    }

    private static DashboardLayoutMapper.CardRow card(
            String code, int order, boolean visible, String feature) {
        return new DashboardLayoutMapper.CardRow(
                code,
                code + "标题",
                "HALF".equals(code) ? "HALF" : "FULL",
                visible,
                order,
                PERM,
                feature,
                true);
    }

    private static SessionPrincipal user(long userId, List<String> roleCodes) {
        return principal(userId, roleCodes, List.of(PERM));
    }

    private static SessionPrincipal principal(
            long userId, List<String> roles, List<String> permissions) {
        return new SessionPrincipal(
                "token-e19t06",
                "HOSPITAL",
                1L,
                "demoa",
                "示例医院（示例）",
                userId,
                "user" + userId,
                "用户" + userId + "（示例）",
                roles,
                List.of(),
                permissions,
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private static List<String> visibleCodes(DashboardLayoutView view) {
        List<String> codes = new ArrayList<>();
        for (LayoutCardView card : view.cards()) {
            if (card.visible()) {
                codes.add(card.code());
            }
        }
        return codes;
    }

    // ---------------------------------------------------------------- 默认布局（未配置角色）

    @Test
    void unconfiguredRoleFallsBackToDefaultLayoutWithoutNodeRate() {
        when(layouts.findRoleIdsByCodes(List.of("A_FOLLOWUP_NURSE"))).thenReturn(List.of(101L));

        DashboardLayoutView view = service.current(user(9L, List.of("A_FOLLOWUP_NURSE")));

        assertEquals("DEFAULT", view.source());
        // A 默认布局（A 规格 §9）：node_rate default_visible=0 不出现；其余按 default_order
        assertEquals(
                List.of(
                        "kpi",
                        "trend",
                        "task_board",
                        "task_table",
                        "yearly_rate",
                        "baseline_completeness",
                        "followup_completeness"),
                visibleCodes(view));
        assertEquals(1, view.cards().get(0).order(), "顺序归一为从 1 起");
        assertTrue(view.cards().get(0).allowUserHide(), "目录默认允许用户隐藏");
        assertTrue(view.cards().get(0).visible());
        assertFalse(view.cards().get(0).userHidden());
    }

    @Test
    void featureOffCardExcludedEvenWhenRoleConfiguredVisible() {
        when(layouts.findRoleIdsByCodes(List.of("R1"))).thenReturn(List.of(101L));
        when(layouts.findRoleLayouts(List.of(101L)))
                .thenReturn(List.of(row(101L, "kpi", true, 1), row(101L, "node_rate", true, 2)));
        when(features.findEnabled("node_follow_rate")).thenReturn(false);

        DashboardLayoutView view = service.current(user(9L, List.of("R1")));

        // 开关关闭 → 卡片不可用（配置不放宽开关，AGENTS §5.7；前端隐藏入口同判定）
        assertEquals(List.of("kpi"), visibleCodes(view));
    }

    @Test
    void cardWithoutPermissionExcluded() {
        SessionPrincipal noView = principal(9L, List.of("R1"), List.of("followup:plan:view"));
        when(layouts.findRoleIdsByCodes(List.of("R1"))).thenReturn(List.of(101L));

        DashboardLayoutView view = service.current(noView);

        assertEquals(List.of(), visibleCodes(view), "无卡片权限 → 全部不出现（不报错）");
    }

    // ---------------------------------------------------------------- 按角色配置（A-DASH-013）

    @Test
    void roleConfigTakesEffectWithConfiguredOrder() {
        // 验收（A 规格 §9）：随访员角色只勾选任务区与指标卡，任务区排在第一
        when(layouts.findRoleIdsByCodes(List.of("A_FOLLOWUP_NURSE"))).thenReturn(List.of(101L));
        when(layouts.findRoleLayouts(List.of(101L)))
                .thenReturn(
                        List.of(
                                row(101L, "task_board", true, 1),
                                row(101L, "kpi", true, 2),
                                row(101L, "trend", false, 3)));

        DashboardLayoutView view = service.current(user(9L, List.of("A_FOLLOWUP_NURSE")));

        assertEquals("ROLE_CONFIG", view.source());
        assertEquals(List.of("task_board", "kpi"), view.codes());
        assertEquals(List.of("task_board", "kpi"), visibleCodes(view));
        assertEquals(1, view.cards().get(0).order());
        assertEquals(2, view.cards().get(1).order());
    }

    @Test
    void multiRoleMergesUnionAndOrdersByFirstConfiguredRole() {
        // 角色创建序：102（已配置）→ 103（未配置）→ 105（已配置）；顺序取 102，其余（yearly_rate）排在后面
        when(layouts.findRoleIdsByCodes(List.of("R2", "R3", "R5")))
                .thenReturn(List.of(102L, 103L, 105L));
        when(layouts.findRoleLayouts(List.of(102L, 103L, 105L)))
                .thenReturn(
                        List.of(
                                row(102L, "kpi", true, 2),
                                row(102L, "trend", true, 1),
                                row(105L, "trend", false, 1),
                                row(105L, "yearly_rate", true, 2)));

        DashboardLayoutView view = service.current(user(9L, List.of("R2", "R3", "R5")));

        assertEquals("ROLE_CONFIG", view.source());
        // 并集 = kpi ∪ trend ∪ yearly_rate（trend 在 102 可见、105 隐藏 → 并集可见）；顺序取 102：trend、kpi
        assertEquals(List.of("trend", "kpi", "yearly_rate"), view.codes());
    }

    @Test
    void allowUserHideReadFromFirstRoleThatConfiguresTheCard() {
        when(layouts.findRoleIdsByCodes(List.of("R2", "R5"))).thenReturn(List.of(102L, 105L));
        when(layouts.findRoleLayouts(List.of(102L, 105L)))
                .thenReturn(
                        List.of(
                                row(102L, "kpi", true, 1),
                                new DashboardLayoutMapper.RoleLayoutRow(
                                        105L, "kpi", true, 1, false)));

        DashboardLayoutView view = service.current(user(9L, List.of("R2", "R5")));

        assertTrue(view.cards().get(0).allowUserHide(), "取第一个配置该卡的角色（102）的允许隐藏值");
    }

    // ---------------------------------------------------------------- 用户隐藏 / 显示（A-DASH-014，P1）

    @Test
    void userPrefHidesCardWithinRoleAllowedRange() {
        when(layouts.findRoleIdsByCodes(List.of("R1"))).thenReturn(List.of(101L));
        when(layouts.findUserPrefs(9L))
                .thenReturn(List.of(new DashboardLayoutMapper.UserPrefRow(9L, "trend")));

        DashboardLayoutView view = service.current(user(9L, List.of("R1")));

        LayoutCardView trend =
                view.cards().stream()
                        .filter(card -> "trend".equals(card.code()))
                        .findFirst()
                        .orElseThrow();
        assertFalse(trend.visible(), "用户隐藏后不显示");
        assertTrue(trend.userHidden());
        assertEquals(
                List.of(
                        "kpi",
                        "task_board",
                        "task_table",
                        "yearly_rate",
                        "baseline_completeness",
                        "followup_completeness"),
                visibleCodes(view),
                "隐藏行保留在响应里供「卡片设置」重新显示，其余照常");
    }

    @Test
    void saveUserPrefRejectsCardOutsideRoleAllowedRange() {
        when(layouts.findRoleIdsByCodes(List.of("A_FOLLOWUP_NURSE"))).thenReturn(List.of(101L));
        when(layouts.findRoleLayouts(List.of(101L)))
                .thenReturn(List.of(row(101L, "kpi", true, 1), row(101L, "trend", false, 2)));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveUserPref(
                                        user(9L, List.of("A_FOLLOWUP_NURSE")),
                                        new DashboardLayoutService.UserPrefSave(List.of("trend"))));

        assertEquals(40001, error.code(), "不能隐藏角色未允许的卡片");
        verify(layouts, never()).deleteUserPrefs(anyLong());
    }

    @Test
    void saveUserPrefRejectsCardNotAllowedToHide() {
        when(layouts.findRoleIdsByCodes(List.of("R1"))).thenReturn(List.of(101L));
        when(layouts.findRoleLayouts(List.of(101L)))
                .thenReturn(
                        List.of(
                                new DashboardLayoutMapper.RoleLayoutRow(
                                        101L, "kpi", true, 1, false)));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveUserPref(
                                        user(9L, List.of("R1")),
                                        new DashboardLayoutService.UserPrefSave(List.of("kpi"))));

        assertEquals(40001, error.code(), "allowUserHide=0 的卡片不可隐藏");
    }

    @Test
    void saveUserPrefReplacesRowsAndEmptyListRestoresRoleDefault() {
        when(layouts.findRoleIdsByCodes(List.of("R1"))).thenReturn(List.of(101L));

        service.saveUserPref(
                user(9L, List.of("R1")),
                new DashboardLayoutService.UserPrefSave(List.of("trend", "trend")));

        verify(layouts).deleteUserPrefs(9L);
        verify(layouts).insertUserPrefRow(9L, "trend");
        verify(audit).append(any(AuditEntry.class));

        // 恢复默认 = 空清单（PUT 全量覆盖语义，A-DASH-014「可恢复为角色默认」）
        service.saveUserPref(
                user(9L, List.of("R1")), new DashboardLayoutService.UserPrefSave(List.of()));
        verify(layouts, times(2)).deleteUserPrefs(9L);
        verify(layouts, times(1)).insertUserPrefRow(9L, "trend");
    }

    // ----------------------------------------------------------------
    // 配置页（followup:dashboard:config）

    @Test
    void roleViewDefaultsToFirstRoleAndCatalogDefaults() {
        when(layouts.findRoleOptions())
                .thenReturn(
                        List.of(
                                new DashboardLayoutMapper.RoleOptionRow(
                                        101L, "TENANT_ADMIN", "租户管理员"),
                                new DashboardLayoutMapper.RoleOptionRow(
                                        102L, "A_FOLLOWUP_NURSE", "随访员")));

        RoleLayoutsView view = service.roleView(null);

        assertEquals(2, view.roles().size());
        assertEquals(101L, view.current().roleId());
        assertEquals("租户管理员", view.current().roleName());
        assertFalse(view.current().configured(), "未配置 → 目录默认视图");
        assertEquals(8, view.current().cards().size(), "配置页列出全部目录卡片（含默认隐藏）");
        assertTrue(
                view.current().cards().stream().anyMatch(card -> "node_rate".equals(card.code())));
    }

    @Test
    void roleViewConfiguredRowsFirstThenUnconfiguredCatalogCards() {
        when(layouts.findRoleOptions())
                .thenReturn(List.of(new DashboardLayoutMapper.RoleOptionRow(102L, "R", "随访员")));
        when(layouts.findRoleLayout(102L))
                .thenReturn(
                        List.of(
                                row(102L, "task_board", true, 1),
                                row(102L, "kpi", true, 2),
                                row(102L, "trend", false, 3)));

        RoleLayoutsView view = service.roleView(102L);

        assertTrue(view.current().configured());
        assertEquals(
                List.of(
                        "task_board",
                        "kpi",
                        "trend",
                        "task_table",
                        "yearly_rate",
                        "node_rate",
                        "baseline_completeness",
                        "followup_completeness"),
                view.current().cards().stream().map(RoleCardViewCode).toList());
        assertFalse(view.current().cards().get(2).visible(), "trend 配置隐藏");
    }

    @Test
    void saveRoleLayoutValidatesRoleCardsAndOrders() {
        when(layouts.findRoleOptions())
                .thenReturn(List.of(new DashboardLayoutMapper.RoleOptionRow(101L, "R", "随访员")));
        ApiException missingRole =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRoleLayout(
                                        user(1L, List.of("TENANT_ADMIN")),
                                        404L,
                                        List.of(new RoleCardSave("kpi", true, 1, null))));
        assertEquals(40400, missingRole.code(), "角色不存在");

        ApiException unknownCard =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRoleLayout(
                                        user(1L, List.of("TENANT_ADMIN")),
                                        101L,
                                        List.of(new RoleCardSave("no_such_card", true, 1, null))));
        assertEquals(40001, unknownCard.code(), "未知卡片编码");

        ApiException duplicateOrder =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRoleLayout(
                                        user(1L, List.of("TENANT_ADMIN")),
                                        101L,
                                        List.of(
                                                new RoleCardSave("kpi", true, 1, null),
                                                new RoleCardSave("trend", true, 1, null))));
        assertEquals(40001, duplicateOrder.code(), "顺序重复");

        ApiException blankVisible =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRoleLayout(
                                        user(1L, List.of("TENANT_ADMIN")),
                                        101L,
                                        List.of(new RoleCardSave("kpi", null, 1, null))));
        assertEquals(40001, blankVisible.code(), "visible 必填");
    }

    @Test
    void saveRoleLayoutReplacesRowsAuditsAndTakesEffect() {
        when(layouts.findRoleOptions())
                .thenReturn(List.of(new DashboardLayoutMapper.RoleOptionRow(101L, "R", "随访员")));
        // 保存前无配置行、保存后读到新行（真实库由 DELETE+INSERT 保证，这里用链式桩模拟）
        when(layouts.findRoleLayout(101L))
                .thenReturn(List.of())
                .thenReturn(List.of(row(101L, "task_board", true, 1), row(101L, "kpi", true, 2)));

        RoleLayoutsView view =
                service.saveRoleLayout(
                        user(1L, List.of("TENANT_ADMIN")),
                        101L,
                        List.of(
                                new RoleCardSave("task_board", true, 1, false),
                                new RoleCardSave("kpi", true, 2, null)));

        verify(layouts).deleteRoleLayout(101L);
        verify(layouts).insertRoleLayoutRow(101L, "task_board", true, 1, false, 1L);
        verify(layouts).insertRoleLayoutRow(101L, "kpi", true, 2, true, 1L);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("DASHBOARD_ROLE_LAYOUT", entry.getValue().action());
        assertTrue(view.current().configured(), "保存后即视为已配置（下一次 GET 即生效，无缓存）");
    }

    @Test
    void resetRoleLayoutDeletesRowsAndAudits() {
        when(layouts.findRoleOptions())
                .thenReturn(List.of(new DashboardLayoutMapper.RoleOptionRow(101L, "R", "随访员")));
        // 恢复前有一行、清空后读到空（真实库由 DELETE 保证，这里用链式桩模拟）
        when(layouts.findRoleLayout(101L))
                .thenReturn(List.of(row(101L, "kpi", true, 1)))
                .thenReturn(List.of());

        RoleLayoutsView view = service.resetRoleLayout(user(1L, List.of("TENANT_ADMIN")), 101L);

        verify(layouts).deleteRoleLayout(101L);
        assertFalse(view.current().configured(), "恢复默认后回到目录默认布局");
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("DASHBOARD_ROLE_LAYOUT", entry.getValue().action());
    }

    // ---------------------------------------------------------------- 内部

    private static final java.util.function.Function<DashboardLayoutService.RoleCardView, String>
            RoleCardViewCode = DashboardLayoutService.RoleCardView::code;

    private static DashboardLayoutMapper.RoleLayoutRow row(
            long roleId, String cardCode, boolean visible, int sortOrder) {
        return new DashboardLayoutMapper.RoleLayoutRow(roleId, cardCode, visible, sortOrder, true);
    }
}
