package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E05-T06 / PLT-PERM-001、002、003：全局菜单树维护——按钮必填全局唯一权限码（格式 {模块}:{资源}:{动作}）、菜单 /
 * 按钮必须归属已登记模块、上级同端、权限码不可改（OQ-11）、删除为软删并清理授予与递增权限版本。
 */
@ExtendWith(MockitoExtension.class)
class MenuAdminServiceTest {

    @Mock private PlatformMapper platformMapper;
    @Mock private JdbcTemplate platform;
    @Mock private TenantRoutingDataSource routing;
    @Mock private PermissionVersionStore permissionVersions;
    @Mock private AuditLogger audit;

    private MenuAdminService service;

    @BeforeEach
    void setUp() {
        service =
                new MenuAdminService(platformMapper, platform, routing, permissionVersions, audit);
        lenient().when(platformMapper.listModuleCodes()).thenReturn(List.of("base", "followup"));
    }

    private static MenuRow row(long id, String side, String type, String permCode, String status) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setParentId(0L);
        row.setSide(side);
        row.setType(type);
        row.setModuleCode("base");
        row.setTitle("演示菜单");
        row.setRouteName("BUTTON".equals(type) ? null : "Demo" + id);
        row.setPath("BUTTON".equals(type) ? null : "/demo" + id);
        row.setComponent("MENU".equals(type) ? "demo/index" : null);
        row.setPermCode(permCode);
        row.setSortNo(0);
        row.setHidden(0);
        row.setStatus(status);
        row.setSource("MANIFEST");
        return row;
    }

    // ---------------------------------------------------------------- 新建校验

    @Test
    void createButtonRequiresPermCode() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new MenuAdminService.CreateRequest(
                                                "PLATFORM",
                                                "113",
                                                "BUTTON",
                                                "base",
                                                "维护菜单与按钮",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                10,
                                                true)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("权限码"), error.getMessage());
        verify(platformMapper, never()).insertMenu(any());
    }

    @Test
    void createButtonRejectsMalformedPermCode() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new MenuAdminService.CreateRequest(
                                                "PLATFORM",
                                                "113",
                                                "BUTTON",
                                                "base",
                                                "维护菜单与按钮",
                                                null,
                                                null,
                                                null,
                                                "platform_menu_edit",
                                                null,
                                                10,
                                                true)));
        assertEquals(40001, error.code());
    }

    @Test
    void createButtonRejectsDuplicatePermCode() {
        when(platformMapper.countMenuByPermCode("PLATFORM", "platform:menu:edit", 0L))
                .thenReturn(1);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new MenuAdminService.CreateRequest(
                                                "PLATFORM",
                                                "113",
                                                "BUTTON",
                                                "base",
                                                "维护菜单与按钮",
                                                null,
                                                null,
                                                null,
                                                "platform:menu:edit",
                                                null,
                                                10,
                                                true)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("全局唯一"), error.getMessage());
    }

    @Test
    void createRequiresRegisteredModule() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new MenuAdminService.CreateRequest(
                                                "PLATFORM",
                                                "0",
                                                "DIR",
                                                "rct",
                                                "研究",
                                                "Rct",
                                                "/rct",
                                                null,
                                                null,
                                                null,
                                                10,
                                                false)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("归属已登记模块"), error.getMessage());
    }

    @Test
    void createMenuRequiresRouteFields() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new MenuAdminService.CreateRequest(
                                                "PLATFORM",
                                                "0",
                                                "MENU",
                                                "base",
                                                "自定义页",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                10,
                                                false)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("routeName"), error.getMessage());
    }

    @Test
    void createMenuUnderForeignSideParentIsRejected() {
        when(platformMapper.findMenu(21L))
                .thenReturn(row(21L, "HOSPITAL", "MENU", null, "ENABLED"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new MenuAdminService.CreateRequest(
                                                "PLATFORM",
                                                "21",
                                                "BUTTON",
                                                "base",
                                                "按钮",
                                                null,
                                                null,
                                                null,
                                                "base:demo:act",
                                                null,
                                                10,
                                                true)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("同一端"), error.getMessage());
    }

    @Test
    void createButtonInsertsCustomSourceRowAndAudits() {
        when(platformMapper.findMenu(113L))
                .thenReturn(row(113L, "PLATFORM", "MENU", "platform:menu:view", "ENABLED"));
        when(platformMapper.countMenuByPermCode("PLATFORM", "base:demo:act", 0L)).thenReturn(0);
        // 模拟 MySQL 生成主键回填（@Options(useGeneratedKeys)）
        when(platformMapper.insertMenu(any()))
                .thenAnswer(
                        invocation -> {
                            MenuRow inserted = invocation.getArgument(0);
                            inserted.setId(999L);
                            return 1;
                        });
        when(platformMapper.findMenu(999L))
                .thenReturn(row(999L, "PLATFORM", "BUTTON", "base:demo:act", "ENABLED"));

        service.create(
                new MenuAdminService.CreateRequest(
                        "PLATFORM",
                        "113",
                        "BUTTON",
                        "base",
                        "演示按钮",
                        null,
                        null,
                        null,
                        "base:demo:act",
                        null,
                        10,
                        true));

        ArgumentCaptor<MenuRow> inserted = ArgumentCaptor.forClass(MenuRow.class);
        verify(platformMapper).insertMenu(inserted.capture());
        assertEquals("CUSTOM", inserted.getValue().getSource(), "平台端新建的项清单同步不触碰");
        assertEquals("base:demo:act", inserted.getValue().getPermCode());
        assertEquals(113L, inserted.getValue().getParentId());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MENU_CREATE", event.getValue().eventType());
    }

    // ---------------------------------------------------------------- 维护

    @Test
    void updateCannotChangePermCodeOrSideOrModule() {
        when(platformMapper.findMenu(211L))
                .thenReturn(row(211L, "HOSPITAL", "BUTTON", "followup:patient:plain", "ENABLED"));

        service.update(
                211L,
                new MenuAdminService.UpdateRequest(
                        "查看明文（改）", null, null, null, null, 20, true, "ENABLED"));

        ArgumentCaptor<MenuRow> updated = ArgumentCaptor.forClass(MenuRow.class);
        verify(platformMapper).updateMenu(updated.capture());
        assertEquals("followup:patient:plain", updated.getValue().getPermCode(), "权限码不可改（OQ-11）");
        assertEquals("查看明文（改）", updated.getValue().getTitle(), "标题可维护");
        assertEquals("HOSPITAL", updated.getValue().getSide());
    }

    @Test
    void updateDisablingBumpsAffectedPlatformAccounts() {
        when(platformMapper.findMenu(119L))
                .thenReturn(row(119L, "PLATFORM", "BUTTON", "platform:tenant:status", "ENABLED"));
        when(platformMapper.accountIdsGrantedMenu(119L)).thenReturn(List.of(1L, 5L));

        service.update(
                119L,
                new MenuAdminService.UpdateRequest(
                        "租户状态变更", null, null, null, null, 20, true, "DISABLED"));

        verify(permissionVersions).bump("PLATFORM", null, 1L);
        verify(permissionVersions).bump("PLATFORM", null, 5L);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MENU_UPDATE", event.getValue().eventType());
    }

    @Test
    void updateRejectsUnknownStatus() {
        when(platformMapper.findMenu(119L))
                .thenReturn(row(119L, "PLATFORM", "BUTTON", "platform:tenant:status", "ENABLED"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        119L,
                                        new MenuAdminService.UpdateRequest(
                                                "租户状态变更", null, null, null, null, 20, true,
                                                "GONE")));
        assertEquals(40001, error.code());
    }

    // ---------------------------------------------------------------- 删除（软删）

    @Test
    void deleteRefusesWhenChildrenExist() {
        when(platformMapper.findMenu(21L))
                .thenReturn(row(21L, "HOSPITAL", "MENU", null, "ENABLED"));
        when(platformMapper.countChildMenus(21L)).thenReturn(2);

        ApiException error = assertThrows(ApiException.class, () -> service.delete(21L));
        assertEquals(40001, error.code());
        verify(platformMapper, never()).softDeleteMenu(anyLong());
    }

    @Test
    void deleteSoftDeletesRemovesGrantsCleansTenantsAndBumps() {
        MenuRow target = row(211L, "HOSPITAL", "BUTTON", "followup:patient:plain", "ENABLED");
        when(platformMapper.findMenu(211L)).thenReturn(target);
        when(platformMapper.countChildMenus(211L)).thenReturn(0);
        when(platformMapper.accountIdsGrantedMenu(211L)).thenReturn(List.of(1L));
        when(platform.queryForList(
                        eq("SELECT id, code FROM plt_tenant WHERE status <> 'CANCELLED'")))
                .thenReturn(List.of(Map.of("id", 1L, "code", "demoa")));
        when(routing.hasTenant(1L)).thenReturn(false);

        service.delete(211L);

        verify(platformMapper).softDeleteMenu(211L);
        verify(platformMapper).deleteRoleMenuGrants(211L);
        verify(permissionVersions).bump("PLATFORM", null, 1L);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MENU_DELETE", event.getValue().eventType());
        assertTrue(event.getValue().detail().contains("跳过不可达 1 个"), event.getValue().detail());
    }

    // ---------------------------------------------------------------- 树

    @Test
    void treeIncludesButtonsAndDisabledRows() {
        MenuRow dir = row(20L, "HOSPITAL", "DIR", null, "ENABLED");
        MenuRow menu = row(21L, "HOSPITAL", "MENU", "followup:patient:view", "ENABLED");
        menu.setParentId(20L);
        MenuRow button = row(211L, "HOSPITAL", "BUTTON", "followup:patient:plain", "DISABLED");
        button.setParentId(21L);
        when(platformMapper.listAllMenus("HOSPITAL")).thenReturn(List.of(dir, menu, button));

        List<MenuAdminService.MenuTreeNode> tree = service.tree("HOSPITAL");

        assertEquals(1, tree.size());
        assertEquals("Demo20", tree.get(0).routeName());
        assertEquals(1, tree.get(0).children().size());
        MenuAdminService.MenuTreeNode menuNode = tree.get(0).children().get(0);
        assertEquals("BUTTON", menuNode.children().get(0).type(), "管理树含按钮");
        assertEquals("DISABLED", menuNode.children().get(0).status(), "管理树含停用行");
    }

    @Test
    void treeRejectsUnknownSide() {
        ApiException error = assertThrows(ApiException.class, () -> service.tree("ADMIN"));
        assertEquals(40001, error.code());
    }
}
