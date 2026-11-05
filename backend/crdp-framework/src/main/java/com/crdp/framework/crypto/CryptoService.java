package com.crdp.framework.crypto;

/** 业务代码只通过此接口做加解密。算法与密钥由实现决定，便于以后切换到 KMS / 国密。 */
public interface CryptoService {

    String encrypt(String purpose, String plaintext);

    String decrypt(String ciphertext);

    String blindIndex(String purpose, String value);

    /**
     * 以外部共享密钥计算 HMAC-SHA256，返回 64 位小写十六进制（E09-T06 Webhook 请求签名）。
     *
     * <p>与 {@link #encrypt} / {@link #blindIndex} 不同：密钥由调用方提供（如 Webhook 的 secret），
     * 不取租户数据密钥，因此不依赖租户上下文。切换国密（HMAC-SM3）时替换实现即可。
     */
    String hmacSha256Hex(byte[] key, String data);
}
