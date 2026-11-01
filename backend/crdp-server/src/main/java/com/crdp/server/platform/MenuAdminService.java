package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.tenant.UnknownTenantDataSourceException;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全局菜单树维护（E05-T06 / PLT-PERM-001、002、003，平台端页面「菜单资源」）。
 *
 * <p>平台端、医院端两棵树都存平台库 plt_menu；目录 / 菜单 / 按钮的约束：每个菜单与按钮必须归属一个已登记模块
 * （PLT-PERM-002）；按钮必填全局唯一权限码（PLT-PERM-003，格式 {模块}:{资源}:{动作}，创建后不可改，OQ-11）； 菜单必填路由名 / 路径 /
 * 组件。删除为软删除：从平台角色授予 中移除、递归清理各租户库 sys_role_menu 里对该菜单的引用、递增受影响用户的权限版本（PLT-PERM-010）。
 */
@Service
public class MenuAdminService {

    private static final Logger log = LoggerFactory.getLogger(MenuAdminService.class);

    /** 权限码格式 {模块}:{资源}:{动作}（platform-spec §4；创建后不可改名）。 */
    private static final Pattern PERM_CODE =
            Pattern.compile("[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*){2}");

    private static final Pattern ROUTE_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");

    private final PlatformMapper platformMapper;
    private final JdbcTemplate platform;
    private final TenantRoutingDataSource routing;
    private final PermissionVersionStore permissionVersions;
    private final AuditLogger audit;

    public MenuAdminService(
            PlatformMapper platformMapper,
            JdbcTemplate platform,
            TenantRoutingDataSource routing,
            PermissionVersionStore permissionVersions,
            AuditLogger audit) {
        this.platformMapper = platformMapper;
        this.platform = platform;
        this.routing = routing;
        this.permissionVersions = permissionVersions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 树节点（含按钮与停用行；管理页展示用，不是下发 Vben 路由的形状）。 */
    public record MenuTreeNode(
            long id,
            long parentId,
            String side,
            String type,
            String moduleCode,
            String title,
            String routeName,
            String path,
            String component,
            String permCode,
            String icon,
            int sortNo,
            boolean hidden,
            String status,
            String source,
            List<MenuTreeNode> children) {

        static MenuTreeNode of(MenuRow row, List<MenuTreeNode> children) {
            return new MenuTreeNode(
                    row.getId(),
                    row.getParentId() == null ? 0L : row.getParentId(),
                    row.getSide(),
                    row.getType(),
                    row.getModuleCode(),
                    row.getTitle(),
                    row.getRouteName(),
                    row.getPath(),
                    row.getComponent(),
                    row.getPermCode(),
                    row.getIcon(),
                    row.getSortNo() == null ? 0 : row.getSortNo(),
                    row.getHidden() != null && row.getHidden() == 1,
                    row.getStatus(),
                    row.getSource(),
                    children);
        }
    }

    /** 新建请求：type 取 DIR / MENU / BUTTON；平台端页面创建的项 source 固定 CUSTOM（清单同步不触碰）。 */
    public record CreateRequest(
            String side,
            String parentId,
            String type,
            String moduleCode,
            String title,
            String routeName,
            String path,
            String component,
            String permCode,
            String icon,
            Integer sortNo,
            Boolean hidden) {}

    /**
     * 维护请求（PLT-PERM-001）：标题、图标、排序、隐藏、路由字段与状态可改；端别、类型、所属模块、权限码、 来源创建后不可改（权限码不可改名是 OQ-11
     * 口径；要换权限码只能删了重建）。
     */
    public record UpdateRequest(
            String title,
            String routeName,
            String path,
            String component,
            String icon,
            Integer sortNo,
            Boolean hidden,
            String status) {}

    // ---------------------------------------------------------------- 查询

    /** 全量菜单树（含按钮与停用行；软删行不出现）。 */
    public List<MenuTreeNode> tree(String side) {
        requireSide(side);
        return TenantContext.callAsPlatform(() -> buildTree(platformMapper.listAllMenus(side)));
    }

    static List<MenuTreeNode> buildTree(List<MenuRow> rows) {
        Map<Long, List<MenuRow>> byParent =
                new java.util.LinkedHashMap<>(
                        rows.stream()
                                .collect(
                                        java.util.stream.Collectors.groupingBy(
                                                row ->
                                                        row.getParentId() == null
                                                                ? 0L
                                                                : row.getParentId(),
                                                java.util.LinkedHashMap::new,
                                                java.util.stream.Collectors.toList())));
        return childrenOf(byParent, 0L);
    }

    private static List<MenuTreeNode> childrenOf(Map<Long, List<MenuRow>> byParent, long parentId) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .map(row -> MenuTreeNode.of(row, childrenOf(byParent, row.getId())))
                .toList();
    }

    // ---------------------------------------------------------------- 新建

    public MenuTreeNode create(CreateRequest request) {
        requireSide(request.side());
        String type = request.type();
        if (!List.of("DIR", "MENU", "BUTTON").contains(type)) {
            throw bad("type 类型必须是 DIR / MENU / BUTTON");
        }
        if (blank(request.title())) {
            throw bad("title 标题必填");
        }
        if (request.title().length() > 64) {
            throw bad("title 标题过长（最多 64 个字符）");
        }
        if (blank(request.moduleCode()) || moduleMissing(request.moduleCode())) {
            throw bad("moduleCode 必须归属已登记模块（PLT-PERM-002）");
        }
        // 类型自身的要求先校验（权限码 / 路由字段），再解析上级：缺权限码比上级不存在更先暴露
        String permCode = null;
        String routeName = request.routeName();
        String path = request.path();
        String component = request.component();
        if ("BUTTON".equals(type)) {
            if (blank(request.permCode()) || !PERM_CODE.matcher(request.permCode()).matches()) {
                throw bad("permCode 按钮必须绑定权限码，格式 {模块}:{资源}:{动作}（PLT-PERM-003）");
            }
            permCode = request.permCode();
            if (platformMapper.countMenuByPermCode(request.side(), permCode, 0L) > 0) {
                throw bad("permCode 权限码已存在，必须全局唯一（" + permCode + "）");
            }
        } else {
            if (blank(routeName) || !ROUTE_NAME.matcher(routeName).matches()) {
                throw bad("routeName 目录 / 菜单必填路由名（大驼峰，如 FollowupPatient）");
            }
            if (platformMapper.countMenuByRouteName(request.side(), routeName, 0L) > 0) {
                throw bad("routeName 路由名已存在（" + routeName + "）");
            }
            if (blank(path) || !path.startsWith("/")) {
                throw bad("path 目录 / 菜单必填以 / 开头的路径");
            }
            if ("MENU".equals(type) && blank(component)) {
                throw bad("component 菜单必填前端视图路径（如 followup/patient/index）");
            }
        }
        MenuRow parent = resolveParent(request.side(), request.parentId());
        if (parent != null && "BUTTON".equals(type) && !"MENU".equals(parent.getType())) {
            throw bad("parentId 按钮必须挂在菜单下");
        }
        if (parent != null && "MENU".equals(type) && !"DIR".equals(parent.getType())) {
            throw bad("parentId 菜单必须挂在目录下");
        }
        MenuRow row = new MenuRow();
        row.setParentId(parent == null ? 0L : parent.getId());
        row.setSide(request.side());
        row.setType(type);
        row.setModuleCode(request.moduleCode());
        row.setTitle(request.title());
        row.setRouteName(routeName);
        row.setPath(path);
        row.setComponent(component);
        row.setPermCode(permCode);
        row.setIcon(request.icon());
        row.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        row.setHidden(Boolean.TRUE.equals(request.hidden()) ? 1 : 0);
        row.setStatus("ENABLED");
        row.setSource("CUSTOM");
        TenantContext.callAsPlatform(() -> platformMapper.insertMenu(row));
        audit(
                "MENU_CREATE",
                row.getId(),
                "新建"
                        + typeText(type)
                        + "："
                        + row.getTitle()
                        + (permCode == null ? "" : "（" + permCode + "）")
                        + "，模块 "
                        + row.getModuleCode());
        return TenantContext.callAsPlatform(() -> nodeOf(row.getId()));
    }

    // ---------------------------------------------------------------- 维护

    public MenuTreeNode update(long id, UpdateRequest request) {
        MenuRow row = TenantContext.callAsPlatform(() -> requireMenu(id));
        if (blank(request.title())) {
            throw bad("title 标题必填");
        }
        String routeName = request.routeName() == null ? row.getRouteName() : request.routeName();
        String path = request.path() == null ? row.getPath() : request.path();
        String component = request.component() == null ? row.getComponent() : request.component();
        if (!"BUTTON".equals(row.getType())) {
            if (blank(routeName)) {
                throw bad("routeName 路由名必填");
            }
            if (!routeName.equals(row.getRouteName())
                    && platformMapper.countMenuByRouteName(row.getSide(), routeName, id) > 0) {
                throw bad("routeName 路由名已存在（" + routeName + "）");
            }
            if (blank(path) || !path.startsWith("/")) {
                throw bad("path 路径必填且以 / 开头");
            }
            if ("MENU".equals(row.getType()) && blank(component)) {
                throw bad("component 菜单必填前端视图路径");
            }
        }
        String status = request.status() == null ? row.getStatus() : request.status();
        if (!List.of("ENABLED", "DISABLED").contains(status)) {
            throw bad("status 状态只允许 ENABLED / DISABLED");
        }
        // 启用 / 停用之间的任何切换都改变授权范围：停用收权、启用恢复，都要让持有人立即重算（E06-T06 / PLT-PERM-010）
        boolean grantsChanged = "ENABLED".equals(status) != "ENABLED".equals(row.getStatus());
        row.setTitle(request.title());
        row.setRouteName(routeName);
        row.setPath(path);
        row.setComponent(component);
        row.setIcon(request.icon());
        row.setSortNo(request.sortNo() == null ? row.getSortNo() : request.sortNo());
        row.setHidden(request.hidden() == null ? row.getHidden() : (request.hidden() ? 1 : 0));
        row.setStatus(status);
        TenantContext.callAsPlatform(() -> platformMapper.updateMenu(row));
        int refreshedPlatformAccounts = 0;
        int refreshedTenantUsers = 0;
        if (grantsChanged) {
            refreshedPlatformAccounts = refreshSessionsOf(row.getId());
            // E06-T06 / PLT-PERM-010：医院端菜单停用后，持有它的租户用户也要重算——授权行保留在租户库，
            // 靠启用菜单集合（listMenus）与可分配范围在重算时把它挤出（platform-spec §1「停用后不再进入授权范围」）
            if ("HOSPITAL".equals(row.getSide())) {
                refreshedTenantUsers = bumpTenantUsersGrantedMenu(row.getId());
            }
        }
        audit(
                "MENU_UPDATE",
                row.getId(),
                "维护"
                        + typeText(row.getType())
                        + "："
                        + row.getTitle()
                        + (grantsChanged
                                ? "，启用 / 停用切换改变授权范围；递增受影响用户版本 "
                                        + (refreshedPlatformAccounts + refreshedTenantUsers)
                                        + " 人"
                                : ""));
        return TenantContext.callAsPlatform(() -> nodeOf(row.getId()));
    }

    // ---------------------------------------------------------------- 删除（软删）

    /**
     * 软删除（PLT-PERM-001）：status 置 DELETED；从平台库 plt_role_menu 移除授予；逐租户清理 sys_role_menu
     * 里引用该菜单的角色授权（租户库不可达时跳过、下次同步收敛，与模块关闭同口径，OQ-46 ③）； 受影响用户权限版本递增。
     */
    public void delete(long id) {
        MenuRow row = TenantContext.callAsPlatform(() -> requireMenu(id));
        if (TenantContext.callAsPlatform(() -> platformMapper.countChildMenus(id)) > 0) {
            throw bad("存在子菜单或按钮，先删除或移走子项（PLT-PERM-001）");
        }
        List<Long> affectedAccounts =
                TenantContext.callAsPlatform(() -> platformMapper.accountIdsGrantedMenu(id));
        // 医院端菜单：先收集持有它的租户用户（清掉租户库授权行之后就查不到了），删除后统一递增版本
        Map<Long, List<Long>> tenantUsers =
                "HOSPITAL".equals(row.getSide()) ? collectTenantUsersGrantedMenu(id) : Map.of();
        TenantContext.callAsPlatform(() -> platformMapper.softDeleteMenu(id));
        int removedGrants =
                TenantContext.callAsPlatform(() -> platformMapper.deleteRoleMenuGrants(id));
        int[] tenantsCleaned = cleanTenantRoleMenus(id);
        for (Long accountId : affectedAccounts) {
            permissionVersions.bump("PLATFORM", null, accountId);
        }
        int refreshedTenantUsers = bumpTenantUsers(tenantUsers);
        audit(
                "MENU_DELETE",
                id,
                "删除"
                        + typeText(row.getType())
                        + "："
                        + row.getTitle()
                        + "；移除平台角色授予 "
                        + removedGrants
                        + " 条；已清理租户库 "
                        + tenantsCleaned[0]
                        + " 个（跳过不可达 "
                        + tenantsCleaned[1]
                        + " 个）；递增受影响用户版本 "
                        + (affectedAccounts.size() + refreshedTenantUsers)
                        + " 人");
    }

    /** 逐租户删除 sys_role_menu 中对该菜单的引用；返回 [已清理租户数, 跳过租户数]。 */
    private int[] cleanTenantRoleMenus(long menuId) {
        int cleaned = 0;
        int skipped = 0;
        List<Map<String, Object>> tenants =
                platform.queryForList(
                        "SELECT id, code FROM plt_tenant WHERE status <> 'CANCELLED'");
        for (Map<String, Object> tenant : tenants) {
            long tenantId = ((Number) tenant.get("id")).longValue();
            String code = String.valueOf(tenant.get("code"));
            if (!routing.hasTenant(tenantId)) {
                skipped++;
                continue;
            }
            try {
                TenantContext.callAsTenant(
                        tenantId,
                        code,
                        () -> {
                            platform.update("DELETE FROM sys_role_menu WHERE menu_id = ?", menuId);
                            return null;
                        });
                cleaned++;
            } catch (UnknownTenantDataSourceException | DataAccessException ex) {
                skipped++;
                log.info("菜单删除清理租户角色授权跳过不可达租户 tenant={} menuId={}", code, menuId);
            }
        }
        return new int[] {cleaned, skipped};
    }

    // ---------------------------------------------------------------- 内部

    private MenuTreeNode nodeOf(long id) {
        MenuRow row = platformMapper.findMenu(id);
        if (row == null) {
            throw new ApiException(404, 40400, "菜单不存在");
        }
        return MenuTreeNode.of(row, List.of());
    }

    private MenuRow requireMenu(long id) {
        MenuRow row = platformMapper.findMenu(id);
        if (row == null || "DELETED".equals(row.getStatus())) {
            throw new ApiException(404, 40400, "菜单不存在");
        }
        return row;
    }

    private MenuRow resolveParent(String side, String parentId) {
        if (blank(parentId) || "0".equals(parentId)) {
            return null;
        }
        long pid;
        try {
            pid = Long.parseLong(parentId);
        } catch (NumberFormatException ex) {
            throw bad("parentId 上级菜单不正确");
        }
        MenuRow parent = platformMapper.findMenu(pid);
        if (parent == null || "DELETED".equals(parent.getStatus())) {
            throw bad("parentId 上级菜单不存在");
        }
        if (!side.equals(parent.getSide())) {
            throw bad("parentId 上级菜单必须与新建项同一端");
        }
        return parent;
    }

    private boolean moduleMissing(String moduleCode) {
        return TenantContext.callAsPlatform(
                () -> !platformMapper.listModuleCodes().contains(moduleCode));
    }

    /** 停用 / 删除后让持有该菜单的平台账号会话立即重算（权限码随停用从会话消失）；返回递增的账号数。 */
    private int refreshSessionsOf(long menuId) {
        int bumped = 0;
        for (Long accountId :
                TenantContext.callAsPlatform(() -> platformMapper.accountIdsGrantedMenu(menuId))) {
            permissionVersions.bump("PLATFORM", null, accountId);
            bumped++;
        }
        return bumped;
    }

    /**
     * 递增「经启用角色持有该医院端菜单」的租户用户权限版本（E06-T06 / PLT-PERM-010）。 停用场景授权行仍在租户库，删除场景由调用方先行收集；
     * 租户库不可达时跳过（与删除清理同口径，OQ-46 ③，下次同步收敛）。返回递增的用户数。
     */
    private int bumpTenantUsersGrantedMenu(long menuId) {
        return bumpTenantUsers(collectTenantUsersGrantedMenu(menuId));
    }

    /** 逐租户收集持有该菜单的用户（角色须启用——停用角色的授权本就不生效，无需重算）。 */
    private Map<Long, List<Long>> collectTenantUsersGrantedMenu(long menuId) {
        Map<Long, List<Long>> usersByTenant = new LinkedHashMap<>();
        // 与 cleanTenantRoleMenus 同一口径：除已注销外的全部租户，按可路由性跳过
        List<Map<String, Object>> tenants =
                TenantContext.callAsPlatform(
                        () ->
                                platform.queryForList(
                                        "SELECT id, code FROM plt_tenant WHERE status <> 'CANCELLED'"));
        for (Map<String, Object> tenant : tenants) {
            long tenantId = ((Number) tenant.get("id")).longValue();
            String code = String.valueOf(tenant.get("code"));
            if (!routing.hasTenant(tenantId)) {
                continue;
            }
            try {
                usersByTenant.put(
                        tenantId,
                        TenantContext.callAsTenant(
                                tenantId,
                                code,
                                () ->
                                        platform.queryForList(
                                                "SELECT DISTINCT ur.user_id"
                                                        + " FROM sys_user_role ur"
                                                        + " JOIN sys_role_menu rm ON rm.role_id = ur.role_id"
                                                        + " JOIN sys_role r ON r.id = ur.role_id"
                                                        + " AND r.status = 'ENABLED'"
                                                        + " WHERE rm.menu_id = ?",
                                                Long.class,
                                                menuId)));
            } catch (UnknownTenantDataSourceException | DataAccessException ex) {
                log.info("菜单变更递增用户版本跳过不可达租户 tenant={} menuId={}", code, menuId);
            }
        }
        return usersByTenant;
    }

    private int bumpTenantUsers(Map<Long, List<Long>> usersByTenant) {
        int bumped = 0;
        for (Map.Entry<Long, List<Long>> entry : usersByTenant.entrySet()) {
            for (Long userId : entry.getValue()) {
                permissionVersions.bump("HOSPITAL", entry.getKey(), userId);
                bumped++;
            }
        }
        return bumped;
    }

    private static void requireSide(String side) {
        if (!List.of("HOSPITAL", "PLATFORM").contains(side)) {
            throw bad("side 必须是 HOSPITAL 或 PLATFORM");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static String typeText(String type) {
        return switch (type) {
            case "DIR" -> "目录";
            case "MENU" -> "菜单";
            case "BUTTON" -> "按钮";
            default -> type;
        };
    }

    private void audit(String eventType, long menuId, String detail) {
        audit.record(
                new AuditEvent(
                        eventType,
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        "WRITE",
                        "plt_menu:" + menuId,
                        detail));
    }
}
