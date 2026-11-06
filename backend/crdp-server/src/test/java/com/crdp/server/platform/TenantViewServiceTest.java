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
                null, STORE, new TokenGenerator(), props(), noopAudit, "http://127.0.0.1:14173");
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
}
