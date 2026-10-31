package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;

/** 生产占位。华为云 DEW/KMS 信封加密在 E03 接入，M0 不放云凭证。 */
public class KmsCryptoService implements CryptoService {

    @Override
    public String encrypt(String purpose, String plaintext) {
        throw unavailable();
    }

    @Override
    public String decrypt(String ciphertext) {
        throw unavailable();
    }

    @Override
    public String blindIndex(String purpose, String value) {
        throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(501, 50100, "KMS 密码服务尚未接入");
    }
}
