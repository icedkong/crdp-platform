package com.crdp.server.system.role;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantScopeService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * E06-T05 / PLT-ORG-004、005（platform-spec §6「组织架构、角色与用户」）：医院端角色管理。
 *
 * <p>规则（关键字段·角色 与 业务规则）：编码租户内唯一、创建后不可改；类型 内置 / 模板 /
 * 自定义；内置角色（租户管理员）不可删除、不可修改权限、不可停用，也不能移除其最后一名启用成员；模板角色由平台随模块开通下发（{@code
 * RoleTemplateService}），只读——租户「从模板复制生成自定义角色后调整」（PLT-ORG-005）；自定义角色可新建、编辑、复制、停用、删除（无成员时）。
 * 菜单授权只能在平台可分配范围内（超出 40001，会话层交集兜底 E05-T07）；数据范围存 sys_role_scope（多类型并集，登录侧由 HospitalDirectory
 * 归并成快照、查询侧由拦截器按病区 / 科室 / 本人 OR 改写，E07-T02 已生效），自定义科室（CUSTOM_DEPT）清单存 sys_role_scope_dept。
 *
 * <p>授权 / 成员 / 状态变更后递增受影响用户的权限版本（PLT-PERM-010 / OQ-40 ①），在线会话下一次请求即按新权限重算。 字段策略页签随 E07-T04 / T06
 * 落地（pages.md §10 角色管理四页签的最后一页）。
 */
@Service
public class RoleAdminService {

    /** 角色类型（platform-spec §6 关键字段·角色：内置 / 模板复制 / 自定义；模板行由开通流程写入）。 */
    public static final String TYPE_BUILT_IN = "BUILT_IN";

    public static final String TYPE_TEMPLATE = "TEMPLATE";
    public static final String TYPE_CUSTOM = "CUSTOM";

    /**
     * 数据范围类型（与 HospitalDirectory 会话装配同一套平台口径，七种 + 研究项目扩展类型）：CUSTOM_DEPT 需在角色上勾选科室清单； WARD /
     * RESPONSIBLE_DEPT 按「用户负责病区 / 科室」取值（platform-spec §6 关键字段·角色：负责病区、负责科室按用户设置，不在角色上勾选）。
     * RESEARCH_PROJECT（E07-T03 / PLT-PERM-008）为研究项目扩展类型：成员项目经 ResearchProjectScopeProvider
     * 取数（空实现不放宽任何数据）， 项目内规则由 rct 模块实现。
     */
    public static final Set<String> SCOPE_TYPES =
            Set.of(
                    "ALL",
                    "WARD",
                    "DEPT",
                    "CUSTOM_DEPT",
                    "RESPONSIBLE_DEPT",
                    "SELF",
                    "RESPONSIBLE_PATIENT",
                    "RESEARCH_PROJECT");

    public static final String SCOPE_CUSTOM_DEPT = "CUSTOM_DEPT";

    private static final String TENANT_ADMIN_CODE = "TENANT_ADMIN";
    private static final int CODE_MAX = 64;
    private static final int NAME_MAX = 64;
    private static final int DESCRIPTION_MAX = 200;
    private static final int REASON_MAX = 200;

    private final JdbcTemplate jdbc;
    private final TenantScopeService tenantScopes;
    private final AuditLogger audit;
    private final PermissionVersionStore permissionVersions;

    public RoleAdminService(
            JdbcTemplate jdbc,
            TenantScopeService tenantScopes,
            AuditLogger audit,
            PermissionVersionStore permissionVersions) {
        this.jdbc = jdbc;
        this.tenantScopes = tenantScopes;
        this.audit = audit;
        this.permissionVersions = permissionVersions;
    }

    // ---------------------------------------------------------------- 视图与请求

    public record RoleView(
            long id,
            String code,
            String name,
            String roleType,
            String moduleCode,
            String description,
            String status,
            int memberCount,
            int menuCount) {}

    public record RoleSave(String code, String name, String description) {}

    /** 复制 / 模板应用（PLT-ORG-005）：目标编码与名称必填，说明可覆盖来源说明。 */
    public record CopyRequest(String code, String name, String description) {}

    public record StatusChange(String action, String reason) {}

    /** 菜单树节点（含按钮）：assignable=平台可分配范围内才允许勾选。 */
    public record MenuNodeView(
            long id,
            long parentId,
            String type,
            String moduleCode,
            String title,
            String permCode,
            boolean assignable,
            List<MenuNodeView> children) {}

    public record RoleMenusView(RoleView role, List<MenuNodeView> menus, List<Long> menuIds) {}

    public record RoleScopeView(RoleView role, List<String> scopeTypes, List<Long> customDeptIds) {}

    public record ScopeSave(List<String> scopeTypes, List<Long> customDeptIds) {}

    public record RoleMemberView(long userId, String username, String displayName, String status) {}

    public record RoleMembersView(RoleView role, List<RoleMemberView> members) {}

    // ---------------------------------------------------------------- 查询

    /** 角色列表（维护视角）：内置 → 模板（按模块）→ 自定义；附成员数与菜单数。 */
    public List<RoleView> list() {
        Map<Long, Integer> members = countsByRole("sys_user_role");
        Map<Long, Integer> menus = countsByRole("sys_role_menu");
        return roles().stream().map(row -> toView(row, members, menus)).toList();
    }

    public RoleMenusView menusView(long roleId) {
        RoleView role =
                toView(
                        roleRow(roleId),
                        countsByRole("sys_user_role"),
                        countsByRole("sys_role_menu"));
        List<Long> menuIds =
                jdbc.queryForList(
                        "SELECT menu_id FROM sys_role_menu WHERE role_id = ? ORDER BY menu_id",
                        Long.class,
                        roleId);
        return new RoleMenusView(role, menuTree(), menuIds);
    }

    public RoleScopeView scopeView(long roleId) {
        RoleView role =
                toView(
                        roleRow(roleId),
                        countsByRole("sys_user_role"),
                        countsByRole("sys_role_menu"));
        List<String> types =
                jdbc.queryForList(
                        "SELECT scope_type FROM sys_role_scope WHERE role_id = ? ORDER BY scope_type",
                        String.class,
                        roleId);
        List<Long> deptIds =
                jdbc.queryForList(
                        "SELECT dept_id FROM sys_role_scope_dept WHERE role_id = ? ORDER BY dept_id",
                        Long.class,
                        roleId);
        return new RoleScopeView(role, types, deptIds);
    }

    public RoleMembersView membersView(long roleId) {
        RoleView role =
                toView(
                        roleRow(roleId),
                        countsByRole("sys_user_role"),
                        countsByRole("sys_role_menu"));
        List<RoleMemberView> members =
                jdbc.query(
                        "SELECT u.id, u.username, u.display_name AS displayName, u.status"
                                + " FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.id"
                                + " WHERE ur.role_id = ? ORDER BY u.id",
                        (rs, rowNum) ->
                                new RoleMemberView(
                                        rs.getLong("id"),
                                        rs.getString("username"),
                                        rs.getString("displayName"),
                                        rs.getString("status")),
                        roleId);
        return new RoleMembersView(role, members);
    }

    // ---------------------------------------------------------------- 基本信息（PLT-ORG-004）

    public RoleView create(RoleSave request) {
        String code = normalizeCode(request == null ? null : request.code());
        String name = requireName(request == null ? null : request.name());
        String description = optionalDescription(request.description());
        if (roleIdByCode(code) != null) {
            throw duplicate("角色编码已存在：" + code);
        }
        try {
            jdbc.update(
                    "INSERT INTO sys_role (code, name, built_in, status, role_type, description)"
                            + " VALUES (?, ?, 0, 'ENABLED', 'CUSTOM', ?)",
                    code,
                    name,
                    description);
        } catch (DuplicateKeyException ex) {
            throw duplicate("角色编码已存在：" + code);
        }
        RoleView created = viewByCode(code);
        audit("ROLE_CREATE", "CREATE", "sys_role:" + code, "新增自定义角色「" + name + "」");
        return created;
    }

    public RoleView update(long roleId, RoleSave request) {
        Map<String, Object> row = editableCustomRole(roleId, "编辑");
        String name = requireName(request == null ? null : request.name());
        String description = optionalDescription(request.description());
        if (request != null
                && request.code() != null
                && !str(row.get("code")).equals(normalizeCode(request.code()))) {
            throw bad("角色编码创建后不可修改");
        }
        jdbc.update(
                "UPDATE sys_role SET name = ?, description = ? WHERE id = ?",
                name,
                description,
                roleId);
        if (!name.equals(str(row.get("name")))) {
            audit(
                    "ROLE_UPDATE",
                    "UPDATE",
                    "sys_role:" + str(row.get("code")),
                    "角色「" + str(row.get("name")) + "」→「" + name + "」");
        }
        return viewByCode(str(row.get("code")));
    }

    /** 复制自定义角色（PLT-ORG-004「复制」）：菜单、数据范围与自定义科室一并复制。 */
    public RoleView copyRole(long roleId, CopyRequest request) {
        Map<String, Object> source = roleRow(roleId);
        if (!TYPE_CUSTOM.equals(str(source.get("role_type")))) {
            throw new ApiException(409, 40901, "只有自定义角色可以复制（内置与模板角色请用「从模板复制」或直接新建）");
        }
        return copy(roleId, source, request);
    }

    /** 应用业务角色模板（PLT-ORG-005）：把模板复制成自定义角色，之后在副本上调整。 */
    public RoleView copyFromTemplate(long templateId, CopyRequest request) {
        Map<String, Object> source = roleRow(templateId);
        if (!TYPE_TEMPLATE.equals(str(source.get("role_type")))) {
            throw new ApiException(409, 40901, "该角色不是模板角色（从模块下发的模板才能复制生成自定义角色）");
        }
        return copy(templateId, source, request);
    }

    public RoleView changeStatus(long roleId, StatusChange request) {
        Map<String, Object> row = roleRow(roleId);
        String type = str(row.get("role_type"));
        boolean disable = parseDisable(request);
        if (TYPE_BUILT_IN.equals(type)) {
            throw new ApiException(409, 40901, disable ? "内置角色不可停用（不能停用最后一个租户管理员）" : "内置角色始终启用");
        }
        if (TYPE_TEMPLATE.equals(type)) {
            throw new ApiException(409, 40901, "模板角色由平台随模块开通下发，不能停用 / 启用；请复制成自定义角色后调整");
        }
        String target = disable ? "DISABLED" : "ENABLED";
        if (target.equals(str(row.get("status")))) {
            throw new ApiException(409, 40901, disable ? "角色已是停用状态" : "角色已是启用状态");
        }
        jdbc.update("UPDATE sys_role SET status = ? WHERE id = ?", target, roleId);
        List<Long> members = refreshMembers(List.of(roleId));
        audit(
                "ROLE_STATUS",
                "STATUS",
                "sys_role:" + str(row.get("code")),
                (disable ? "停用" : "启用")
                        + "角色「"
                        + str(row.get("name"))
                        + "」"
                        + reasonSuffix(request)
                        + "；影响的成员 "
                        + members.size()
                        + " 人");
        return viewByCode(str(row.get("code")));
    }

    /** 删除（docs/api/system.md §3）：仅无成员的自定义角色；内置与模板不可删除（规格「内置角色不可删除」）。 */
    public void delete(long roleId) {
        Map<String, Object> row = roleRow(roleId);
        String type = str(row.get("role_type"));
        if (TYPE_BUILT_IN.equals(type) || TYPE_TEMPLATE.equals(type)) {
            throw new ApiException(
                    409, 40901, TYPE_BUILT_IN.equals(type) ? "内置角色不可删除" : "模板角色不可删除（重新开通模块会再次下发）");
        }
        Integer members =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user_role WHERE role_id = ?",
                        Integer.class,
                        roleId);
        if (members != null && members > 0) {
            throw new ApiException(409, 40901, "角色还有 " + members + " 名成员，请先移除成员或改用停用");
        }
        String code = str(row.get("code"));
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role_scope WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role_scope_dept WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_user_role WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM sys_role WHERE id = ?", roleId);
        audit(
                "ROLE_DELETE",
                "DELETE",
                "sys_role:" + code,
                "删除自定义角色「" + str(row.get("name")) + "」（无成员）");
    }

    // ---------------------------------------------------------------- 菜单与按钮（PLT-ORG-004）

    /** 保存菜单授权：只能选平台可分配范围内的医院端菜单 / 按钮（超出 40001，docs/api/system.md §3）。 */
    public RoleView updateMenus(long roleId, List<Long> menuIds) {
        Map<String, Object> row = editableCustomRole(roleId, "配置菜单");
        LinkedHashSet<Long> desired = normalizeIds(menuIds, "menuIds");
        Set<Long> assignable = assignableMenuIds();
        List<Long> known = hospitalMenuIds();
        List<Long> rejected = new ArrayList<>();
        for (Long menuId : desired) {
            if (!known.contains(menuId) || !assignable.contains(menuId)) {
                rejected.add(menuId);
            }
        }
        if (!rejected.isEmpty()) {
            throw bad("以下菜单不在本院可分配范围内：" + rejected);
        }
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
        for (Long menuId : desired) {
            jdbc.update(
                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
        int refreshed = refreshMembers(List.of(roleId)).size();
        audit(
                "ROLE_MENUS",
                "UPDATE",
                "sys_role:" + str(row.get("code")),
                "角色「"
                        + str(row.get("name"))
                        + "」授权菜单 "
                        + desired.size()
                        + " 项；影响的成员 "
                        + refreshed
                        + " 人");
        return viewByCode(str(row.get("code")));
    }

    // ---------------------------------------------------------------- 数据范围（PLT-ORG-004；生效口径见
    // HospitalDirectory + RestrictedScopeRewriter，E07-T02）

    public RoleView updateScope(long roleId, ScopeSave request) {
        Map<String, Object> row = editableCustomRole(roleId, "配置数据范围");
        if (request == null || request.scopeTypes() == null) {
            throw bad("scopeTypes 不能为空");
        }
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String raw : request.scopeTypes()) {
            String type = raw == null ? "" : raw.trim();
            if (!SCOPE_TYPES.contains(type)) {
                throw bad("未知的数据范围类型：" + type + "（可选：" + String.join(" / ", SCOPE_TYPES) + "）");
            }
            types.add(type);
        }
        if (types.isEmpty()) {
            throw bad("至少选择一种数据范围类型");
        }
        LinkedHashSet<Long> deptIds = normalizeIds(request.customDeptIds(), "customDeptIds");
        if (types.contains(SCOPE_CUSTOM_DEPT)) {
            if (deptIds.isEmpty()) {
                throw bad("选择「自定义科室」时必须勾选至少一个科室");
            }
            List<Long> unknown = unknownDeptIds(deptIds);
            if (!unknown.isEmpty()) {
                throw new ApiException(404, 40400, "科室不存在：" + unknown);
            }
        }
        jdbc.update("DELETE FROM sys_role_scope WHERE role_id = ?", roleId);
        for (String type : types) {
            jdbc.update(
                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)", roleId, type);
        }
        jdbc.update("DELETE FROM sys_role_scope_dept WHERE role_id = ?", roleId);
        for (Long deptId : deptIds) {
            jdbc.update(
                    "INSERT INTO sys_role_scope_dept (role_id, dept_id) VALUES (?, ?)",
                    roleId,
                    deptId);
        }
        int refreshed = refreshMembers(List.of(roleId)).size();
        audit(
                "ROLE_SCOPE",
                "UPDATE",
                "sys_role:" + str(row.get("code")),
                "角色「"
                        + str(row.get("name"))
                        + "」数据范围 "
                        + String.join("、", types)
                        + (deptIds.isEmpty() ? "" : "（科室 " + deptIds.size() + " 个）")
                        + "；影响的成员 "
                        + refreshed
                        + " 人");
        return viewByCode(str(row.get("code")));
    }

    // ---------------------------------------------------------------- 成员（PLT-ORG-004 编辑页「成员」页签）

    /** 替换成员集合：模板角色不可直接分配（复制后使用）；不能移出租户管理员的最后一名启用成员。 */
    public RoleMembersView updateMembers(long roleId, List<Long> userIds) {
        Map<String, Object> row = roleRow(roleId);
        String type = str(row.get("role_type"));
        if (TYPE_TEMPLATE.equals(type)) {
            throw new ApiException(409, 40901, "模板角色不能直接分配给用户，请先复制成自定义角色（PLT-ORG-005）");
        }
        LinkedHashSet<Long> desired = normalizeIds(userIds, "userIds");
        Map<Long, Map<String, Object>> users = usersById(desired);
        for (Long userId : desired) {
            Map<String, Object> user = users.get(userId);
            if (user == null) {
                throw new ApiException(404, 40400, "用户不存在：" + userId);
            }
            if (!"ENABLED".equals(str(user.get("status")))) {
                throw new ApiException(
                        409, 40901, "用户 " + str(user.get("username")) + " 不是启用状态，不能加入角色");
            }
        }
        boolean tenantAdmin = TENANT_ADMIN_CODE.equals(str(row.get("code")));
        if (tenantAdmin && desired.isEmpty()) {
            throw new ApiException(409, 40901, "不能移除最后一名启用的租户管理员（角色至少保留一名启用成员）");
        }
        List<Long> previous =
                jdbc.queryForList(
                        "SELECT user_id FROM sys_user_role WHERE role_id = ?", Long.class, roleId);
        jdbc.update("DELETE FROM sys_user_role WHERE role_id = ?", roleId);
        for (Long userId : desired) {
            jdbc.update(
                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        }
        List<Long> changed = new ArrayList<>();
        for (Long userId : previous) {
            if (!desired.contains(userId)) {
                changed.add(userId);
            }
        }
        for (Long userId : desired) {
            if (!previous.contains(userId)) {
                changed.add(userId);
            }
        }
        bumpUsers(changed);
        audit(
                "ROLE_MEMBERS",
                "UPDATE",
                "sys_role:" + str(row.get("code")),
                "角色「"
                        + str(row.get("name"))
                        + "」成员 "
                        + previous.size()
                        + " → "
                        + desired.size()
                        + " 人（变动 "
                        + changed.size()
                        + " 人）");
        return membersView(roleId);
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /** 角色编码：必填、≤64、字母数字下划线短横线（与院区 / 科室编码同规则）。 */
    static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("角色编码必填");
        }
        String code = raw.trim();
        if (code.length() > CODE_MAX) {
            throw bad("角色编码过长（最多 " + CODE_MAX + " 个字符）");
        }
        if (!code.matches("[A-Za-z0-9_-]+")) {
            throw bad("角色编码只允许字母、数字、下划线与短横线");
        }
        return code;
    }

    static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("角色名称必填");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw bad("角色名称过长（最多 " + NAME_MAX + " 个字符）");
        }
        return name;
    }

    static String optionalDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String description = raw.trim();
        if (description.length() > DESCRIPTION_MAX) {
            throw bad("角色说明过长（最多 " + DESCRIPTION_MAX + " 个字符）");
        }
        return description;
    }

    static boolean parseDisable(StatusChange request) {
        String action = request == null || request.action() == null ? "" : request.action().trim();
        if ("DISABLE".equalsIgnoreCase(action)) {
            return true;
        }
        if ("ENABLE".equalsIgnoreCase(action)) {
            return false;
        }
        throw bad("action 只允许 ENABLE / DISABLE");
    }

    static String reasonSuffix(StatusChange request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            return "";
        }
        if (reason.length() > REASON_MAX) {
            throw bad("变更原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return "，原因：" + reason;
    }

    static LinkedHashSet<Long> normalizeIds(List<Long> raw, String field) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (raw == null) {
            throw bad(field + " 不能为空");
        }
        for (Long id : raw) {
            if (id == null || id <= 0) {
                throw bad(field + " 含无效编号");
            }
            ids.add(id);
        }
        return ids;
    }

    // ---------------------------------------------------------------- 内部

    private RoleView copy(long sourceId, Map<String, Object> source, CopyRequest request) {
        String code = normalizeCode(request == null ? null : request.code());
        String name = requireName(request == null ? null : request.name());
        String description =
                optionalDescription(
                        request != null && request.description() != null
                                ? request.description()
                                : strOrNull(source.get("description")));
        if (roleIdByCode(code) != null) {
            throw duplicate("角色编码已存在：" + code);
        }
        try {
            jdbc.update(
                    "INSERT INTO sys_role (code, name, built_in, status, role_type, description)"
                            + " VALUES (?, ?, 0, 'ENABLED', 'CUSTOM', ?)",
                    code,
                    name,
                    description);
        } catch (DuplicateKeyException ex) {
            throw duplicate("角色编码已存在：" + code);
        }
        Long targetId = roleIdByCode(code);
        // 复制内容：菜单授权 + 数据范围 + 自定义科室（模板的默认授权全量带来，租户在副本上调整）
        for (Long menuId :
                jdbc.queryForList(
                        "SELECT menu_id FROM sys_role_menu WHERE role_id = ? ORDER BY menu_id",
                        Long.class,
                        sourceId)) {
            jdbc.update(
                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", targetId, menuId);
        }
        for (String scopeType :
                jdbc.queryForList(
                        "SELECT scope_type FROM sys_role_scope WHERE role_id = ?",
                        String.class,
                        sourceId)) {
            jdbc.update(
                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)",
                    targetId,
                    scopeType);
        }
        for (Long deptId :
                jdbc.queryForList(
                        "SELECT dept_id FROM sys_role_scope_dept WHERE role_id = ?",
                        Long.class,
                        sourceId)) {
            jdbc.update(
                    "INSERT INTO sys_role_scope_dept (role_id, dept_id) VALUES (?, ?)",
                    targetId,
                    deptId);
        }
        boolean fromTemplate = TYPE_TEMPLATE.equals(str(source.get("role_type")));
        audit(
                "ROLE_COPY",
                "CREATE",
                "sys_role:" + code,
                (fromTemplate ? "从模板" : "复制角色")
                        + "「"
                        + str(source.get("name"))
                        + "」生成自定义角色「"
                        + name
                        + "」");
        return viewByCode(code);
    }

    /** 可编辑的自定义角色（内置不可修改权限、模板只读，均 40901）。 */
    private Map<String, Object> editableCustomRole(long roleId, String action) {
        Map<String, Object> row = roleRow(roleId);
        String type = str(row.get("role_type"));
        if (TYPE_BUILT_IN.equals(type)) {
            throw new ApiException(409, 40901, "内置角色不可修改权限（" + action + "）");
        }
        if (TYPE_TEMPLATE.equals(type)) {
            throw new ApiException(409, 40901, "模板角色只读，请复制成自定义角色后" + action + "（PLT-ORG-005）");
        }
        return row;
    }

    private Map<String, Object> roleRow(long roleId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, code, name, role_type, module_code, description, status, built_in"
                                + " FROM sys_role WHERE id = ?",
                        roleId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "角色不存在或不在本院");
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> roles() {
        return jdbc.queryForList(
                "SELECT id, code, name, role_type, module_code, description, status, built_in"
                        + " FROM sys_role ORDER BY built_in DESC, role_type, module_code, code");
    }

    private Long roleIdByCode(String code) {
        List<Long> ids =
                jdbc.queryForList("SELECT id FROM sys_role WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private RoleView viewByCode(String code) {
        Long id = roleIdByCode(code);
        if (id == null) {
            throw new ApiException(404, 40400, "角色不存在或不在本院");
        }
        return toView(roleRow(id), countsByRole("sys_user_role"), countsByRole("sys_role_menu"));
    }

    private Map<Long, Integer> countsByRole(String table) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT role_id, COUNT(*) AS total FROM " + table + " GROUP BY role_id")) {
            counts.put(
                    ((Number) row.get("role_id")).longValue(),
                    ((Number) row.get("total")).intValue());
        }
        return counts;
    }

    private RoleView toView(
            Map<String, Object> row, Map<Long, Integer> members, Map<Long, Integer> menus) {
        long id = ((Number) row.get("id")).longValue();
        return new RoleView(
                id,
                str(row.get("code")),
                str(row.get("name")),
                str(row.get("role_type")),
                strOrNull(row.get("module_code")),
                strOrNull(row.get("description")),
                str(row.get("status")),
                members.getOrDefault(id, 0),
                menus.getOrDefault(id, 0));
    }

    /** 医院端菜单树（目录 / 菜单 / 按钮，维护视角）：assignable 标记平台可分配范围。 */
    private List<MenuNodeView> menuTree() {
        Set<Long> assignable = assignableMenuIds();
        List<Map<String, Object>> menus =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForList(
                                        "SELECT id, parent_id, type, module_code, title, perm_code"
                                                + " FROM plt_menu WHERE side = 'HOSPITAL'"
                                                + " AND status = 'ENABLED' ORDER BY sort_no, id"));
        Map<Long, List<MapNode>> byParent = new LinkedHashMap<>();
        for (Map<String, Object> menu : menus) {
            MapNode node = new MapNode(menu);
            byParent.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node);
        }
        return childrenOf(byParent, 0L, assignable);
    }

    private List<MenuNodeView> childrenOf(
            Map<Long, List<MapNode>> byParent, long parentId, Set<Long> assignable) {
        List<MenuNodeView> nodes = new ArrayList<>();
        for (MapNode node : byParent.getOrDefault(parentId, List.of())) {
            long id = node.id();
            nodes.add(
                    new MenuNodeView(
                            id,
                            parentId,
                            node.type(),
                            node.moduleCode(),
                            node.title(),
                            strOrNull(node.menu().get("perm_code")),
                            assignable.contains(id),
                            childrenOf(byParent, id, assignable)));
        }
        return nodes;
    }

    private record MapNode(Map<String, Object> menu) {
        long id() {
            return ((Number) menu.get("id")).longValue();
        }

        long parentId() {
            return ((Number) menu.get("parent_id")).longValue();
        }

        String type() {
            return str(menu.get("type"));
        }

        String moduleCode() {
            return str(menu.get("module_code"));
        }

        String title() {
            return str(menu.get("title"));
        }
    }

    private Set<Long> assignableMenuIds() {
        TenantContext.State state = TenantContext.require();
        return tenantScopes.assignableMenuIds(state.tenantId());
    }

    private List<Long> hospitalMenuIds() {
        return TenantContext.callAsPlatform(
                () ->
                        jdbc.queryForList(
                                "SELECT id FROM plt_menu WHERE side = 'HOSPITAL' AND status = 'ENABLED'",
                                Long.class));
    }

    private Map<Long, Map<String, Object>> usersById(Set<Long> userIds) {
        Map<Long, Map<String, Object>> users = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return users;
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, username, display_name, status FROM sys_user WHERE id IN (");
        sql.append("?, ".repeat(userIds.size() - 1)).append("?)");
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), userIds.toArray())) {
            users.put(((Number) row.get("id")).longValue(), row);
        }
        return users;
    }

    private List<Long> unknownDeptIds(Set<Long> deptIds) {
        if (deptIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT id FROM sys_dept WHERE id IN (");
        sql.append("?, ".repeat(deptIds.size() - 1)).append("?)");
        Set<Long> known =
                new LinkedHashSet<>(
                        jdbc.queryForList(sql.toString(), Long.class, deptIds.toArray()));
        List<Long> unknown = new ArrayList<>();
        for (Long deptId : deptIds) {
            if (!known.contains(deptId)) {
                unknown.add(deptId);
            }
        }
        return unknown;
    }

    /** 角色授权 / 成员变化后递增受影响用户的权限版本（OQ-40 ①），并返回受影响用户。 */
    private List<Long> refreshMembers(List<Long> roleIds) {
        List<Long> changed = new ArrayList<>();
        for (Long roleId : roleIds) {
            changed.addAll(
                    jdbc.queryForList(
                            "SELECT user_id FROM sys_user_role WHERE role_id = ?",
                            Long.class,
                            roleId));
        }
        bumpUsers(changed);
        return changed;
    }

    private void bumpUsers(List<Long> userIds) {
        TenantContext.State state = TenantContext.require();
        for (Long userId : userIds) {
            permissionVersions.bump("HOSPITAL", state.tenantId(), userId);
        }
    }

    private void audit(String eventType, String action, String target, String detail) {
        audit.record(
                new AuditEvent(
                        eventType,
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        action,
                        target,
                        detail));
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static ApiException duplicate(String message) {
        return new ApiException(409, 40902, message);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }
}
