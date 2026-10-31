package com.fu.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fu.security")
public class SecurityProperties {

    /** 会话有效期，小时。M0 不做滑动续期。 */
    private int sessionTtlHours = 8;

    private int loginFailureLimit = 8;

    private int loginFailureWindowSeconds = 600;

    public int getSessionTtlHours() {
        return sessionTtlHours;
    }

    public void setSessionTtlHours(int sessionTtlHours) {
        this.sessionTtlHours = sessionTtlHours;
    }

    public int getLoginFailureLimit() {
        return loginFailureLimit;
    }

    public void setLoginFailureLimit(int loginFailureLimit) {
        this.loginFailureLimit = loginFailureLimit;
    }

    public int getLoginFailureWindowSeconds() {
        return loginFailureWindowSeconds;
    }

    public void setLoginFailureWindowSeconds(int loginFailureWindowSeconds) {
        this.loginFailureWindowSeconds = loginFailureWindowSeconds;
    }
}
