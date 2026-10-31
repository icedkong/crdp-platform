package com.crdp.framework.security;

public final class AuthMessages {

    /** 租户不存在、账号不存在、密码错误使用同一句，避免探测。 */
    public static final String BAD_CREDENTIALS = "租户、账号或密码不正确";

    private AuthMessages() {}
}
