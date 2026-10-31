package com.crdp.server.web;

import com.crdp.framework.security.CaptchaStore;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.AuthService;
import com.crdp.server.auth.MenuQuery;
import com.crdp.server.auth.PasswordChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "平台端认证")
@RestController
public class PlatformAuthController {

    private final AuthService authService;
    private final PasswordChangeService passwordChangeService;
    private final MenuQuery menuQuery;
    private final CaptchaStore captchaStore;
    private final SecurityProperties securityProperties;

    public PlatformAuthController(
            AuthService authService,
            PasswordChangeService passwordChangeService,
            MenuQuery menuQuery,
            CaptchaStore captchaStore,
            SecurityProperties securityProperties) {
        this.authService = authService;
        this.passwordChangeService = passwordChangeService;
        this.menuQuery = menuQuery;
        this.captchaStore = captchaStore;
        this.securityProperties = securityProperties;
    }

    @Operation(summary = "平台端登录。平台账号与医院账号不能混用；强制双因素时返回 challengeId 而不是令牌。")
    @PostMapping("/api/platform/auth/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody PlatformLoginRequest request) {
        return ApiResponse.ok(
                authService.loginPlatform(
                        request.username(),
                        request.password(),
                        new AuthService.CaptchaInput(request.captchaId(), request.captchaCode())));
    }

    @Operation(summary = "取图形验证码（公开，按 IP 每小时限流），与医院端同构。", method = "GET")
    @GetMapping("/api/platform/auth/captcha")
    public ApiResponse<CaptchaStore.CaptchaImage> captcha(HttpServletRequest request) {
        return ApiResponse.ok(captchaStore.issue(HospitalAuthController.clientIp(request)));
    }

    @Operation(summary = "平台端双因素第二步。验证短信验证码后换会话令牌（E04-T04 / PLT-AUTH-003、005）。")
    @PostMapping("/api/platform/auth/2fa/verify")
    public ApiResponse<AuthService.LoginResult> verifyTwoFactor(
            @Valid @RequestBody TwoFactorVerifyRequest request) {
        return ApiResponse.ok(authService.verifyTwoFactor(request.challengeId(), request.code()));
    }

    @Operation(
            summary = "平台端修改密码（PLT-AUTH-006、010）。初始 / 重置 / 到期密码强制改密走同一接口；" + "成功后本会话继续可用，其他会话全部失效。")
    @PostMapping("/api/platform/auth/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        passwordChangeService.changePlatform(
                principal, request.oldPassword(), request.newPassword());
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/platform/auth/logout")
    public ApiResponse<Void> logout() {
        authService.logout(CurrentSession.requirePlatform().token());
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/platform/auth/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(
                HospitalAuthController.toMe(CurrentSession.requirePlatform(), securityProperties));
    }

    @GetMapping("/api/platform/auth/codes")
    public ApiResponse<List<String>> codes() {
        return ApiResponse.ok(CurrentSession.requirePlatform().permissionCodes());
    }

    @GetMapping("/api/platform/menus")
    public ApiResponse<List<com.crdp.platform.menu.MenuNode>> menus() {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        return ApiResponse.ok(menuQuery.tree("PLATFORM", principal.menuIds()));
    }
}
