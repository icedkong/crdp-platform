package com.crdp.framework.security;

public final class AuthMessages {

    /** 租户不存在、账号不存在、密码错误使用同一句，避免探测。 */
    public static final String BAD_CREDENTIALS = "租户、账号或密码不正确";

    /** 短信验证码错误、过期、被作废共用同一句，不区分存在性（E04-T04 / 40102）。 */
    public static final String BAD_SMS_CODE = "验证码错误或已过期";

    private AuthMessages() {}
}
