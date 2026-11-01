package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.ApiResourceScanner;
import com.crdp.server.platform.ApiResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 平台端接口资源 API（E05-T06 / PLT-PERM-004）：启动扫描自动登记的接口清单，按模块与权限码筛选，未绑定权限码
 * 的接口高亮；平台端可标记公共接口、审计级别、敏感级别、动作类型，手动触发与启动同一套逻辑的重扫。
 */
@Tag(name = "平台端接口资源")
@RestController
@RequestMapping("/api/platform/apis")
public class PlatformApiResourceController {

    private final ApiResourceService service;
    private final ApiResourceScanner scanner;

    public PlatformApiResourceController(ApiResourceService service, ApiResourceScanner scanner) {
        this.service = service;
        this.scanner = scanner;
    }

    @Operation(
            summary =
                    "接口资源清单（PLT-PERM-004）：方法 + 路径模式、权限码、模块、公共 / 审计 / 敏感 / 动作；"
                            + "unbound=true 只看未绑定权限码的接口")
    @GetMapping
    @RequiresPermission("platform:api:view")
    public ApiResponse<List<ApiResourceService.ApiView>> list(
            @RequestParam(required = false) String moduleCode,
            @RequestParam(required = false) String permCode,
            @RequestParam(defaultValue = "false") boolean unbound) {
        return ApiResponse.ok(service.list(moduleCode, permCode, unbound));
    }

    @Operation(
            summary =
                    "标记接口属性（PLT-PERM-004）：公共接口、审计级别、敏感级别、动作类型；可给未加注解的接口补绑"
                            + " 权限码；写 API_UPDATE 审计并即时刷新默认拒绝闸门")
    @PutMapping("/{id}")
    @RequiresPermission("platform:api:edit")
    public ApiResponse<ApiResourceService.ApiView> mark(
            @PathVariable long id, @Valid @RequestBody ApiResourceService.MarkRequest request) {
        return ApiResponse.ok(service.mark(id, request));
    }

    @Operation(
            summary =
                    "手动触发接口登记同步（PLT-PERM-004）：与启动时同一套扫描逻辑；返回每条 REGISTERED / UPDATED /"
                            + " UNCHANGED / REMOVED")
    @PostMapping("/sync")
    @RequiresPermission("platform:api:edit")
    public ApiResponse<ApiResourceScanner.ScanResult> sync() {
        return ApiResponse.ok(scanner.scan());
    }
}
