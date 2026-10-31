package com.fu.framework.crypto;

/** 业务代码只通过此接口做加解密。算法与密钥由实现决定，便于以后切换到 KMS / 国密。 */
public interface CryptoService {

    String encrypt(String purpose, String plaintext);

    String decrypt(String ciphertext);

    String blindIndex(String purpose, String value);
}
