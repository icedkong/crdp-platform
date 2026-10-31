package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class LocalEnvelopeKeyProviderTest {

    @Test
    void generatedKeysAreRandomAndWrappedForStorage() {
        LocalEnvelopeKeyProvider provider =
                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");

        GeneratedDataKey first = provider.generateDataKey();
        GeneratedDataKey second = provider.generateDataKey();

        assertTrue(first.key().getEncoded().length == 32, "AES-256 数据密钥应为 32 字节");
        assertNotEquals(first.wrappedKey(), second.wrappedKey(), "两次生成的数据密钥必须不同");
        assertTrue(!first.wrappedKey().contains(bytesToText(first.key())), "包装结果不得包含明文密钥");
    }

    @Test
    void unwrapReturnsTheSameKeyThatWasGenerated() {
        LocalEnvelopeKeyProvider provider =
                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");

        GeneratedDataKey generated = provider.generateDataKey();
        SecretKey unwrapped = provider.unwrap(generated.wrappedKey());

        assertTrue(java.util.Arrays.equals(generated.key().getEncoded(), unwrapped.getEncoded()));
    }

    @Test
    void unwrapFailsWithADifferentMasterKey() {
        GeneratedDataKey generated = new LocalEnvelopeKeyProvider("master-one").generateDataKey();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                new LocalEnvelopeKeyProvider("master-two")
                                        .unwrap(generated.wrappedKey()));
        assertEquals(40002, ex.code());
    }

    /** E03-T06：包装密钥只能用 keywrap 用途、AES-256-GCM 算法解开，其余明确拒绝。 */
    @Test
    void unwrapRejectsWrappedKeysWithTheWrongPurpose() {
        LocalEnvelopeKeyProvider provider =
                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");
        GeneratedDataKey generated = provider.generateDataKey();
        Ciphertext parsed = Ciphertext.parse(generated.wrappedKey());
        String wrongPurpose =
                new Ciphertext(
                                parsed.version(),
                                parsed.algorithm(),
                                parsed.keyVersion(),
                                "field-name",
                                parsed.iv(),
                                parsed.data())
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> provider.unwrap(wrongPurpose));
        assertEquals(40002, ex.code(), "包装密钥用途不匹配");
    }

    @Test
    void unwrapRejectsWrappedKeysWithAnUnsupportedAlgorithm() {
        LocalEnvelopeKeyProvider provider =
                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");
        GeneratedDataKey generated = provider.generateDataKey();
        Ciphertext parsed = Ciphertext.parse(generated.wrappedKey());
        String wrongAlgorithm =
                new Ciphertext(
                                parsed.version(),
                                "SM4-GCM",
                                parsed.keyVersion(),
                                parsed.purpose(),
                                parsed.iv(),
                                parsed.data())
                        .encode();

        ApiException ex = assertThrows(ApiException.class, () -> provider.unwrap(wrongAlgorithm));
        assertEquals(40002, ex.code(), "包装密钥算法无法识别");
    }

    @Test
    void blankLocalKeyIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new LocalEnvelopeKeyProvider(" "));
        assertThrows(IllegalStateException.class, () -> new LocalEnvelopeKeyProvider(null));
    }

    private static String bytesToText(SecretKey key) {
        return java.util.Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
