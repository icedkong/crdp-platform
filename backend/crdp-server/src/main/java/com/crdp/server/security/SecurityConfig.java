package com.crdp.server.security;

import com.crdp.server.config.CorsProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenAuthenticationFilter tokenFilter,
            MustChangePasswordFilter mustChangePasswordFilter,
            GraceReadOnlyFilter graceReadOnlyFilter,
            ApiResourceGuardFilter apiResourceGuardFilter,
            ApiErrorWriter errors)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(
                                                (request, response, ex) ->
                                                        errors.write(
                                                                response, 401, 40100, "未登录或登录已失效"))
                                        .accessDeniedHandler(
                                                (request, response, ex) ->
                                                        errors.write(response, 403, 40300, "没有权限")))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.POST,
                                                "/api/hospital/auth/login",
                                                "/api/platform/auth/login")
                                        .permitAll()
                                        // E04-T04 / PLT-AUTH-002、003：短信登录与双因素第二步。
                                        // 公开口必须限流（AGENTS.md 第 6 节）：
                                        // /auth/sms 有 60 秒冷却 + 每日上限 + IP 层由网关/ ingress 限流；
                                        // /login/sms 与 /2fa/verify 走 LoginRateLimiter / 验证码错误上限。
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/hospital/auth/sms",
                                                "/api/hospital/auth/login/sms",
                                                "/api/hospital/auth/2fa/verify",
                                                "/api/platform/auth/2fa/verify")
                                        .permitAll()
                                        // E04-T05 / PLT-AUTH-001、007：图形验证码取图（公开口，
                                        // CaptchaStore 按 IP 每小时限流；登录时验证码错误上限同 42900）
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/hospital/auth/captcha",
                                                "/api/platform/auth/captcha")
                                        .permitAll()
                                        // E06-T11 / PLT-CFG-008、PLT-AUTH-004：登录页租户识别
                                        // （显示名称 / Logo / 背景色 / 主题色）。公开口按 IP 每小时限流
                                        // （LoginBrandService，超限 42900）；未知租户返回通用默认值不暴露存在性
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/hospital/auth/tenant")
                                        .permitAll()
                                        // E04-T07 / PLT-AUTH-011：忘记密码（免登录）。公开口限流同上：
                                        // /password/forgot 有 60 秒冷却 + 每日上限（未知账号也消耗冷却位，
                                        // 防探测）；/password/reset 走 LoginRateLimiter（连续失败 42900）
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/hospital/auth/password/forgot",
                                                "/api/hospital/auth/password/reset")
                                        .permitAll()
                                        // E04-T03 / PLT-AUTH-005：平台端与医院端接口前缀各自只接受
                                        // 对应端的会话（令牌由 TokenAuthenticationFilter 附带端别权限），
                                        // 跨端令牌一律 40300，不依赖各控制器自行调用 require* 系列
                                        .requestMatchers("/api/platform/**")
                                        .hasAnyAuthority(
                                                TokenAuthenticationFilter
                                                        .SESSION_PLATFORM_AUTHORITY)
                                        .requestMatchers("/api/hospital/**")
                                        .hasAnyAuthority(
                                                TokenAuthenticationFilter
                                                        .SESSION_HOSPITAL_AUTHORITY)
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info")
                                        .permitAll()
                                        .requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                // E04-T05 / PLT-AUTH-010：强制改密会话除改密 / 登出 / me 外一律 40305（放在令牌认证之后）
                .addFilterAfter(mustChangePasswordFilter, TokenAuthenticationFilter.class)
                // E05-T03 / PLT-TEN-011、012：宽限期只读的租户，医院端写请求一律 40306（读照常、auth 前缀放行）
                .addFilterAfter(graceReadOnlyFilter, MustChangePasswordFilter.class)
                // E05-T06 / PLT-PERM-004 验收「未登记接口默认拒绝」：/api/** 必须登记在
                // plt_api_resource（公共或绑定权限码），否则 40307；放在认证之后（要读会话权限码）、
                // 端别与 permitAll 判定之前（登记缺失是部署缺陷，先于 40100/40300 暴露）
                .addFilterAfter(apiResourceGuardFilter, GraceReadOnlyFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(properties.getAllowedOriginPatterns());
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
