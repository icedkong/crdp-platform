package com.crdp.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crdp.security")
public class SecurityProperties {

    /**
     * 医院端空闲超时，秒（E04-T06 / PLT-AUTH-008，等保参数表：30 分钟）。 每次请求滑动续期；租户可用 cfg_param
     * security.session.idle-minutes 在此之下加严。
     */
    private int sessionIdleSeconds = 1800;

    /** 平台端空闲超时，秒（规格：15 分钟，含租户视角会话）。 */
    private int platformSessionIdleSeconds = 900;

    /** 绝对会话时长，秒（规格：12 小时）。从登录起算、不随活跃滑动，到时重新登录； 0 表示不启用（不建议）。 */
    private int sessionAbsoluteSeconds = 43200;

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

    /**
     * 同一 IP 每小时可查多少次登录页租户品牌（E06-T11 / PLT-CFG-008，GET /auth/tenant 公开口按 IP 限流； 取图与品牌查询分开计数，互不挤占）。
     */
    private int tenantBrandIpHourlyLimit = 120;

    /** 视角进入凭据（ticket）的有效期，秒（E20-T04 / PLT-VIEW-002）：平台端为进行中的视角会话 签发一次性凭据，医院端公开口在窗口内换取视角令牌，取出即作废。 */
    private int tenantViewEntryTicketTtlSeconds = 60;

    /** 同一 IP 每小时可调多少次视角凭据换取（E20-T04，POST /api/hospital/view/enter 公开口按 IP 限流，AGENTS.md 第 6 节）。 */
    private int tenantViewEnterIpHourlyLimit = 60;

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

    /** 敏感操作二次认证令牌有效期，秒（E04-T08 / PLT-AUTH-012）。规格（auth.md）：5 分钟； 窗口内可多次使用，过期后敏感接口重新要求验证（40304）。 */
    private int reauthTokenTtlSeconds = 300;

    public int getSessionIdleSeconds() {
        return sessionIdleSeconds;
    }

    public void setSessionIdleSeconds(int sessionIdleSeconds) {
        this.sessionIdleSeconds = sessionIdleSeconds;
    }

    public int getPlatformSessionIdleSeconds() {
        return platformSessionIdleSeconds;
    }

    public void setPlatformSessionIdleSeconds(int platformSessionIdleSeconds) {
        this.platformSessionIdleSeconds = platformSessionIdleSeconds;
    }

    public int getSessionAbsoluteSeconds() {
        return sessionAbsoluteSeconds;
    }

    public void setSessionAbsoluteSeconds(int sessionAbsoluteSeconds) {
        this.sessionAbsoluteSeconds = sessionAbsoluteSeconds;
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

    public int getTenantBrandIpHourlyLimit() {
        return tenantBrandIpHourlyLimit;
    }

    public void setTenantBrandIpHourlyLimit(int tenantBrandIpHourlyLimit) {
        this.tenantBrandIpHourlyLimit = tenantBrandIpHourlyLimit;
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

    public int getReauthTokenTtlSeconds() {
        return reauthTokenTtlSeconds;
    }

    public void setReauthTokenTtlSeconds(int reauthTokenTtlSeconds) {
        this.reauthTokenTtlSeconds = reauthTokenTtlSeconds;
    }

    public int getTenantViewEntryTicketTtlSeconds() {
        return tenantViewEntryTicketTtlSeconds;
    }

    public void setTenantViewEntryTicketTtlSeconds(int tenantViewEntryTicketTtlSeconds) {
        this.tenantViewEntryTicketTtlSeconds = tenantViewEntryTicketTtlSeconds;
    }

    public int getTenantViewEnterIpHourlyLimit() {
        return tenantViewEnterIpHourlyLimit;
    }

    public void setTenantViewEnterIpHourlyLimit(int tenantViewEnterIpHourlyLimit) {
        this.tenantViewEnterIpHourlyLimit = tenantViewEnterIpHourlyLimit;
    }
}
