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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端租户视角 API（E20-T01 / PLT-VIEW-001、004；E20-T04 / PLT-VIEW-002、003，docs/api/platform.md §6）。
 *
 * <p>进入：校验平台角色视角授权（PLT-VIEW-005）、租户范围（全部 / 负责的租户 PLT-VIEW-006 / 指定租户）与
 * 租户状态（已注销不可进入），选目的并填说明后签发视角会话（TENANT_VIEW，2 小时 / 空闲 15 分钟）。 「打开医院端界面」时为本人的进行中会话签发 60
 * 秒一次性进入凭据（ticket），医院端 /api/hospital/view/enter 凭它换视角令牌（PLT-VIEW-002）。 进入、退出与被拒的进入尝试写平台访问审计（VIEW_
 * 前缀，PLT-AUD-004）。
 *
 * <p>接口登记（PLT-PERM-004）：进入绑定 {@code platform:view:enter}；本人会话查询、进入凭据签发与一键退出是
 * 「登录即可访问」的公共接口（{@code @PublicApi} 登记为 public_access=1，只操作自己的会话）， SecurityConfig 仍要求平台会话。
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

    @Operation(
            summary =
                    "签发医院端进入凭据（E20-T04 / PLT-VIEW-002）：60 秒一次性 ticket，前端拼"
                            + " hospitalUrl + /view/entry#ticket=… 打开医院端视角页；须先进入视角（40001）")
    @PublicApi
    @PostMapping("/current/entry-ticket")
    public ApiResponse<TenantViewService.EntryTicket> entryTicket() {
        return ApiResponse.ok(viewService.issueEntryTicket());
    }

    @Operation(summary = "退出租户视角（一键退出，PLT-VIEW-003 会话侧）：只允许退自己的进行中会话；他人 / 已结束 40400")
    @PublicApi
    @DeleteMapping("/{sessionId}")
    public ApiResponse<TenantViewService.ExitResult> exit(@PathVariable long sessionId) {
        return ApiResponse.ok(viewService.exit(sessionId));
    }

    // ---------------------------------------------------------------- 会话台账（E20-T05 /
    // PLT-VIEW-016，仅平台侧）

    @Operation(
            summary =
                    "视角会话列表（PLT-VIEW-016 审计中心「视角会话」）：平台账号、租户、目的、起止时间、"
                            + "结束方式与计数摘要；过滤参数均可空；明细（页面 / 查询 / 被拒）按会话号经审计中心"
                            + "「租户视角记录」检索；仅平台侧可见，医院端无入口")
    @GetMapping
    @RequiresPermission("platform:view:session:view")
    public ApiResponse<TenantViewService.SessionPage> sessions(
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(
                viewService.sessions(
                        new TenantViewService.SessionQuery(
                                accountName, tenantId, status, purpose, pageNo, pageSize)));
    }

    @Operation(summary = "视角会话详情（PLT-VIEW-016）：台账整行（含生效规则版本与计数摘要）；未知 40400")
    @GetMapping("/{sessionId}")
    @RequiresPermission("platform:view:session:view")
    public ApiResponse<TenantViewService.SessionSummary> sessionDetail(
            @PathVariable long sessionId) {
        return ApiResponse.ok(viewService.sessionDetail(sessionId));
    }
}
