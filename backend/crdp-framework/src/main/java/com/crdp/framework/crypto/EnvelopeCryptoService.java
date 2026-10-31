package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 信封加密实现（ADR 0004）：AES-256-GCM 字段加密、HMAC-SHA256 盲索引，密钥一律来自 {@link TenantDataKeyService}
 * 的每租户数据密钥；密文是 {@link Ciphertext} 自描述格式，解密按算法 ID 分派、 按密钥版本选钥（E03-T01 的语义不变）。必须在租户上下文内使用。
 */
public class EnvelopeCryptoService implements CryptoService {

    private static final String ALGORITHM = "AES-256-GCM";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern PURPOSE = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private final TenantDataKeyService dataKeys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCryptoService(TenantDataKeyService dataKeys) {
        this.dataKeys = dataKeys;
    }

    @Override
    public String encrypt(String purpose, String plaintext) {
        requirePurpose(purpose);
        int keyVersion = dataKeys.currentVersion(purpose);
        SecretKey key = dataKeys.keyFor(purpose, keyVersion);
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Ciphertext(1, ALGORITHM, keyVersion, purpose, iv, encrypted).encode();
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        Ciphertext parsed = Ciphertext.parse(ciphertext);
        if (!ALGORITHM.equals(parsed.algorithm())) {
            throw new ApiException(400, 40002, "密文算法无法识别: " + parsed.algorithm());
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    dataKeys.keyFor(parsed.purpose(), parsed.keyVersion()),
                    new GCMParameterSpec(TAG_BITS, parsed.iv()));
            return new String(cipher.doFinal(parsed.data()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new ApiException(400, 40002, "密文无法解密");
        }
    }

    @Override
    public String blindIndex(String purpose, String value) {
        requirePurpose(purpose);
        String normalized = BlindIndexNormalizer.normalize(value);
        if (normalized == null) {
            return null;
        }
        SecretKey key = dataKeys.keyFor(purpose, dataKeys.currentVersion(purpose));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private static void requirePurpose(String purpose) {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new ApiException(400, 40002, "加密用途不合法");
        }
    }
}
