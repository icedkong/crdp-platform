package com.fu.server.config;

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
                                .title("随访云 API")
                                .version("0.1.0")
                                .description("M0 工程骨架。没有患者业务接口。演示数据全部虚构。"));
    }
}
