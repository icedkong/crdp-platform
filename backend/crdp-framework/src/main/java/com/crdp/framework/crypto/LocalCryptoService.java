package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 开发环境的 AES-256-GCM。密文格式：{@code v1.AES-256-GCM.1.<purpose>.<iv>.<cipher>}。 purpose、iv、cipher 里的
 * iv/cipher 为 Base64。不接入华为云 KMS，prod 剖面禁止使用。
 */
public class LocalCryptoService implements CryptoService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern PURPOSE = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private final String localKey;
    private final SecureRandom random = new SecureRandom();

    public LocalCryptoService(String localKey) {
        if (localKey == null || localKey.isBlank()) {
            throw new IllegalStateException("crdp.crypto.local-key 未配置");
        }
        this.localKey = localKey;
    }

    @Override
    public String encrypt(String purpose, String plaintext) {
        requirePurpose(purpose);
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(purpose), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1.AES-256-GCM.1."
                    + purpose
                    + "."
                    + Base64.getEncoder().encodeToString(iv)
                    + "."
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        String[] parts = ciphertext.split("\\.", 6);
        if (parts.length != 6 || !"v1".equals(parts[0]) || !"AES-256-GCM".equals(parts[1])) {
            throw new ApiException(400, 40002, "密文格式无法识别");
        }
        String purpose = parts[3];
        requirePurpose(purpose);
        try {
            byte[] iv = Base64.getDecoder().decode(parts[4]);
            byte[] encrypted = Base64.getDecoder().decode(parts[5]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(purpose), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new ApiException(400, 40002, "密文无法解密");
        }
    }

    @Override
    public String blindIndex(String purpose, String value) {
        requirePurpose(purpose);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(purpose).getEncoded(), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private SecretKeySpec key(String purpose) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] material =
                digest.digest((localKey + ":" + purpose).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(material, "AES");
    }

    private static void requirePurpose(String purpose) {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new ApiException(400, 40002, "加密用途不合法");
        }
    }
}
