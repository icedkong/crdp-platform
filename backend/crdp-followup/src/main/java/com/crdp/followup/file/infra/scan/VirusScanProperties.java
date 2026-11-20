package com.crdp.followup.file.infra.scan;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传病毒扫描配置（E22-T15 / D-01 整改）。
 *
 * <p>本机 / CI 可显式 off；生产默认 clamd，启动核验 mode、服务地址和有限超时。 扫描不可用一律拒绝上传，不把 UNSCANNED 当作 CLEAN。
 *
 * <p>{@code retro-batch-limit}：存量补扫作业单租户单趟最多处理的文件数（防首趟全量扫描打爆对象存储读与 clamd； 存量按 SHA-256 补扫，多趟推进）。
 */
@ConfigurationProperties(prefix = "crdp.file.virus-scan")
public class VirusScanProperties {

    /** 扫描模式：off（默认）| clamd。 */
    private String mode = "off";

    /** 补扫作业单租户单趟批量上限。 */
    private int retroBatchLimit = 200;

    private final Clamd clamd = new Clamd();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getRetroBatchLimit() {
        return retroBatchLimit;
    }

    public void setRetroBatchLimit(int retroBatchLimit) {
        this.retroBatchLimit = retroBatchLimit;
    }

    public Clamd getClamd() {
        return clamd;
    }

    public void validateClamd() {
        if (clamd.host == null
                || clamd.host.isBlank()
                || clamd.port < 1
                || clamd.port > 65535
                || clamd.connectTimeout == null
                || clamd.readTimeout == null
                || clamd.connectTimeout.toMillis() < 1
                || clamd.connectTimeout.toMillis() > 60000
                || clamd.readTimeout.toMillis() < 1
                || clamd.readTimeout.toMillis() > 60000)
            throw new IllegalStateException("病毒扫描配置无效：检查 clamd host、port 及 1–60000ms 的有限连接 / 读取超时");
    }

    /** clamd 连接参数（默认本机 3310；K8s 部署经 Service 名注入）。 */
    public static class Clamd {

        private String host = "127.0.0.1";

        private int port = 3310;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(30);

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
