package com.crdp.server.security;

import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.auth.TenantIdentifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 会话链路（E04-T02）：逐请求按令牌查服务端会话，会话被删除（登出 / 吊销）后令牌立即失效。 令牌所属租户与访问入口子域名必须一致（PLT-AUTH-004，E04-T01
 * 移交）：demoa 的令牌不能在 demob 的子域名入口使用；不一致时按未认证处理，由入口点统一返回 40100，不暴露差异。
 *
 * <p>端别隔离（E04-T03 / PLT-AUTH-005）：认证时按会话端别附加 {@link #SESSION_PLATFORM_AUTHORITY} / {@link
 * #SESSION_HOSPITAL_AUTHORITY}，由 SecurityConfig 的路径规则拒绝跨端令牌（40300）； 控制器层的 {@code
 * CurrentSession.requirePlatform/requireHospital} 保留为内层防线。
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** 平台会话的端别权限，SecurityConfig 用它约束 /api/platform/** 只接受平台会话。 */
    public static final String SESSION_PLATFORM_AUTHORITY = "SESSION_PLATFORM";

    /** 医院会话的端别权限，SecurityConfig 用它约束 /api/hospital/** 只接受医院会话。 */
    public static final String SESSION_HOSPITAL_AUTHORITY = "SESSION_HOSPITAL";

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    private final SessionStore sessionStore;
    private final TenantIdentifier tenantIdentifier;

    public TokenAuthenticationFilter(SessionStore sessionStore, TenantIdentifier tenantIdentifier) {
        this.sessionStore = sessionStore;
        this.tenantIdentifier = tenantIdentifier;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = bearer(request.getHeader("Authorization"));
        if (token != null) {
            sessionStore
                    .find(token)
                    .filter(principal -> matchesEntryHost(principal, request))
                    .ifPresent(
                            principal -> {
                                List<SimpleGrantedAuthority> authorities = authorities(principal);
                                var authentication =
                                        new UsernamePasswordAuthenticationToken(
                                                principal, null, authorities);
                                SecurityContextHolder.getContext()
                                        .setAuthentication(authentication);
                                if (principal.hospital()) {
                                    TenantContext.useTenant(
                                            principal.tenantId(), principal.tenantCode());
                                } else {
                                    TenantContext.usePlatform();
                                }
                            });
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    /** 权限码 + 会话端别权限；端别权限供 SecurityConfig 做跨端拒绝（PLT-AUTH-005）。 */
    private static List<SimpleGrantedAuthority> authorities(SessionPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(
                new SimpleGrantedAuthority(
                        principal.hospital()
                                ? SESSION_HOSPITAL_AUTHORITY
                                : SESSION_PLATFORM_AUTHORITY));
        principal.permissionCodes().stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return authorities;
    }

    /** 入口是租户子域名时，会话必须是该租户的医院会话；其余入口（IP、API 域名、基域名）不约束。 */
    private boolean matchesEntryHost(SessionPrincipal principal, HttpServletRequest request) {
        String hostTenant = tenantIdentifier.codeFromHost(request.getHeader(HttpHeaders.HOST));
        if (hostTenant == null) {
            return true;
        }
        boolean matches = principal.hospital() && hostTenant.equals(principal.tenantCode());
        if (!matches) {
            log.warn(
                    "会话与入口子域名不一致，按未登录拒绝 sessionType={} entryTenant={}",
                    principal.sessionType(),
                    hostTenant);
        }
        return matches;
    }

    private static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
