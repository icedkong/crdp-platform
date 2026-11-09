package com.crdp.framework.storage;

import com.crdp.framework.audit.AuditArchiveProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审计归档桶装配（E22-T08 / ADR 0005）：按 {@code crdp.audit.archive.storage.type} 二选一。
 *
 * <p>{@code none}（默认，本机 / CI 无云凭证）→ {@link UnsupportedAuditArchiveStorage}（enabled=false，作业只做分区
 * 维护，不上传、不删在线数据——失败安全口径，防止「桶没配好却删了在线分区」）； {@code obs}（生产）→ 华为云 {@link ObsAuditArchiveStorage}（单次
 * PUT + 对象级 COMPLIANCE 保留），必填配置缺失启动即失败，且要求桶级 WORM 已由运维 开启（docs/ops/audit-worm-archive.md）。
 */
@Configuration
@EnableConfigurationProperties(AuditArchiveProperties.class)
public class AuditArchiveStorageConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "crdp.audit.archive.storage.type",
            havingValue = "none",
            matchIfMissing = true)
    public AuditArchiveStorage unsupportedAuditArchiveStorage() {
        return new UnsupportedAuditArchiveStorage();
    }

    @Bean
    @ConditionalOnProperty(name = "crdp.audit.archive.storage.type", havingValue = "obs")
    public AuditArchiveStorage obsAuditArchiveStorage(AuditArchiveProperties properties) {
        return new ObsAuditArchiveStorage(properties);
    }
}
