package com.crdp.followup.report.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 手机扫码上传配置（E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006；docs/api/report.md §5）。
 *
 * <p>{@code crdp.h5-upload.ttl-seconds} 是一次性二维码的有效期，规格固定 **15 分钟**（A-ATT-015 / C-NFR-006），配置写大也按
 * 900 秒执行（防部署失误把一次性二维码变成长效凭据）。{@code mobile-base-url} 是 H5 应用（apps/mobile-upload）的对外 基地址，签发的 qrUrl
 * = 基地址 + {@code /#/u/{token}}（令牌只放 URL 片段，不进服务端访问日志）；本机开发默认 14175 端口， 生产经环境变量
 * CRDP_H5_UPLOAD_MOBILE_BASE_URL 指向真实域名。{@code ip-hourly-limit} 是公开口（/api/h5/upload/**）按 IP 的每小时上限
 * （AGENTS.md 第 6 节：新增公开接口必须限流）。
 */
@ConfigurationProperties(prefix = "crdp.h5-upload")
public class MobileUploadProperties {

    /** 一次性二维码有效期秒数（A-ATT-015「15 分钟有效」）。 */
    private int ttlSeconds = 900;

    /** H5 应用对外基地址（无尾斜杠；令牌拼在 URL 片段 #/u/{token}）。 */
    private String mobileBaseUrl = "http://127.0.0.1:14175";

    /** 公开口同一 IP 每小时上限（拍照连传 / 轮询会多次请求，取宽）。 */
    private int ipHourlyLimit = 600;

    /** 规格上限：有效期最多 15 分钟，配置写大按 900 秒执行。 */
    public int effectiveTtlSeconds() {
        return Math.min(ttlSeconds, 900);
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getMobileBaseUrl() {
        return mobileBaseUrl;
    }

    public void setMobileBaseUrl(String mobileBaseUrl) {
        this.mobileBaseUrl = mobileBaseUrl;
    }

    public int getIpHourlyLimit() {
        return ipHourlyLimit;
    }

    public void setIpHourlyLimit(int ipHourlyLimit) {
        this.ipHourlyLimit = ipHourlyLimit;
    }
}
