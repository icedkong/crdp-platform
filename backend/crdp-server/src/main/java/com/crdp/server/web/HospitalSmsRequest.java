package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** E04-T04 / PLT-AUTH-002：发送医院端登录短信验证码。无论手机号是否已绑定都返回成功（防探测）。 */
public record HospitalSmsRequest(
        String tenantCode,
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone) {}
