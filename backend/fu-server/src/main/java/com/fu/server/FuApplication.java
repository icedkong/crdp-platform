package com.fu.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.fu")
@ConfigurationPropertiesScan("com.fu")
@MapperScan({"com.fu.platform.infra.mapper", "com.fu.system.infra.mapper"})
public class FuApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuApplication.class, args);
    }
}
