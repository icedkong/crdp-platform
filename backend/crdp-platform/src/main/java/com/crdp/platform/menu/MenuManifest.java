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
                            20));

    public List<MenuManifestItem> items() {
        return ITEMS;
    }
}
