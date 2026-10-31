package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** E04-T04 / PLT-AUTH-002：短信验证码登录（手机号已绑定且本院唯一）。 */
public record HospitalSmsLoginRequest(
        String tenantCode,
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字") String code) {}
