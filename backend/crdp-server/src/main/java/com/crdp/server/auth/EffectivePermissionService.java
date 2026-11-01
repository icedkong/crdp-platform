package com.crdp.server.auth;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import com.crdp.server.platform.TenantScopeService;
import com.crdp.system.user.HospitalAccount;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 有效权限计算（E06-T06 / PLT-ROLE-006）：按「已开通模块 ∩ 可分配范围 ∩ 用户角色并集」 实时计算菜单、按钮与接口权限。
 *
 * <pre>用户最终权限 = 医院已开通模块 ∩ 平台允许租户管理员分配的范围 ∩ 用户全部角色权限的并集</pre>
 *
 * <p>多角色合并（platform-spec §4 业务规则，已确认）：菜单、按钮、数据范围取并集——角色并集由租户库 SQL 先行完成 （{@code
 * HospitalMapper.menuIds} 只统计启用角色，停用角色的授权立即失效），本服务再做三层交集： 平台库启用菜单（停用 / 软删的菜单不出现）∩ 已开通模块 ∩
 * 可分配范围。会话层交集是兜底： 租户库不可达导致自动收回被跳过时，超范围授权也在下次重算时失效。
 *
 * <p>变更即生效（PLT-PERM-010）：本服务不缓存结果，登录与会话重算（{@code SessionPermissionRefresher}）每次都按库内当前状态计算； 授权用例（角色
 * / 用户 / 范围 / 菜单 / 模块）变更后递增受影响用户的权限版本号， 旧令牌下一次请求即按新权限执行。E06-T09 用户权限预览复用同一入口，保证「预览所见 = 会话所得」。
 */
@Service
public class EffectivePermissionService {

    private final PlatformMapper platformMapper;
    private final TenantScopeService tenantScopes;

    public EffectivePermissionService(
            PlatformMapper platformMapper, TenantScopeService tenantScopes) {
        this.platformMapper = platformMapper;
        this.tenantScopes = tenantScopes;
    }

    /** 有效授权结果：菜单（含按钮）编号、权限码与该院已开通模块（会话与 @RequireModule 校验共用）。 */
    public record Granted(
            List<Long> menuIds, List<String> permissionCodes, List<String> openedModules) {}

    /** 医院端某账号的有效授权（登录草稿与会话重算共用）。调用方需在平台 / 租户上下文之外（内部自行切换）。 */
    public Granted hospitalGrants(long tenantId, HospitalAccount account) {
        List<String> opened =
                TenantContext.callAsPlatform(() -> platformMapper.openedModules(tenantId));
        List<MenuRow> menus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("HOSPITAL"));
        Set<Long> assignable =
                TenantContext.callAsPlatform(() -> tenantScopes.assignableMenuIds(tenantId));
        return grant(menus, account.menuIds(), opened, assignable);
    }

    /** 平台账号的有效授权：平台端菜单不受租户可分配范围限制（assignable=null 表示不限）， 模块取平台库已登记的全集——平台账号先于租户看到新注册模块的管理入口。 */
    public Granted platformGrants(List<Long> grantedMenuIds) {
        List<MenuRow> menus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("PLATFORM"));
        List<String> modules = TenantContext.callAsPlatform(() -> platformMapper.listModuleCodes());
        return grant(menus, grantedMenuIds, modules, null);
    }

    /**
     * 三层交集的纯函数（便于单测与预览复用）：授权并集 ∩ 启用菜单 ∩ 已开通模块 ∩ 可分配范围。
     *
     * @param menus 平台库该端的启用菜单（停用 / 软删行不传入）
     * @param grantedIds 用户全部（启用）角色授权菜单的并集
     * @param openedModules 该租户已开通模块（平台端传全部登记模块）
     * @param assignableIds 可分配范围；null 表示不受限（平台端）
     */
    public static Granted grant(
            List<MenuRow> menus,
            List<Long> grantedIds,
            List<String> openedModules,
            Set<Long> assignableIds) {
        Set<Long> wanted = new HashSet<>(grantedIds);
        Set<String> opened = new HashSet<>(openedModules);
        List<MenuRow> effective =
                menus.stream()
                        .filter(row -> wanted.contains(row.getId()))
                        .filter(row -> opened.contains(row.getModuleCode()))
                        .filter(row -> assignableIds == null || assignableIds.contains(row.getId()))
                        .toList();
        List<Long> ids = effective.stream().map(MenuRow::getId).toList();
        List<String> codes =
                effective.stream()
                        .map(MenuRow::getPermCode)
                        .filter(Objects::nonNull)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .toList();
        return new Granted(ids, codes, List.copyOf(openedModules));
    }
}
