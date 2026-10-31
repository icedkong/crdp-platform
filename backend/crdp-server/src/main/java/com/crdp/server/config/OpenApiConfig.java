package com.crdp.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI followupOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("临床科研数据一体化平台 API")
                                .version("0.1.0")
                                .description("临床科研数据一体化平台（crdp）M0 工程骨架。没有患者业务接口。演示数据全部虚构。"));
    }
}
