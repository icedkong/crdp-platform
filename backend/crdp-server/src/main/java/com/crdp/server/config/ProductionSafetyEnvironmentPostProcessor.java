package com.crdp.server.config;

import com.crdp.framework.crypto.CryptoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/** 在数据源等 Bean 创建之前拒绝未接入的生产 KMS。否则演示库里的本地包装口令会先抛出另一句错误，把 KMS 未接入盖住。 */
public class ProductionSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        CryptoProperties crypto = new CryptoProperties();
        crypto.setMode(environment.getProperty("crdp.crypto.mode", "local"));
        new ProductionSafetyCheck(environment, crypto);
    }
}
