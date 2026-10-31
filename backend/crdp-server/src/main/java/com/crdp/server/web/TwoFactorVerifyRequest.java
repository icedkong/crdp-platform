package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** E04-T04 / PLT-AUTH-003：双因素第二步。challengeId 来自密码第一步的返回。 */
public record TwoFactorVerifyRequest(
        @NotBlank String challengeId,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字") String code) {}
