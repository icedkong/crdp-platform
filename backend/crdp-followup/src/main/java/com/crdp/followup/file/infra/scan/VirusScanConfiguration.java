package com.crdp.followup.file.infra.scan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 病毒扫描装配（E22-T15 / D-01 整改）：按 {@code crdp.file.virus-scan.mode} 二选一（与 {@code
 * com.crdp.framework.storage.StorageConfiguration} 同款口径）。
 *
 * <p>{@code clamd} → INSTREAM 同步扫描（不可用即 50300 fail closed）；{@code off}（默认， 缺省匹配）→ 禁用替身（不扫描，台账
 * UNSCANNED，补扫作业启用后按 SHA-256 补扫存量）。
 */
@Configuration
@EnableConfigurationProperties(VirusScanProperties.class)
public class VirusScanConfiguration {

    @Bean
    @ConditionalOnProperty(name = "crdp.file.virus-scan.mode", havingValue = "clamd")
    public VirusScanner clamdVirusScanner(VirusScanProperties properties) {
        return new ClamdVirusScanner(properties);
    }

    @Bean
    @ConditionalOnProperty(
            name = "crdp.file.virus-scan.mode",
            havingValue = "off",
            matchIfMissing = true)
    public VirusScanner disabledVirusScanner() {
        return DisabledVirusScanner.INSTANCE;
    }
}
