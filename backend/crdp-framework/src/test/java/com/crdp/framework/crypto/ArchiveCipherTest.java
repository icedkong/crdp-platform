package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** E05-T08 迁出包 / 封存包加密容器（AES-256-GCM + PBKDF2 密码派生）： 加解密往返、错误密码拒绝、随机密码与随机化（同明文两次密文不同）。 */
class ArchiveCipherTest {

    private final AesGcmArchiveCipher cipher = new AesGcmArchiveCipher();

    @Test
    void roundTripWithPassword() {
        byte[] plain = "标准迁出包内容（示例）standard export package".getBytes();
        char[] password = cipher.randomPassword().toCharArray();
        byte[] sealed = cipher.seal(plain, password);
        assertTrue(sealed.length > plain.length, "容器含 magic/salt/iv/认证标签");
        assertArrayEquals(plain, cipher.open(sealed, password));
    }

    @Test
    void wrongPasswordIsRejected() {
        byte[] sealed = cipher.seal("payload".getBytes(), "Demo@2026".toCharArray());
        assertThrows(
                IllegalArgumentException.class,
                () -> cipher.open(sealed, "Demo@2027".toCharArray()));
    }

    @Test
    void tamperedContainerIsRejected() {
        char[] password = "Demo@2026".toCharArray();
        byte[] sealed = cipher.seal("payload".getBytes(), password);
        sealed[sealed.length - 1] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> cipher.open(sealed, password));
    }

    @Test
    void badMagicIsRejected() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> cipher.open("not-a-container".getBytes(), "Demo@2026".toCharArray()));
        assertTrue(ex.getMessage().contains("容器"));
    }

    @Test
    void randomPasswordIsStrongAndSealingIsRandomized() {
        String first = cipher.randomPassword();
        String second = cipher.randomPassword();
        assertNotEquals(first, second);
        assertTrue(first.length() >= 16, "解压密码至少 16 字符");
        assertTrue(first.matches("[A-Za-z0-9]+"), "密码只含字母数字，便于短信发送与手工输入");

        byte[] plain = "same plain".getBytes();
        char[] password = first.toCharArray();
        assertNotEquals(
                Arrays.toString(cipher.seal(plain, password)),
                Arrays.toString(cipher.seal(plain, password)),
                "随机 IV + 随机 salt：同明文两次封装密文不同");
    }

    @Test
    void emptyPlainRoundTrips() {
        char[] password = "Demo@2026".toCharArray();
        byte[] sealed = cipher.seal(new byte[0], password);
        assertArrayEquals(new byte[0], cipher.open(sealed, password));
    }
}
