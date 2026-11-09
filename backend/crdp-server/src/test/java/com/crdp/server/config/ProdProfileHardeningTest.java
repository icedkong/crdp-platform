package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * E22-T10 / 渗透修复自查 PT-SELF-01、PT-SELF-03：生产 profile 的攻击面收敛以 yml 字面量锁定 —— swagger / OpenAPI
 * 文档默认关闭（只显式 {@code CRDP_API_DOCS_ENABLED=true} 时开放，UAT 调试用）， CORS 允许来源无默认回落（缺 {@code
 * CRDP_CORS_ORIGINS} 启动即失败，不落到保留域通配符）。 字面量断言（占位符原样比较）防配置漂移：改回默认开放 / 补一个「安全的」默认值都会先红。
 */
class ProdProfileHardeningTest {

    private static Properties prodYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties, "application-prod.yml 应可解析");
        return properties;
    }

    @Test
    void swaggerUiDisabledByDefaultInProd() {
        assertEquals(
                "${CRDP_SWAGGER_ENABLED:false}",
                prodYaml().getProperty("springdoc.swagger-ui.enabled"),
                "生产 swagger-ui 必须默认关闭（显式 CRDP_SWAGGER_ENABLED=true 才开放）");
    }

    @Test
    void openApiDocsDisabledByDefaultInProd() {
        assertEquals(
                "${CRDP_API_DOCS_ENABLED:false}",
                prodYaml().getProperty("springdoc.api-docs.enabled"),
                "生产 /v3/api-docs 必须默认关闭（显式 CRDP_API_DOCS_ENABLED=true 才开放）");
    }

    @Test
    void corsOriginsHaveNoDefaultFallbackInProd() {
        assertEquals(
                "${CRDP_CORS_ORIGINS}",
                prodYaml().getProperty("crdp.cors.allowed-origin-patterns"),
                "生产 CORS 来源必须显式注入（无默认回落：缺 CRDP_CORS_ORIGINS 启动即失败）");
    }

    @Test
    void smsReceiptAuthCredentialsWiredThroughEnvInProd() {
        Properties yaml = prodYaml();
        assertEquals(
                "${CRDP_SMS_RECEIPT_AUTH_USER:}",
                yaml.getProperty("crdp.sms.receipt-auth-username"),
                "生产短信回执 Basic Auth 用户名经环境变量注入（缺省空 = 迁移期仅限流）");
        assertEquals(
                "${CRDP_SMS_RECEIPT_AUTH_PASSWORD:}",
                yaml.getProperty("crdp.sms.receipt-auth-password"),
                "生产短信回执 Basic Auth 密码经环境变量注入（缺省空 = 迁移期仅限流）");
    }
}
