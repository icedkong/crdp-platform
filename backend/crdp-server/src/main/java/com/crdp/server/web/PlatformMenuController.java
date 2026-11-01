package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.MenuAdminService;
import com.crdp.server.platform.MenuSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端菜单资源 API（E05-T06 / PLT-PERM-001、002、003、011）：全量菜单树（含按钮与停用行）、 目录 / 菜单 / 按钮维护（软删）、菜单清单同步（差异 +
 * 确认应用）。
 */
@Tag(name = "平台端菜单资源")
@RestController
@RequestMapping("/api/platform/menus")
public class PlatformMenuController {

    private final MenuAdminService adminService;
    private final MenuSyncService syncService;

    public PlatformMenuController(MenuAdminService adminService, MenuSyncService syncService) {
        this.adminService = adminService;
        this.syncService = syncService;
    }

    @Operation(summary = "全量菜单树（PLT-PERM-001..003）：按端别取目录 / 菜单 / 按钮三级树，含停用行（软删行不返回）")
    @GetMapping("/tree")
    @RequiresPermission("platform:menu:view")
    public ApiResponse<List<MenuAdminService.MenuTreeNode>> tree(
            @RequestParam(defaultValue = "PLATFORM") String side) {
        return ApiResponse.ok(adminService.tree(side));
    }

    @Operation(summary = "新建目录 / 菜单 / 按钮（PLT-PERM-001..003）：moduleCode 必填且须已登记；按钮必填全局唯一权限码")
    @PostMapping
    @RequiresPermission("platform:menu:edit")
    public ApiResponse<MenuAdminService.MenuTreeNode> create(
            @Valid @RequestBody MenuAdminService.CreateRequest request) {
        return ApiResponse.ok(adminService.create(request));
    }

    @Operation(
            summary =
                    "维护菜单（PLT-PERM-001）：标题、图标、排序、隐藏、路由字段、停用可改；端别 / 类型 / 模块 /"
                            + " 权限码 / 来源不可改（权限码不可改名，OQ-11）")
    @PutMapping("/{id}")
    @RequiresPermission("platform:menu:edit")
    public ApiResponse<MenuAdminService.MenuTreeNode> update(
            @PathVariable long id, @Valid @RequestBody MenuAdminService.UpdateRequest request) {
        return ApiResponse.ok(adminService.update(id, request));
    }

    @Operation(summary = "删除（软删，PLT-PERM-001）：移除平台角色授予并逐租户清理 sys_role_menu，受影响用户权限版本递增")
    @DeleteMapping("/{id}")
    @RequiresPermission("platform:menu:edit")
    public ApiResponse<Void> delete(@PathVariable long id) {
        adminService.delete(id);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "菜单清单同步差异（PLT-PERM-011）：随包清单与库内比对——新增 / 变更（结构字段）/ 下线，只读不写库")
    @GetMapping("/sync/diff")
    @RequiresPermission("platform:menu:view")
    public ApiResponse<MenuSyncService.DiffResult> diff() {
        return ApiResponse.ok(syncService.diff());
    }

    @Operation(summary = "应用菜单清单差异（PLT-PERM-011）：平台确认后生效——新增插入、变更改结构字段、下线置停用；" + "写 MENU_SYNC 审计")
    @PostMapping("/sync")
    @RequiresPermission("platform:menu:edit")
    public ApiResponse<MenuSyncService.ApplyResult> apply() {
        return ApiResponse.ok(syncService.apply());
    }
}
