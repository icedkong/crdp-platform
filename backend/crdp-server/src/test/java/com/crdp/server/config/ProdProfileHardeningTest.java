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

    /**
     * S3（M3 审查）/ PLT-AUD-010：生产默认 type=obs——审计归档必须开启（在线 1 年 + WORM 15 年）。
     * 缺归档桶任一项（CRDP_OBS_ARCHIVE_*）启动即失败 （ObsAuditArchiveStorage fail
     * fast，报错指明配置键，ObsAuditArchiveStorageTest 已锁）； 不允许写回默认 none（字面量断言防漂移）。 本机 / CI
     * 剖面不激活本文件，application.yml 的 none 保持可用（DemoProfileYamlTest 另锁演示剖面）。
     */
    @Test
    void auditArchiveStorageDefaultsToObsInProd() {
        Properties yaml = prodYaml();
        assertEquals(
                "${CRDP_AUDIT_ARCHIVE_STORAGE_TYPE:obs}",
                yaml.getProperty("crdp.audit.archive.storage.type"),
                "生产审计归档默认 type=obs（缺归档桶配置启动失败，不静默回落 none）");
        assertEquals(
                "${CRDP_OBS_ARCHIVE_ENDPOINT:}",
                yaml.getProperty("crdp.audit.archive.storage.obs.endpoint"),
                "归档桶 endpoint 经 CRDP_OBS_ARCHIVE_ENDPOINT 注入");
        assertEquals(
                "${CRDP_OBS_ARCHIVE_AK:}",
                yaml.getProperty("crdp.audit.archive.storage.obs.access-key"),
                "归档桶 AK 经 CRDP_OBS_ARCHIVE_AK 注入");
        assertEquals(
                "${CRDP_OBS_ARCHIVE_SK:}",
                yaml.getProperty("crdp.audit.archive.storage.obs.secret-key"),
                "归档桶 SK 经 CRDP_OBS_ARCHIVE_SK 注入");
        assertEquals(
                "${CRDP_OBS_ARCHIVE_BUCKET:}",
                yaml.getProperty("crdp.audit.archive.storage.obs.bucket"),
                "归档桶名经 CRDP_OBS_ARCHIVE_BUCKET 注入");
    }
}
