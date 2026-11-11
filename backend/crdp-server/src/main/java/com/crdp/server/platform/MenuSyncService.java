package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuManifest;
import com.crdp.platform.menu.MenuManifestItem;
import com.crdp.platform.menu.MenuRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 菜单清单同步（E05-T06 / PLT-PERM-011，P1）：模块发版时比对 {@link MenuManifest}（随包代码清单）与平台库 plt_menu
 * 的差异——新增（清单有、库内无）、变更（结构字段不一致：路径 / 组件 / 类型 / 上级 / 所属模块）、 下线（库内 source='MANIFEST'
 * 且清单已不含）。差异先给平台看（{@link #diff()}），确认后 {@link #apply()} 生效。
 *
 * <p>同步只动结构字段；标题、图标、排序、隐藏是平台端维护值，不覆盖（与模块注册同步同一口径）。平台端页面新建的
 * 菜单（source='CUSTOM'）不参与差异，也不会被下线。新增项按模块模板默认进入授权范围由 E05-T07（授权范围）接管； 本任务先把新增 / 变更行登记进
 * plt_menu，平台角色与租户授权范围仍按现有机制（plt_role_menu）授予。
 */
@Service
public class MenuSyncService {

    private final MenuManifest manifest;
    private final PlatformMapper platformMapper;
    private final PermissionVersionStore permissionVersions;
    private final AuditLogger audit;
    private final ResourceChangeRecorder resourceChanges;

    public MenuSyncService(
            MenuManifest manifest,
            PlatformMapper platformMapper,
            PermissionVersionStore permissionVersions,
            AuditLogger audit,
            ResourceChangeRecorder resourceChanges) {
        this.manifest = manifest;
        this.platformMapper = platformMapper;
        this.permissionVersions = permissionVersions;
        this.audit = audit;
        this.resourceChanges = resourceChanges;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * 差异项：action 为 ADDED / CHANGED / OFFLINE；current 仅 OFFLINE 有库内值，incoming 仅 ADDED / CHANGED
     * 有清单值。
     */
    public record DiffItem(
            String action,
            String moduleCode,
            String side,
            String key,
            Map<String, Object> incoming,
            Map<String, Object> current) {}

    public record DiffResult(List<DiffItem> items, boolean empty) {}

    public record ApplyResult(int added, int changed, int offline) {}

    // ---------------------------------------------------------------- 差异

    /** 比对当前差异（只读，不写库）。 */
    public DiffResult diff() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<DiffItem> items = new ArrayList<>();
                    // 清单按「模块 + 端」分组，与库内 source='MANIFEST' 行比对
                    Map<String, List<MenuManifestItem>> byModuleSide = new LinkedHashMap<>();
                    for (MenuManifestItem item : manifest.items()) {
                        byModuleSide
                                .computeIfAbsent(
                                        item.moduleCode() + "|" + item.side(),
                                        key -> new ArrayList<>())
                                .add(item);
                    }
                    Map<String, List<MenuRow>> rowsByModuleSide = new LinkedHashMap<>();
                    for (var entry : byModuleSide.keySet()) {
                        String[] parts = entry.split("\\|");
                        rowsByModuleSide.put(
                                entry, platformMapper.listManifestMenus(parts[0], parts[1]));
                    }
                    for (var entry : byModuleSide.entrySet()) {
                        String[] parts = entry.getKey().split("\\|");
                        diffOneModuleSide(
                                parts[0],
                                parts[1],
                                entry.getValue(),
                                rowsByModuleSide.get(entry.getKey()),
                                items);
                    }
                    return new DiffResult(List.copyOf(items), items.isEmpty());
                });
    }

    private void diffOneModuleSide(
            String moduleCode,
            String side,
            List<MenuManifestItem> manifestItems,
            List<MenuRow> rows,
            List<DiffItem> items) {
        Map<String, MenuRow> rowsByKey = new HashMap<>();
        for (MenuRow row : rows) {
            rowsByKey.put(keyOf(row), row);
        }
        // 清单项的上级 key 需在清单内解析；两级遍历保证父项先建
        Map<String, Long> parentIdByKey = new HashMap<>();
        for (MenuManifestItem item : manifestItems) {
            if (item.parentKey() == null) {
                parentIdByKey.put(item.key(), 0L);
            } else {
                MenuRow parentRow = rowsByKey.get(item.parentKey());
                parentIdByKey.put(
                        item.key(),
                        parentRow == null
                                ? resolveParentLater(side, item.parentKey())
                                : parentRow.getId());
            }
        }
        java.util.Set<String> manifestKeys = new java.util.HashSet<>();
        for (MenuManifestItem item : manifestItems) {
            manifestKeys.add(item.key());
            MenuRow row = rowsByKey.get(item.key());
            Long parentId = parentIdByKey.get(item.key());
            // 清单又包含、库内却停用 / 软删的行按「新增」复活：按钮权限码有唯一键，复活原行而不是再插一行
            if (row == null || !"ENABLED".equals(row.getStatus())) {
                items.add(
                        new DiffItem(
                                "ADDED",
                                moduleCode,
                                side,
                                item.key(),
                                incomingView(item, parentId),
                                row == null ? null : currentView(row)));
            } else if (!item.structuralEquals(row, parentId)) {
                items.add(
                        new DiffItem(
                                "CHANGED",
                                moduleCode,
                                side,
                                item.key(),
                                incomingView(item, parentId),
                                currentView(row)));
            }
        }
        for (MenuRow row : rows) {
            if ("ENABLED".equals(row.getStatus()) && !manifestKeys.contains(keyOf(row))) {
                items.add(
                        new DiffItem(
                                "OFFLINE", moduleCode, side, keyOf(row), null, currentView(row)));
            }
        }
    }

    /**
     * 上级 key 不在本模块清单行内：可能是同批新增的清单父项（应用时先插父项），也可能是 跨模块父项（如 followup 的「通知设置」挂在 base
     * 的系统设置目录下）——DIR/MENU 的 key 即 routeName， 直接按 routeName 查库内；都解析不到按 0（顶级）呈现。
     */
    private Long resolveParentLater(String side, String parentKey) {
        return manifest.items().stream()
                .filter(item -> item.key().equals(parentKey))
                .findFirst()
                .map(item -> platformMapper.findMenuIdByRouteName(side, item.routeName()))
                .orElse(platformMapper.findMenuIdByRouteName(side, parentKey));
    }

    // ---------------------------------------------------------------- 应用（平台确认后）

    /** 应用当前差异：新增插入、变更改结构字段、下线置 DISABLED（软下线，可再启用）；写 MENU_SYNC 审计。 */
    public ApplyResult apply() {
        DiffResult diff = diff();
        int added = 0;
        int changed = 0;
        int offline = 0;
        List<String> lines = new ArrayList<>();
        for (DiffItem item : diff.items()) {
            switch (item.action()) {
                case "ADDED" -> {
                    long menuId = insertManifestRow(item);
                    added++;
                    lines.add("新增 " + item.side() + " " + item.key());
                    recordSyncChange(item, menuId, ResourceChangeRecorder.ACTION_CREATE, "清单同步新增");
                }
                case "CHANGED" -> {
                    updateStructuralFields(item);
                    changed++;
                    lines.add("变更 " + item.side() + " " + item.key());
                    recordSyncChange(
                            item, null, ResourceChangeRecorder.ACTION_UPDATE, "清单同步变更结构字段");
                }
                case "OFFLINE" -> {
                    offlineRow(item);
                    offline++;
                    lines.add("下线 " + item.side() + " " + item.key());
                    recordSyncChange(
                            item, null, ResourceChangeRecorder.ACTION_OFFLINE, "清单同步下线（停用）");
                }
                default -> throw new IllegalStateException("未知差异类型：" + item.action());
            }
        }
        if (!lines.isEmpty()) {
            bumpGrantedAccounts(diff.items());
            audit.record(
                    new AuditEvent(
                            "MENU_SYNC",
                            CurrentSession.current()
                                    .map(principal -> principal.userId())
                                    .orElse(null),
                            "APPLY",
                            "plt_menu",
                            String.join("；", lines)));
        }
        return new ApplyResult(added, changed, offline);
    }

    /** 插入（或复活）清单行；返回行 id（供资源变更记录追溯）。 */
    private long insertManifestRow(DiffItem item) {
        MenuManifestItem manifestItem = findManifestItem(item);
        // 复活停用 / 软删的原行（按钮权限码唯一键挂在原行上），否则插入新行
        if (item.current() != null) {
            MenuRow revived = requireRow(item);
            revived.setTitle(manifestItem.title());
            revived.setRouteName(manifestItem.routeName());
            revived.setPath(manifestItem.path());
            revived.setComponent(manifestItem.component());
            revived.setParentId(parentRowId(item.side(), manifestItem.parentKey()));
            revived.setStatus("ENABLED");
            platformMapper.updateMenu(revived);
            return revived.getId();
        }
        MenuRow row = new MenuRow();
        // 上级在应用时按 key 解析：父项同批新增时先插父、后插子（apply 按差异顺序逐项执行）
        row.setParentId(parentRowId(item.side(), manifestItem.parentKey()));
        row.setSide(item.side());
        row.setType(manifestItem.type());
        row.setModuleCode(item.moduleCode());
        row.setTitle(manifestItem.title());
        row.setRouteName(manifestItem.routeName());
        row.setPath(manifestItem.path());
        row.setComponent(manifestItem.component());
        row.setPermCode(manifestItem.permCode());
        row.setIcon(manifestItem.icon());
        row.setSortNo(manifestItem.sortNo());
        row.setHidden(0);
        row.setStatus("ENABLED");
        row.setSource("MANIFEST");
        platformMapper.insertMenu(row);
        return row.getId() == null ? 0L : row.getId();
    }

    /** 清单同步差异项 → 资源变更记录（PLT-PERM-012）。 */
    private void recordSyncChange(DiffItem item, Long menuId, String action, String summary) {
        Long id = menuId;
        if (id == null
                && item.current() != null
                && item.current().get("id") instanceof Long rowId) {
            id = rowId;
        }
        String title =
                item.current() != null && item.current().get("title") instanceof String currentTitle
                        ? currentTitle
                        : incomingTitle(item);
        resourceChanges.record(
                ResourceChangeRecorder.TYPE_MENU,
                id,
                title,
                item.key(),
                action,
                ResourceChangeRecorder.CHANNEL_SYNC,
                summary
                        + "："
                        + sideText(item.side())
                        + " "
                        + item.key()
                        + "，模块 "
                        + item.moduleCode()
                        + (title == null ? "" : "（" + title + "）"));
    }

    private static String incomingTitle(DiffItem item) {
        return item.incoming() != null && item.incoming().get("title") instanceof String title
                ? title
                : null;
    }

    private static String sideText(String side) {
        return "PLATFORM".equals(side) ? "平台端" : "医院端";
    }

    private void updateStructuralFields(DiffItem item) {
        MenuManifestItem manifestItem = findManifestItem(item);
        MenuRow row = requireRow(item);
        row.setRouteName(manifestItem.routeName());
        row.setPath(manifestItem.path());
        row.setComponent(manifestItem.component());
        row.setParentId(parentRowId(item.side(), manifestItem.parentKey()));
        platformMapper.updateMenu(row);
    }

    /** 清单上级 key（目录 / 菜单的路由名）→ 库内行 id；顶级或找不到时为 0。 */
    private long parentRowId(String side, String parentKey) {
        if (parentKey == null) {
            return 0L;
        }
        Long id = platformMapper.findMenuIdByRouteName(side, parentKey);
        return id == null ? 0L : id;
    }

    private void offlineRow(DiffItem item) {
        MenuRow row = requireRow(item);
        row.setStatus("DISABLED");
        platformMapper.updateMenu(row);
    }

    /** 下线 / 变更可能收回权限码：让持有这些菜单的平台账号会话立即重算。 */
    private void bumpGrantedAccounts(List<DiffItem> items) {
        java.util.Set<Long> accountIds = new java.util.HashSet<>();
        for (DiffItem item : items) {
            if (item.current() != null) {
                Long id = (Long) item.current().get("id");
                if (id != null) {
                    accountIds.addAll(platformMapper.accountIdsGrantedMenu(id));
                }
            }
        }
        for (Long accountId : accountIds) {
            permissionVersions.bump("PLATFORM", null, accountId);
        }
    }

    private MenuRow requireRow(DiffItem item) {
        Long id = (Long) item.current().get("id");
        MenuRow row = platformMapper.findMenu(id);
        if (row == null) {
            throw new ApiException(409, 40901, "菜单行已变化，请刷新差异后重试");
        }
        return row;
    }

    private MenuManifestItem findManifestItem(DiffItem item) {
        return manifest.items().stream()
                .filter(candidate -> candidate.key().equals(item.key()))
                .findFirst()
                .orElseThrow(() -> new ApiException(409, 40901, "清单项已变化，请刷新差异后重试"));
    }

    // ---------------------------------------------------------------- 视图

    private static Map<String, Object> incomingView(MenuManifestItem item, Long parentId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", item.type());
        view.put("title", item.title());
        view.put("routeName", item.routeName());
        view.put("path", item.path());
        view.put("component", item.component());
        view.put("permCode", item.permCode());
        view.put("parentId", parentId == null ? 0L : parentId);
        return view;
    }

    private static Map<String, Object> currentView(MenuRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("type", row.getType());
        view.put("title", row.getTitle());
        view.put("routeName", row.getRouteName());
        view.put("path", row.getPath());
        view.put("component", row.getComponent());
        view.put("permCode", row.getPermCode());
        view.put("parentId", row.getParentId());
        view.put("status", row.getStatus());
        return view;
    }

    /** 行 → 清单 key：目录 / 菜单用路由名，按钮用权限码（与 MenuManifestItem 一致）。 */
    static String keyOf(MenuRow row) {
        return "BUTTON".equals(row.getType()) ? row.getPermCode() : row.getRouteName();
    }
}
