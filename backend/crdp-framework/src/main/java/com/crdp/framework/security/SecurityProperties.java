package com.crdp.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crdp.security")
public class SecurityProperties {

    /** 会话有效期，小时。M0 不做滑动续期。 */
    private int sessionTtlHours = 8;

    private int loginFailureLimit = 8;

    private int loginFailureWindowSeconds = 600;

    /** 短信验证码有效期，秒。规格（PLT-AUTH-002，等保参数表）：5 分钟。 */
    private int smsCodeTtlSeconds = 300;

    /** 短信重发间隔，秒。规格：60 秒。 */
    private int smsResendCooldownSeconds = 60;

    /** 同一手机号每日发送上限。规格：10 条。 */
    private int smsDailyLimitPerPhone = 10;

    /** 验证码输错几次后作废。规格：5 次。 */
    private int smsMaxVerifyFailures = 5;

    /** 双因素第二步 challenge 的有效期，秒。 */
    private int twoFactorChallengeTtlSeconds = 300;

    /**
     * 平台账号是否强制双因素（PLT-AUTH-003、PLT-AUTH-005）。短信通道在 E09-T03 接入前， local/dev 配置里关闭以保留演示可用；生产保持 true。
     */
    private boolean platformTwoFactorEnabled = true;

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

    public int getSmsCodeTtlSeconds() {
        return smsCodeTtlSeconds;
    }

    public void setSmsCodeTtlSeconds(int smsCodeTtlSeconds) {
        this.smsCodeTtlSeconds = smsCodeTtlSeconds;
    }

    public int getSmsResendCooldownSeconds() {
        return smsResendCooldownSeconds;
    }

    public void setSmsResendCooldownSeconds(int smsResendCooldownSeconds) {
        this.smsResendCooldownSeconds = smsResendCooldownSeconds;
    }

    public int getSmsDailyLimitPerPhone() {
        return smsDailyLimitPerPhone;
    }

    public void setSmsDailyLimitPerPhone(int smsDailyLimitPerPhone) {
        this.smsDailyLimitPerPhone = smsDailyLimitPerPhone;
    }

    public int getSmsMaxVerifyFailures() {
        return smsMaxVerifyFailures;
    }

    public void setSmsMaxVerifyFailures(int smsMaxVerifyFailures) {
        this.smsMaxVerifyFailures = smsMaxVerifyFailures;
    }

    public int getTwoFactorChallengeTtlSeconds() {
        return twoFactorChallengeTtlSeconds;
    }

    public void setTwoFactorChallengeTtlSeconds(int twoFactorChallengeTtlSeconds) {
        this.twoFactorChallengeTtlSeconds = twoFactorChallengeTtlSeconds;
    }

    public boolean isPlatformTwoFactorEnabled() {
        return platformTwoFactorEnabled;
    }

    public void setPlatformTwoFactorEnabled(boolean platformTwoFactorEnabled) {
        this.platformTwoFactorEnabled = platformTwoFactorEnabled;
    }
}
