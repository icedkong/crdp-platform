package com.crdp.server.security;

import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final SessionStore sessionStore;

    public TokenAuthenticationFilter(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = bearer(request.getHeader("Authorization"));
        if (token != null) {
            sessionStore
                    .find(token)
                    .ifPresent(
                            principal -> {
                                List<SimpleGrantedAuthority> authorities =
                                        principal.permissionCodes().stream()
                                                .map(SimpleGrantedAuthority::new)
                                                .toList();
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

    private static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
