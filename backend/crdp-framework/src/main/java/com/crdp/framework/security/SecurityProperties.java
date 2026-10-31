package com.crdp.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crdp.security")
public class SecurityProperties {

    /** 会话有效期，小时。M0 不做滑动续期。 */
    private int sessionTtlHours = 8;

    /**
     * 连续失败多少次后锁定（E04-T05 / PLT-AUTH-007，等保参数表：5 次）。 达到即锁定 {@link #loginLockSeconds} 秒，期间登录一律
     * 42900；成功登录或管理员解锁清零。
     */
    private int loginFailureLimit = 5;

    /** 失败计数窗口，秒。锁定前按此窗口滚动计数。 */
    private int loginFailureWindowSeconds = 600;

    /** 锁定时长，秒。规格（等保参数表）：30 分钟。 */
    private int loginLockSeconds = 1800;

    /** 连续失败多少次后要求图形验证码（E04-T05 / PLT-AUTH-001、007）。规格：3 次。 */
    private int captchaThreshold = 3;

    /** 图形验证码有效期，秒。 */
    private int captchaTtlSeconds = 300;

    /** 同一 IP 每小时可取多少张图形验证码（公开接口必须限流，AGENTS.md 第 6 节）。 */
    private int captchaIpHourlyLimit = 60;

    /** 密码最小长度（平台下限，租户只能加严）。规格：8 位。 */
    private int passwordMinLength = 8;

    /** 密码至少包含的字符类别数（大写 / 小写 / 数字 / 特殊字符）。规格：3 类。 */
    private int passwordMinCategories = 3;

    /** 历史密码检查个数（不得与最近 N 次相同）。规格：5 次。 */
    private int passwordHistoryCount = 5;

    /** 密码有效期，天。规格：90 天，到期强制修改。 */
    private int passwordValidDays = 90;

    /** 密码到期前几天开始提醒。规格：7 天。 */
    private int passwordExpiryRemindDays = 7;

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

    public int getLoginLockSeconds() {
        return loginLockSeconds;
    }

    public void setLoginLockSeconds(int loginLockSeconds) {
        this.loginLockSeconds = loginLockSeconds;
    }

    public int getCaptchaThreshold() {
        return captchaThreshold;
    }

    public void setCaptchaThreshold(int captchaThreshold) {
        this.captchaThreshold = captchaThreshold;
    }

    public int getCaptchaTtlSeconds() {
        return captchaTtlSeconds;
    }

    public void setCaptchaTtlSeconds(int captchaTtlSeconds) {
        this.captchaTtlSeconds = captchaTtlSeconds;
    }

    public int getCaptchaIpHourlyLimit() {
        return captchaIpHourlyLimit;
    }

    public void setCaptchaIpHourlyLimit(int captchaIpHourlyLimit) {
        this.captchaIpHourlyLimit = captchaIpHourlyLimit;
    }

    public int getPasswordMinLength() {
        return passwordMinLength;
    }

    public void setPasswordMinLength(int passwordMinLength) {
        this.passwordMinLength = passwordMinLength;
    }

    public int getPasswordMinCategories() {
        return passwordMinCategories;
    }

    public void setPasswordMinCategories(int passwordMinCategories) {
        this.passwordMinCategories = passwordMinCategories;
    }

    public int getPasswordHistoryCount() {
        return passwordHistoryCount;
    }

    public void setPasswordHistoryCount(int passwordHistoryCount) {
        this.passwordHistoryCount = passwordHistoryCount;
    }

    public int getPasswordValidDays() {
        return passwordValidDays;
    }

    public void setPasswordValidDays(int passwordValidDays) {
        this.passwordValidDays = passwordValidDays;
    }

    public int getPasswordExpiryRemindDays() {
        return passwordExpiryRemindDays;
    }

    public void setPasswordExpiryRemindDays(int passwordExpiryRemindDays) {
        this.passwordExpiryRemindDays = passwordExpiryRemindDays;
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
