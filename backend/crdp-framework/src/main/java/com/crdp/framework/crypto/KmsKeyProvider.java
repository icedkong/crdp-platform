package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import javax.crypto.SecretKey;

/** 生产占位。华为云 DEW/KMS 每院一个主密钥，云账号就绪后在此接入，接口见 ADR 0004。 */
public class KmsKeyProvider implements KeyProvider {

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
