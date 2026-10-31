package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * local/dev 的主密钥实现：主密钥从 {@code crdp.crypto.local-key} 派生，数据密钥用主密钥 AES-256-GCM 包装，包装格式沿用 {@link
 * Ciphertext}（用途固定 keywrap）。数据密钥本身独立随机，即使主密钥相同， 各租户的数据密钥也互不相同。生产剖面禁止 local
 * 模式（ProductionSafetyCheck）。
 */
public class LocalEnvelopeKeyProvider implements KeyProvider {

    private static final int DATA_KEY_BYTES = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String WRAP_PURPOSE = "keywrap";

    private final SecretKeySpec masterKey;
    private final SecureRandom random = new SecureRandom();

    public LocalEnvelopeKeyProvider(String localKey) {
        if (localKey == null || localKey.isBlank()) {
            throw new IllegalStateException("crdp.crypto.local-key 未配置");
        }
        this.masterKey = deriveMasterKey(localKey);
    }

    @Override
    public GeneratedDataKey generateDataKey() {
        byte[] material = new byte[DATA_KEY_BYTES];
        random.nextBytes(material);
        String wrapped = wrap(material);
        return new GeneratedDataKey(wrapped, new SecretKeySpec(material, "AES"));
    }

    @Override
    public SecretKey unwrap(String wrappedKey) {
        Ciphertext ciphertext = Ciphertext.parse(wrappedKey);
        if (!"AES-256-GCM".equals(ciphertext.algorithm())) {
            throw new ApiException(400, 40002, "包装密钥算法无法识别: " + ciphertext.algorithm());
        }
        if (!WRAP_PURPOSE.equals(ciphertext.purpose())) {
            throw new ApiException(400, 40002, "包装密钥用途不匹配");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    masterKey,
                    new GCMParameterSpec(TAG_BITS, ciphertext.iv()));
            return new SecretKeySpec(cipher.doFinal(ciphertext.data()), "AES");
        } catch (GeneralSecurityException ex) {
            throw new ApiException(400, 40002, "包装密钥无法解开");
        }
    }

    private String wrap(byte[] material) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(material);
            return new Ciphertext(1, "AES-256-GCM", 1, WRAP_PURPOSE, iv, encrypted).encode();
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private static SecretKeySpec deriveMasterKey(String localKey) {
        try {
            byte[] material =
                    MessageDigest.getInstance("SHA-256")
                            .digest(localKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }
}
