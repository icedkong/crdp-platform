package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiResponse;
import com.crdp.system.profile.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T11 / PLT-ORG-011：个人中心（/profile，登录即可访问——平台规格接口资源表标「公共接口」）。 修改密码（/auth/password）与在线会话
 * （/auth/sessions）已由 E04 落地，此处聚合基本信息、换绑手机号与登录记录。
 */
@PublicApi
@Tag(name = "医院端个人中心")
@RestController
@Validated
public class HospitalProfileController {

    private final ProfileService service;

    public HospitalProfileController(ProfileService service) {
        this.service = service;
    }

    public record DisplayNameRequest(@NotBlank String displayName) {}

    public record PhoneSmsRequest(@NotBlank String phone) {}

    public record PhoneBindRequest(@NotBlank String phone, @NotBlank String code) {}

    @Operation(summary = "本人基本信息：账号、姓名、脱敏手机号、角色、医院名称、创建时间")
    @GetMapping("/api/hospital/profile")
    public ApiResponse<ProfileService.ProfileView> view() {
        return ApiResponse.ok(service.view(CurrentSession.requireHospital()));
    }

    @Operation(summary = "修改本人姓名（账号不可改）。写审计 USER_PROFILE_UPDATE。")
    @PutMapping("/api/hospital/profile")
    public ApiResponse<ProfileService.ProfileView> updateDisplayName(
            @RequestBody DisplayNameRequest request) {
        return ApiResponse.ok(
                service.updateDisplayName(CurrentSession.requireHospital(), request.displayName()));
    }

    @Operation(
            summary =
                    "换绑手机号第一步：验证码发到新手机号（与登录 / 重置验证码分命名空间；" + "60 秒冷却与每日上限超限 42900；新号与当前号相同 40001）。")
    @PostMapping("/api/hospital/profile/phone/sms")
    public ApiResponse<Void> requestPhoneCode(@RequestBody PhoneSmsRequest request) {
        service.requestPhoneCode(CurrentSession.requireHospital(), request.phone());
        return ApiResponse.ok(null);
    }

    @Operation(
            summary =
                    "换绑手机号第二步：验证码通过后落新密文与盲索引。验证码错误 40102；"
                            + "新号已被本院其他账号绑定 40902；写审计 USER_PHONE_REBIND（脱敏）。")
    @PutMapping("/api/hospital/profile/phone")
    public ApiResponse<ProfileService.ProfileView> rebindPhone(
            @RequestBody PhoneBindRequest request) {
        return ApiResponse.ok(
                service.rebindPhone(
                        CurrentSession.requireHospital(), request.phone(), request.code()));
    }

    @Operation(summary = "本人最近登录记录（最近 20 条：结果与时间，不包含 IP）。")
    @GetMapping("/api/hospital/profile/login-logs")
    public ApiResponse<List<ProfileService.LoginLogView>> loginLogs() {
        return ApiResponse.ok(service.loginLogs(CurrentSession.requireHospital()));
    }
}
