package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** E04-T07 / PLT-AUTH-011：忘记密码第一步（发送重置验证码到账号绑定手机号）。 无论账号是否存在、是否绑定手机号都返回成功（防探测）。 */
public record HospitalPasswordForgotRequest(
        String tenantCode,
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{1,63}$", message = "账号格式不正确")
                String username) {}
