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
 * 开发环境的 AES-256-GCM。密文是 {@link Ciphertext} 的自描述格式（带算法与密钥版本），解密按密文里的 算法 ID
 * 分派、按密钥版本选钥，因此新旧算法与密钥版本可以并存。不接入华为云 KMS，prod 剖面禁止使用。 多密钥版本与轮换作业是 E03-T05；本实现只有版本 1。
 */
public class LocalCryptoService implements CryptoService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String ALGORITHM = "AES-256-GCM";
    private static final int KEY_VERSION = 1;
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
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key(purpose, KEY_VERSION),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Ciphertext(1, ALGORITHM, KEY_VERSION, purpose, iv, encrypted).encode();
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
                    key(parsed.purpose(), parsed.keyVersion()),
                    new GCMParameterSpec(TAG_BITS, parsed.iv()));
            return new String(cipher.doFinal(parsed.data()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new ApiException(400, 40002, "密文无法解密");
        }
    }

    @Override
    public String blindIndex(String purpose, String value) {
        requirePurpose(purpose);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(purpose, KEY_VERSION).getEncoded(), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private SecretKeySpec key(String purpose, int keyVersion) {
        if (keyVersion != KEY_VERSION) {
            throw new ApiException(400, 40002, "密钥版本不支持: " + keyVersion);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] material =
                    digest.digest((localKey + ":" + purpose).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(material, "AES");
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
