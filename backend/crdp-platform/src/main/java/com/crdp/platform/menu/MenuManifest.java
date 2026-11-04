package com.crdp.platform.menu;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 随包登记的模块菜单清单（PLT-PERM-011，E05-T06）：模块发版时与平台库 plt_menu 比对差异， 新增、变更、下线项经平台确认后生效。
 *
 * <p>清单只声明模块带来的菜单与按钮；平台基础（base）的平台端管理菜单由种子与平台端维护，不在清单里。 首期只登记随访模块的医院端菜单（与种子 V100
 * 一致，作为清单的首次物化）；后续模块发版在这里加清单项。
 */
@Component
public class MenuManifest {

    private static final List<MenuManifestItem> ITEMS =
            List.of(
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "DIR",
                            "Followup",
                            null,
                            "随访",
                            "Followup",
                            "/followup",
                            "LAYOUT",
                            null,
                            "MedicineBoxOutlined",
                            20),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupPatient",
                            "Followup",
                            "患者档案",
                            "FollowupPatient",
                            "/followup/patient",
                            "followup/patient/index",
                            "followup:patient:view",
                            null,
                            10),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupTask",
                            "Followup",
                            "随访任务",
                            "FollowupTask",
                            "/followup/task",
                            "followup/task/index",
                            "followup:task:view",
                            null,
                            20),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupReport",
                            "Followup",
                            "报告单",
                            "FollowupReport",
                            "/followup/report",
                            "followup/report/index",
                            "followup:report:view",
                            null,
                            30),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:patient:plain",
                            "FollowupPatient",
                            "查看明文",
                            null,
                            null,
                            null,
                            "followup:patient:plain",
                            null,
                            10),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:patient:export",
                            "FollowupPatient",
                            "导出患者",
                            null,
                            null,
                            null,
                            "followup:patient:export",
                            null,
                            20),
                    // E09-T04 / A-NTF-001、C-NTF-001：通知设置（挂 base 的系统设置目录下，菜单本体属 followup 模块）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupNotice",
                            "HospitalSystem",
                            "通知设置",
                            "FollowupNotice",
                            "/system/notice",
                            "followup/notice/index",
                            "followup:notice:config",
                            null,
                            45),
                    // E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典（挂 base 的系统设置目录下，平台库 V35 同构）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupField",
                            "HospitalSystem",
                            "字段字典",
                            "FollowupField",
                            "/system/field",
                            "followup/field/index",
                            "followup:field:edit",
                            null,
                            50),
                    // E13-T08 / A-WARD-001、A-WARD-008：病区管理（挂 base 的系统设置目录下，平台库 V40 同构；
                    // A-WARD-001「租户管理员维护」，内置角色只授 TENANT_ADMIN）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "HospitalWard",
                            "HospitalSystem",
                            "病区管理",
                            "HospitalWard",
                            "/system/ward",
                            "system/ward/index",
                            "followup:ward:edit",
                            null,
                            33),
                    // E10-T01 / A-ATT-008、PLT-CFG-010：报告单页签下的上传按钮（平台库 V33 同构，OQ-12 权限码）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:scan:upload",
                            "FollowupReport",
                            "上传文件",
                            null,
                            null,
                            null,
                            "followup:scan:upload",
                            null,
                            10),
                    // E10-T05 / tech-plan §10「下载」、A-ATT-006：报告单页签下的下载按钮（平台库 V34 同构）。
                    // 敏感权限（V18 plt_sensitive_perm）：不进授权范围模板、不预授予内置角色，
                    // 平台按租户放开后医院端才可授权（与查看明文按钮同口径）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:scan:download",
                            "FollowupReport",
                            "下载文件",
                            null,
                            null,
                            null,
                            "followup:scan:download",
                            null,
                            20),
                    // E13-T01 / A-PAT-003、C-PAT-001：新建患者隐藏路由（患者档案下，平台库 V37 同构；
                    // hidden 是平台端维护值，不在清单结构字段里）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupPatientNew",
                            "FollowupPatient",
                            "新建患者",
                            "FollowupPatientNew",
                            "/followup/patient/new",
                            "followup/patient/new",
                            "followup:patient:create",
                            null,
                            30),
                    // E13-T08 / A-WARD-005：患者档案下的「转病区」按钮
                    // （followup:patient:transfer，A/C 规格权限表；平台库 V40 同构）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:patient:transfer",
                            "FollowupPatient",
                            "转病区",
                            null,
                            null,
                            null,
                            "followup:patient:transfer",
                            null,
                            30),
                    // E13-T03 / docs/api/patient.md §6：患者档案下的「编辑患者资料」按钮
                    // （followup:patient:edit，A/C 规格权限表；平台库 V38 同构）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:patient:edit",
                            "FollowupPatient",
                            "编辑患者资料",
                            null,
                            null,
                            null,
                            "followup:patient:edit",
                            null,
                            40),
                    // E13-T09 / A-PAT-009：患者档案下的「审批患者导出」按钮（租户普通用户导出由
                    // 租户管理员审批，platform-spec §9；平台库 V41 同构）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:patient:export:approve",
                            "FollowupPatient",
                            "审批患者导出",
                            null,
                            null,
                            null,
                            "followup:patient:export:approve",
                            null,
                            25),
                    // E13-T09 / A-PAT-012、C-PAT-015：患者档案下的「删除患者」按钮（软删除，
                    // 敏感权限；C 规格权限表原有码，A 按 OQ-17 同名新增；平台库 V41 同构）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:patient:delete",
                            "FollowupPatient",
                            "删除患者",
                            null,
                            null,
                            null,
                            "followup:patient:delete",
                            null,
                            50),
                    // E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010：患者详情隐藏路由（患者档案下，
                    // 平台库 V39 同构；perm_code 为 NULL——followup:patient:view 已由患者档案菜单
                    // 持有（uk_plt_menu_perm 全库唯一），接口校验在 @RequiresPermission）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupPatientDetail",
                            "FollowupPatient",
                            "患者详情",
                            "FollowupPatientDetail",
                            "/followup/patient/:id",
                            "followup/patient/detail",
                            null,
                            null,
                            20),
                    // E14-T01 / A-FU-016、A-FU-018、A-FU-019、C-FU-001：随访计划页面菜单（随访目录下，
                    // 平台库 V42 同构；页面随 E14-T02 落地，本任务先落数据面接口与菜单资源）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupPlan",
                            "Followup",
                            "随访计划",
                            "FollowupPlan",
                            "/followup/plan",
                            "followup/plan/index",
                            "followup:plan:view",
                            null,
                            15),
                    // E14-T01 / pages.md §8：计划详情隐藏路由（随访计划下，平台库 V42 同构；
                    // perm_code 为 NULL——followup:plan:view 已由随访计划菜单持有，接口校验在
                    // @RequiresPermission；页签患者列表 / 变更记录随 E14-T08）
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "MENU",
                            "FollowupPlanDetail",
                            "FollowupPlan",
                            "计划详情",
                            "FollowupPlanDetail",
                            "/followup/plan/:planId",
                            "followup/plan/detail",
                            null,
                            null,
                            20),
                    // E14-T01 / A-FU-019、OQ-13：随访计划下的操作按钮（平台库 V42 同构）——
                    // C 没有这三个码，计划相关一律用 followup:plan:edit，接口按「任一码命中」声明
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:plan:edit",
                            "FollowupPlan",
                            "编辑计划",
                            null,
                            null,
                            null,
                            "followup:plan:edit",
                            null,
                            10),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:plan:publish",
                            "FollowupPlan",
                            "发布计划",
                            null,
                            null,
                            null,
                            "followup:plan:publish",
                            null,
                            20),
                    new MenuManifestItem(
                            "followup",
                            "HOSPITAL",
                            "BUTTON",
                            "followup:plan:enroll",
                            "FollowupPlan",
                            "计划入组",
                            null,
                            null,
                            null,
                            "followup:plan:enroll",
                            null,
                            30));

    public List<MenuManifestItem> items() {
        return ITEMS;
    }
}
