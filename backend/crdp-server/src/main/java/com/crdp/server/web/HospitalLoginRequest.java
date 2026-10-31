package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 医院端账号密码登录。E04-T05 / PLT-AUTH-001、007：连续失败 3 次后必须携带图形验证码（captchaId + captchaCode）， 之前的请求可以不传。 */
public record HospitalLoginRequest(
        // 子域名可识别租户时编码可不填（PLT-AUTH-004）；识别失败由登录流程统一拒绝，不做格式探测
        String tenantCode,
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{1,63}$", message = "格式不正确")
                String username,
        @NotBlank @Size(min = 8, max = 64) String password,
        // 连续失败达到阈值（默认 3 次）后必填；缺失时由 CaptchaStore 统一按验证码错误拒绝（40102）
        String captchaId,
        @Size(max = 8) String captchaCode) {}
