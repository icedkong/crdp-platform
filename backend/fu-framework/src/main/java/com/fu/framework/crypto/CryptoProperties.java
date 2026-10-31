package com.fu.framework.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fu.crypto")
public class CryptoProperties {

    /** local：开发用进程内 AES-GCM。kms：生产占位，调用即失败。 */
    private String mode = "local";

    /** 仅 local 模式使用。生产禁止。 */
    private String localKey = "";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getLocalKey() {
        return localKey;
    }

    public void setLocalKey(String localKey) {
        this.localKey = localKey;
    }
}
