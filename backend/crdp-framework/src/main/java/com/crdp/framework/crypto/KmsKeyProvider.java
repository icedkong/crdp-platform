package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import javax.crypto.SecretKey;

/** 生产占位。华为云 DEW/KMS 每院一个主密钥，云账号就绪后在此接入，接口见 ADR 0004。 */
public class KmsKeyProvider implements KeyProvider {

    /** 尚未接入华为云。生产启动检查看到 false 会拒绝启动，避免第一次加密才返回 501。接入 DEW 后改为真实探测并返回 true。 */
    public boolean connected() {
        return false;
    }

    @Override
    public GeneratedDataKey generateDataKey() {
        throw unavailable();
    }

    @Override
    public SecretKey unwrap(String wrappedKey) {
        throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(501, 50100, "KMS 密钥服务尚未接入");
    }
}
