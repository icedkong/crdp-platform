package com.crdp.framework.crypto;

import com.crdp.framework.audit.AuditLogger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 按剖面组装密码服务：local 用进程内主密钥（生产禁止），kms 用占位待云账号接入。见 ADR 0004。 重加密作业按各模块注册的 {@link
 * EncryptedColumnProvider} 巡检加密列（E03-T05）；作业经 JobScheduler 逐租户执行， 定时注册在 E14-T04。
 */
@Configuration
public class CryptoConfiguration {

    @Bean
    public KeyProvider keyProvider(CryptoProperties properties) {
        if ("kms".equalsIgnoreCase(properties.getMode())) {
            return new KmsKeyProvider();
        }
        if (!"local".equalsIgnoreCase(properties.getMode())) {
            throw new IllegalStateException("未知的 crdp.crypto.mode: " + properties.getMode());
        }
        String key =
                LocalKeyMaterial.resolve(
                        properties.getLocalKey(), Path.of("data", "demo-crypto.key"));
        return new LocalEnvelopeKeyProvider(key);
    }

    @Bean
    public TenantDataKeyService tenantDataKeyService(
            KeyProvider keyProvider, JdbcTemplate jdbcTemplate) {
        return new TenantDataKeyService(keyProvider, jdbcTemplate);
    }

    @Bean
    public CryptoService cryptoService(TenantDataKeyService tenantDataKeyService) {
        CryptoService service = new EnvelopeCryptoService(tenantDataKeyService);
        // MyBatis 反射实例化 TypeHandler、不走容器，这里完成静态注入（E03-T03）
        CryptoTypeHandler.configure(service);
        return service;
    }

    @Bean
    public KeyRotationService keyRotationService(
            TenantDataKeyService tenantDataKeyService, AuditLogger auditLogger) {
        return new KeyRotationService(tenantDataKeyService, auditLogger);
    }

    @Bean
    public CryptoReencryptionJob cryptoReencryptionJob(
            CryptoService cryptoService,
            TenantDataKeyService tenantDataKeyService,
            JdbcTemplate jdbcTemplate,
            CryptoProperties properties,
            ObjectProvider<EncryptedColumnProvider> providers) {
        List<EncryptedColumn> columns = new ArrayList<>();
        providers.stream().forEach(provider -> columns.addAll(provider.encryptedColumns()));
        return new CryptoReencryptionJob(
                cryptoService,
                tenantDataKeyService,
                jdbcTemplate,
                columns,
                properties.getReencryptBatchSize());
    }
}
