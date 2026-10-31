package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** E03-T06：密码服务配置项的默认值与读写（local 模式默认、重加密批大小）。 */
class CryptoPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedLocalMode() {
        CryptoProperties properties = new CryptoProperties();

        assertEquals("local", properties.getMode(), "未配置时默认 local，生产剖面由 ProductionSafetyCheck 拦截");
        assertEquals("", properties.getLocalKey());
        assertEquals(200, properties.getReencryptBatchSize());
    }

    @Test
    void settersRoundTrip() {
        CryptoProperties properties = new CryptoProperties();

        properties.setMode("kms");
        properties.setLocalKey("dev-only-not-a-production-key");
        properties.setReencryptBatchSize(500);

        assertEquals("kms", properties.getMode());
        assertEquals("dev-only-not-a-production-key", properties.getLocalKey());
        assertEquals(500, properties.getReencryptBatchSize());
    }
}
