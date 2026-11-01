package com.crdp.server.web;

import com.crdp.framework.security.CaptchaStore;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.AuthService;
import com.crdp.server.auth.MenuQuery;
import com.crdp.server.auth.PasswordChangeService;
import com.crdp.server.auth.ReauthService;
import com.crdp.server.auth.SessionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@PublicApi
@Tag(name = "平台端认证")
@RestController
public class PlatformAuthController {

    private final AuthService authService;
    private final PasswordChangeService passwordChangeService;
    private final ReauthService reauthService;
    private final MenuQuery menuQuery;
    private final CaptchaStore captchaStore;
    private final SecurityProperties securityProperties;
    private final SessionAdminService sessionAdminService;

    public PlatformAuthController(
            AuthService authService,
            PasswordChangeService passwordChangeService,
            ReauthService reauthService,
            MenuQuery menuQuery,
            CaptchaStore captchaStore,
            SecurityProperties securityProperties,
            SessionAdminService sessionAdminService) {
        this.authService = authService;
        this.passwordChangeService = passwordChangeService;
        this.reauthService = reauthService;
        this.menuQuery = menuQuery;
        this.captchaStore = captchaStore;
        this.securityProperties = securityProperties;
        this.sessionAdminService = sessionAdminService;
    }

    @Operation(summary = "平台端登录。平台账号与医院账号不能混用；强制双因素时返回 challengeId 而不是令牌。")
    @PostMapping("/api/platform/auth/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody PlatformLoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(
                authService.loginPlatform(
                        request.username(),
                        request.password(),
                        new AuthService.CaptchaInput(request.captchaId(), request.captchaCode()),
                        HospitalAuthController.clientIp(httpRequest)));
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

    @Operation(
            summary =
                    "敏感操作二次认证（PLT-AUTH-012，E04-T08，与医院端同构）：密码再次验证通过后签发 5 分钟有效、"
                            + "绑定当前会话的 reauthToken；平台敏感接口（审计导出提交与下载）经 X-Reauth-Token"
                            + " 请求头携带，缺失或过期 40304。密码错误 42200 且计入登录失败计数。")
    @PostMapping("/api/platform/auth/reauth")
    public ApiResponse<ReauthService.ReauthResult> reauth(
            @Valid @RequestBody ReauthRequest request) {
        return ApiResponse.ok(
                reauthService.reauthPlatform(CurrentSession.requirePlatform(), request.password()));
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

    @Operation(summary = "本人在线会话（PLT-AUTH-008 / PLT-ORG-011，平台端同构）。")
    @GetMapping("/api/platform/auth/sessions")
    public ApiResponse<List<SessionAdminService.OnlineSessionView>> mySessions() {
        return ApiResponse.ok(sessionAdminService.listMySessions(CurrentSession.requirePlatform()));
    }

    @Operation(summary = "下线自己的某个会话（按会话号；被下线令牌下次请求即 40100）。写审计。")
    @DeleteMapping("/api/platform/auth/sessions/{sessionId}")
    public ApiResponse<SessionAdminService.OnlineSessionView> revokeMySession(
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.ok(
                sessionAdminService.revokeMySession(CurrentSession.requirePlatform(), sessionId));
    }
}
