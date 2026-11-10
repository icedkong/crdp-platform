package com.crdp.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** local / demo 启动时说明：本地加密与模拟短信只服务虚构数据。 */
@Component
@Profile({"local", "demo"})
@Order(0)
public class DemoModeNotice implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoModeNotice.class);

    @Override
    public void run(ApplicationArguments args) {
        log.warn(
                "演示模式：敏感字段使用本地 AES-256-GCM 信封加密（不是明文，也不是华为云 KMS）。"
                        + "短信为模拟发送，不离开本机，验证码见日志「模拟短信」或 http://127.0.0.1:18080/dev/mock-sms 。"
                        + "仅可使用虚构数据。接入华为云 KMS 之前禁止导入真实患者数据。");
    }
}
