package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.AuthService;
import com.crdp.server.auth.MenuQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "医院端认证")
@RestController
public class HospitalAuthController {

    private final AuthService authService;
    private final MenuQuery menuQuery;

    public HospitalAuthController(AuthService authService, MenuQuery menuQuery) {
        this.authService = authService;
        this.menuQuery = menuQuery;
    }

    @Operation(summary = "医院端登录。先识别租户编码，再在该院库中校验账号。")
    @PostMapping("/api/hospital/auth/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody HospitalLoginRequest request) {
        return ApiResponse.ok(
                authService.loginHospital(
                        request.tenantCode(), request.username(), request.password()));
    }

    @PostMapping("/api/hospital/auth/logout")
    public ApiResponse<Void> logout() {
        authService.logout(CurrentSession.requireHospital().token());
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/hospital/auth/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(toMe(CurrentSession.requireHospital()));
    }

    @GetMapping("/api/hospital/auth/codes")
    public ApiResponse<java.util.List<String>> codes() {
        return ApiResponse.ok(CurrentSession.requireHospital().permissionCodes());
    }

    @Operation(summary = "按角色下发医院端菜单，字段对齐 Vben 后端权限模式。")
    @GetMapping("/api/hospital/menus")
    public ApiResponse<java.util.List<com.crdp.platform.menu.MenuNode>> menus() {
        SessionPrincipal principal = CurrentSession.requireHospital();
        return ApiResponse.ok(menuQuery.tree("HOSPITAL", principal.menuIds()));
    }

    static MeResponse toMe(SessionPrincipal principal) {
        return new MeResponse(
                principal.userId(),
                principal.username(),
                principal.displayName(),
                principal.sessionType(),
                principal.tenantCode(),
                principal.tenantName(),
                principal.roles(),
                principal.permissionVersion());
    }
}
