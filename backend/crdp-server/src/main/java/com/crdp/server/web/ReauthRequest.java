package com.crdp.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** E04-T08 / PLT-AUTH-012：敏感操作二次认证请求。 用当前账号密码再次验证身份，通过后签发 5 分钟有效的 reauthToken。 */
public record ReauthRequest(@NotBlank @Size(min = 8, max = 64) String password) {}
