package com.crdp.platform.menu;

/**
 * 模块菜单清单项（PLT-PERM-011，E05-T06）：随模块发版的菜单 / 按钮声明。
 *
 * <p>key 是清单内稳定标识：目录 / 菜单用路由名（{@code routeName}），按钮用权限码（{@code permCode}）； 父项用同样的 key 引用（{@code
 * parentKey}，顶级为 null）。清单与平台库 plt_menu 比对差异：新增、变更（结构字段）、下线（库内 source='MANIFEST' 且清单已不含），经平台确认后生效（见
 * MenuSyncService）。
 *
 * @param title 默认标题：仅首次插入使用，平台端改过标题后同步不覆盖
 * @param icon 默认图标：同 title，不覆盖平台端维护值
 */
public record MenuManifestItem(
        String moduleCode,
        String side,
        String type,
        String key,
        String parentKey,
        String title,
        String routeName,
        String path,
        String component,
        String permCode,
        String icon,
        int sortNo) {

    /** 清单项与库内行比对的结构字段（变更检测用；title / icon / sortNo / hidden 是平台端维护值）。 */
    public boolean structuralEquals(MenuRow row, Long resolvedParentId) {
        return equalsOrNull(routeName, row.getRouteName())
                && equalsOrNull(path, row.getPath())
                && equalsOrNull(component, row.getComponent())
                && equalsOrNull(permCode, row.getPermCode())
                && type.equals(row.getType())
                && side.equals(row.getSide())
                && moduleCode.equals(row.getModuleCode())
                && (resolvedParentId == null
                        ? row.getParentId() == null || row.getParentId() == 0L
                        : resolvedParentId.equals(row.getParentId()));
    }

    private static boolean equalsOrNull(String expected, String actual) {
        return expected == null ? actual == null || actual.isBlank() : expected.equals(actual);
    }
}
