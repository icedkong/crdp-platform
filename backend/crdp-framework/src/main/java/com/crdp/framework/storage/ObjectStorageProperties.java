package com.crdp.framework.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置（E10-T02 / ADR 0006）。本机与 CI 用 {@code local}（文件系统替身，目录结构与 OBS 一致）； 生产 {@code obs}： 华为云 OBS +
 * SSE-KMS，endpoint / AK / SK / 桶名 / KMS 密钥 ID 只经环境变量注入，不进仓库、不写日志。
 */
@ConfigurationProperties(prefix = "crdp.storage")
public class ObjectStorageProperties {

    /** 存储实现：local（默认，本机 / CI）或 obs（生产，需要云凭证）。 */
    private String type = "local";

    private final Local local = new Local();

    private final Obs obs = new Obs();

    /** 本地替身配置。 */
    public static class Local {

        /** 存储根目录（桶的替身；对象按 t/{租户}/… 落在其下）。 */
        private String root = "data/storage";

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    /** OBS 业务桶配置（type=obs 时必填项缺一启动即失败）。 */
    public static class Obs {

        /** 区域接入点（如 obs.cn-north-4.myhuaweicloud.com）。 */
        private String endpoint = "";

        /** 访问密钥 ID（只授予该桶读写权限的子账号）。 */
        private String accessKey = "";

        /** 访问密钥 Secret。只经配置注入，不写日志。 */
        private String secretKey = "";

        /** 业务桶名（一个桶，每院一个对象键前缀）。 */
        private String bucket = "";

        /** KMS 密钥 ID（SSE-KMS 用；留空走桶默认密钥，加密本身不可关）。 */
        private String kmsKeyId = "";

        /** 连接超时，毫秒。 */
        private int connectTimeoutMs = 3000;

        /** 读写超时，毫秒（公网经 NAT 抖动留足）。 */
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

        public String getKmsKeyId() {
            return kmsKeyId;
        }

        public void setKmsKeyId(String kmsKeyId) {
            this.kmsKeyId = kmsKeyId;
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

    public Local getLocal() {
        return local;
    }

    public Obs getObs() {
        return obs;
    }
}
