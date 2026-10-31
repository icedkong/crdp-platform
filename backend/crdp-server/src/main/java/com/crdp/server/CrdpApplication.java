package com.crdp.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.crdp")
@ConfigurationPropertiesScan("com.crdp")
@MapperScan({"com.crdp.platform.infra.mapper", "com.crdp.system.infra.mapper"})
public class CrdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrdpApplication.class, args);
    }
}
