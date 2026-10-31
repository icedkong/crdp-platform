package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.Test;

/** E03-T02：KMS 是生产占位，云账号就绪前调用即明确失败，不能悄悄降级到别的实现。 */
class KmsKeyProviderTest {

    private final KmsKeyProvider provider = new KmsKeyProvider();

    @Test
    void generateDataKeyFailsExplicitly() {
        ApiException ex = assertThrows(ApiException.class, provider::generateDataKey);

        assertEquals(501, ex.httpStatus());
        assertEquals(50100, ex.code());
    }

    @Test
    void unwrapFailsExplicitly() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> provider.unwrap("v1.AES-256-GCM.1.keywrap.iv.data"));

        assertEquals(501, ex.httpStatus());
        assertEquals(50100, ex.code());
    }
}
