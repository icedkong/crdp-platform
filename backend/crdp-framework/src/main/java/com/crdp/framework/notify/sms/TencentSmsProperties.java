package com.crdp.framework.notify.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云短信通道配置（E09-T03 / PLT-CFG-005：平台统一账号、签名与模板）。
 *
 * <p>密钥不落库、不进仓库：生产经环境变量 / 凭据管理注入（技术方案第 4 章：腾讯云短信密钥存 DEW 凭据管理， 子账号只授予发短信权限）； 未配置（enabled=false
 * 或密钥为空）时通道明确报错，不假装发送成功。
 */
@ConfigurationProperties(prefix = "crdp.sms")
public class TencentSmsProperties {

    /** 通道开关：false（默认）时任何发送都抛 CHANNEL_DISABLED（本地 / 未开通环境）。 */
    private boolean enabled = false;

    /** 腾讯云访问密钥 ID（只授短信发送权限的子账号）。 */
    private String secretId = "";

    /** 腾讯云访问密钥 Key。只经配置注入，不写日志。 */
    private String secretKey = "";

    /** 短信应用 SdkAppId（控制台应用管理，形如 1400006666）。 */
    private String sdkAppId = "";

    /** 通道默认签名（UTF-8 签名内容，模板级 sign_name 为空时使用；国内短信必填）。 */
    private String signName = "";

    /** 接入地域（默认 ap-guangzhou）。 */
    private String region = "ap-guangzhou";

    /** API 接入点域名（默认 sms.tencentcloudapi.com）。 */
    private String endpoint = "sms.tencentcloudapi.com";

    /** 连接超时，毫秒。 */
    private int connectTimeoutMs = 3000;

    /** 请求超时，毫秒（出网经 NAT，公网抖动留足）。 */
    private int requestTimeoutMs = 10000;

    /** 回执回调公开口按 IP 每小时允许的请求数（AGENTS.md 第 6 节：新增公开接口必须限流）。 */
    private int receiptIpHourlyLimit = 600;

    /**
     * 回执回调 Basic Auth 用户名（E22-T10 / PT-SELF-02）：与腾讯云控制台「状态回执推送」配置的凭据一致，
     * 经环境变量注入。用户名与密码都非空时回调强制校验；留空保持仅限流（迁移期，启动告警提醒配置）。
     */
    private String receiptAuthUsername = "";

    /** 回执回调 Basic Auth 密码（只经配置注入，不写日志）。 */
    private String receiptAuthPassword = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecretId() {
        return secretId;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getSdkAppId() {
        return sdkAppId;
    }

    public void setSdkAppId(String sdkAppId) {
        this.sdkAppId = sdkAppId;
    }

    public String getSignName() {
        return signName;
    }

    public void setSignName(String signName) {
        this.signName = signName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getReceiptIpHourlyLimit() {
        return receiptIpHourlyLimit;
    }

    public void setReceiptIpHourlyLimit(int receiptIpHourlyLimit) {
        this.receiptIpHourlyLimit = receiptIpHourlyLimit;
    }

    /** 回执凭据是否配置完整（用户名与密码都非空才算启用强制校验）。 */
    public boolean receiptAuthConfigured() {
        return receiptAuthUsername != null
                && !receiptAuthUsername.isBlank()
                && receiptAuthPassword != null
                && !receiptAuthPassword.isBlank();
    }

    public String getReceiptAuthUsername() {
        return receiptAuthUsername;
    }

    public void setReceiptAuthUsername(String receiptAuthUsername) {
        this.receiptAuthUsername = receiptAuthUsername;
    }

    public String getReceiptAuthPassword() {
        return receiptAuthPassword;
    }

    public void setReceiptAuthPassword(String receiptAuthPassword) {
        this.receiptAuthPassword = receiptAuthPassword;
    }
}
