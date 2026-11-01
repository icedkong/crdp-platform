package com.crdp.server.config;

import com.crdp.server.security.ReauthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 拦截器注册。E04-T08 / PLT-AUTH-012：敏感操作二次认证闸门只挂在 /api/** 业务接口上（actuator、swagger 不在守卫面内），执行顺序在
 * Spring Security 过滤器链之后（权限码校验先于二次认证）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ReauthInterceptor reauthInterceptor;

    public WebMvcConfig(ReauthInterceptor reauthInterceptor) {
        this.reauthInterceptor = reauthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reauthInterceptor).addPathPatterns("/api/**");
    }
}
