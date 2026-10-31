package com.crdp.server.config;

/** 本地演示把数据库口令以 local: 前缀放在平台库。生产应改为 KMS 密文，M0 遇到非 local 前缀直接拒绝。 */
public final class DatasourceSecrets {

    private DatasourceSecrets() {}

    public static String resolve(String cipher, String cryptoMode) {
        if (cipher != null && cipher.startsWith("local:")) {
            if (!"local".equalsIgnoreCase(cryptoMode)
                    && !"dev".equalsIgnoreCase(cryptoMode)
                    && !"test".equalsIgnoreCase(cryptoMode)) {
                throw new IllegalStateException("local 数据源口令不能用于当前密码模式");
            }
            return cipher.substring("local:".length());
        }
        throw new IllegalStateException("无法解析数据源口令。生产环境应使用 KMS，见 E03");
    }
}
