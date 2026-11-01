package com.crdp.server.security;

import com.crdp.framework.audit.DeniedOperationLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 接口校验链的登记闸门（PLT-PERM-004，E05-T06 验收：未登记接口默认拒绝）。
 *
 * <p>登录态 → 租户状态 → 模块开通 → 权限码 → 数据范围 → 字段策略（platform-spec §4）里，本过滤器守「登记」这一层：
 *
 * <ul>
 *   <li>未登记（plt_api_resource 查无「方法 + 路径」）→ 40307「接口未登记，默认拒绝」；
 *   <li>已登记为公共接口（public_access=1，登录即可访问，如个人中心）→ 放行（匿名与否由 SecurityConfig 决定）；
 *   <li>已登记且绑定权限码 → 会话须持有该权限码，否则 40300（与 @RequiresPermission 同码同口径，这里前置拦截）；
 *   <li>已登记但既未绑定权限码也未标记公共 → 40307（platform-spec：非公共接口未绑定权限码的一律不可访问）。
 * </ul>
 *
 * <p>未认证请求不在本层拒绝（交给 SecurityConfig 统一 40100，防探测口径不变）；OPTIONS 预检与非 /api 路径
 * （actuator、swagger，SecurityConfig 已单列）直接放行。
 */
@Component
public class ApiResourceGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiResourceGuardFilter.class);

    private final ApiResourceRegistry registry;
    private final ApiErrorWriter errors;
    private final DeniedOperationLogger deniedLogger;

    public ApiResourceGuardFilter(
            ApiResourceRegistry registry,
            ApiErrorWriter errors,
            DeniedOperationLogger deniedLogger) {
        this.registry = registry;
        this.errors = errors;
        this.deniedLogger = deniedLogger;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || !uri.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        ApiResourceRegistry.Entry entry = registry.find(request.getMethod(), uri).orElse(null);
        if (entry == null) {
            log.warn("接口未登记被拒 method={} uri={}", request.getMethod(), uri);
            errors.write(response, 403, 40307, "接口未登记，默认拒绝");
            return;
        }
        if (entry.publicAccess()) {
            filterChain.doFilter(request, response);
            return;
        }
        String permCode = entry.permCode();
        if (permCode == null || permCode.isBlank()) {
            log.warn("接口未绑定权限码被拒 method={} uri={}", request.getMethod(), uri);
            errors.write(response, 403, 40307, "接口未绑定权限码，默认拒绝");
            return;
        }
        SessionPrincipal principal = currentPrincipal();
        // 接口校验链顺序（platform-spec §4）：模块开通在权限码之前——模块未开通时放行，
        // 由 @RequireModule 切面返回 40301（否则这里会抢先用 40300 掩盖模块口径）
        if (principal != null
                && principal.hospital()
                && entry.moduleCode() != null
                && !principal.openedModules().contains(entry.moduleCode())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (principal != null && !principal.permissionCodes().contains(permCode)) {
            log.info(
                    "接口权限码校验未过 method={} uri={} perm={} userId={}",
                    request.getMethod(),
                    uri,
                    permCode,
                    principal.userId());
            // E08-T01 / audit.md §1：被拒请求写 result=DENIED 审计（过滤器层不经过全局异常处理器）
            deniedLogger.onDenied(new ApiException(403, 40300, "没有权限"), request.getMethod(), uri);
            errors.write(response, 403, 40300, "没有权限");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static SessionPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            return principal;
        }
        return null;
    }
}
