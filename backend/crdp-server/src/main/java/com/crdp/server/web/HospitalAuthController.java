package com.crdp.server.web;

import com.crdp.framework.security.CaptchaStore;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.AuthService;
import com.crdp.server.auth.MenuQuery;
import com.crdp.server.auth.PasswordChangeService;
import com.crdp.server.auth.TenantIdentifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "医院端认证")
@RestController
public class HospitalAuthController {

    private final AuthService authService;
    private final PasswordChangeService passwordChangeService;
    private final MenuQuery menuQuery;
    private final TenantIdentifier tenantIdentifier;
    private final CaptchaStore captchaStore;
    private final SecurityProperties securityProperties;

    public HospitalAuthController(
            AuthService authService,
            PasswordChangeService passwordChangeService,
            MenuQuery menuQuery,
            TenantIdentifier tenantIdentifier,
            CaptchaStore captchaStore,
            SecurityProperties securityProperties) {
        this.authService = authService;
        this.passwordChangeService = passwordChangeService;
        this.menuQuery = menuQuery;
        this.tenantIdentifier = tenantIdentifier;
        this.captchaStore = captchaStore;
        this.securityProperties = securityProperties;
    }

    @Operation(summary = "医院端登录。先识别租户编码，再在该院库中校验账号；连续失败 3 次后需携带图形验证码。")
    @PostMapping("/api/hospital/auth/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody HospitalLoginRequest request,
            @RequestHeader(value = HttpHeaders.HOST, required = false) String host) {
        // PLT-AUTH-004：子域名识别的租户优先，统一登录页输入的编码兜底
        String tenantCode = tenantIdentifier.resolve(host, request.tenantCode());
        return ApiResponse.ok(
                authService.loginHospital(
                        tenantCode,
                        request.username(),
                        request.password(),
                        new AuthService.CaptchaInput(request.captchaId(), request.captchaCode())));
    }

    @Operation(
            summary = "取图形验证码（公开，按 IP 每小时限流）。连续失败 3 次后登录必须携带 captchaId + captchaCode。",
            method = "GET")
    @GetMapping("/api/hospital/auth/captcha")
    public ApiResponse<CaptchaStore.CaptchaImage> captcha(HttpServletRequest request) {
        return ApiResponse.ok(captchaStore.issue(clientIp(request)));
    }

    @Operation(summary = "发送医院端登录短信验证码。60 秒冷却；无论手机号是否已绑定均返回成功（防探测）。")
    @PostMapping("/api/hospital/auth/sms")
    public ApiResponse<Void> sendLoginSms(
            @Valid @RequestBody HospitalSmsRequest request,
            @RequestHeader(value = HttpHeaders.HOST, required = false) String host) {
        String tenantCode = tenantIdentifier.resolve(host, request.tenantCode());
        authService.sendHospitalLoginSms(tenantCode, request.phone());
        return ApiResponse.ok(null);
    }

    @Operation(summary = "短信验证码登录。手机号需已绑定且本院唯一；验证码 5 分钟内有效。")
    @PostMapping("/api/hospital/auth/login/sms")
    public ApiResponse<AuthService.LoginResult> loginBySms(
            @Valid @RequestBody HospitalSmsLoginRequest request,
            @RequestHeader(value = HttpHeaders.HOST, required = false) String host) {
        String tenantCode = tenantIdentifier.resolve(host, request.tenantCode());
        return ApiResponse.ok(
                authService.loginHospitalWithSms(tenantCode, request.phone(), request.code()));
    }

    @Operation(summary = "双因素第二步。密码第一步返回 challengeId 时，验证短信验证码后换会话令牌。")
    @PostMapping("/api/hospital/auth/2fa/verify")
    public ApiResponse<AuthService.LoginResult> verifyTwoFactor(
            @Valid @RequestBody TwoFactorVerifyRequest request) {
        return ApiResponse.ok(authService.verifyTwoFactor(request.challengeId(), request.code()));
    }

    @Operation(summary = "修改密码（PLT-AUTH-006、010）。首次登录或密码到期强制改密走同一接口；" + "成功后本会话继续可用，其他会话全部失效。")
    @PostMapping("/api/hospital/auth/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        passwordChangeService.changeHospital(
                principal, request.oldPassword(), request.newPassword());
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/hospital/auth/logout")
    public ApiResponse<Void> logout() {
        authService.logout(CurrentSession.requireHospital().token());
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/hospital/auth/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(toMe(CurrentSession.requireHospital(), securityProperties));
    }

    @GetMapping("/api/hospital/auth/codes")
    public ApiResponse<List<String>> codes() {
        return ApiResponse.ok(CurrentSession.requireHospital().permissionCodes());
    }

    @Operation(summary = "按角色下发医院端菜单，字段对齐 Vben 后端权限模式。")
    @GetMapping("/api/hospital/menus")
    public ApiResponse<List<com.crdp.platform.menu.MenuNode>> menus() {
        SessionPrincipal principal = CurrentSession.requireHospital();
        return ApiResponse.ok(menuQuery.tree("HOSPITAL", principal.menuIds()));
    }

    static MeResponse toMe(SessionPrincipal principal, SecurityProperties properties) {
        return new MeResponse(
                principal.userId(),
                principal.username(),
                principal.displayName(),
                principal.sessionType(),
                principal.tenantCode(),
                principal.tenantName(),
                principal.roles(),
                principal.permissionVersion(),
                principal.mustChangePassword(),
                MeResponse.expiryDays(
                        principal.passwordExpiresAtEpochSecond(),
                        Instant.now().getEpochSecond(),
                        properties.getPasswordExpiryRemindDays()));
    }

    /** 反代后取真实 IP：优先 X-Forwarded-For 首段，兜底 remoteAddr（图形验证码按 IP 限流用）。 */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
