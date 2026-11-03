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
                            40));

    public List<MenuManifestItem> items() {
        return ITEMS;
    }
}
