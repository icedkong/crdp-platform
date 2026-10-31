package com.fu.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HospitalLoginRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9]{1,31}$", message = "只允许小写字母和数字")
                String tenantCode,
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{1,63}$", message = "格式不正确")
                String username,
        @NotBlank @Size(min = 8, max = 64) String password) {}
