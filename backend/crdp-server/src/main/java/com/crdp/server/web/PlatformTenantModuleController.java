package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.TenantModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端租户模块 API（E05-T05 / PLT-MOD-004、005、006、008）：租户详情 · 模块页签。开通与关闭是 两个动作两个权限点（平台规格 §3 权限点），依赖校验失败返回
 * 40901 带依赖说明；模块级配置与开通记录见同服务。
 */
@Tag(name = "平台端租户模块")
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/modules")
public class PlatformTenantModuleController {

    private final TenantModuleService moduleService;

    public PlatformTenantModuleController(TenantModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @Operation(
            summary =
                    "租户模块视图（PLT-MOD-004、005）：目录全量行 + 该租户的开通状态（OPEN/CLOSED/OPEN_FAILED"
                            + " 或空=未开通）、当前迁移版本、开通 / 关闭时间与原因")
    @GetMapping
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantModuleService.TenantModulesView> modules(@PathVariable long tenantId) {
        return ApiResponse.ok(moduleService.modulesOf(tenantId));
    }

    @Operation(summary = "关闭影响评估（PLT-MOD-005）：在线用户数、进行中任务数与仍依赖它的已开通模块，供二次确认展示")
    @GetMapping("/{moduleCode}/close-impact")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantModuleService.CloseImpactView> closeImpact(
            @PathVariable long tenantId, @PathVariable String moduleCode) {
        return ApiResponse.ok(moduleService.closeImpact(tenantId, moduleCode));
    }

    @Operation(
            summary =
                    "开通模块（PLT-MOD-004）：校验依赖与租户状态 → 执行该租户库模块迁移（失败置开通失败并告警）→"
                            + " 菜单进入授权范围、初始化配置默认值；开通后菜单可见；写 MODULE_OPEN 审计")
    @PostMapping("/{moduleCode}/open")
    @RequiresPermission("platform:tenant:module:open")
    public ApiResponse<TenantModuleService.ChangeResult> open(
            @PathVariable long tenantId,
            @PathVariable String moduleCode,
            @RequestBody(required = false) TenantModuleService.OpenRequest request) {
        return ApiResponse.ok(moduleService.open(tenantId, moduleCode, request));
    }

    @Operation(
            summary =
                    "关闭模块（PLT-MOD-005）：依赖校验（被依赖时 40901）→ 原因必填 → 收回菜单与接口（40301）、"
                            + "数据保留；写 MODULE_CLOSE 审计")
    @PostMapping("/{moduleCode}/close")
    @RequiresPermission("platform:tenant:module:close")
    public ApiResponse<TenantModuleService.ChangeResult> close(
            @PathVariable long tenantId,
            @PathVariable String moduleCode,
            @RequestBody TenantModuleService.CloseRequest request) {
        return ApiResponse.ok(moduleService.close(tenantId, moduleCode, request));
    }

    @Operation(summary = "模块级配置（PLT-MOD-008）：每租户的模块参数，默认取模块定义；value 为租户覆盖值（空=用默认）")
    @GetMapping("/{moduleCode}/config")
    @RequiresPermission("platform:tenant:edit")
    public ApiResponse<TenantModuleService.ModuleConfigView> config(
            @PathVariable long tenantId, @PathVariable String moduleCode) {
        return ApiResponse.ok(moduleService.configOf(tenantId, moduleCode));
    }

    @Operation(
            summary =
                    "修改模块级配置（PLT-MOD-008）：{values:{配置项编码:取值}}；未知配置项 40001、未开通模块 40901；"
                            + "写 MODULE_CONFIG 审计（只记变化项）")
    @PutMapping("/{moduleCode}/config")
    @RequiresPermission("platform:tenant:edit")
    public ApiResponse<TenantModuleService.ModuleConfigView> updateConfig(
            @PathVariable long tenantId,
            @PathVariable String moduleCode,
            @RequestBody TenantModuleService.ConfigUpdateRequest request) {
        return ApiResponse.ok(moduleService.applyConfig(tenantId, moduleCode, request));
    }
}
