package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.TokenGenerator;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantViewService.EnterAuthorization;
import com.crdp.server.platform.TenantViewService.RoleViewGrant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E20-T01 / PLT-VIEW-001、004、005、006 单元测试（纯函数）：目的与说明校验、多角色进入授权并集、 视角会话草稿的时长冻结（2 小时 / 空闲 15
 * 分钟）、文案口径。
 */
class TenantViewServiceTest {

    private static final SessionStore STORE =
            new SessionStore(null, new com.fasterxml.jackson.databind.ObjectMapper(), props());

    private static SecurityProperties props() {
        SecurityProperties properties = new SecurityProperties();
        properties.setPlatformSessionIdleSeconds(900);
        properties.setSessionAbsoluteSeconds(43200);
        return properties;
    }

    private static TenantViewService service() {
        com.crdp.framework.audit.AuditLogger noopAudit =
                new com.crdp.framework.audit.AuditLogger() {
                    @Override
                    public void append(com.crdp.framework.audit.AuditEntry entry) {}

                    @Override
                    @SuppressWarnings("deprecation")
                    public void record(com.crdp.framework.audit.AuditEvent event) {}

                    @Override
                    public void loginAttempt(String username, boolean success, String message) {}
                };
        return new TenantViewService(
                null,
                STORE,
                new TokenGenerator(),
                props(),
                noopAudit,
                null,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "http://127.0.0.1:14173",
                // 视角规则（E20-T02）：单测只测纯函数，不触库；进入时的规则版本戳由 IT 覆盖
                new ViewRuleService(
                        null, noopAudit, new com.fasterxml.jackson.databind.ObjectMapper()),
                // 代为操作能力（E20-T06）：单测只测纯函数，不触库
                new TenantViewProxyService(null, null, null, null, null, noopAudit));
    }

    // ---------------------------------------------------------------- 校验（PLT-VIEW-001）

    @Test
    void purposeMustBeKnownEnum() {
        assertEquals("TROUBLESHOOT", TenantViewService.requirePurpose(" TROUBLESHOOT "));
        assertEquals("OTHER", TenantViewService.requirePurpose("OTHER"));
        ApiException unknown =
                assertThrows(ApiException.class, () -> TenantViewService.requirePurpose("AUDIT"));
        assertEquals(40001, unknown.code());
        ApiException missing =
                assertThrows(ApiException.class, () -> TenantViewService.requirePurpose(null));
        assertEquals(40001, missing.code());
    }

    @Test
    void noteIsRequiredAndBounded() {
        assertEquals("排查登录失败", TenantViewService.requireNote(" 排查登录失败 "));
        ApiException missing =
                assertThrows(ApiException.class, () -> TenantViewService.requireNote("  "));
        assertEquals(40001, missing.code());
        ApiException tooLong =
                assertThrows(
                        ApiException.class, () -> TenantViewService.requireNote("长".repeat(501)));
        assertEquals(40001, tooLong.code());
    }

    // ---------------------------------------------------------------- 进入授权并集（PLT-VIEW-005、006）

    @Test
    void noEnterCapableRoleMeansNoEntry() {
        EnterAuthorization authorization =
                EnterAuthorization.of(
                        List.of(
                                new RoleViewGrant(false, "ALL", Set.of()),
                                new RoleViewGrant(false, "RESPONSIBLE", Set.of())),
                        Set.of(1L));
        assertFalse(authorization.canEnter());
        assertFalse(authorization.enterable(1L));
    }

    @Test
    void allScopeOpensEveryTenant() {
        EnterAuthorization authorization =
                EnterAuthorization.of(
                        List.of(
                                new RoleViewGrant(false, "ALL", Set.of()),
                                new RoleViewGrant(true, "ALL", Set.of())),
                        Set.of());
        assertTrue(authorization.canEnter());
        assertTrue(authorization.allTenants());
        assertTrue(authorization.enterable(999L));
    }

    @Test
    void responsibleScopeOnlyEntersAssignedTenants() {
        // 客服支持默认形态（种子 V104）：RESPONSIBLE——只能进入分配的负责租户（验收：只能进入负责租户）
        EnterAuthorization authorization =
                EnterAuthorization.of(
                        List.of(new RoleViewGrant(true, "RESPONSIBLE", Set.of())), Set.of(2L, 5L));
        assertTrue(authorization.canEnter());
        assertFalse(authorization.allTenants());
        assertTrue(authorization.enterable(2L));
        assertTrue(authorization.enterable(5L));
        assertFalse(authorization.enterable(3L));
    }

    @Test
    void specifiedScopeOnlyEntersListedTenants() {
        EnterAuthorization authorization =
                EnterAuthorization.of(
                        List.of(new RoleViewGrant(true, "SPECIFIED", Set.of(7L))), Set.of());
        assertTrue(authorization.enterable(7L));
        assertFalse(authorization.enterable(2L));
    }

    @Test
    void multipleRolesUnionScopes() {
        // 多角色并集（platform-spec §8 已确认）：RESPONSIBLE ∪ SPECIFIED ∪ 不可进入角色（不放宽）
        EnterAuthorization authorization =
                EnterAuthorization.of(
                        List.of(
                                new RoleViewGrant(true, "RESPONSIBLE", Set.of()),
                                new RoleViewGrant(true, "SPECIFIED", Set.of(7L, 8L)),
                                new RoleViewGrant(false, "ALL", Set.of())),
                        Set.of(2L));
        assertTrue(authorization.canEnter());
        assertFalse(authorization.allTenants());
        assertTrue(authorization.enterable(2L));
        assertTrue(authorization.enterable(7L));
        assertTrue(authorization.enterable(8L));
        assertFalse(authorization.enterable(3L));
    }

    // ---------------------------------------------------------------- 会话草稿（PLT-VIEW-004）

    @Test
    void viewDraftFreezesTwoHourCapAndPlatformIdle() {
        TenantViewService service = service();
        SessionPrincipal caller =
                new SessionPrincipal(
                        "caller-token",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        9L,
                        "ops.demo",
                        "运维（示例）",
                        List.of("OPERATIONS"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        com.crdp.framework.security.ScopeSnapshot.allowAll(),
                        false,
                        null);
        SessionPrincipal draft = service.viewDraft(caller, tenantRow());
        assertTrue(draft.tenantView());
        assertEquals(10L, draft.tenantId());
        assertEquals("demoa", draft.tenantCode());
        assertEquals(9L, draft.userId());
        assertEquals("ops.demo", draft.username());
        assertEquals(List.of("OPERATIONS"), draft.roles());
        // 空闲 15 分钟（平台端口径）+ 单次 2 小时上限冻结进会话（比全局 12 小时短，到时自动退出）
        assertEquals(900L, draft.idleSeconds());
        assertEquals(TenantViewService.MAX_SESSION_SECONDS, draft.absoluteSeconds());
        assertTrue(draft.menuIds().isEmpty() && draft.permissionCodes().isEmpty());
    }

    private static Map<String, Object> tenantRow() {
        return Map.of("id", 10L, "code", "demoa", "name", "演示医院 A（示例）", "status", "ENABLED");
    }

    // ---------------------------------------------------------------- 文案

    @Test
    void purposeAndEndMethodTexts() {
        assertEquals("排障", TenantViewService.purposeText("TROUBLESHOOT"));
        assertEquals("配置核对", TenantViewService.purposeText("CONFIG_CHECK"));
        assertEquals("培训支持", TenantViewService.purposeText("TRAINING"));
        assertEquals("其他", TenantViewService.purposeText("OTHER"));
        assertEquals("主动退出", TenantViewService.endMethodText(TenantViewService.END_LOGOUT));
        assertEquals("到时", TenantViewService.endMethodText(TenantViewService.END_TIMEOUT));
        assertEquals("空闲超时", TenantViewService.endMethodText(TenantViewService.END_IDLE));
        assertEquals("权限变更", TenantViewService.endMethodText(TenantViewService.END_PERM_CHANGE));
    }

    // ---------------------------------------------------------------- 视角菜单（E20-T04 / PLT-VIEW-002）

    /** 种子 V100 的医院端菜单骨架（含迁移追加的隐藏路由与按钮形态）。 */
    private static List<TenantViewService.MenuLite> hospitalMenus() {
        return List.of(
                new TenantViewService.MenuLite(10L, 0L, "MENU", "/dashboard", "base"),
                new TenantViewService.MenuLite(20L, 0L, "DIR", "/followup", "followup"),
                new TenantViewService.MenuLite(21L, 20L, "MENU", "/followup/patient", "followup"),
                new TenantViewService.MenuLite(22L, 20L, "MENU", "/followup/task", "followup"),
                new TenantViewService.MenuLite(
                        215L, 21L, "MENU", "/followup/patient/:id", "followup"),
                new TenantViewService.MenuLite(211L, 21L, "BUTTON", null, "followup"),
                new TenantViewService.MenuLite(30L, 0L, "DIR", "/system", "base"),
                new TenantViewService.MenuLite(31L, 30L, "MENU", "/system/user", "base"),
                // module_code 同为 followup 的配置页（字典 / 通知模板）——在 /system 目录下，仅配置类可见
                new TenantViewService.MenuLite(37L, 30L, "MENU", "/system/field", "followup"),
                new TenantViewService.MenuLite(36L, 30L, "MENU", "/system/notice", "followup"));
    }

    @Test
    void viewMenusFollowTenantOpenedModules() {
        // PLT-VIEW-002「按该租户已开通模块」：未开通 followup 的租户只剩 base 菜单
        List<Long> ids = TenantViewService.viewMenuIds(hospitalMenus(), List.of("base"), false);
        assertTrue(ids.containsAll(List.of(10L, 30L, 31L)));
        assertFalse(ids.contains(20L) || ids.contains(21L) || ids.contains(37L));
    }

    @Test
    void businessScopeSeesFollowupSubtreeIncludingHiddenRoutes() {
        // 配置类 + 业务数据：与该院一致（含 /followup 目录、隐藏路由与按钮）
        List<Long> ids =
                TenantViewService.viewMenuIds(hospitalMenus(), List.of("base", "followup"), false);
        assertTrue(ids.containsAll(List.of(10L, 20L, 21L, 22L, 215L, 211L, 30L, 31L, 37L, 36L)));
    }

    @Test
    void configOnlyHidesFollowupBusinessSubtreeButKeepsSystemConfigPages() {
        // 仅配置类（运维角色，种子 V104）：隐藏顶级「随访」目录的全部后代（含隐藏路由 / 按钮），
        // /system 下的字典与通知配置页保留（platform-spec §8 配置类清单含「系统参数与字典、通知模板」）
        List<Long> ids =
                TenantViewService.viewMenuIds(hospitalMenus(), List.of("base", "followup"), true);
        assertTrue(ids.containsAll(List.of(10L, 30L, 31L, 37L, 36L)));
        assertFalse(ids.contains(20L) || ids.contains(21L) || ids.contains(215L));
        assertFalse(ids.contains(211L), "业务按钮（如查看明文）一并隐藏");
    }

    @Test
    void missingFollowupDirectoryMeansNothingClassifiedBusiness() {
        // 目录行缺失（异常态）时不额外隐藏——不把整站菜单误判为业务
        List<TenantViewService.MenuLite> menus =
                hospitalMenus().stream()
                        .filter(row -> row.id() != 20L)
                        .map(
                                row ->
                                        row.parentId() != null && row.parentId() == 20L
                                                ? new TenantViewService.MenuLite(
                                                        row.id(),
                                                        0L,
                                                        row.type(),
                                                        row.path(),
                                                        row.moduleCode())
                                                : row)
                        .toList();
        List<Long> ids = TenantViewService.viewMenuIds(menus, List.of("base", "followup"), true);
        assertTrue(ids.contains(21L) && ids.contains(215L));
    }
}
