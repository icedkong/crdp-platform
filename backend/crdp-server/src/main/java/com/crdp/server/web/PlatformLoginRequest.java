package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 平台端账号密码登录。E04-T05 / PLT-AUTH-007：连续失败 3 次后同样必须携带图形验证码（与医院端同构）。 */
public record PlatformLoginRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{1,63}$", message = "格式不正确")
                String username,
        @NotBlank @Size(min = 8, max = 64) String password,
        String captchaId,
        @Size(max = 8) String captchaCode) {}
