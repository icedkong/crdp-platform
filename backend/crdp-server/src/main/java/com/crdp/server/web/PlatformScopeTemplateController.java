package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.ScopeTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端授权范围模板 API（E05-T07 / PLT-SCOPE-003、004，页面「授权范围模板」）：按模块预置默认
 * 可分配项与敏感权限清单。模板只改默认值；租户角色的收回走租户级范围变更。
 */
@Tag(name = "平台端授权范围模板")
@RestController
@RequestMapping("/api/platform/scope-template")
public class PlatformScopeTemplateController {

    private final ScopeTemplateService templateService;

    public PlatformScopeTemplateController(ScopeTemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(
            summary =
                    "模板视图（PLT-SCOPE-003、004）：按模块的医院端菜单 / 按钮清单（inTemplate 勾选态，敏感项" + "不可预置）+ 敏感权限清单")
    @GetMapping
    @RequiresPermission("platform:scope:template:edit")
    public ApiResponse<ScopeTemplateService.ScopeTemplateView> view() {
        return ApiResponse.ok(templateService.view());
    }

    @Operation(
            summary =
                    "保存模块模板（PLT-SCOPE-003）：替换式提交默认可分配全集；base 固定能力不进模板；敏感项"
                            + " 40001；写 SCOPE_TEMPLATE_UPDATE 审计")
    @PutMapping("/{moduleCode}")
    @RequiresPermission("platform:scope:template:edit")
    public ApiResponse<ScopeTemplateService.ScopeTemplateView> applyTemplate(
            @PathVariable String moduleCode,
            @RequestBody ScopeTemplateService.TemplateUpdateRequest request) {
        return ApiResponse.ok(templateService.applyTemplate(moduleCode, request));
    }

    @Operation(
            summary =
                    "保存敏感权限清单（PLT-SCOPE-004）：整表替换；权限码须符合 {模块}:{资源}:{动作}，未绑定"
                            + "菜单的码先登记、命中后生效；写 SENSITIVE_PERM_UPDATE 审计")
    @PutMapping("/sensitive-perms")
    @RequiresPermission("platform:scope:template:edit")
    public ApiResponse<ScopeTemplateService.ScopeTemplateView> applySensitivePerms(
            @RequestBody ScopeTemplateService.SensitivePermUpdateRequest request) {
        return ApiResponse.ok(templateService.applySensitivePerms(request));
    }
}
