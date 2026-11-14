package com.crdp.followup.file.infra.scan;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传病毒扫描配置（E22-T15 / D-01 整改）。
 *
 * <p>{@code crdp.file.virus-scan.mode}：{@code off}（默认，本机 / CI / 未部署 clamd 的环境——不扫描， 台账落 UNSCANNED
 * 由补扫作业补）或 {@code clamd}（启用同步扫描；扫描不可用上传 50300 不放行）。 生产经 {@code CRDP_VIRUS_SCAN_MODE}
 * 注入（application-prod.yml）：clamd 副本在 CCE 部署并由运维 核验后置 {@code clamd}（整改手册 §5 待人工项），避免 clamd
 * 未就绪时生产上传整体不可用。
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
