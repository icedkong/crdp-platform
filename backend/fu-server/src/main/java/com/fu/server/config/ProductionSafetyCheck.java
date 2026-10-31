package com.fu.server.config;

import com.fu.framework.crypto.CryptoProperties;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyCheck {

    public ProductionSafetyCheck(Environment environment, CryptoProperties cryptoProperties) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prod && "local".equalsIgnoreCase(cryptoProperties.getMode())) {
            throw new IllegalStateException("生产环境禁止 fu.crypto.mode=local");
        }
    }
}
