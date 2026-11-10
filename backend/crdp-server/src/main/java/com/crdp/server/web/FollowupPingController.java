package com.crdp.server.web;

import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E01-T03 权限链占位接口（/api/hospital/followup/ping）：所有剖面注册，供模块开关（40301）与权限链 （40300）在线验证（{@code
 * PlatformTenantModuleMySqlIT} 等套件依赖）。
 *
 * <p>A3（M3 审查）：原来与 {@link HospitalHelloController} 同类；hello 拆出去按剖面注册后，本类不再携带 任何环境信息，保持全剖面可用。
 */
@Tag(name = "租户路由")
@RestController
public class FollowupPingController {

    @Operation(summary = "权限链占位：模块开通并且持有 followup:task:view 才能访问。")
    @GetMapping("/api/hospital/followup/ping")
    @RequireModule("followup")
    @RequiresPermission("followup:task:view")
    public ApiResponse<PingResponse> ping() {
        return ApiResponse.ok(new PingResponse("followup", "placeholder"));
    }

    public record PingResponse(String module, String status) {}
}
