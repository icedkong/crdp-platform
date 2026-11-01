package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.RoleTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端业务角色模板清单（E06-T05 / PLT-ROLE-005，docs/api/platform.md §5）：按模块登记的启用模板与 默认菜单授权（plt_role_template
 * / plt_role_template_menu，种子 V105）。模块开通时由 TenantModuleService 第 4 步下发到
 * 租户库；这里只读展示（模板维护走平台库数据，随模块目录页在后续任务接入）。
 */
@Tag(name = "平台端业务角色模板")
@RestController
@RequestMapping("/api/platform/role-templates")
public class PlatformRoleTemplateController {

    private final RoleTemplateService service;

    public PlatformRoleTemplateController(RoleTemplateService service) {
        this.service = service;
    }

    @Operation(summary = "模板清单（PLT-ROLE-005）：moduleCode 省略时返回全部启用模板")
    @GetMapping
    @RequiresPermission("platform:role:view")
    public ApiResponse<List<RoleTemplateService.TemplateRow>> list(
            @RequestParam(required = false) String moduleCode) {
        return ApiResponse.ok(
                moduleCode == null || moduleCode.isBlank()
                        ? service.allTemplates()
                        : service.templatesOf(moduleCode.trim()));
    }
}
