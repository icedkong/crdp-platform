package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiResponse;
import com.crdp.framework.web.ClientIpResolver;
import com.crdp.server.platform.TenantViewProxyService;
import com.crdp.server.platform.TenantViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端租户视角 API（E20-T04 / PLT-VIEW-002、003，docs/api/platform.md §6）。
 *
 * <p>进入（PLT-VIEW-002）：平台端「进入租户视角」弹窗签发 60 秒一次性凭据（ticket）后打开 {@code /view/entry#ticket=…}，本页凭 ticket
 * 调公开口换取视角令牌（只出现一次）；换到的令牌装壳医院端—— 菜单与该院一致（/menus 按开通模块与可见范围实时计算）， 顶部横幅常驻（PLT-VIEW-003）。
 *
 * <p>横幅与一键退出（PLT-VIEW-003）：GET /view/session 提供会话号 / 租户 / 剩余时长 / 平台账号（水印同口径）， DELETE /view/session
 * 一键退出（只退自己的会话，写平台访问审计 VIEW_EXIT）；到时 / 空闲 15 分钟 / 权限变更由 服务端收口（令牌下一次请求即 40100）。
 *
 * <p>接口登记（PLT-PERM-004）：三个接口都标注 {@code @PublicApi}（登记为「登录即可」，不绑权限码）—— enter 的匿名性由 SecurityConfig
 * permitAll 放行并按 IP 限流；session 两个接口由 SecurityConfig 要求视角令牌（SESSION_VIEW）。
 */
@Tag(name = "医院端租户视角")
@RestController
public class HospitalViewController {

    /** 进入凭据换取请求体（凭据是 256 位随机值，一次性）。 */
    public record EnterRequest(@NotBlank String ticket) {}

    private final TenantViewService viewService;

    private final TenantViewProxyService proxyService;
    private final ClientIpResolver clientIps;

    public HospitalViewController(
            TenantViewService viewService,
            TenantViewProxyService proxyService,
            ClientIpResolver clientIps) {
        this.viewService = viewService;
        this.proxyService = proxyService;
        this.clientIps = clientIps;
    }

    @Operation(
            summary =
                    "凭一次性 ticket 换取视角令牌（PLT-VIEW-002，公开口按 IP 限流）。令牌只在本次响应出现"
                            + "一次；会话单次最长 2 小时、空闲 15 分钟（PLT-VIEW-004）")
    @PublicApi
    @PostMapping("/api/hospital/view/enter")
    public ApiResponse<TenantViewService.ExchangeResult> enter(
            @Valid @RequestBody EnterRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                viewService.exchangeTicket(request.ticket(), clientIps.resolve(httpRequest)));
    }

    @Operation(summary = "视角会话信息（PLT-VIEW-003 横幅）：会话号、租户、剩余时长与平台账号（页面水印同口径）；" + "只接受视角令牌")
    @PublicApi
    @GetMapping("/api/hospital/view/session")
    public ApiResponse<TenantViewService.ViewShellSession> session() {
        return ApiResponse.ok(viewService.hospitalCurrent(CurrentSession.requireTenantView()));
    }

    @Operation(
            summary = "一键退出租户视角（PLT-VIEW-003）：视角令牌只允许退自己的会话；台账 ENDED / 主动退出，" + "写平台访问审计 VIEW_EXIT")
    @PublicApi
    @DeleteMapping("/api/hospital/view/session")
    public ApiResponse<TenantViewService.ExitResult> exit() {
        return ApiResponse.ok(viewService.hospitalExit(CurrentSession.requireTenantView()));
    }

    /** 代重置密码请求（PLT-VIEW-013 / 017 同口径：二次确认后填写的操作原因，必填）。 */
    public record ProxyResetRequest(String reason) {}

    @Operation(
            summary =
                    "代重置医院用户密码（E20-T06 / PLT-VIEW-017，只接受视角令牌）：角色持有「可代重置医院用户密码」"
                            + "才可执行；生成的一次性临时密码<b>只经短信发给用户本人</b>，任何响应都不含新密码；"
                            + "发送成功才落库（强制改密、会话失效、解锁）。写平台访问审计 VIEW_PROXY_RESET（含原因），"
                            + "医院侧重置口对视角令牌仍拒绝（专用代理口）")
    @PublicApi
    @PostMapping("/api/hospital/view/users/{id}/reset-password")
    public ApiResponse<TenantViewProxyService.ProxyResetResult> resetUserPassword(
            @PathVariable("id") long id, @RequestBody ProxyResetRequest request) {
        return ApiResponse.ok(
                proxyService.resetUserPassword(id, request == null ? null : request.reason()));
    }
}
