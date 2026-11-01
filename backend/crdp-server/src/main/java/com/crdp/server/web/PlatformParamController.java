package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformParamAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T10 / PLT-CFG-003：平台端「系统参数」API（docs/api/platform.md §7，pages.md §11 /platform/param）。 查看与维护同码
 * platform:param:edit。参数键创建后不可改；可覆盖参数必须配置取值范围（BOOLEAN / JSON 除外）；改为锁定后租户已覆盖的值即时失效并提示（PLT-CFG-011）。
 */
@Tag(name = "平台端系统参数")
@RestController
@RequestMapping("/api/platform/params")
@Validated
public class PlatformParamController {

    private final PlatformParamAdminService service;

    public PlatformParamController(PlatformParamAdminService service) {
        this.service = service;
    }

    public record ParamRequest(
            @Size(max = 64) String paramKey,
            @Size(max = 128) String name,
            @Size(max = 255) String description,
            @Size(max = 16) String valueType,
            @Size(max = 512) String platformValue,
            Boolean tenantOverridable,
            @Size(max = 512) String valueRange) {}

    @Operation(summary = "系统参数列表（参数键 / 值类型 / 平台值 / 层级 / 取值范围）")
    @GetMapping
    @RequiresPermission("platform:param:edit")
    public ApiResponse<List<PlatformParamAdminService.ParamView>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary =
                    "新建系统参数：参数键全局唯一（40902）、创建后不可改；可覆盖时取值范围必填"
                            + "（BOOLEAN / JSON 除外）；平台默认值须通过类型与范围校验")
    @PostMapping
    @RequiresPermission("platform:param:edit")
    public ApiResponse<PlatformParamAdminService.ParamView> create(
            @RequestBody ParamRequest request) {
        return ApiResponse.ok(
                service.create(
                        new PlatformParamAdminService.ParamSave(
                                request.paramKey(),
                                request.name(),
                                request.description(),
                                request.valueType(),
                                request.platformValue(),
                                request.tenantOverridable(),
                                request.valueRange())));
    }

    @Operation(
            summary =
                    "维护系统参数：名称 / 说明 / 值类型 / 平台默认值 / 层级 / 取值范围（参数键不可改）。"
                            + "改为平台锁定后租户已覆盖的值即时失效并提示，覆盖行保留、重新放开自动恢复")
    @PutMapping("/{paramKey}")
    @RequiresPermission("platform:param:edit")
    public ApiResponse<PlatformParamAdminService.ParamView> update(
            @PathVariable("paramKey") String paramKey, @RequestBody ParamRequest request) {
        return ApiResponse.ok(
                service.update(
                        paramKey,
                        new PlatformParamAdminService.ParamUpdate(
                                request.name(),
                                request.description(),
                                request.valueType(),
                                request.platformValue(),
                                request.tenantOverridable(),
                                request.valueRange())));
    }
}
