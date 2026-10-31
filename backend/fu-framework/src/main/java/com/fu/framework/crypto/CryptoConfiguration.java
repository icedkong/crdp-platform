package com.fu.framework.crypto;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfiguration {

    @Bean
    public CryptoService cryptoService(CryptoProperties properties) {
        if ("kms".equalsIgnoreCase(properties.getMode())) {
            return new KmsCryptoService();
        }
        if (!"local".equalsIgnoreCase(properties.getMode())) {
            throw new IllegalStateException("未知的 fu.crypto.mode: " + properties.getMode());
        }
        return new LocalCryptoService(properties.getLocalKey());
    }
}
