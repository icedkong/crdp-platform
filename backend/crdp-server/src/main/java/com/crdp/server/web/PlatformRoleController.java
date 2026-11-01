package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端角色管理 API（E06-T04 / PLT-ROLE-002、003，docs/api/platform.md §5）。
 *
 * <p>平台预置角色（平台管理员、实施人员、运维人员、客服支持）与自定义角色同表维护：预置角色不可删除
 * （不提供删除接口），权限（菜单授权、租户视角字段、状态）可由平台管理员调整；自定义角色新建后同样 只能维护与停用。查看与维护是两个权限点（platform-spec §1 权限点表）。
 */
@Tag(name = "平台端角色管理")
@RestController
@RequestMapping("/api/platform/roles")
public class PlatformRoleController {

    private final PlatformRoleService roleService;

    public PlatformRoleController(PlatformRoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "平台角色列表（PLT-ROLE-002、003）：预置 + 自定义，含停用行与持有人数")
    @GetMapping
    @RequiresPermission("platform:role:view")
    public ApiResponse<List<PlatformRoleService.RoleView>> list() {
        return ApiResponse.ok(roleService.list());
    }

    @Operation(summary = "可授权的平台端启用菜单 / 按钮（平台角色页授权树数据源）")
    @GetMapping("/menus")
    @RequiresPermission("platform:role:view")
    public ApiResponse<List<PlatformRoleService.GrantMenuNode>> grantableMenus() {
        return ApiResponse.ok(roleService.grantableMenus());
    }

    @Operation(summary = "角色详情（含已授权菜单 id 与租户视角权限六字段）")
    @GetMapping("/{roleId}")
    @RequiresPermission("platform:role:view")
    public ApiResponse<PlatformRoleService.RoleView> get(@PathVariable long roleId) {
        return ApiResponse.ok(roleService.get(roleId));
    }

    @Operation(summary = "持有人列表（platform-spec §1 主要页面「查看持有人」）")
    @GetMapping("/{roleId}/holders")
    @RequiresPermission("platform:role:view")
    public ApiResponse<List<PlatformRoleService.RoleHolder>> holders(@PathVariable long roleId) {
        return ApiResponse.ok(roleService.holders(roleId));
    }

    @Operation(summary = "新建平台自定义角色（PLT-ROLE-003）：授权平台端菜单并配置租户视角权限；" + "编码由服务端生成，创建后不可改")
    @PostMapping
    @RequiresPermission("platform:role:edit")
    public ApiResponse<PlatformRoleService.RoleView> create(
            @RequestBody PlatformRoleService.RoleSaveRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    @Operation(
            summary =
                    "维护平台角色（PLT-ROLE-002「预置角色不可删除，权限可调整」）：名称、说明、菜单授权、"
                            + "租户视角六字段与状态；授权或状态变化即递增持有者权限版本（在线会话下次请求生效）")
    @PutMapping("/{roleId}")
    @RequiresPermission("platform:role:edit")
    public ApiResponse<PlatformRoleService.RoleView> update(
            @PathVariable long roleId, @RequestBody PlatformRoleService.RoleSaveRequest request) {
        return ApiResponse.ok(roleService.update(roleId, request));
    }
}
