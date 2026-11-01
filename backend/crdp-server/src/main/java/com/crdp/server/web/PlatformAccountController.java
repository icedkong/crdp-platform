package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端账号管理 API（E06-T04 / PLT-ROLE-001，docs/api/platform.md §5「平台端 · 平台账号管理」）。
 *
 * <p>平台账号存平台库、只能授予平台角色（医院账号在各自租户库，两个登录入口分立，互不可见）。 新建强制绑定手机号（双因素）；支持停用 / 启用、重置密码（临时密码只返回一次）、解锁。
 * 负责租户多选（PLT-VIEW-006）属 E20-T01。手机号只回脱敏值。
 */
@Tag(name = "平台端账号管理")
@RestController
@RequestMapping("/api/platform/accounts")
public class PlatformAccountController {

    private final PlatformAccountService accountService;

    public PlatformAccountController(PlatformAccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "平台账号列表（含停用；手机号脱敏；平台账号量级小，不分页）")
    @GetMapping
    @RequiresPermission("platform:account:view")
    public ApiResponse<List<PlatformAccountService.AccountView>> list() {
        return ApiResponse.ok(accountService.list());
    }

    @Operation(summary = "平台账号详情（角色绑定与状态；手机号脱敏）")
    @GetMapping("/{accountId}")
    @RequiresPermission("platform:account:view")
    public ApiResponse<PlatformAccountService.AccountView> get(@PathVariable long accountId) {
        return ApiResponse.ok(accountService.get(accountId));
    }

    @Operation(summary = "新建平台账号（PLT-ROLE-001）：强制绑定手机号（双因素）、选平台角色、" + "初始密码按平台策略校验，首次登录强制改密")
    @PostMapping
    @RequiresPermission("platform:account:edit")
    public ApiResponse<PlatformAccountService.AccountView> create(
            @RequestBody PlatformAccountService.AccountSaveRequest request) {
        return ApiResponse.ok(accountService.create(request));
    }

    @Operation(summary = "维护平台账号：展示名、手机号（换绑）、平台角色与停用 / 启用；" + "平台管理员启用账号至少保留 2 个（已确认），不足即 40901")
    @PutMapping("/{accountId}")
    @RequiresPermission("platform:account:edit")
    public ApiResponse<PlatformAccountService.AccountView> update(
            @PathVariable long accountId,
            @RequestBody PlatformAccountService.AccountSaveRequest request) {
        return ApiResponse.ok(accountService.update(accountId, request));
    }

    @Operation(
            summary =
                    "重置平台账号密码（platform:account:reset）：生成一次性临时密码（响应只返回一次），"
                            + "首次登录强制改密、该账号全部会话失效并解除登录锁定")
    @PostMapping("/{accountId}/reset-password")
    @RequiresPermission("platform:account:reset")
    public ApiResponse<PlatformAccountService.ResetPasswordResult> resetPassword(
            @PathVariable long accountId) {
        return ApiResponse.ok(accountService.resetPassword(accountId));
    }

    @Operation(summary = "解锁平台账号登录锁定（连续失败 5 次锁 30 分钟，可提前解锁）。写审计。")
    @PostMapping("/{accountId}/unlock")
    @RequiresPermission("platform:account:reset")
    public ApiResponse<Map<String, Object>> unlock(@PathVariable long accountId) {
        return ApiResponse.ok(accountService.unlock(accountId));
    }
}
