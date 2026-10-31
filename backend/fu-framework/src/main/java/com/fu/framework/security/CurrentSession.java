package com.fu.framework.security;

import com.fu.framework.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentSession {

    private CurrentSession() {}

    public static SessionPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new ApiException(401, 40100, "未登录或登录已失效");
        }
        return principal;
    }

    public static SessionPrincipal requireHospital() {
        SessionPrincipal principal = require();
        if (!principal.hospital()) {
            throw new ApiException(403, 40300, "平台账号不能访问医院端");
        }
        return principal;
    }

    public static SessionPrincipal requirePlatform() {
        SessionPrincipal principal = require();
        if (!principal.platform()) {
            throw new ApiException(403, 40300, "医院账号不能访问平台端");
        }
        return principal;
    }
}
