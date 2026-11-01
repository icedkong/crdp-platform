package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;

/** 本人登录记录行（E06-T11 / PLT-ORG-011，aud_login_log）。 */
public class LoginLogRow {

    private boolean success;
    private String message;
    private LocalDateTime createdAt;

    public boolean getSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
