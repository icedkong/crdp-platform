package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.Test;

class LocalCryptoServiceTest {

    private final LocalCryptoService crypto =
            new LocalCryptoService("dev-only-not-a-production-key");

    @Test
    void roundTripsAndBuildsAStableBlindIndex() {
        String ciphertext = crypto.encrypt("field", "虚构姓名");
        assertEquals("虚构姓名", crypto.decrypt(ciphertext));
        assertEquals(
                crypto.blindIndex("blind", "MR-DEMO-001"),
                crypto.blindIndex("blind", "MR-DEMO-001"));
        assertNotEquals(
                crypto.blindIndex("blind", "MR-DEMO-001"),
                crypto.blindIndex("file", "MR-DEMO-001"));
    }

    @Test
    void decryptRejectsAlgorithmsThatAreWellFormedButUnsupported() {
        Ciphertext original = Ciphertext.parse(crypto.encrypt("field", "虚构姓名"));
        String otherAlgorithm =
                new Ciphertext(
                                1,
                                "SM4-GCM",
                                original.keyVersion(),
                                original.purpose(),
                                original.iv(),
                                original.data())
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(otherAlgorithm));
        assertEquals(40002, ex.code(), "按密文中的算法 ID 分派，未知算法应明确拒绝");
    }

    @Test
    void decryptRejectsUnknownKeyVersionsInsteadOfTryingTheCurrentKey() {
        Ciphertext original = Ciphertext.parse(crypto.encrypt("field", "虚构姓名"));
        String rotated =
                new Ciphertext(
                                1,
                                "AES-256-GCM",
                                2,
                                original.purpose(),
                                original.iv(),
                                original.data())
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(rotated));
        assertEquals(40002, ex.code(), "未知密钥版本应被拒绝，而不是拿当前密钥硬解");
    }

    @Test
    void decryptDetectsTamperedCiphertext() {
        Ciphertext original = Ciphertext.parse(crypto.encrypt("field", "虚构姓名"));
        byte[] tampered = original.data().clone();
        tampered[0] ^= 0x01;
        String value =
                new Ciphertext(1, "AES-256-GCM", 1, original.purpose(), original.iv(), tampered)
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> crypto.decrypt(value));
        assertEquals(40002, ex.code(), "GCM 认证标签应发现密文被改动");
    }
}
