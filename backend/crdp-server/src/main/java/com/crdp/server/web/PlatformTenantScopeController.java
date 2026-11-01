package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.TenantScopeService;
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
 * 平台端租户授权范围 API（E05-T07 / PLT-SCOPE-001..006、008）：租户详情 · 授权范围页签。 查看与配置是两个权限点（平台规格 §5 权限点表）；保存前先调
 * preview 做影响分析（PLT-SCOPE-005），保存即自动收回 租户角色超出项并递增受影响用户权限版本（PLT-SCOPE-006）。
 */
@Tag(name = "平台端租户授权范围")
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/scope")
public class PlatformTenantScopeController {

    private final TenantScopeService scopeService;

    public PlatformTenantScopeController(TenantScopeService scopeService) {
        this.scopeService = scopeService;
    }

    @Operation(
            summary =
                    "租户授权范围视图（PLT-SCOPE-001、002、004）：按模块分组的医院端菜单 / 按钮，每项三种状态"
                            + "（未开通 / 已开通未放开 / 可分配）+ 敏感标记与放开痕迹")
    @GetMapping
    @RequiresPermission("platform:tenant:scope:view")
    public ApiResponse<TenantScopeService.TenantScopeView> scope(@PathVariable long tenantId) {
        return ApiResponse.ok(scopeService.scopeOf(tenantId));
    }

    @Operation(summary = "变更影响分析（PLT-SCOPE-005）：与保存同一套校验，列出将被移除的项、受影响角色（将消失的" + "权限码）与受影响用户数；只读不写")
    @PostMapping("/preview")
    @RequiresPermission("platform:tenant:scope:edit")
    public ApiResponse<TenantScopeService.ScopePreviewView> preview(
            @PathVariable long tenantId,
            @RequestBody TenantScopeService.ScopeUpdateRequest request) {
        return ApiResponse.ok(scopeService.preview(tenantId, request));
    }

    @Operation(
            summary =
                    "保存可分配范围（PLT-SCOPE-002、004、006、008）：提交期望全集，原因必填；放开敏感项逐一填"
                            + "原因；缩小范围自动从租户库角色移除超出项并刷新用户权限；写变更记录与 SCOPE_UPDATE 审计")
    @PutMapping
    @RequiresPermission("platform:tenant:scope:edit")
    public ApiResponse<TenantScopeService.ScopeChangeResult> apply(
            @PathVariable long tenantId,
            @RequestBody TenantScopeService.ScopeUpdateRequest request) {
        return ApiResponse.ok(scopeService.apply(tenantId, request));
    }

    @Operation(summary = "变更记录（PLT-SCOPE-008）：操作人、前后差异与原因，倒序")
    @GetMapping("/logs")
    @RequiresPermission("platform:tenant:scope:view")
    public ApiResponse<List<TenantScopeService.ScopeLogView>> logs(@PathVariable long tenantId) {
        return ApiResponse.ok(scopeService.logs(tenantId));
    }
}
