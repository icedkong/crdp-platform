package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.system.param.TenantParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T10 / PLT-CFG-004、011：医院端「机构设置 · 租户参数」API（docs/api/system.md §4，pages.md §10）。 查看与维护同码
 * tenant:param:edit。租户在平台允许的范围内覆盖参数默认值；锁定参数不可覆盖； 覆盖与恢复都写 cfg_param_log 变更记录与租户审计。
 */
@Tag(name = "医院端租户参数")
@RestController
@RequestMapping("/api/hospital/system/params")
@Validated
public class HospitalParamController {

    private final TenantParamService service;

    public HospitalParamController(TenantParamService service) {
        this.service = service;
    }

    public record OverrideRequest(@Size(max = 512) String value, @Size(max = 200) String reason) {}

    @Operation(
            summary =
                    "参数列表（平台定义 + 本院覆盖状态）：effectiveValue 为生效值；"
                            + "参数被平台锁定且本院有覆盖时 stale=true（覆盖失效并提示）")
    @GetMapping
    @RequiresPermission("tenant:param:edit")
    public ApiResponse<List<TenantParamService.ParamView>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(
            summary =
                    "覆盖平台默认值：取值须在平台取值范围内（超范围 / 类型不符 40001）；"
                            + "平台锁定参数不可覆盖（40001）；未知参数 40400。写变更记录与审计")
    @PutMapping("/{paramKey}")
    @RequiresPermission("tenant:param:edit")
    public ApiResponse<TenantParamService.ParamView> override(
            @PathVariable("paramKey") String paramKey, @RequestBody OverrideRequest request) {
        return ApiResponse.ok(
                service.override(
                        paramKey,
                        new TenantParamService.OverrideRequest(request.value(), request.reason())));
    }

    @Operation(summary = "恢复平台默认值：删除本院覆盖（幂等）；写变更记录与审计")
    @DeleteMapping("/{paramKey}")
    @RequiresPermission("tenant:param:edit")
    public ApiResponse<TenantParamService.ParamView> reset(
            @PathVariable("paramKey") String paramKey) {
        return ApiResponse.ok(service.reset(paramKey));
    }

    @Operation(summary = "覆盖变更记录（cfg_param_log 倒序，最近 100 条）")
    @GetMapping("/logs")
    @RequiresPermission("tenant:param:edit")
    public ApiResponse<List<TenantParamService.ParamLogView>> logs() {
        return ApiResponse.ok(service.logs());
    }
}
