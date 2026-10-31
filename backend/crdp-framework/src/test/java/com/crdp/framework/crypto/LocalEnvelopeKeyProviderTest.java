package com.crdp.framework.crypto;

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

        assertThrows(
                ApiException.class,
                () -> new LocalEnvelopeKeyProvider("master-two").unwrap(generated.wrappedKey()));
    }

    private static String bytesToText(SecretKey key) {
        return java.util.Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
