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

@Tag(name = "平台端认证")
@RestController
public class PlatformAuthController {

    private final AuthService authService;
    private final MenuQuery menuQuery;

    public PlatformAuthController(AuthService authService, MenuQuery menuQuery) {
        this.authService = authService;
        this.menuQuery = menuQuery;
    }

    @Operation(summary = "平台端登录。平台账号与医院账号不能混用。")
    @PostMapping("/api/platform/auth/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody PlatformLoginRequest request) {
        return ApiResponse.ok(authService.loginPlatform(request.username(), request.password()));
    }

    @PostMapping("/api/platform/auth/logout")
    public ApiResponse<Void> logout() {
        authService.logout(CurrentSession.requirePlatform().token());
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/platform/auth/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(HospitalAuthController.toMe(CurrentSession.requirePlatform()));
    }

    @GetMapping("/api/platform/auth/codes")
    public ApiResponse<java.util.List<String>> codes() {
        return ApiResponse.ok(CurrentSession.requirePlatform().permissionCodes());
    }

    @GetMapping("/api/platform/menus")
    public ApiResponse<java.util.List<com.crdp.platform.menu.MenuNode>> menus() {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        return ApiResponse.ok(menuQuery.tree("PLATFORM", principal.menuIds()));
    }
}
