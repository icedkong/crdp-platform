package com.crdp.framework.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计归档策略（E08-T03 / PLT-AUD-010）。验收口径默认值：在线保留 1 年（12 个月）、归档对象 WORM 锁定 15 年； 均可按等保测评或合同年限加严（保留期只能延长）。
 *
 * <p>归档桶接入（E22-T08）：{@code storage.type=obs} 时装配 {@code ObsAuditArchiveStorage}（华为云 OBS， 单次 PUT +
 * 对象级 COMPLIANCE 保留）；默认 {@code none} 装配占位实现，作业只做分区维护不动在线数据（ADR 0005 的失败安全口径）。
 */
@ConfigurationProperties(prefix = "crdp.audit.archive")
public class AuditArchiveProperties {

    /** 在线保留月数：上界早于「今天 − 该月数」的月分区才允许归档删除。 */
    private int onlineRetentionMonths = 12;

    /** WORM 对象级保留年数：retainUntil = 归档日 + 该年数。 */
    private int wormRetentionYears = 15;

    /** 预建未来月分区数（REORGANIZE pmax），保证新月份审计写入即有分区。 */
    private int partitionsAhead = 3;

    /** 归档桶接入配置（E22-T08）。 */
    private final Storage storage = new Storage();

    /** 归档桶配置：none（默认，本机 / CI 无云凭证）或 obs（生产，需要云凭证且桶级 WORM 已开启）。 */
    public static class Storage {

        /** 存储实现：none（默认）或 obs。 */
        private String type = "none";

        /** OBS 归档桶配置（type=obs 时必填项缺一启动即失败）。 */
        private final Obs obs = new Obs();

        /** 华为云 OBS 归档桶配置（与业务桶分离，桶级 WORM 由运维开启，见 docs/ops/audit-worm-archive.md）。 */
        public static class Obs {

            /** 区域接入点（如 obs.cn-north-4.myhuaweicloud.com，只写北京四一份）。 */
            private String endpoint = "";

            /** 访问密钥 ID（只授予该归档桶读写权限的子账号，AK/SK 只经环境变量注入）。 */
            private String accessKey = "";

            /** 访问密钥 Secret。只经配置注入，不写日志。 */
            private String secretKey = "";

            /** 审计归档桶名（单独的单可用区 WORM 桶，与业务桶分离）。 */
            private String bucket = "";

            /** 连接超时，毫秒。 */
            private int connectTimeoutMs = 3000;

            /** 读写超时，毫秒。 */
            private int socketTimeoutMs = 30000;

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String endpoint) {
                this.endpoint = endpoint;
            }

            public String getAccessKey() {
                return accessKey;
            }

            public void setAccessKey(String accessKey) {
                this.accessKey = accessKey;
            }

            public String getSecretKey() {
                return secretKey;
            }

            public void setSecretKey(String secretKey) {
                this.secretKey = secretKey;
            }

            public String getBucket() {
                return bucket;
            }

            public void setBucket(String bucket) {
                this.bucket = bucket;
            }

            public int getConnectTimeoutMs() {
                return connectTimeoutMs;
            }

            public void setConnectTimeoutMs(int connectTimeoutMs) {
                this.connectTimeoutMs = connectTimeoutMs;
            }

            public int getSocketTimeoutMs() {
                return socketTimeoutMs;
            }

            public void setSocketTimeoutMs(int socketTimeoutMs) {
                this.socketTimeoutMs = socketTimeoutMs;
            }
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Obs getObs() {
            return obs;
        }
    }

    public int getOnlineRetentionMonths() {
        return onlineRetentionMonths;
    }

    public void setOnlineRetentionMonths(int onlineRetentionMonths) {
        this.onlineRetentionMonths = onlineRetentionMonths;
    }

    public int getWormRetentionYears() {
        return wormRetentionYears;
    }

    public void setWormRetentionYears(int wormRetentionYears) {
        this.wormRetentionYears = wormRetentionYears;
    }

    public int getPartitionsAhead() {
        return partitionsAhead;
    }

    public void setPartitionsAhead(int partitionsAhead) {
        this.partitionsAhead = partitionsAhead;
    }

    public Storage getStorage() {
        return storage;
    }

    /** 转归档器策略对象。 */
    public AuditArchiver.ArchivePolicy toPolicy() {
        return new AuditArchiver.ArchivePolicy(
                onlineRetentionMonths, wormRetentionYears, partitionsAhead);
    }
}
