package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class LocalCryptoServiceTest {

    @Test
    void roundTripsAndBuildsAStableBlindIndex() {
        LocalCryptoService crypto = new LocalCryptoService("dev-only-not-a-production-key");
        String ciphertext = crypto.encrypt("field", "虚构姓名");
        assertEquals("虚构姓名", crypto.decrypt(ciphertext));
        assertEquals(
                crypto.blindIndex("blind", "MR-DEMO-001"),
                crypto.blindIndex("blind", "MR-DEMO-001"));
        assertNotEquals(
                crypto.blindIndex("blind", "MR-DEMO-001"),
                crypto.blindIndex("file", "MR-DEMO-001"));
    }
}
