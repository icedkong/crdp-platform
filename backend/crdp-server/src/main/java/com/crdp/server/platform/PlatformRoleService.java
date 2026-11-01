package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.menu.MenuRow;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 平台角色管理（E06-T04 / PLT-ROLE-002、003，docs/api/platform.md §5）。
 *
 * <p>账号类型模型（PLT-ROLE-001）：平台账号只能授予平台角色（plt_account_role 只引用平台库 plt_role， 租户账号在各自租户库引用
 * sys_role，天然互不可见），账号类型由存放位置区分、决定登录入口与可授予 角色范围。平台预置角色（PLT-ROLE-002）：平台管理员、实施人员、运维人员、客服支持四个，种子 V104
 * 预置默认权限；预置角色不可删除（本服务不提供删除），权限可由平台管理员调整。平台自定义角色 （PLT-ROLE-003）：新建、授权平台端菜单、配置租户视角权限（§8
 * 的六个字段）、停用、查看持有人。
 *
 * <p>生效语义（platform-spec §1 业务规则）：角色停用后持有者立即失去对应权限——菜单并集在 PlatformMapper.accountMenuIds
 * 只统计启用角色，且每次变更递增持有者权限版本号（PLT-PERM-010， 在线会话下一次请求即重算，OQ-40 ①）。租户范围「指定租户」的租户清单由
 * E20-T01（PLT-VIEW-005） 落地，本任务提交 SPECIFIED 校验失败（暂行口径，见 open-questions OQ-48）。
 */
@Service
public class PlatformRoleService {

    /** 可见范围（PLT-VIEW-005）：仅配置类 / 配置类 + 业务数据。 */
    static final String VISIBLE_CONFIG_ONLY = "CONFIG_ONLY";

    static final String VISIBLE_CONFIG_AND_BUSINESS = "CONFIG_AND_BUSINESS";

    /** 租户范围（PLT-VIEW-005）：全部 / 负责的租户 / 指定租户。 */
    static final String TENANT_SCOPE_ALL = "ALL";

    static final String TENANT_SCOPE_RESPONSIBLE = "RESPONSIBLE";

    static final String TENANT_SCOPE_SPECIFIED = "SPECIFIED";

    private static final Set<String> VISIBLE_SCOPES =
            Set.of(VISIBLE_CONFIG_ONLY, VISIBLE_CONFIG_AND_BUSINESS);

    private static final Set<String> TENANT_SCOPES =
            Set.of(TENANT_SCOPE_ALL, TENANT_SCOPE_RESPONSIBLE, TENANT_SCOPE_SPECIFIED);

    private static final int NAME_MAX = 64;

    private static final int DESCRIPTION_MAX = 255;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate platform;
    private final PermissionVersionStore permissionVersions;
    private final AuditLogger audit;

    public PlatformRoleService(
            JdbcTemplate platform, PermissionVersionStore permissionVersions, AuditLogger audit) {
        this.platform = platform;
        this.permissionVersions = permissionVersions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 角色上的租户视角权限（平台规格 §8「关键字段 · 平台角色视角权限」，docs/api §5 六字段）。 */
    public record ViewFields(
            boolean canEnter,
            String visibleScope,
            String tenantScope,
            boolean canConfigure,
            boolean canResetPassword,
            boolean canEditBusiness) {}

    /** 角色视图：menuIds 仅详情返回；holderCount 为绑定账号数（含停用账号，展示用）。 */
    public record RoleView(
            long id,
            String code,
            String name,
            String description,
            boolean builtIn,
            String status,
            ViewFields view,
            List<Long> menuIds,
            int holderCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /**
     * 新建 / 维护请求（PLT-ROLE-002、003）：预置角色权限可调整（菜单、视角字段、状态），名称与说明 也可维护；自定义角色全量可改；code 创建后不可改（新建时由服务端生成
     * CUSTOM 前缀码）。
     */
    public record RoleSaveRequest(
            String name,
            String description,
            String status,
            List<Long> menuIds,
            Boolean viewCanEnter,
            String viewVisibleScope,
            String viewTenantScope,
            Boolean viewCanConfigure,
            Boolean viewCanResetPassword,
            Boolean viewCanEditBusiness) {}

    /** 持有人视图（platform-spec §1 主要页面「查看持有人」）。 */
    public record RoleHolder(
            long accountId, String username, String displayName, String accountStatus) {}

    /** 授权平台端菜单的树节点（只含启用行；含按钮，角色页授权树用，避免依赖 platform:menu:view）。 */
    public record GrantMenuNode(
            long id, long parentId, String type, String title, String permCode, int sortNo) {}

    // ---------------------------------------------------------------- 查询

    /** 平台角色列表（预置 + 自定义，含停用行；建号时间倒序不满意——预置固定在前，按 id 升序稳定）。 */
    public List<RoleView> list() {
        return TenantContext.callAsPlatform(this::listInternal);
    }

    private List<RoleView> listInternal() {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, description, built_in, status, view_can_enter,"
                                + " view_visible_scope, view_tenant_scope, view_can_configure,"
                                + " view_can_reset_password, view_can_edit_business, created_at, updated_at"
                                + " FROM plt_role ORDER BY id ASC");
        Map<Long, Long> holders = holderCounts();
        List<RoleView> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(toView(row, null, holders));
        }
        return result;
    }

    /** 角色详情（含已授权的平台端菜单 id）。 */
    public RoleView get(long roleId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireRole(roleId);
                    List<Long> menuIds =
                            new ArrayList<>(
                                    platform.queryForList(
                                            "SELECT menu_id FROM plt_role_menu WHERE role_id = ?"
                                                    + " ORDER BY menu_id",
                                            Long.class,
                                            roleId));
                    return toView(row, menuIds, holderCounts());
                });
    }

    /** 持有人列表（查看持有人）。 */
    public List<RoleHolder> holders(long roleId) {
        return TenantContext.callAsPlatform(
                () -> {
                    requireRole(roleId);
                    return platform.query(
                            "SELECT a.id, a.username, a.display_name, a.status"
                                    + " FROM plt_account a JOIN plt_account_role ar ON ar.account_id = a.id"
                                    + " WHERE ar.role_id = ? ORDER BY a.id",
                            (rs, rowNum) ->
                                    new RoleHolder(
                                            rs.getLong("id"),
                                            rs.getString("username"),
                                            rs.getString("display_name"),
                                            rs.getString("status")),
                            roleId);
                });
    }

    /** 可授权的平台端启用菜单（目录 / 菜单 / 按钮平铺，前端组树；平台角色页授权树数据源）。 */
    public List<GrantMenuNode> grantableMenus() {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT id, parent_id, type, title, perm_code, sort_no FROM plt_menu"
                                        + " WHERE side = 'PLATFORM' AND status = 'ENABLED'"
                                        + " ORDER BY sort_no ASC, id ASC",
                                (rs, rowNum) ->
                                        new GrantMenuNode(
                                                rs.getLong("id"),
                                                rs.getLong("parent_id"),
                                                rs.getString("type"),
                                                rs.getString("title"),
                                                rs.getString("perm_code"),
                                                rs.getInt("sort_no"))));
    }

    // ---------------------------------------------------------------- 新建 / 维护

    /** 新建平台自定义角色（PLT-ROLE-003）。编码由服务端生成（CUSTOM_ 前缀 + 随机段），创建后不可改。 */
    public RoleView create(RoleSaveRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    validateCommon(request);
                    Set<Long> desired = validateMenuIds(request.menuIds());
                    ViewFields view = validateViewFields(request);
                    String code = nextCustomCode();
                    platform.update(
                            "INSERT INTO plt_role (code, name, description, built_in, status,"
                                    + " view_can_enter, view_visible_scope, view_tenant_scope,"
                                    + " view_can_configure, view_can_reset_password, view_can_edit_business)"
                                    + " VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)",
                            code,
                            request.name().trim(),
                            blankToNull(request.description()),
                            statusOf(request.status()),
                            view.canEnter() ? 1 : 0,
                            view.visibleScope(),
                            view.tenantScope(),
                            view.canConfigure() ? 1 : 0,
                            view.canResetPassword() ? 1 : 0,
                            view.canEditBusiness() ? 1 : 0);
                    long roleId =
                            platform.queryForObject(
                                    "SELECT id FROM plt_role WHERE code = ?", Long.class, code);
                    saveGrants(roleId, desired);
                    audit.record(
                            new AuditEvent(
                                    "ROLE_CREATE",
                                    operatorId(),
                                    "CREATE",
                                    "plt_role:" + code,
                                    "新建平台角色："
                                            + request.name().trim()
                                            + "，授权平台端菜单 "
                                            + desired.size()
                                            + " 项"
                                            + viewDetail(view)));
                    return get(roleId);
                });
    }

    /**
     * 维护平台角色（PLT-ROLE-002「预置角色不可删除，权限可调整」+ PLT-ROLE-003）。不提供删除； 菜单授权或状态变化时递增持有者权限版本（PLT-PERM-010 /
     * OQ-40 ①）。
     */
    public RoleView update(long roleId, RoleSaveRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> before = requireRole(roleId);
                    validateCommon(request);
                    Set<Long> desired = validateMenuIds(request.menuIds());
                    ViewFields view = validateViewFields(request);
                    Set<Long> beforeMenuIds =
                            new LinkedHashSet<>(
                                    platform.queryForList(
                                            "SELECT menu_id FROM plt_role_menu WHERE role_id = ?",
                                            Long.class,
                                            roleId));
                    String beforeStatus = str(before.get("status"));
                    String afterStatus = statusOf(request.status());
                    boolean grantsChanged = !beforeMenuIds.equals(desired);
                    boolean statusChanged = !beforeStatus.equals(afterStatus);
                    platform.update(
                            "UPDATE plt_role SET name = ?, description = ?, status = ?,"
                                    + " view_can_enter = ?, view_visible_scope = ?, view_tenant_scope = ?,"
                                    + " view_can_configure = ?, view_can_reset_password = ?,"
                                    + " view_can_edit_business = ?, updated_at = ?"
                                    + " WHERE id = ?",
                            request.name().trim(),
                            blankToNull(request.description()),
                            afterStatus,
                            view.canEnter() ? 1 : 0,
                            view.visibleScope(),
                            view.tenantScope(),
                            view.canConfigure() ? 1 : 0,
                            view.canResetPassword() ? 1 : 0,
                            view.canEditBusiness() ? 1 : 0,
                            LocalDateTime.now(),
                            roleId);
                    if (grantsChanged) {
                        saveGrants(roleId, desired);
                    }
                    String detail =
                            updateDetail(
                                    before,
                                    request,
                                    beforeMenuIds,
                                    desired,
                                    beforeStatus,
                                    afterStatus);
                    if (grantsChanged || statusChanged) {
                        refreshHolders(roleId);
                    }
                    audit.record(
                            new AuditEvent(
                                    "ROLE_UPDATE",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_role:" + str(before.get("code")),
                                    abbreviate(detail)));
                    return get(roleId);
                });
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /** 名称 / 说明 / 状态校验：名称必填 ≤64，说明 ≤255，状态只能 ENABLED / DISABLED。 */
    static void validateCommon(RoleSaveRequest request) {
        if (request == null) {
            throw bad("请求不能为空");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw bad("角色名称必填");
        }
        if (request.name().trim().length() > NAME_MAX) {
            throw bad("角色名称过长（最多 " + NAME_MAX + " 个字符）");
        }
        if (request.description() != null
                && request.description().trim().length() > DESCRIPTION_MAX) {
            throw bad("角色说明过长（最多 " + DESCRIPTION_MAX + " 个字符）");
        }
        String status = request.status();
        if (status != null
                && !status.isBlank()
                && !"ENABLED".equals(status)
                && !"DISABLED".equals(status)) {
            throw bad("角色状态只允许 ENABLED / DISABLED");
        }
    }

    /**
     * 租户视角权限校验（platform-spec §8）。租户范围「指定租户」需要租户清单（PLT-VIEW-005 属 E20-T01）， 本任务提交即
     * 40001（暂行口径，OQ-48）。
     */
    static ViewFields validateViewFields(RoleSaveRequest request) {
        boolean canEnter = Boolean.TRUE.equals(request.viewCanEnter());
        String visibleScope =
                request.viewVisibleScope() == null || request.viewVisibleScope().isBlank()
                        ? VISIBLE_CONFIG_ONLY
                        : request.viewVisibleScope();
        if (!VISIBLE_SCOPES.contains(visibleScope)) {
            throw bad("可见范围只允许 CONFIG_ONLY / CONFIG_AND_BUSINESS");
        }
        String tenantScope =
                request.viewTenantScope() == null || request.viewTenantScope().isBlank()
                        ? TENANT_SCOPE_RESPONSIBLE
                        : request.viewTenantScope();
        if (!TENANT_SCOPES.contains(tenantScope)) {
            throw bad("租户范围只允许 ALL / RESPONSIBLE / SPECIFIED");
        }
        if (TENANT_SCOPE_SPECIFIED.equals(tenantScope)) {
            throw bad("租户范围「指定租户」需维护指定租户清单，由租户视角任务（E20-T01）提供，暂不可选");
        }
        return new ViewFields(
                canEnter,
                visibleScope,
                tenantScope,
                Boolean.TRUE.equals(request.viewCanConfigure()),
                Boolean.TRUE.equals(request.viewCanResetPassword()),
                Boolean.TRUE.equals(request.viewCanEditBusiness()));
    }

    /** 授权菜单校验（PLT-ROLE-003「授权平台端菜单」）：必须全部是平台端启用菜单 / 按钮。 */
    Set<Long> validateMenuIds(List<Long> menuIds) {
        Set<Long> desired = new LinkedHashSet<>();
        if (menuIds == null || menuIds.isEmpty()) {
            return desired;
        }
        Map<Long, MenuRow> grantable = new LinkedHashMap<>();
        for (MenuRow menu : platformMapperMenus()) {
            grantable.put(menu.getId(), menu);
        }
        for (Long menuId : new LinkedHashSet<>(menuIds)) {
            MenuRow menu = grantable.get(menuId);
            if (menu == null) {
                throw bad("菜单 / 按钮 " + menuId + " 不是平台端启用菜单，不能授权");
            }
            desired.add(menuId);
        }
        return desired;
    }

    List<MenuRow> platformMapperMenus() {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT id, parent_id, side, type, module_code, title, route_name,"
                                        + " path, component, perm_code, icon, sort_no, hidden"
                                        + " FROM plt_menu WHERE side = 'PLATFORM' AND status = 'ENABLED'"
                                        + " ORDER BY sort_no ASC, id ASC",
                                (rs, rowNum) -> {
                                    MenuRow row = new MenuRow();
                                    row.setId(rs.getLong("id"));
                                    row.setParentId(rs.getLong("parent_id"));
                                    row.setSide(rs.getString("side"));
                                    row.setType(rs.getString("type"));
                                    row.setModuleCode(rs.getString("module_code"));
                                    row.setTitle(rs.getString("title"));
                                    row.setPermCode(rs.getString("perm_code"));
                                    row.setSortNo(rs.getInt("sort_no"));
                                    return row;
                                }));
    }

    // ---------------------------------------------------------------- 内部

    private RoleView toView(Map<String, Object> row, List<Long> menuIds, Map<Long, Long> holders) {
        long id = ((Number) row.get("id")).longValue();
        return new RoleView(
                id,
                str(row.get("code")),
                str(row.get("name")),
                strOrNull(row.get("description")),
                ((Number) row.get("built_in")).intValue() == 1,
                str(row.get("status")),
                new ViewFields(
                        ((Number) row.get("view_can_enter")).intValue() == 1,
                        str(row.get("view_visible_scope")),
                        str(row.get("view_tenant_scope")),
                        ((Number) row.get("view_can_configure")).intValue() == 1,
                        ((Number) row.get("view_can_reset_password")).intValue() == 1,
                        ((Number) row.get("view_can_edit_business")).intValue() == 1),
                menuIds,
                holders.getOrDefault(id, 0L).intValue(),
                offsetDateTime(row.get("created_at")),
                offsetDateTime(row.get("updated_at")));
    }

    private Map<Long, Long> holderCounts() {
        Map<Long, Long> holders = new LinkedHashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT role_id, COUNT(*) AS holders FROM plt_account_role"
                                + " GROUP BY role_id")) {
            holders.put(
                    ((Number) row.get("role_id")).longValue(),
                    ((Number) row.get("holders")).longValue());
        }
        return holders;
    }

    private Map<String, Object> requireRole(long roleId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, description, built_in, status, view_can_enter,"
                                + " view_visible_scope, view_tenant_scope, view_can_configure,"
                                + " view_can_reset_password, view_can_edit_business, created_at, updated_at"
                                + " FROM plt_role WHERE id = ?",
                        roleId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "平台角色不存在");
        }
        return rows.get(0);
    }

    private void saveGrants(long roleId, Set<Long> menuIds) {
        platform.update("DELETE FROM plt_role_menu WHERE role_id = ?", roleId);
        for (Long menuId : menuIds) {
            platform.update(
                    "INSERT INTO plt_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
    }

    /** 变更影响持有者：递增权限版本号，在线会话下一次请求即按新权限重算（PLT-PERM-010 / OQ-40 ①）。 */
    private void refreshHolders(long roleId) {
        for (Long accountId :
                platform.queryForList(
                        "SELECT account_id FROM plt_account_role WHERE role_id = ?",
                        Long.class,
                        roleId)) {
            permissionVersions.bump("PLATFORM", null, accountId);
        }
    }

    /** 自定义角色编码：CUSTOM_ 前缀 + 8 位随机大写十六进制（唯一键冲突时重试）。 */
    private String nextCustomCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "CUSTOM_" + randomHex();
            Integer exists =
                    platform.queryForObject(
                            "SELECT COUNT(*) FROM plt_role WHERE code = ?",
                            Integer.class,
                            candidate);
            if (exists != null && exists == 0) {
                return candidate;
            }
        }
        throw new ApiException(409, 40901, "自定义角色编码生成冲突，请重试");
    }

    private String randomHex() {
        StringBuilder text = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) {
            text.append(Character.toUpperCase(Character.forDigit(random.nextInt(16), 16)));
        }
        return text.toString();
    }

    private static String updateDetail(
            Map<String, Object> before,
            RoleSaveRequest request,
            Set<Long> beforeMenuIds,
            Set<Long> desired,
            String beforeStatus,
            String afterStatus) {
        StringBuilder text = new StringBuilder("维护平台角色：").append(request.name().trim());
        if (!beforeStatus.equals(afterStatus)) {
            text.append("；状态 ").append(beforeStatus).append(" → ").append(afterStatus);
        }
        Set<Long> removed = new LinkedHashSet<>(beforeMenuIds);
        removed.removeAll(desired);
        Set<Long> added = new LinkedHashSet<>(desired);
        added.removeAll(beforeMenuIds);
        if (!removed.isEmpty()) {
            text.append("；收回菜单 ").append(removed.size()).append(" 项");
        }
        if (!added.isEmpty()) {
            text.append("；新增菜单 ").append(added.size()).append(" 项");
        }
        if (removed.isEmpty() && added.isEmpty()) {
            text.append("；菜单授权无变化");
        }
        text.append(viewDetail(validateViewFields(request)));
        return text.toString();
    }

    private static String viewDetail(ViewFields view) {
        return "；租户视角["
                + (view.canEnter() ? "可进入" : "不可进入")
                + "，"
                + view.visibleScope()
                + "，"
                + view.tenantScope()
                + (view.canConfigure() ? "，可代为配置" : "")
                + (view.canResetPassword() ? "，可代重置密码" : "")
                + (view.canEditBusiness() ? "，可编辑业务数据" : "")
                + "]";
    }

    private static String statusOf(String status) {
        return status == null || status.isBlank() ? "ENABLED" : status;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static Long operatorId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static OffsetDateTime offsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        LocalDateTime local =
                value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
        return local.atZone(ZONE).toOffsetDateTime();
    }

    static String abbreviate(String text) {
        String value = text == null ? "" : text;
        return value.length() <= 512 ? value : value.substring(0, 511) + "…";
    }
}
