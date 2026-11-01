package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 授权范围模板与敏感权限清单（E05-T07 / PLT-SCOPE-003、004，页面「授权范围模板」）。
 *
 * <p>模板是按模块预置的默认可分配项（白名单，{@code plt_scope_template}）：新开通模块与无租户级覆盖的项 都取模板默认；清单同步新增的菜单项不在模板内 →
 * 默认不进入可分配范围，须平台确认（改模板）后对租户生效（平台规格 §4 业务规则）。敏感项不能预置进模板，只能按租户单独放开（{@link TenantScopeService}）。
 *
 * <p>敏感权限清单（{@code plt_sensitive_perm}，平台规格 §5「默认不可分配的敏感权限」）命中的按钮默认不可
 * 分配；平台管理员可增减清单。模板与清单变更只改默认值：对已开通租户的生效集在下次会话重算 / 登录时收敛，租户角色的收回走租户级范围变更（影响分析 +
 * 确认，PLT-SCOPE-005、006），模板编辑不做逐租户自动收回。
 */
@Service
public class ScopeTemplateService {

    /** 权限码格式 {模块}:{资源}:{动作}（平台规格 §4；创建后不可改名，OQ-11）。 */
    static final Pattern PERM_CODE = Pattern.compile("[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*){2}");

    private static final int NAME_MAX = 128;

    private final JdbcTemplate platform;
    private final PlatformMapper platformMapper;
    private final AuditLogger audit;

    public ScopeTemplateService(
            JdbcTemplate platform, PlatformMapper platformMapper, AuditLogger audit) {
        this.platform = platform;
        this.platformMapper = platformMapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 模板视图里的一个菜单 / 按钮（sensitive 项不可预置进模板）。 */
    public record TemplateItem(
            long menuId,
            long parentId,
            String title,
            String permCode,
            boolean sensitive,
            boolean inTemplate) {}

    public record TemplateModuleGroup(
            String code, String name, boolean baseModule, List<TemplateItem> items) {}

    /** 敏感权限清单行（PLT-SCOPE-004；命中的按钮默认不可分配）。 */
    public record SensitivePermRow(long id, String permCode, String name) {}

    public record ScopeTemplateView(
            List<TemplateModuleGroup> modules, List<SensitivePermRow> sensitivePerms) {}

    /** 模板保存（PLT-SCOPE-003）：提交该模块默认可分配的全集（替换式）。 */
    public record TemplateUpdateRequest(List<Long> menuIds) {}

    /** 敏感清单保存（PLT-SCOPE-004）：整表替换；perm_code 须符合权限码格式，未绑定的码先登记后生效。 */
    public record SensitivePermItem(String permCode, String name) {}

    public record SensitivePermUpdateRequest(List<SensitivePermItem> items) {}

    // ---------------------------------------------------------------- 查询

    /** 模板页：按模块的医院端菜单 / 按钮清单（inTemplate 勾选态）+ 敏感权限清单。 */
    public ScopeTemplateView view() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<MenuRow> menus = platformMapper.listMenus("HOSPITAL");
                    Set<Long> template = templateMenuIds();
                    Set<String> sensitivePerms = sensitivePermCodes();
                    Map<String, Map<String, Object>> modules = moduleRows();
                    List<TemplateModuleGroup> groups = new ArrayList<>();
                    for (Map.Entry<String, Map<String, Object>> entry : modules.entrySet()) {
                        String code = entry.getKey();
                        List<TemplateItem> items = new ArrayList<>();
                        for (MenuRow menu : menus) {
                            if (!code.equals(menu.getModuleCode())) {
                                continue;
                            }
                            boolean sensitive =
                                    menu.getPermCode() != null
                                            && sensitivePerms.contains(menu.getPermCode());
                            items.add(
                                    new TemplateItem(
                                            menu.getId(),
                                            menu.getParentId() == null ? 0L : menu.getParentId(),
                                            menu.getTitle(),
                                            menu.getPermCode(),
                                            sensitive,
                                            template.contains(menu.getId())));
                        }
                        if (!items.isEmpty()) {
                            groups.add(
                                    new TemplateModuleGroup(
                                            code,
                                            str(entry.getValue().get("name")),
                                            TenantScopeService.BASE_MODULE.equals(code),
                                            items));
                        }
                    }
                    List<SensitivePermRow> rows =
                            platform.query(
                                    "SELECT id, perm_code, name FROM plt_sensitive_perm"
                                            + " ORDER BY id",
                                    (rs, rowNum) ->
                                            new SensitivePermRow(
                                                    rs.getLong("id"),
                                                    rs.getString("perm_code"),
                                                    rs.getString("name")));
                    return new ScopeTemplateView(groups, rows);
                });
    }

    // ---------------------------------------------------------------- 模板维护（PLT-SCOPE-003）

    /** 替换式保存某模块的默认可分配项；只能勾该模块的医院端启用菜单且不能包含敏感项。 */
    public ScopeTemplateView applyTemplate(String moduleCode, TemplateUpdateRequest request) {
        return TenantContext.callAsPlatform(
                () -> {
                    List<Long> desired =
                            normalizeTemplate(request, moduleCode, platformMapper, platform);
                    Set<Long> before = templateMenuIds(moduleCode);
                    platform.update(
                            "DELETE FROM plt_scope_template WHERE module_code = ?", moduleCode);
                    for (Long menuId : desired) {
                        platform.update(
                                "INSERT INTO plt_scope_template (module_code, menu_id, updated_by)"
                                        + " VALUES (?, ?, ?)",
                                moduleCode,
                                menuId,
                                operatorId());
                    }
                    audit.record(
                            new AuditEvent(
                                    "SCOPE_TEMPLATE_UPDATE",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_scope_template:" + moduleCode,
                                    TenantScopeService.abbreviate(
                                            "模板默认项 "
                                                    + before.size()
                                                    + "→"
                                                    + desired.size()
                                                    + "（新增 "
                                                    + countAdded(before, desired)
                                                    + "、移除 "
                                                    + countRemoved(before, desired)
                                                    + "）；只改默认值，租户角色收回须走租户级范围变更",
                                            512)));
                    return view();
                });
    }

    // ---------------------------------------------------------------- 敏感清单维护（PLT-SCOPE-004）

    /** 整表替换敏感权限清单；新码先登记后生效（命中的按钮默认不可分配，已放开租户的覆盖不受影响）。 */
    public ScopeTemplateView applySensitivePerms(SensitivePermUpdateRequest request) {
        List<SensitivePermItem> items = normalizeSensitivePerms(request);
        return TenantContext.callAsPlatform(
                () -> {
                    List<SensitivePermRow> before =
                            platform.query(
                                    "SELECT id, perm_code, name FROM plt_sensitive_perm ORDER BY id",
                                    (rs, rowNum) ->
                                            new SensitivePermRow(
                                                    rs.getLong("id"),
                                                    rs.getString("perm_code"),
                                                    rs.getString("name")));
                    platform.update("DELETE FROM plt_sensitive_perm");
                    for (SensitivePermItem item : items) {
                        platform.update(
                                "INSERT INTO plt_sensitive_perm (perm_code, name) VALUES (?, ?)",
                                item.permCode(),
                                item.name());
                    }
                    audit.record(
                            new AuditEvent(
                                    "SENSITIVE_PERM_UPDATE",
                                    operatorId(),
                                    "UPDATE",
                                    "plt_sensitive_perm",
                                    TenantScopeService.abbreviate(
                                            "敏感权限清单 "
                                                    + before.size()
                                                    + "→"
                                                    + items.size()
                                                    + " 项（新增 "
                                                    + countPermAdded(before, items)
                                                    + "、移除 "
                                                    + countPermRemoved(before, items)
                                                    + "）；新码命中按钮后默认不可分配",
                                            512)));
                    return view();
                });
    }

    // ---------------------------------------------------------------- 校验（纯函数，供单测）

    /** 模板保存校验：base 是固定能力不进模板；勾选项必须是该模块的医院端启用菜单（40001）；敏感项不能预置 进模板（只能按租户单独放开）。 */
    static List<Long> normalizeTemplate(
            TemplateUpdateRequest request,
            String moduleCode,
            PlatformMapper platformMapper,
            JdbcTemplate platform) {
        if (request == null || request.menuIds() == null) {
            throw bad("menuIds 不能为空（提交该模块默认可分配的全集）");
        }
        if (TenantScopeService.BASE_MODULE.equals(moduleCode)) {
            throw bad("平台基础模块是固定能力，不进授权范围模板");
        }
        Map<Long, MenuRow> moduleMenus = new LinkedHashMap<>();
        for (MenuRow menu : platformMapper.listMenus("HOSPITAL")) {
            if (moduleCode.equals(menu.getModuleCode())) {
                moduleMenus.put(menu.getId(), menu);
            }
        }
        if (moduleMenus.isEmpty() && moduleMissing(platform, moduleCode)) {
            throw new ApiException(404, 40400, "模块不存在");
        }
        Set<String> sensitivePerms =
                new LinkedHashSet<>(
                        platform.queryForList(
                                "SELECT perm_code FROM plt_sensitive_perm", String.class));
        Set<Long> desired = new LinkedHashSet<>();
        for (Long menuId : request.menuIds()) {
            MenuRow menu = moduleMenus.get(menuId);
            if (menu == null) {
                throw bad("菜单 / 按钮 " + menuId + " 不属于模块 " + moduleCode + "（或不是医院端启用菜单）");
            }
            if (menu.getPermCode() != null && sensitivePerms.contains(menu.getPermCode())) {
                throw bad("敏感权限“" + menu.getTitle() + "”不能预置进模板，只能按租户单独放开");
            }
            desired.add(menuId);
        }
        // 按 id 升序落库，保证替换式保存的行序稳定
        return new ArrayList<>(new java.util.TreeSet<>(desired));
    }

    /** 敏感清单校验：每项权限码格式 {模块}:{资源}:{动作}、名称必填 ≤128；清单内权限码唯一。 */
    static List<SensitivePermItem> normalizeSensitivePerms(SensitivePermUpdateRequest request) {
        if (request == null || request.items() == null) {
            throw bad("items 不能为空（整表替换敏感权限清单）");
        }
        Set<String> codes = new LinkedHashSet<>();
        List<SensitivePermItem> items = new ArrayList<>();
        for (SensitivePermItem item : request.items()) {
            if (item == null
                    || item.permCode() == null
                    || !PERM_CODE.matcher(item.permCode()).matches()) {
                throw bad("敏感权限的权限码必须符合 {模块}:{资源}:{动作} 格式");
            }
            if (!codes.add(item.permCode())) {
                throw bad("敏感权限清单内权限码重复：" + item.permCode());
            }
            if (item.name() == null || item.name().isBlank()) {
                throw bad("敏感权限 " + item.permCode() + " 的说明必填");
            }
            String name = item.name().trim();
            if (name.length() > NAME_MAX) {
                throw bad("敏感权限说明过长（最多 " + NAME_MAX + " 个字符）");
            }
            items.add(new SensitivePermItem(item.permCode(), name));
        }
        return items;
    }

    // ---------------------------------------------------------------- 内部

    private static boolean moduleMissing(JdbcTemplate platform, String moduleCode) {
        return platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_module WHERE code = ?", Integer.class, moduleCode)
                == 0;
    }

    private void requireModule(String moduleCode) {
        if (moduleMissing(platform, moduleCode)) {
            throw new ApiException(404, 40400, "模块不存在");
        }
    }

    private Set<Long> templateMenuIds() {
        return new LinkedHashSet<>(
                platform.queryForList("SELECT menu_id FROM plt_scope_template", Long.class));
    }

    private Set<Long> templateMenuIds(String moduleCode) {
        return new LinkedHashSet<>(
                platform.queryForList(
                        "SELECT menu_id FROM plt_scope_template WHERE module_code = ?",
                        Long.class,
                        moduleCode));
    }

    private Set<String> sensitivePermCodes() {
        return new LinkedHashSet<>(
                platform.queryForList("SELECT perm_code FROM plt_sensitive_perm", String.class));
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

    private static long countAdded(Set<Long> before, List<Long> after) {
        return after.stream().filter(id -> !before.contains(id)).count();
    }

    private static long countRemoved(Set<Long> before, List<Long> after) {
        return before.stream().filter(id -> !after.contains(id)).count();
    }

    private static long countPermAdded(
            List<SensitivePermRow> before, List<SensitivePermItem> after) {
        return after.stream()
                .filter(
                        item ->
                                before.stream()
                                        .noneMatch(row -> row.permCode().equals(item.permCode())))
                .count();
    }

    private static long countPermRemoved(
            List<SensitivePermRow> before, List<SensitivePermItem> after) {
        return before.stream()
                .filter(
                        row ->
                                after.stream()
                                        .noneMatch(item -> item.permCode().equals(row.permCode())))
                .count();
    }

    private static Long operatorId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
