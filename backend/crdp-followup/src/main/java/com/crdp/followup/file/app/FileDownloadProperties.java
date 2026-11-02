package com.crdp.followup.file.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 签名下载配置（E10-T05 / tech-plan §10「下载」：5 分钟签名链接、公开口限流）。
 *
 * <p>{@code crdp.file.download.ttl-seconds} 即使配置写大也会压回 5 分钟以内（规格上限， 防止部署失误把下载链接变成长效直链）；{@code
 * ip-hourly-limit} 是公开取流口按 IP 的每小时上限（AGENTS.md 第 6 节： 新增公开接口必须限流）；{@code signing-key}
 * 是链接签名密钥（Base64，≥ 32 字节）——application.yml 只带开发默认值， 生产经环境变量注入（application-prod.yml 无默认，缺省启动即失败，见
 * {@code CRDP_FILE_DOWNLOAD_SIGNING_KEY}）。
 */
@ConfigurationProperties(prefix = "crdp.file.download")
public class FileDownloadProperties {

    /** 下载链接有效期秒数（tech-plan §10「5 分钟有效」）。 */
    private int ttlSeconds = 300;

    /** 公开取流口同一 IP 每小时上限（浏览器断点续传 / 重试会多次请求，取宽）。 */
    private int ipHourlyLimit = 600;

    /** 链接签名密钥（Base64；解码后至少 32 字节；生产经环境变量注入，不落镜像）。 */
    private String signingKey = "";

    /** tech-plan §10 规格上限：有效期最多 5 分钟，配置写大按 5 分钟执行。 */
    public int effectiveTtlSeconds() {
        return Math.min(ttlSeconds, 300);
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getIpHourlyLimit() {
        return ipHourlyLimit;
    }

    public void setIpHourlyLimit(int ipHourlyLimit) {
        this.ipHourlyLimit = ipHourlyLimit;
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }
}
