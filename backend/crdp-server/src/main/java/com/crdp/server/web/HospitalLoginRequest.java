package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HospitalLoginRequest(
        // 子域名可识别租户时编码可不填（PLT-AUTH-004）；识别失败由登录流程统一拒绝，不做格式探测
        String tenantCode,
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{1,63}$", message = "格式不正确")
                String username,
        @NotBlank @Size(min = 8, max = 64) String password) {}
