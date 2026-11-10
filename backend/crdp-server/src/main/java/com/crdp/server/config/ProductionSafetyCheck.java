package com.crdp.server.config;

import com.crdp.framework.crypto.CryptoProperties;
import com.crdp.framework.crypto.KmsKeyProvider;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyCheck {

    public ProductionSafetyCheck(Environment environment, CryptoProperties cryptoProperties) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prod) {
            return;
        }
        String mode = cryptoProperties.getMode();
        if ("local".equalsIgnoreCase(mode)) {
            throw new IllegalStateException(
                    "生产环境禁止 crdp.crypto.mode=local。演示请使用 --spring.profiles.active=demo（仅虚构数据，接入华为云 KMS 前禁止导入真实患者）。");
        }
        if ("kms".equalsIgnoreCase(mode) && !new KmsKeyProvider().connected()) {
            throw new IllegalStateException(
                    "生产剖面要求华为云 KMS（crdp.crypto.mode=kms），但 KmsKeyProvider 尚未接入，进程拒绝启动，避免第一次加密才返回 501。"
                            + "演示与虚构数据请使用 --spring.profiles.active=demo。"
                            + "接入 KMS 后让 KmsKeyProvider.connected() 返回 true。"
                            + "接入之前禁止导入真实患者数据。");
        }
    }
}
