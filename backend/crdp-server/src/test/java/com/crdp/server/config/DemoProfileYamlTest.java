package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** 演示剖面必须走本地信封加密和模拟短信，不能把部署默认指回未接入的 KMS。 */
class DemoProfileYamlTest {

    @Test
    void demoUsesLocalEnvelopeAndMockSms() {
        Properties yaml = yaml("application-demo.yml");
        assertEquals("local", yaml.getProperty("crdp.crypto.mode"));
        assertEquals("${CRDP_CRYPTO_LOCAL_KEY:}", yaml.getProperty("crdp.crypto.local-key"));
        assertEquals("mock", yaml.getProperty("crdp.sms.provider"));
        assertTrue(
                yaml.getProperty("crdp.h5-upload.mobile-base-url")
                        .contains("CRDP_H5_UPLOAD_MOBILE_BASE_URL"),
                "手机上传基地址必须能用环境变量改成局域网地址");
    }

    @Test
    void prodStillForcesKms() {
        Properties yaml = yaml("application-prod.yml");
        assertEquals("kms", yaml.getProperty("crdp.crypto.mode"));
        assertNotNull(yaml.getProperty("crdp.crypto.mode"));
    }

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(name));
        Properties properties = factory.getObject();
        assertNotNull(properties);
        return properties;
    }
}
