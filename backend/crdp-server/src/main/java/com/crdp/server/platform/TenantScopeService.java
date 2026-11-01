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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 租户授权范围与可分配范围（E05-T07 / PLT-SCOPE-001..006、008，验收：收回范围后租户角色同步收回）。
 *
 * <p>权限集合层次（平台规格 §5「关键字段 · 权限集合层次」）：已开通模块（平台库）→ 租户授权范围（系统自动 生成：已开通模块下的全部医院端菜单与按钮，不落表，随模块开通 / 关闭自动扩张
 * / 收缩）→ 可分配范围（平台管理员、 实施人员维护）→ 角色权限（租户库）→ 用户最终权限（实时计算）：
 *
 * <pre>用户最终权限 = 已开通模块 ∩ 平台允许租户管理员分配的范围 ∩ 用户全部角色权限的并集</pre>
 *
 * <p>可分配范围的生效值按推导计算：base 模块医院端菜单是固定能力（租户管理员内置的组织、用户、角色管理权限 不受可分配范围限制，也不允许收回）；业务模块默认取授权范围模板（{@code
 * plt_scope_template} 白名单 ∩ 非敏感）， 租户级覆盖（{@code
 * plt_tenant_scope}）只存与默认不同的项，最终再与授权范围（已开通模块）求交——模块关闭时自动收缩、 重新开通自动恢复，无需回写。会话交集在 AuthService
 * 的医院端授权草稿里执行（与已开通模块过滤同一层）。
 *
 * <p>范围变更流程：调整勾选 → 影响分析（{@link #preview}：将被移除的角色权限与受影响用户数）→ 确认保存（原因必填， 写 {@code plt_scope_log}
 * 变更记录与 SCOPE_UPDATE 审计）→ 自动收回（从租户库 sys_role_menu 移除超出项，受影响
 * 用户权限版本递增，在线会话下一次请求即重算）。范围扩大不自动授予任何角色；租户库不可达时保存整体失败（40901）， 不留「范围已缩、角色未收」的中间态。
 *
 * <p>敏感权限（PLT-SCOPE-004）：{@code plt_sensitive_perm} 清单命中的按钮默认不可分配，平台按租户单独放开且 原因必填；清单与模板维护见 {@link
 * ScopeTemplateService}。
 */
@Service
public class TenantScopeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final int REASON_MAX = 200;

    private static final int DETAIL_MAX = 512;

    /** 平台基础模块：医院端菜单是租户管理员固定能力，不受可分配范围限制（平台规格 §5 业务规则）。 */
    static final String BASE_MODULE = "base";

    /** 三种状态（平台规格 §5 主要页面）：未开通（不可选）/ 已开通未放开 / 可分配。 */
    static final String STATE_CLOSED = "CLOSED";

    static final String STATE_NOT_RELEASED = "NOT_RELEASED";

    static final String STATE_ASSIGNABLE = "ASSIGNABLE";

    private final JdbcTemplate platform;
    private final PlatformMapper platformMapper;
    private final TenantRoutingDataSource routing;
    private final PermissionVersionStore permissionVersions;
    private final AuditLogger audit;

    public TenantScopeService(
            JdbcTemplate platform,
            PlatformMapper platformMapper,
            TenantRoutingDataSource routing,
            PermissionVersionStore permissionVersions,
            AuditLogger audit) {
        this.platform = platform;
        this.platformMapper = platformMapper;
        this.routing = routing;
        this.permissionVersions = permissionVersions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 可分配范围保存请求（PLT-SCOPE-002、004、008）：提交期望的可分配全集（含 base 固定项）； 本次新放开的敏感项必须逐一填写原因；顶层原因是变更记录必填项。 */
    public record ScopeUpdateRequest(
            List<Long> assignableMenuIds, Map<Long, String> sensitiveReasons, String reason) {}

    /** 变更结果：移除 / 放开清单与自动收回的痕迹（PLT-SCOPE-006）。 */
    public record ScopeChangeResult(
            long tenantId,
            String tenantCode,
            List<Long> removedMenuIds,
            List<Long> addedMenuIds,
            int revokedRoles,
            int revokedGrants,
            int refreshedUsers) {}

    /** 授权范围树里的一个菜单 / 按钮（三种状态 + 敏感与覆盖痕迹）。 */
    public record ScopeItem(
            long id,
            long parentId,
            String type,
            String title,
            String permCode,
            String moduleCode,
            boolean sensitive,
            boolean defaultAssignable,
            boolean assignable,
            boolean fixed,
            String state,
            String releaseReason,
            OffsetDateTime setAt) {}

    /** 按模块分组的授权范围（模块开通状态决定组内项是否可选）。 */
    public record ScopeModuleGroup(
            String code, String name, boolean baseModule, boolean opened, List<ScopeItem> items) {}

    public record TenantScopeView(
            long tenantId,
            String tenantCode,
            String tenantName,
            String tenantStatus,
            List<ScopeModuleGroup> modules) {}

    /** 影响分析（PLT-SCOPE-005）：将被移除 / 新放开的项与受影响角色、用户数。 */
    public record ScopeImpactItem(long menuId, String title, String permCode) {}

    public record ScopeAffectedRole(
            long roleId,
            String roleCode,
            String roleName,
            int removedMenuCount,
            List<String> removedPermCodes,
            int userCount) {}

    public record ScopePreviewView(
            long tenantId,
            String tenantCode,
            List<ScopeImpactItem> removed,
            List<ScopeImpactItem> added,
            List<ScopeImpactItem> sensitiveReleases,
            List<ScopeAffectedRole> affectedRoles,
            int affectedUserCount) {}

    /** 变更记录行（PLT-SCOPE-008）：操作人、前后差异（detail）与原因。 */
    public record ScopeLogView(
            long id,
            long tenantId,
            String reason,
            String detail,
            Long operatorId,
            OffsetDateTime createdAt) {}

    /** 计算模型（一次读取，视图 / 校验 / 保存共用）：全部输入的不可变快照。 */
    record ScopeModel(
            List<MenuRow> hospitalMenus,
            Map<Long, MenuRow> menusById,
            Set<String> openedModules,
            Set<Long> templateMenuIds,
            Set<String> sensitivePermCodes,
            Map<Long, Boolean> overrides,
            Map<Long, Map<String, Object>> overrideRows) {

        boolean sensitive(MenuRow menu) {
            return menu.getPermCode() != null && sensitivePermCodes.contains(menu.getPermCode());
        }

        boolean sensitiveById(long menuId) {
            MenuRow menu = menusById.get(menuId);
            return menu != null && sensitive(menu);
        }

        /** 模板默认可分配（敏感项不能预置进模板，默认一律不可分配，只能按租户单独放开）。 */
        boolean defaultAssignable(MenuRow menu) {
            return !sensitive(menu) && templateMenuIds.contains(menu.getId());
        }

        static ScopeModel of(
                List<MenuRow> hospitalMenus,
                Set<String> openedModules,
                Set<Long> templateMenuIds,
                Set<String> sensitivePermCodes,
                Map<Long, Boolean> overrides) {
            Map<Long, MenuRow> byId = new LinkedHashMap<>();
            hospitalMenus.forEach(menu -> byId.put(menu.getId(), menu));
            Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
            overrides.forEach((menuId, value) -> rows.put(menuId, Map.of("assignable", value)));
            return new ScopeModel(
                    hospitalMenus,
                    byId,
                    openedModules,
                    templateMenuIds,
                    sensitivePermCodes,
                    overrides,
                    rows);
        }
    }

    /** 租户库的影响面（PLT-SCOPE-005、006）：持有被收回菜单的角色、涉及授权条数与受影响用户。 */
    record RevokeImpact(List<ScopeAffectedRole> roles, Set<Long> userIds, int grantCount) {

        static final RevokeImpact EMPTY = new RevokeImpact(List.of(), Set.of(), 0);
    }

    record Diff(List<Long> removed, List<Long> added) {}

    // ---------------------------------------------------------------- 查询（PLT-SCOPE-001、002）

    /** 租户详情 · 授权范围页签：按模块分组的菜单 / 按钮清单（平铺带 parentId，前端组树）。 */
    public TenantScopeView scopeOf(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    ScopeModel model = loadModel(tenantId);
                    Set<Long> effective = effectiveAssignable(model);
                    Map<String, Map<String, Object>> modules = moduleRows();
                    List<ScopeModuleGroup> groups = new ArrayList<>();
                    for (Map.Entry<String, Map<String, Object>> entry : modules.entrySet()) {
                        String code = entry.getKey();
                        boolean base = BASE_MODULE.equals(code);
                        boolean opened = base || model.openedModules().contains(code);
                        List<ScopeItem> items = new ArrayList<>();
                        for (MenuRow menu : model.hospitalMenus()) {
                            if (!code.equals(menu.getModuleCode())) {
                                continue;
                            }
                            boolean assignable = opened && effective.contains(menu.getId());
                            String state =
                                    !opened
                                            ? STATE_CLOSED
                                            : assignable ? STATE_ASSIGNABLE : STATE_NOT_RELEASED;
                            Map<String, Object> row = model.overrideRows().get(menu.getId());
                            items.add(
                                    new ScopeItem(
                                            menu.getId(),
                                            menu.getParentId() == null ? 0L : menu.getParentId(),
                                            menu.getType(),
                                            menu.getTitle(),
                                            menu.getPermCode(),
                                            menu.getModuleCode(),
                                            model.sensitive(menu),
                                            model.defaultAssignable(menu),
                                            assignable,
                                            base,
                                            state,
                                            row == null ? null : strOrNull(row.get("reason")),
                                            row == null
                                                    ? null
                                                    : offsetDateTime(row.get("set_at"))));
                        }
                        if (!items.isEmpty()) {
                            groups.add(
                                    new ScopeModuleGroup(
                                            code,
                                            str(entry.getValue().get("name")),
                                            base,
                                            opened,
                                            items));
                        }
                    }
                    return new TenantScopeView(
                            tenantId,
                            str(tenant.get("code")),
                            str(tenant.get("name")),
                            str(tenant.get("status")),
                            groups);
                });
    }

    /** 生效可分配集（AuthService 医院端会话交集用）：base 固定能力 ∪（模板默认按覆盖调整）∩ 已开通模块。 */
    public Set<Long> assignableMenuIds(long tenantId) {
        return TenantContext.callAsPlatform(() -> effectiveAssignable(loadModel(tenantId)));
    }

    // ---------------------------------------------------------------- 影响分析（PLT-SCOPE-005）

    /** 保存前的影响分析：与 {@link #apply} 同一套校验与差异计算，只读不写。 */
    public ScopePreviewView preview(long tenantId, ScopeUpdateRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    ScopeModel model = loadModel(tenantId);
                    Set<Long> before = effectiveAssignable(model);
                    Set<Long> desired = validateUpdate(request, model, before);
                    Diff diff = diff(before, desired);
                    RevokeImpact revoke =
                            diff.removed().isEmpty()
                                    ? RevokeImpact.EMPTY
                                    : revokeImpact(tenantId, tenant, model, diff.removed());
                    return new ScopePreviewView(
                            tenantId,
                            str(tenant.get("code")),
                            impactItems(model, diff.removed()),
                            impactItems(model, diff.added()),
                            impactItems(
                                    model,
                                    diff.added().stream().filter(model::sensitiveById).toList()),
                            revoke.roles(),
                            revoke.userIds().size());
                });
    }

    // ---------------------------------------------------------------- 保存 + 自动收回（PLT-SCOPE-006、008）

    public ScopeChangeResult apply(long tenantId, ScopeUpdateRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    ScopeModel model = loadModel(tenantId);
                    Set<Long> before = effectiveAssignable(model);
                    Set<Long> desired = validateUpdate(request, model, before);
                    Diff diff = diff(before, desired);

                    // 自动收回：从租户库角色中移除超出项，相关用户权限版本递增（PLT-SCOPE-006）。
                    // 租户库不可达时整体失败（40901），不留「范围已缩、角色未收」的中间态。
                    int revokedRoles = 0;
                    int revokedGrants = 0;
                    int refreshedUsers = 0;
                    if (!diff.removed().isEmpty()) {
                        if (!routing.hasTenant(tenantId)) {
                            throw new ApiException(409, 40901, "租户库未注册路由，无法同步收回角色授权，请先完成租户开通");
                        }
                        RevokeImpact revoke = revokeImpact(tenantId, tenant, model, diff.removed());
                        TenantContext.callAsTenant(
                                tenantId,
                                str(tenant.get("code")),
                                () ->
                                        platform.update(
                                                "DELETE FROM sys_role_menu WHERE menu_id IN ("
                                                        + placeholders(diff.removed().size())
                                                        + ")",
                                                diff.removed().toArray()));
                        revokedRoles = revoke.roles().size();
                        revokedGrants = revoke.grantCount();
                        refreshedUsers = revoke.userIds().size();
                        for (Long userId : revoke.userIds()) {
                            permissionVersions.bump("HOSPITAL", tenantId, userId);
                        }
                    }

                    applyOverrides(tenantId, model, desired, request);
                    String detail =
                            diffText(model, diff)
                                    + "；自动收回角色授权 "
                                    + revokedGrants
                                    + " 条（"
                                    + revokedRoles
                                    + " 个角色），刷新 "
                                    + refreshedUsers
                                    + " 个用户的权限版本";
                    String reason = request.reason().trim();
                    platform.update(
                            "INSERT INTO plt_scope_log (tenant_id, reason, detail, operator_id)"
                                    + " VALUES (?, ?, ?, ?)",
                            tenantId,
                            reason,
                            abbreviate(detail, DETAIL_MAX),
                            operatorId());
                    audit.record(
                            new AuditEvent(
                                    "SCOPE_UPDATE",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_tenant:" + str(tenant.get("code")) + ":scope",
                                    abbreviate(detail + "；原因：" + reason, DETAIL_MAX)));
                    return new ScopeChangeResult(
                            tenantId,
                            str(tenant.get("code")),
                            diff.removed(),
                            diff.added(),
                            revokedRoles,
                            revokedGrants,
                            refreshedUsers);
                });
    }

    // ---------------------------------------------------------------- 变更记录（PLT-SCOPE-008）

    /** 变更记录倒序（最新在前）。 */
    public List<ScopeLogView> logs(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    requireTenant(tenantId);
                    return platform.query(
                            "SELECT id, tenant_id, reason, detail, operator_id, created_at"
                                    + " FROM plt_scope_log WHERE tenant_id = ? ORDER BY id DESC",
                            (rs, rowNum) ->
                                    new ScopeLogView(
                                            rs.getLong("id"),
                                            rs.getLong("tenant_id"),
                                            rs.getString("reason"),
                                            rs.getString("detail"),
                                            rs.getObject("operator_id") == null
                                                    ? null
                                                    : rs.getLong("operator_id"),
                                            offsetDateTime(rs.getTimestamp("created_at"))),
                            tenantId);
                });
    }

    // ---------------------------------------------------------------- 校验与计算（纯函数，供单测）

    /** 生效可分配集（PLT-SCOPE-001、002、004）：base 固定能力恒可分配；已开通模块内取「模板默认 ∩ 非敏感」 再按租户覆盖调整；未开通模块一律不可分配。 */
    static Set<Long> effectiveAssignable(ScopeModel model) {
        Set<Long> result = new LinkedHashSet<>();
        for (MenuRow menu : model.hospitalMenus()) {
            boolean base = BASE_MODULE.equals(menu.getModuleCode());
            if (!base && !model.openedModules().contains(menu.getModuleCode())) {
                continue;
            }
            boolean assignable =
                    base
                            ? true
                            : model.overrides().containsKey(menu.getId())
                                    ? model.overrides().get(menu.getId())
                                    : model.defaultAssignable(menu);
            if (assignable) {
                result.add(menu.getId());
            }
        }
        return result;
    }

    /**
     * 保存校验（PLT-SCOPE-002、004、008）：变更原因必填 ≤200；勾选项必须属于授权范围（医院端启用菜单且模块 已开通）；base
     * 固定能力不能收回；新放开敏感项逐一填写原因 ≤200。
     *
     * @return 期望的可分配全集
     */
    static Set<Long> validateUpdate(
            ScopeUpdateRequest request, ScopeModel model, Set<Long> before) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw bad("必须填写变更原因");
        }
        if (request.reason().trim().length() > REASON_MAX) {
            throw bad("变更原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        if (request.assignableMenuIds() == null) {
            throw bad("assignableMenuIds 不能为空（提交期望的可分配全集）");
        }
        Map<Long, String> releaseReasons = new HashMap<>();
        if (request.sensitiveReasons() != null) {
            for (Map.Entry<Long, String> entry : request.sensitiveReasons().entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (value.isEmpty()) {
                    throw bad("放开敏感权限的原因必填（菜单 / 按钮 " + entry.getKey() + "）");
                }
                if (value.length() > REASON_MAX) {
                    throw bad(
                            "放开敏感权限的原因过长（最多 "
                                    + REASON_MAX
                                    + " 个字符，菜单 / 按钮 "
                                    + entry.getKey()
                                    + "）");
                }
                releaseReasons.put(entry.getKey(), value);
            }
        }
        Set<Long> desired = new LinkedHashSet<>();
        for (Long menuId : request.assignableMenuIds()) {
            MenuRow menu = model.menusById().get(menuId);
            if (menu == null) {
                throw bad("菜单 / 按钮 " + menuId + " 不在租户授权范围内（须为医院端启用菜单）");
            }
            boolean base = BASE_MODULE.equals(menu.getModuleCode());
            if (!base && !model.openedModules().contains(menu.getModuleCode())) {
                throw bad("“" + menu.getTitle() + "”所属模块未开通，不能进入可分配范围");
            }
            desired.add(menuId);
        }
        // base 固定能力（组织、用户、角色管理）不受可分配范围限制，也不允许收回
        for (MenuRow menu : model.hospitalMenus()) {
            if (BASE_MODULE.equals(menu.getModuleCode()) && !desired.contains(menu.getId())) {
                throw bad("平台基础模块固定能力不能收回：“" + menu.getTitle() + "”");
            }
        }
        // 本次新放开的敏感项：原因必填（PLT-SCOPE-004）
        for (Long menuId : desired) {
            if (before.contains(menuId) || !model.sensitiveById(menuId)) {
                continue;
            }
            if (!releaseReasons.containsKey(menuId)) {
                throw bad("放开敏感权限“" + model.menusById().get(menuId).getTitle() + "”必须填写原因");
            }
        }
        return desired;
    }

    /** 前后差异：removed（将收回）/ added（将放开），按菜单 id 升序保证稳定。 */
    static Diff diff(Set<Long> before, Set<Long> desired) {
        List<Long> removed = new ArrayList<>(new TreeSet<>(before));
        removed.removeAll(desired);
        List<Long> added = new ArrayList<>(new TreeSet<>(desired));
        added.removeAll(before);
        return new Diff(removed, added);
    }

    // ---------------------------------------------------------------- 内部

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private ScopeModel loadModel(long tenantId) {
        // listMenus 只返回 ENABLED 行（与菜单下发 / 会话交集同一口径，停用与软删行不进授权范围）
        List<MenuRow> menus = platformMapper.listMenus("HOSPITAL");
        Set<String> opened =
                new LinkedHashSet<>(
                        platform.queryForList(
                                "SELECT module_code FROM plt_tenant_module"
                                        + " WHERE tenant_id = ? AND status = 'OPEN'",
                                String.class,
                                tenantId));
        Set<Long> template =
                new LinkedHashSet<>(
                        platform.queryForList(
                                "SELECT menu_id FROM plt_scope_template", Long.class));
        Set<String> sensitive =
                new LinkedHashSet<>(
                        platform.queryForList(
                                "SELECT perm_code FROM plt_sensitive_perm", String.class));
        Map<Long, Boolean> overrides = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> overrideRows = new LinkedHashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT menu_id, assignable, reason, set_at FROM plt_tenant_scope"
                                + " WHERE tenant_id = ?",
                        tenantId)) {
            long menuId = ((Number) row.get("menu_id")).longValue();
            overrides.put(menuId, ((Number) row.get("assignable")).intValue() == 1);
            overrideRows.put(menuId, row);
        }
        return new ScopeModel(
                menus, menusById(menus), opened, template, sensitive, overrides, overrideRows);
    }

    private static Map<Long, MenuRow> menusById(List<MenuRow> menus) {
        Map<Long, MenuRow> byId = new LinkedHashMap<>();
        menus.forEach(menu -> byId.put(menu.getId(), menu));
        return byId;
    }

    private Map<String, Map<String, Object>> moduleRows() {
        Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
        for (Map<String, Object> row :
                platform.queryForList(
                        "SELECT code, name, base_module FROM plt_module"
                                + " ORDER BY base_module DESC, code")) {
            modules.put(str(row.get("code")), row);
        }
        return modules;
    }

    private Map<String, Object> requireTenant(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, status FROM plt_tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private static List<ScopeImpactItem> impactItems(ScopeModel model, List<Long> menuIds) {
        List<ScopeImpactItem> items = new ArrayList<>();
        for (Long menuId : menuIds) {
            MenuRow menu = model.menusById().get(menuId);
            items.add(new ScopeImpactItem(menuId, menu.getTitle(), menu.getPermCode()));
        }
        return items;
    }

    /** 租户库的影响面：持有被收回菜单的角色（含涉及授权条数、将消失的权限码）与受影响用户。 */
    private RevokeImpact revokeImpact(
            long tenantId, Map<String, Object> tenant, ScopeModel model, List<Long> removed) {
        String in = placeholders(removed.size());
        Object[] args = removed.toArray();
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    str(tenant.get("code")),
                    () -> {
                        Map<Long, Integer> menuCounts = new LinkedHashMap<>();
                        for (Map<String, Object> row :
                                platform.queryForList(
                                        "SELECT rm.role_id AS roleId, COUNT(*) AS grants"
                                                + " FROM sys_role_menu rm WHERE rm.menu_id IN ("
                                                + in
                                                + ") GROUP BY rm.role_id",
                                        args)) {
                            menuCounts.put(
                                    ((Number) row.get("roleId")).longValue(),
                                    ((Number) row.get("grants")).intValue());
                        }
                        Map<Long, Integer> userCounts = new LinkedHashMap<>();
                        for (Map<String, Object> row :
                                platform.queryForList(
                                        "SELECT ur.role_id AS roleId, COUNT(DISTINCT ur.user_id) AS"
                                                + " users FROM sys_user_role ur JOIN sys_role_menu rm"
                                                + " ON rm.role_id = ur.role_id WHERE rm.menu_id IN ("
                                                + in
                                                + ") GROUP BY ur.role_id",
                                        args)) {
                            userCounts.put(
                                    ((Number) row.get("roleId")).longValue(),
                                    ((Number) row.get("users")).intValue());
                        }
                        Set<Long> userIds =
                                new LinkedHashSet<>(
                                        platform.queryForList(
                                                "SELECT DISTINCT ur.user_id FROM sys_user_role ur"
                                                        + " JOIN sys_role_menu rm ON rm.role_id ="
                                                        + " ur.role_id WHERE rm.menu_id IN ("
                                                        + in
                                                        + ")",
                                                Long.class,
                                                args));
                        List<String> removedPermCodes = new ArrayList<>();
                        for (Long menuId : removed) {
                            String permCode = model.menusById().get(menuId).getPermCode();
                            if (permCode != null && !permCode.isBlank()) {
                                removedPermCodes.add(permCode);
                            }
                        }
                        List<ScopeAffectedRole> roles = new ArrayList<>();
                        int grantCount = 0;
                        for (Map.Entry<Long, Integer> entry : menuCounts.entrySet()) {
                            Map<String, Object> role =
                                    platform.queryForMap(
                                            "SELECT code, name FROM sys_role WHERE id = ?",
                                            entry.getKey());
                            roles.add(
                                    new ScopeAffectedRole(
                                            entry.getKey(),
                                            str(role.get("code")),
                                            str(role.get("name")),
                                            entry.getValue(),
                                            removedPermCodes,
                                            userCounts.getOrDefault(entry.getKey(), 0)));
                            grantCount += entry.getValue();
                        }
                        return new RevokeImpact(roles, userIds, grantCount);
                    });
        } catch (UnknownTenantDataSourceException | DataAccessException ex) {
            throw new ApiException(409, 40901, "租户库暂不可达，无法评估或执行收回，请稍后重试");
        }
    }

    /** 持久化覆盖（PLT-SCOPE-002）：只存与「模板默认 ∩ 非敏感」不同的项——期望值等于默认时删掉覆盖行（回到 模板默认），敏感放开行保留原因与设置人。 */
    private void applyOverrides(
            long tenantId, ScopeModel model, Set<Long> desired, ScopeUpdateRequest request) {
        for (MenuRow menu : model.hospitalMenus()) {
            if (BASE_MODULE.equals(menu.getModuleCode())
                    || !model.openedModules().contains(menu.getModuleCode())) {
                continue; // base 固定能力与未开通模块不落覆盖
            }
            boolean wanted = desired.contains(menu.getId());
            if (wanted == model.defaultAssignable(menu)) {
                platform.update(
                        "DELETE FROM plt_tenant_scope WHERE tenant_id = ? AND menu_id = ?",
                        tenantId,
                        menu.getId());
                continue;
            }
            // 敏感项保持放开时沿用已登记的放开原因；本次新放开的原因已在 validateUpdate 强制校验
            String releaseReason = null;
            if (wanted && model.sensitive(menu)) {
                String provided =
                        request.sensitiveReasons() == null
                                ? null
                                : request.sensitiveReasons().get(menu.getId());
                if (provided != null && !provided.isBlank()) {
                    releaseReason = provided.trim();
                } else {
                    Map<String, Object> existing = model.overrideRows().get(menu.getId());
                    releaseReason = existing == null ? null : strOrNull(existing.get("reason"));
                }
            }
            platform.update(
                    "INSERT INTO plt_tenant_scope (tenant_id, menu_id, assignable, reason, set_by)"
                            + " VALUES (?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE assignable = VALUES(assignable),"
                            + " reason = VALUES(reason), set_by = VALUES(set_by)",
                    tenantId,
                    menu.getId(),
                    wanted ? 1 : 0,
                    releaseReason,
                    operatorId());
        }
    }

    private static String diffText(ScopeModel model, Diff diff) {
        StringBuilder text = new StringBuilder();
        if (!diff.removed().isEmpty()) {
            text.append("收回：").append(titlesOf(model, diff.removed()));
        }
        if (!diff.added().isEmpty()) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append("放开：").append(titlesOf(model, diff.added()));
        }
        if (text.length() == 0) {
            text.append("无变化");
        }
        return text.toString();
    }

    private static String titlesOf(ScopeModel model, List<Long> menuIds) {
        List<String> titles = new ArrayList<>();
        for (Long menuId : menuIds) {
            MenuRow menu = model.menusById().get(menuId);
            titles.add(
                    menu.getTitle()
                            + (menu.getPermCode() == null ? "" : "（" + menu.getPermCode() + "）"));
        }
        return String.join("、", titles);
    }

    private static String placeholders(int size) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
        return sql.toString();
    }

    private static Long operatorId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    static String abbreviate(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
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
}
