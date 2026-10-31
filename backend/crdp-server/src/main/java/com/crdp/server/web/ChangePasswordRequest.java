package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** E04-T05 / PLT-AUTH-006、010：自助修改密码。 原密码必填；新密码长度给平台上限保护（策略校验在用例层，文案列明全部不满足项）。 */
public record ChangePasswordRequest(
        @NotBlank @Size(min = 8, max = 64) String oldPassword,
        @NotBlank @Size(min = 8, max = 64) String newPassword) {}
