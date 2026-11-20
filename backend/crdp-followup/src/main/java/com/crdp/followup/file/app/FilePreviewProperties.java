package com.crdp.followup.file.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 预览代理配置（E10-T04 / tech-plan §10「预览」：短时链接 ≤ 5 分钟、鉴权取流限流）。
 *
 * <p>{@code crdp.file.preview.ttl-seconds} 即使配置写大也会压回 5 分钟以内（规格上限， 防止部署失误把预览链接变成长效直链）；{@code
 * ip-hourly-limit} 是鉴权取流口按 IP 的每小时上限（减少重复转换和带宽滥用）。
 */
@ConfigurationProperties(prefix = "crdp.file.preview")
public class FilePreviewProperties {

    /** 预览链接有效期秒数（tech-plan §10「≤ 5 分钟」）。 */
    private int ttlSeconds = 300;

    /** 鉴权取流口同一 IP 每小时上限（取流 = pdf.js / audio 拉流，单次预览可能多次请求，取宽）。 */
    private int ipHourlyLimit = 600;

    /** tech-plan §10 规格上限：有效期最多 5 分钟，配置写大按 5 分钟执行。 */
    public int effectiveTtlSeconds() {
        return Math.max(1, Math.min(ttlSeconds, 300));
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

    /** 受控本地沙箱启动器，参数由程序固定，不经 shell；未部署时可视预览失败关闭。 */
    private String rendererLauncher = "/opt/crdp-preview/bin/preview-sandbox";

    private int renderTimeoutSeconds = 45;

    public String getRendererLauncher() {
        return rendererLauncher;
    }

    public void setRendererLauncher(String value) {
        rendererLauncher = value;
    }

    public int getRenderTimeoutSeconds() {
        return Math.max(1, Math.min(renderTimeoutSeconds, 60));
    }

    public void setRenderTimeoutSeconds(int value) {
        renderTimeoutSeconds = value;
    }
}
