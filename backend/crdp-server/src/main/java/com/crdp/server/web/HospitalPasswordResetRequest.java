package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** E04-T07 / PLT-AUTH-011：忘记密码第二步（验证码 + 新密码重置）。 新密码长度给平台上限保护（策略校验在用例层，文案列明全部不满足项）。 */
public record HospitalPasswordResetRequest(
        String tenantCode,
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{1,63}$", message = "账号格式不正确")
                String username,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字") String code,
        @NotBlank @Size(min = 8, max = 64) String newPassword) {}
