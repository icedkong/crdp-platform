package com.crdp.server.config;

import com.crdp.framework.crypto.CryptoProperties;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyCheck {

    public ProductionSafetyCheck(Environment environment, CryptoProperties cryptoProperties) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prod && "local".equalsIgnoreCase(cryptoProperties.getMode())) {
            throw new IllegalStateException("生产环境禁止 crdp.crypto.mode=local");
        }
    }
}
