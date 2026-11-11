package com.crdp.framework.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link ArchiveCipher} 的 JDK 实现：PBKDF2WithHmacSHA256（210_000 轮，OWASP 2023 建议）派生 AES-256 密钥，
 * AES-256-GCM 认证加密。容器自描述（见接口注释），无第三方算法库依赖；密码错误与篡改统一在 GCM 标签校验处失败。
 */
public class AesGcmArchiveCipher implements ArchiveCipher {

    private static final byte[] MAGIC = {'C', 'R', 'D', 'P', 'A', '1', 0};
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private final SecureRandom random = new SecureRandom();

    @Override
    public String randomPassword() {
        StringBuilder builder = new StringBuilder(20);
        ThreadLocalRandom current = ThreadLocalRandom.current();
        for (int i = 0; i < 20; i++) {
            builder.append(PASSWORD_ALPHABET.charAt(current.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }

    @Override
    public byte[] seal(byte[] plain, char[] password) {
        requirePassword(password);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    deriveKey(password, salt),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain == null ? new byte[0] : plain);
            byte[] sealed = new byte[MAGIC.length + SALT_LENGTH + IV_LENGTH + encrypted.length];
            int offset = 0;
            System.arraycopy(MAGIC, 0, sealed, offset, MAGIC.length);
            offset += MAGIC.length;
            System.arraycopy(salt, 0, sealed, offset, SALT_LENGTH);
            offset += SALT_LENGTH;
            System.arraycopy(iv, 0, sealed, offset, IV_LENGTH);
            offset += IV_LENGTH;
            System.arraycopy(encrypted, 0, sealed, offset, encrypted.length);
            return sealed;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("整包加密失败", ex);
        }
    }

    @Override
    public byte[] open(byte[] sealed, char[] password) {
        requirePassword(password);
        if (sealed == null
                || sealed.length < MAGIC.length + SALT_LENGTH + IV_LENGTH + (TAG_BITS / 8)
                || !Arrays.equals(Arrays.copyOf(sealed, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("不是合法的加密包容器（CRDPA1）");
        }
        int offset = MAGIC.length;
        byte[] salt = Arrays.copyOfRange(sealed, offset, offset + SALT_LENGTH);
        offset += SALT_LENGTH;
        byte[] iv = Arrays.copyOfRange(sealed, offset, offset + IV_LENGTH);
        offset += IV_LENGTH;
        byte[] encrypted = Arrays.copyOfRange(sealed, offset, sealed.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(password, salt),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("密码错误或加密包已损坏");
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("解压密码不能为空");
        }
    }
}
