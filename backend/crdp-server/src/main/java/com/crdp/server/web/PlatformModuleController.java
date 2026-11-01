package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.ModuleCatalogService;
import com.crdp.server.platform.ModuleRegistrySyncService;
import com.crdp.server.platform.TenantModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端模块目录 API（E05-T04）。目录与版本记录（PLT-MOD-001、003）只读；注册同步（PLT-MOD-002）随启动自动
 * 执行，这里提供手动触发；展示信息与上下架维护是平台端对目录仅有的写入口。按租户开通 / 关闭模块（E05-T05 / PLT-MOD-004、005）走
 * /tenants/{id}/modules；开通记录（PLT-MOD-009）在此查询。
 */
@Tag(name = "平台端模块目录")
@RestController
@RequestMapping("/api/platform/modules")
public class PlatformModuleController {

    private final ModuleCatalogService catalogService;
    private final ModuleRegistrySyncService syncService;
    private final TenantModuleService tenantService;

    public PlatformModuleController(
            ModuleCatalogService catalogService,
            ModuleRegistrySyncService syncService,
            TenantModuleService tenantService) {
        this.catalogService = catalogService;
        this.syncService = syncService;
        this.tenantService = tenantService;
    }

    @Operation(summary = "模块目录（PLT-MOD-001、003）：编码、名称、说明、功能 / 迁移版本、依赖、菜单与权限码清单、" + "配置项、版本历史（倒序）")
    @GetMapping
    @RequiresPermission("platform:module:view")
    public ApiResponse<List<ModuleCatalogService.ModuleView>> list() {
        return ApiResponse.ok(catalogService.list());
    }

    @Operation(summary = "维护模块展示信息与上下架（PLT-MOD-002）：只改名称、说明、上架状态，写 MODULE_UPDATE 审计")
    @PutMapping("/{code}")
    @RequiresPermission("platform:module:edit")
    public ApiResponse<ModuleCatalogService.ModuleView> update(
            @PathVariable String code, @RequestBody ModuleCatalogService.UpdateRequest request) {
        return ApiResponse.ok(catalogService.update(code, request));
    }

    @Operation(
            summary =
                    "手动触发注册同步（PLT-MOD-002）：与启动时同一套逻辑；返回每个模块的 REGISTERED / UPDATED /"
                            + " UNCHANGED 结果")
    @PostMapping("/sync")
    @RequiresPermission("platform:module:edit")
    public ApiResponse<ModuleRegistrySyncService.SyncResult> sync() {
        return ApiResponse.ok(syncService.sync());
    }

    @Operation(
            summary =
                    "开通记录（PLT-MOD-009）：该模块在各租户的开通 / 关闭操作（升级 UPGRADE 随 PLT-MOD-007 灰度批次），"
                            + "倒序（最新在前）；tenantId 可选过滤")
    @GetMapping("/{code}/open-logs")
    @RequiresPermission("platform:module:view")
    public ApiResponse<List<TenantModuleService.OpenLogView>> openLogs(
            @PathVariable String code, @RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(tenantService.openLogs(code, tenantId));
    }
}
