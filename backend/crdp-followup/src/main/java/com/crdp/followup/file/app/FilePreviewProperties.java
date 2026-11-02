package com.crdp.followup.file.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 预览代理配置（E10-T04 / tech-plan §10「预览」：短时链接 ≤ 5 分钟、公开口限流）。
 *
 * <p>{@code crdp.file.preview.ttl-seconds} 即使配置写大也会压回 5 分钟以内（规格上限， 防止部署失误把预览链接变成长效直链）；{@code
 * ip-hourly-limit} 是公开取流口按 IP 的每小时上限（AGENTS.md 第 6 节： 新增公开接口必须限流）。
 */
@ConfigurationProperties(prefix = "crdp.file.preview")
public class FilePreviewProperties {

    /** 预览链接有效期秒数（tech-plan §10「≤ 5 分钟」）。 */
    private int ttlSeconds = 300;

    /** 公开取流口同一 IP 每小时上限（取流 = pdf.js / audio 拉流，单次预览可能多次请求，取宽）。 */
    private int ipHourlyLimit = 600;

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
}
