package com.crdp.framework.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配（E10-T02 / ADR 0006）：按 {@code crdp.storage.type} 二选一。
 *
 * <p>{@code local}（默认，本机 / CI）→ 文件系统替身；{@code obs}（生产）→ 华为云 OBS + SSE-KMS， 必填配置缺失时启动即失败（fail-fast）。
 * 占位实现 UnsupportedObjectStorage 已随本任务删除。
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class StorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "crdp.storage.type", havingValue = "local", matchIfMissing = true)
    public ObjectStorage localObjectStorage(ObjectStorageProperties properties) {
        return new LocalObjectStorage(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "crdp.storage.type", havingValue = "obs")
    public ObjectStorage obsObjectStorage(ObjectStorageProperties properties) {
        return new ObsObjectStorage(properties);
    }
}
