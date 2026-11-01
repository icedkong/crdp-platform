package com.crdp.server.system.user;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import com.crdp.server.auth.EffectivePermissionService;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * E06-T09 / PLT-ORG-009、A-WARD-009、C-WARD-008：用户权限预览——展示某用户的最终菜单（含按钮）、权限码、
 * 数据范围（含可见病区）与<b>来源角色</b>，供「为什么看不到某菜单」排查。
 *
 * <p>口径与登录草稿完全一致（「预览所见 = 会话所得」）：菜单与权限码复用 {@link EffectivePermissionService} 的三层交集（已开通模块 ∩ 可分配范围 ∩
 * 启用角色授权并集），数据范围与 {@link HospitalDirectory} 的快照 归并同口径——WARD 范围由角色触发、病区清单取本人负责病区（sys_user_ward），DEPT
 * / CUSTOM_DEPT / RESPONSIBLE_DEPT 触发负责科室清单（sys_user_dept_scope）；任一启用角色为 ALL 即全院可见（A-WARD-007），
 * 不再列病区。实时计算，不缓存；本接口只读、不写审计。
 *
 * <p>来源角色只统计<b>启用中</b>的角色（停用角色的授权立即失效，与 HospitalMapper.menuIds / scopeTypes 一致）；
 * 角色清单本身保留停用行并标注状态，便于排查。
 */
@Service
public class UserPermissionPreviewService {

    private static final String DEPT = "DEPT";
    private static final String CUSTOM_DEPT = "CUSTOM_DEPT";
    private static final String RESPONSIBLE_DEPT = "RESPONSIBLE_DEPT";
    private static final String WARD = "WARD";
    private static final String ALL = "ALL";

    private final JdbcTemplate jdbc;
    private final HospitalDirectory hospitalDirectory;
    private final EffectivePermissionService effectivePermissions;
    private final PlatformMapper platformMapper;

    public UserPermissionPreviewService(
            JdbcTemplate jdbc,
            HospitalDirectory hospitalDirectory,
            EffectivePermissionService effectivePermissions,
            PlatformMapper platformMapper) {
        this.jdbc = jdbc;
        this.hospitalDirectory = hospitalDirectory;
        this.effectivePermissions = effectivePermissions;
        this.platformMapper = platformMapper;
    }

    // ---------------------------------------------------------------- 视图

    /** 用户全部角色（含停用行，标注状态与类型；来源角色排查用）。 */
    public record RoleBrief(long id, String code, String name, String roleType, String status) {}

    /** 预览菜单树节点（含按钮）：sourceRoles 为授予该项的启用角色名（按角色序）。 */
    public record MenuPreviewNode(
            long id,
            long parentId,
            String type,
            String moduleCode,
            String title,
            String permCode,
            List<String> sourceRoles,
            List<MenuPreviewNode> children) {}

    /** 生效数据范围类型及来源角色（多角色取并集）。 */
    public record ScopeTypeView(String type, List<String> sourceRoles) {}

    /** 可见病区（A-WARD-009）：本人负责病区 × WARD 范围触发，附来源角色。 */
    public record ScopeWardView(long id, String code, String name, List<String> sourceRoles) {}

    /** 负责科室清单：DEPT / CUSTOM_DEPT / RESPONSIBLE_DEPT 触发，附来源角色。 */
    public record ScopeDeptView(long id, String code, String name, List<String> sourceRoles) {}

    /** 权限预览视图（只读）。 */
    public record UserEffectivePermissionsView(
            long userId,
            String username,
            String displayName,
            String status,
            List<RoleBrief> roles,
            List<String> openedModules,
            List<MenuPreviewNode> menus,
            List<String> permissionCodes,
            boolean scopeAll,
            List<ScopeTypeView> scopeTypes,
            List<ScopeWardView> wards,
            List<ScopeDeptView> depts) {}

    /** 启用角色的范围行（sourceRoles 归并的输入，roleId 保序）。 */
    public record RoleScopeRow(long roleId, String roleName, String scopeType) {}

    // ---------------------------------------------------------------- 查询

    /** 某用户的有效权限预览。调用方需已在租户上下文内（医院端会话）。未知用户 40400。 */
    public UserEffectivePermissionsView preview(long userId) {
        TenantContext.State state = TenantContext.require();
        Map<String, Object> user = userRow(userId);
        HospitalAccount account =
                hospitalDirectory
                        .findById(userId)
                        .orElseThrow(() -> new ApiException(404, 40400, "用户不存在或不在本院"));
        EffectivePermissionService.Granted granted =
                effectivePermissions.hospitalGrants(state.tenantId(), account);

        List<RoleBrief> roles = roles(userId);
        Map<Long, List<String>> menuSources = menuSources(userId);
        List<MenuRow> platformMenus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("HOSPITAL"));
        List<MenuPreviewNode> menus =
                menuTree(platformMenus, Set.copyOf(granted.menuIds()), menuSources);

        List<RoleScopeRow> scopeRows = scopeRows(userId);
        Map<String, List<String>> sources = scopeSources(scopeRows);
        boolean all = sources.containsKey(ALL);
        List<String> effectiveTypes = List.copyOf(sources.keySet());
        List<ScopeTypeView> scopeTypes =
                effectiveTypes.stream()
                        .sorted()
                        .map(type -> new ScopeTypeView(type, sources.get(type)))
                        .toList();

        List<ScopeWardView> wards =
                wardsEffective(all, effectiveTypes)
                        ? wardRows(userId, sources.getOrDefault(WARD, List.of()))
                        : List.of();
        List<ScopeDeptView> depts =
                deptsEffective(all, effectiveTypes)
                        ? deptRows(userId, deptScopeSources(sources))
                        : List.of();

        return new UserEffectivePermissionsView(
                userId,
                str(user.get("username")),
                str(user.get("display_name")),
                str(user.get("status")),
                roles,
                granted.openedModules(),
                menus,
                granted.permissionCodes(),
                all,
                scopeTypes,
                wards,
                depts);
    }

    // ---------------------------------------------------------------- 纯函数（单测覆盖）

    /**
     * 菜单树装配（与登录 /menus 同构，但保留按钮节点并逐项标注来源角色）： 只保留生效菜单集合内的行，根为 parent 0，层级按平台 sort_no / id。
     *
     * @param platformMenus 平台库医院端启用菜单（含按钮）
     * @param effectiveIds 三层交集后的生效菜单编号
     * @param sources 菜单编号 → 授予它的启用角色名（按角色序）
     */
    public static List<MenuPreviewNode> menuTree(
            List<MenuRow> platformMenus, Set<Long> effectiveIds, Map<Long, List<String>> sources) {
        List<MenuRow> effective =
                platformMenus.stream()
                        .filter(row -> effectiveIds.contains(row.getId()))
                        .sorted(
                                Comparator.comparingInt(
                                        row -> row.getSortNo() == null ? 0 : row.getSortNo()))
                        .toList();
        Map<Long, List<MenuRow>> byParent = new LinkedHashMap<>();
        for (MenuRow row : effective) {
            long parent = row.getParentId() == null ? 0L : row.getParentId();
            byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(row);
        }
        return childrenOf(byParent, 0L, sources);
    }

    private static List<MenuPreviewNode> childrenOf(
            Map<Long, List<MenuRow>> byParent, long parentId, Map<Long, List<String>> sources) {
        List<MenuPreviewNode> nodes = new ArrayList<>();
        for (MenuRow row : byParent.getOrDefault(parentId, List.of())) {
            nodes.add(
                    new MenuPreviewNode(
                            row.getId(),
                            parentId,
                            row.getType(),
                            row.getModuleCode(),
                            row.getTitle(),
                            blankToNull(row.getPermCode()),
                            sources.getOrDefault(row.getId(), List.of()),
                            childrenOf(byParent, row.getId(), sources)));
        }
        return nodes;
    }

    /** 可见病区是否生效：任一启用角色带 WARD 范围，且没有角色放开全院（快照同口径）。 */
    public static boolean wardsEffective(boolean scopeAll, List<String> effectiveTypes) {
        return !scopeAll && effectiveTypes.contains(WARD);
    }

    /** 负责科室清单是否生效：DEPT / CUSTOM_DEPT / RESPONSIBLE_DEPT 任一触发，全院时不列。 */
    public static boolean deptsEffective(boolean scopeAll, List<String> effectiveTypes) {
        return !scopeAll
                && (effectiveTypes.contains(DEPT)
                        || effectiveTypes.contains(CUSTOM_DEPT)
                        || effectiveTypes.contains(RESPONSIBLE_DEPT));
    }

    /** 启用角色的范围行 → 范围类型 → 来源角色名。角色按 id 升序保序（同名去重由 SQL 角色 id 天然保证）， 范围类型按首次出现插入；平台七种之外的范围脏行直接忽略。 */
    public static Map<String, List<String>> scopeSources(List<RoleScopeRow> rows) {
        List<RoleScopeRow> sorted =
                rows.stream()
                        .filter(row -> knownScopeType(row.scopeType()))
                        .sorted(Comparator.comparingLong(RoleScopeRow::roleId))
                        .toList();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (RoleScopeRow row : sorted) {
            grouped.computeIfAbsent(row.scopeType(), key -> new ArrayList<>()).add(row.roleName());
        }
        return grouped;
    }

    /** 科室类范围（DEPT / CUSTOM_DEPT / RESPONSIBLE_DEPT）的来源角色并集，按该顺序去重。 */
    static List<String> deptScopeSources(Map<String, List<String>> sources) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String type : List.of(DEPT, CUSTOM_DEPT, RESPONSIBLE_DEPT)) {
            names.addAll(sources.getOrDefault(type, List.of()));
        }
        return List.copyOf(names);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> userRow(long userId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, username, display_name, status FROM sys_user WHERE id = ?",
                        userId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "用户不存在或不在本院");
        }
        return rows.get(0);
    }

    /** 用户全部角色（含停用；按角色 id 升序）。 */
    private List<RoleBrief> roles(long userId) {
        return jdbc.query(
                "SELECT r.id, r.code, r.name, r.role_type, r.status"
                        + " FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id"
                        + " WHERE ur.user_id = ? ORDER BY r.id",
                (rs, rowNum) ->
                        new RoleBrief(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("role_type"),
                                rs.getString("status")),
                userId);
    }

    /** 菜单编号 → 授予它的启用角色名（只统计启用角色，与登录并集同口径）。 */
    private Map<Long, List<String>> menuSources(long userId) {
        Map<Long, List<String>> sources = new LinkedHashMap<>();
        jdbc.query(
                        "SELECT rm.menu_id, r.id AS role_id, r.name AS role_name"
                                + " FROM sys_role_menu rm"
                                + " JOIN sys_role r ON r.id = rm.role_id AND r.status = 'ENABLED'"
                                + " JOIN sys_user_role ur ON ur.role_id = r.id"
                                + " WHERE ur.user_id = ? ORDER BY rm.menu_id, r.id",
                        (rs, rowNum) ->
                                Map.entry(
                                        rs.getLong("menu_id"),
                                        Map.entry(
                                                rs.getLong("role_id"), rs.getString("role_name"))),
                        userId)
                .forEach(
                        entry -> {
                            List<String> names =
                                    sources.computeIfAbsent(
                                            entry.getKey(), key -> new ArrayList<>());
                            String name = entry.getValue().getValue();
                            if (!names.contains(name)) {
                                names.add(name);
                            }
                        });
        return sources;
    }

    /** 启用角色的范围授权行（含 ALL；停用角色不计，与 HospitalMapper.scopeTypes 同口径）。 */
    private List<RoleScopeRow> scopeRows(long userId) {
        return jdbc.query(
                "SELECT r.id AS role_id, r.name AS role_name, s.scope_type"
                        + " FROM sys_role_scope s"
                        + " JOIN sys_role r ON r.id = s.role_id AND r.status = 'ENABLED'"
                        + " JOIN sys_user_role ur ON ur.role_id = r.id"
                        + " WHERE ur.user_id = ? ORDER BY r.id, s.scope_type",
                (rs, rowNum) ->
                        new RoleScopeRow(
                                rs.getLong("role_id"),
                                rs.getString("role_name"),
                                rs.getString("scope_type")),
                userId);
    }

    /** 本人负责病区（含后来停用的病区行——与登录快照同口径，停用病区仍生效直到 E07 收口）。 */
    private List<ScopeWardView> wardRows(long userId, List<String> sourceRoles) {
        return jdbc.query(
                "SELECT w.id, w.code, w.name FROM sys_user_ward uw"
                        + " JOIN ward w ON w.id = uw.ward_id WHERE uw.user_id = ? ORDER BY w.code",
                (rs, rowNum) ->
                        new ScopeWardView(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("name"),
                                sourceRoles),
                userId);
    }

    /** 本人负责科室清单（sys_user_dept_scope）。 */
    private List<ScopeDeptView> deptRows(long userId, List<String> sourceRoles) {
        return jdbc.query(
                "SELECT d.id, d.code, d.name FROM sys_user_dept_scope ds"
                        + " JOIN sys_dept d ON d.id = ds.dept_id WHERE ds.user_id = ? ORDER BY d.code",
                (rs, rowNum) ->
                        new ScopeDeptView(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("name"),
                                sourceRoles),
                userId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final Set<String> KNOWN_SCOPE_TYPES =
            Set.of(
                    ALL,
                    WARD,
                    "RESPONSIBLE_PATIENT",
                    "SELF",
                    DEPT,
                    CUSTOM_DEPT,
                    RESPONSIBLE_DEPT,
                    // E07-T03 / PLT-PERM-008：研究项目类型进预览类型清单；成员项目清单随 rct 模块落地（空实现下该分支为空）
                    "RESEARCH_PROJECT");

    /** 防御性校验（数据脏行不进预览，与平台范围类型口径对齐：七种 + RESEARCH_PROJECT）。 */
    static boolean knownScopeType(String type) {
        return type != null && KNOWN_SCOPE_TYPES.contains(type);
    }
}
