package com.crdp.server.security;

import com.crdp.framework.security.SessionPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E04-T05 / PLT-AUTH-010：会话带 mustChangePassword（初始密码、管理员重置后、密码到期）时， 除改密 / 登出 / me 外的接口一律
 * 40305「首次登录或密码已过期，请先修改密码」，放在 TokenAuthenticationFilter 之后按端别路径放行。
 *
 * <p>放行清单与 docs/api/auth.md 第 2 节一致：POST /api/{hospital,platform}/auth/password、 POST
 * /api/{hospital,platform}/auth/logout、GET /api/{hospital,platform}/auth/me。 前端登录后按
 * me.mustChangePassword 跳 /password/change，不再拉取 codes / menus（二者同样返回 40305）。
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /** 允许的「方法 + 路径」精确匹配。 */
    private static final Set<String> ALLOWED =
            Set.of(
                    "POST /api/hospital/auth/password",
                    "POST /api/hospital/auth/logout",
                    "GET /api/hospital/auth/me",
                    "POST /api/platform/auth/password",
                    "POST /api/platform/auth/logout",
                    "GET /api/platform/auth/me");

    private final ApiErrorWriter errors;

    public MustChangePasswordFilter(ApiErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SessionPrincipal principal
                && principal.mustChangePassword()
                && !allowed(request)) {
            errors.write(response, 403, 40305, "首次登录或密码已过期，请先修改密码");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean allowed(HttpServletRequest request) {
        String method = HttpMethod.valueOf(request.getMethod()).name();
        return ALLOWED.contains(method + " " + request.getRequestURI());
    }
}
