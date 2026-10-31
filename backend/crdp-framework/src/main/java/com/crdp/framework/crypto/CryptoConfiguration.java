package com.crdp.framework.crypto;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 按剖面组装密码服务：local 用进程内主密钥（生产禁止），kms 用占位待云账号接入。见 ADR 0004。 */
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
        return new LocalEnvelopeKeyProvider(properties.getLocalKey());
    }

    @Bean
    public CryptoService cryptoService(KeyProvider keyProvider, JdbcTemplate jdbcTemplate) {
        return new EnvelopeCryptoService(new TenantDataKeyService(keyProvider, jdbcTemplate));
    }
}
