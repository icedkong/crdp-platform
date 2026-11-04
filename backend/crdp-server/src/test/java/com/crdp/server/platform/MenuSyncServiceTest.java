package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuManifest;
import com.crdp.platform.menu.MenuRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E05-T06 / PLT-PERM-011：菜单清单同步——清单与库内 MANIFEST 行比对差异（新增 / 变更只看结构字段 / 下线只看
 * 启用行），确认后应用；平台端改过的标题、图标、排序不被覆盖；CUSTOM 行不参与。
 */
@ExtendWith(MockitoExtension.class)
class MenuSyncServiceTest {

    @Mock private PlatformMapper platformMapper;
    @Mock private PermissionVersionStore permissionVersions;
    @Mock private AuditLogger audit;

    private MenuSyncService service;

    /** 与种子 V100 同构的随访菜单行（followup / HOSPITAL）。 */
    private static MenuRow manifestRow(
            long id,
            long parentId,
            String type,
            String title,
            String routeName,
            String path,
            String component,
            String permCode,
            int sortNo,
            String status) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setParentId(parentId);
        row.setSide("HOSPITAL");
        row.setType(type);
        row.setModuleCode("followup");
        row.setTitle(title);
        row.setRouteName(routeName);
        row.setPath(path);
        row.setComponent(component);
        row.setPermCode(permCode);
        row.setIcon(null);
        row.setSortNo(sortNo);
        row.setHidden(0);
        row.setStatus(status);
        row.setSource("MANIFEST");
        return row;
    }

    private static List<MenuRow> seedRows() {
        return List.of(
                manifestRow(
                        20L,
                        0L,
                        "DIR",
                        "随访",
                        "Followup",
                        "/followup",
                        "LAYOUT",
                        null,
                        20,
                        "ENABLED"),
                manifestRow(
                        21L,
                        20L,
                        "MENU",
                        "患者档案",
                        "FollowupPatient",
                        "/followup/patient",
                        "followup/patient/index",
                        "followup:patient:view",
                        10,
                        "ENABLED"),
                manifestRow(
                        22L,
                        20L,
                        "MENU",
                        "随访任务",
                        "FollowupTask",
                        "/followup/task",
                        "followup/task/index",
                        "followup:task:view",
                        20,
                        "ENABLED"),
                manifestRow(
                        23L,
                        20L,
                        "MENU",
                        "报告单",
                        "FollowupReport",
                        "/followup/report",
                        "followup/report/index",
                        "followup:report:view",
                        30,
                        "ENABLED"),
                manifestRow(
                        211L,
                        21L,
                        "BUTTON",
                        "查看明文",
                        null,
                        null,
                        null,
                        "followup:patient:plain",
                        10,
                        "ENABLED"),
                manifestRow(
                        212L,
                        21L,
                        "BUTTON",
                        "导出患者",
                        null,
                        null,
                        null,
                        "followup:patient:export",
                        20,
                        "ENABLED"),
                // E13-T01 新建患者（隐藏路由，平台库 V37 同构）：患者档案菜单下的 followup 菜单项
                manifestRow(
                        213L,
                        21L,
                        "MENU",
                        "新建患者",
                        "FollowupPatientNew",
                        "/followup/patient/new",
                        "followup/patient/new",
                        "followup:patient:create",
                        30,
                        "ENABLED"),
                // E13-T03 编辑患者资料按钮（平台库 V38 同构）：患者档案菜单下的 followup 按钮
                manifestRow(
                        214L,
                        21L,
                        "BUTTON",
                        "编辑患者资料",
                        null,
                        null,
                        null,
                        "followup:patient:edit",
                        40,
                        "ENABLED"),
                // E13-T05 患者详情隐藏路由（平台库 V39 同构）：perm_code 为 NULL
                // （followup:patient:view 已由菜单 21 持有，uk_plt_menu_perm 全库唯一）
                manifestRow(
                        215L,
                        21L,
                        "MENU",
                        "患者详情",
                        "FollowupPatientDetail",
                        "/followup/patient/:id",
                        "followup/patient/detail",
                        null,
                        20,
                        "ENABLED"),
                // E14-T01 随访计划菜单 / 计划详情隐藏路由 / 三个操作按钮（平台库 V42 同构）
                manifestRow(
                        24L,
                        20L,
                        "MENU",
                        "随访计划",
                        "FollowupPlan",
                        "/followup/plan",
                        "followup/plan/index",
                        "followup:plan:view",
                        15,
                        "ENABLED"),
                manifestRow(
                        25L,
                        24L,
                        "MENU",
                        "计划详情",
                        "FollowupPlanDetail",
                        "/followup/plan/:planId",
                        "followup/plan/detail",
                        null,
                        20,
                        "ENABLED"),
                manifestRow(
                        219L,
                        24L,
                        "BUTTON",
                        "编辑计划",
                        null,
                        null,
                        null,
                        "followup:plan:edit",
                        10,
                        "ENABLED"),
                manifestRow(
                        220L,
                        24L,
                        "BUTTON",
                        "发布计划",
                        null,
                        null,
                        null,
                        "followup:plan:publish",
                        20,
                        "ENABLED"),
                manifestRow(
                        221L,
                        24L,
                        "BUTTON",
                        "计划入组",
                        null,
                        null,
                        null,
                        "followup:plan:enroll",
                        30,
                        "ENABLED"),
                // E09-T04 通知设置：followup 菜单挂在 base 的系统设置目录（30）下，
                // 父项经 findMenuIdByRouteName 跨模块解析（MenuSyncService.resolveParentLater 回落）
                manifestRow(
                        36L,
                        30L,
                        "MENU",
                        "通知设置",
                        "FollowupNotice",
                        "/system/notice",
                        "followup/notice/index",
                        "followup:notice:config",
                        45,
                        "ENABLED"),
                // E11-T01 字段字典：followup 菜单挂在 base 的系统设置目录（30）下（平台库 V35 同构）
                manifestRow(
                        37L,
                        30L,
                        "MENU",
                        "字段字典",
                        "FollowupField",
                        "/system/field",
                        "followup/field/index",
                        "followup:field:edit",
                        50,
                        "ENABLED"),
                // E10-T01 上传按钮（followup:scan:upload，OQ-12）：报告单菜单下的非敏感 followup 项
                manifestRow(
                        231L,
                        23L,
                        "BUTTON",
                        "上传文件",
                        null,
                        null,
                        null,
                        "followup:scan:upload",
                        10,
                        "ENABLED"),
                // E10-T05 下载按钮（followup:scan:download，敏感权限 V34）：报告单菜单下的
                // 敏感 followup 项（清单只登记菜单结构，授权范围与角色授予都不预置）
                manifestRow(
                        232L,
                        23L,
                        "BUTTON",
                        "下载文件",
                        null,
                        null,
                        null,
                        "followup:scan:download",
                        20,
                        "ENABLED"),
                // E13-T08 转病区按钮（平台库 V40 同构）：患者档案菜单下的 followup 按钮
                manifestRow(
                        216L,
                        21L,
                        "BUTTON",
                        "转病区",
                        null,
                        null,
                        null,
                        "followup:patient:transfer",
                        30,
                        "ENABLED"),
                // E13-T08 病区管理（平台库 V40 同构）：followup 菜单挂在 base 的系统设置目录（30）下
                manifestRow(
                        38L,
                        30L,
                        "MENU",
                        "病区管理",
                        "HospitalWard",
                        "/system/ward",
                        "system/ward/index",
                        "followup:ward:edit",
                        33,
                        "ENABLED"),
                // E13-T09 审批患者导出按钮（平台库 V41 同构）：租户普通用户导出由租户管理员审批
                manifestRow(
                        218L,
                        21L,
                        "BUTTON",
                        "审批患者导出",
                        null,
                        null,
                        null,
                        "followup:patient:export:approve",
                        25,
                        "ENABLED"),
                // E13-T09 删除患者按钮（平台库 V41 同构，敏感权限）：软删除（C 规格权限表原有码，A 按 OQ-17 同名新增）
                manifestRow(
                        217L,
                        21L,
                        "BUTTON",
                        "删除患者",
                        null,
                        null,
                        null,
                        "followup:patient:delete",
                        50,
                        "ENABLED"));
    }

    @BeforeEach
    void setUp() {
        service =
                new MenuSyncService(new MenuManifest(), platformMapper, permissionVersions, audit);
        lenient()
                .when(platformMapper.listManifestMenus("followup", "HOSPITAL"))
                .thenReturn(seedRows());
        lenient()
                .when(platformMapper.findMenuIdByRouteName("HOSPITAL", "Followup"))
                .thenReturn(20L);
        lenient()
                .when(platformMapper.findMenuIdByRouteName("HOSPITAL", "FollowupPatient"))
                .thenReturn(21L);
        lenient()
                .when(platformMapper.findMenuIdByRouteName("HOSPITAL", "FollowupReport"))
                .thenReturn(23L);
        lenient()
                .when(platformMapper.findMenuIdByRouteName("HOSPITAL", "HospitalSystem"))
                .thenReturn(30L);
    }

    @Test
    void noDiffWhenLibraryMatchesManifest() {
        MenuSyncService.DiffResult diff = service.diff();
        assertTrue(diff.empty(), () -> String.valueOf(diff.items()));
    }

    @Test
    void changedDetectedOnlyForStructuralFields() {
        // 平台端只改了标题（患者档案 → 患者）与排序：不算变更（同步不覆盖平台端维护值）
        List<MenuRow> edited = new java.util.ArrayList<>(seedRows());
        edited.set(
                1,
                manifestRow(
                        21L,
                        20L,
                        "MENU",
                        "患者",
                        "FollowupPatient",
                        "/followup/patient",
                        "followup/patient/index",
                        "followup:patient:view",
                        99,
                        "ENABLED"));
        when(platformMapper.listManifestMenus("followup", "HOSPITAL")).thenReturn(edited);

        assertTrue(service.diff().empty(), "标题 / 排序差异不算结构变更");

        // 组件路径变了（模块发版换了视图）：结构变更
        edited.set(
                1,
                manifestRow(
                        21L,
                        20L,
                        "MENU",
                        "患者档案",
                        "FollowupPatient",
                        "/followup/patient",
                        "followup/patient/v2",
                        "followup:patient:view",
                        10,
                        "ENABLED"));
        MenuSyncService.DiffResult diff = service.diff();
        assertEquals(1, diff.items().size());
        assertEquals("CHANGED", diff.items().get(0).action());
        assertEquals("FollowupPatient", diff.items().get(0).key());
    }

    @Test
    void addedDetectedForMissingAndDisabledRows() {
        List<MenuRow> missing = new java.util.ArrayList<>(seedRows());
        missing.remove(5); // 导出患者按钮不在库内
        // 查看明文按钮被平台停用：清单又包含 → 按新增（复活）报告
        missing.set(
                4,
                manifestRow(
                        211L,
                        21L,
                        "BUTTON",
                        "查看明文",
                        null,
                        null,
                        null,
                        "followup:patient:plain",
                        10,
                        "DISABLED"));
        when(platformMapper.listManifestMenus("followup", "HOSPITAL")).thenReturn(missing);

        MenuSyncService.DiffResult diff = service.diff();
        assertEquals(2, diff.items().size());
        assertTrue(diff.items().stream().allMatch(item -> "ADDED".equals(item.action())));
        assertTrue(
                diff.items().stream()
                        .anyMatch(item -> "followup:patient:export".equals(item.key())),
                "库内缺的按钮按新增报告");
        assertTrue(
                diff.items().stream()
                        .anyMatch(
                                item ->
                                        "followup:patient:plain".equals(item.key())
                                                && item.current() != null),
                "停用的清单行带库内现值（复活原行，避免权限码唯一键冲突）");
    }

    @Test
    void offlineDetectedForEnabledManifestRowsNotInManifest() {
        // 库内多出一条已启用的 MANIFEST 行（旧版本菜单，新清单已不含）→ 下线；CUSTOM 行不参与（查询按 source 过滤）
        List<MenuRow> extra = new java.util.ArrayList<>(seedRows());
        extra.add(
                manifestRow(
                        24L,
                        20L,
                        "MENU",
                        "旧版随访日历",
                        "FollowupCalendar",
                        "/followup/calendar",
                        "followup/calendar/index",
                        "followup:calendar:view",
                        40,
                        "ENABLED"));
        when(platformMapper.listManifestMenus("followup", "HOSPITAL")).thenReturn(extra);

        MenuSyncService.DiffResult diff = service.diff();
        assertEquals(1, diff.items().size());
        assertEquals("OFFLINE", diff.items().get(0).action());
        assertEquals("FollowupCalendar", diff.items().get(0).key());
    }

    @Test
    void applyInsertsChangesAndDisablesAndAudits() {
        List<MenuRow> rows = new java.util.ArrayList<>(seedRows());
        rows.remove(5); // 导出患者：ADDED
        rows.set(
                1,
                manifestRow(
                        21L,
                        20L,
                        "MENU",
                        "患者档案",
                        "FollowupPatient",
                        "/followup/patient",
                        "followup/patient/v2",
                        "followup:patient:view",
                        10,
                        "ENABLED")); // CHANGED
        rows.add(
                manifestRow(
                        24L,
                        20L,
                        "MENU",
                        "旧版随访日历",
                        "FollowupCalendar",
                        "/followup/calendar",
                        "followup/calendar/index",
                        "followup:calendar:view",
                        40,
                        "ENABLED")); // OFFLINE
        when(platformMapper.listManifestMenus("followup", "HOSPITAL")).thenReturn(rows);
        when(platformMapper.findMenu(21L))
                .thenReturn(
                        manifestRow(
                                21L,
                                20L,
                                "MENU",
                                "患者档案",
                                "FollowupPatient",
                                "/followup/patient",
                                "followup/patient/index",
                                "followup:patient:view",
                                10,
                                "ENABLED"));
        when(platformMapper.findMenu(24L))
                .thenReturn(
                        manifestRow(
                                24L,
                                20L,
                                "MENU",
                                "旧版随访日历",
                                "FollowupCalendar",
                                "/followup/calendar",
                                "followup/calendar/index",
                                "followup:calendar:view",
                                40,
                                "ENABLED"));

        MenuSyncService.ApplyResult result = service.apply();

        assertEquals(1, result.added());
        assertEquals(1, result.changed());
        assertEquals(1, result.offline());
        ArgumentCaptor<MenuRow> inserted = ArgumentCaptor.forClass(MenuRow.class);
        verify(platformMapper).insertMenu(inserted.capture());
        assertEquals("followup:patient:export", inserted.getValue().getPermCode());
        assertEquals(21L, inserted.getValue().getParentId(), "按钮挂在清单上级（患者档案）下");
        ArgumentCaptor<MenuRow> updated = ArgumentCaptor.forClass(MenuRow.class);
        verify(platformMapper, org.mockito.Mockito.times(2)).updateMenu(updated.capture());
        assertTrue(
                updated.getAllValues().stream()
                        .anyMatch(
                                row ->
                                        "followup/patient/index".equals(row.getComponent())
                                                && row.getId() == 21L),
                "结构变更以清单为准改回路由字段（库内 v2 → 清单 index）");
        assertTrue(
                updated.getAllValues().stream()
                        .anyMatch(row -> "DISABLED".equals(row.getStatus()) && row.getId() == 24L),
                "下线置停用（软下线，可再启用）");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("MENU_SYNC", event.getValue().eventType());
    }

    @Test
    void applyOnEmptyDiffWritesNothing() {
        MenuSyncService.ApplyResult result = service.apply();

        assertEquals(0, result.added() + result.changed() + result.offline());
        verify(platformMapper, never()).insertMenu(any());
        verify(platformMapper, never()).updateMenu(any());
        verify(audit, never()).record(any());
    }
}
