package com.crdp.server.web;

import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.TenantViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端租户视角 API（E20-T01 / PLT-VIEW-001、004，docs/api/platform.md §6）。
 *
 * <p>进入：校验平台角色视角授权（PLT-VIEW-005）、租户范围（全部 / 负责的租户 PLT-VIEW-006 / 指定租户）与
 * 租户状态（已注销不可进入），选目的并填说明后签发视角会话（TENANT_VIEW，2 小时 / 空闲 15 分钟）。 视角令牌在医院端界面与执行点的落位随 E20-T04 /
 * T03；进入、退出与被拒的进入尝试写平台访问审计（VIEW_ 前缀，PLT-AUD-004）。
 *
 * <p>接口登记（PLT-PERM-004）：进入绑定 {@code platform:view:enter}；本人会话查询与一键退出是「登录即可访问」的
 * 公共接口（{@code @PublicApi} 登记为 public_access=1，只操作自己的会话），SecurityConfig 仍要求平台会话。
 */
@Tag(name = "平台端租户视角")
@RestController
@RequestMapping("/api/platform/tenant-view/sessions")
public class PlatformTenantViewController {

    private final TenantViewService viewService;

    public PlatformTenantViewController(TenantViewService viewService) {
        this.viewService = viewService;
    }

    @Operation(
            summary =
                    "进入租户视角（PLT-VIEW-001）：{tenantId, purpose: TROUBLESHOOT|CONFIG_CHECK"
                            + "|TRAINING|OTHER, note}；视角令牌只在本响应出现一次，单次最长 2 小时、空闲 15 分钟（PLT-VIEW-004）")
    @PostMapping
    @RequiresPermission("platform:view:enter")
    public ApiResponse<TenantViewService.EnterResult> enter(
            @RequestBody TenantViewService.EnterRequest request) {
        return ApiResponse.ok(viewService.enter(request));
    }

    @Operation(summary = "本人进行中的视角会话（倒计时与一键退出用）；空闲超时 / 到时的会话在本次调用按实际结束方式收尾并返回空")
    @PublicApi
    @GetMapping("/current")
    public ApiResponse<TenantViewService.ActiveSession> current() {
        return ApiResponse.ok(viewService.current());
    }

    @Operation(summary = "退出租户视角（一键退出，PLT-VIEW-003 会话侧）：只允许退自己的进行中会话；他人 / 已结束 40400")
    @PublicApi
    @DeleteMapping("/{sessionId}")
    public ApiResponse<TenantViewService.ExitResult> exit(@PathVariable long sessionId) {
        return ApiResponse.ok(viewService.exit(sessionId));
    }
}
