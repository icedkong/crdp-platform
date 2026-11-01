package com.crdp.server.security;

import com.crdp.framework.security.SessionPrincipal;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.server.auth.TenantLookupCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * E05-T03 / PLT-TEN-011、012：宽限期配置为只读的租户，医院端写请求（POST / PUT / PATCH / DELETE）一律 40306
 * 「宽限期只读，禁止写入」，读请求照常（宽限期默认正常使用，只显示续费提示）。
 *
 * <p>租户状态与只读标记走 {@link TenantLookupCache}（60 秒本地缓存，正负都缓存）：本节点的状态流转（E05-T03 的人工与自动流转都会 调用 {@code
 * TenantLookupCache.evict}）即时生效，其他节点至多 60 秒收敛（M1 单实例，ADR 0003）。
 * 身份类接口（/api/hospital/auth/**）放行——改密、登出是会话自救手段，不属于业务的新增修改删除； 平台端与未认证请求不受影响。
 */
@Component
public class GraceReadOnlyFilter extends OncePerRequestFilter {

    /** 登录即可用的身份接口前缀（与 MustChangePasswordFilter 的放行口径一致）。 */
    private static final String AUTH_PREFIX = "/api/hospital/auth/";

    private static final Set<HttpMethod> WRITES =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private static final Logger log = LoggerFactory.getLogger(GraceReadOnlyFilter.class);

    private final ApiErrorWriter errors;

    private final TenantLookupCache tenantLookup;

    public GraceReadOnlyFilter(ApiErrorWriter errors, TenantLookupCache tenantLookup) {
        this.errors = errors;
        this.tenantLookup = tenantLookup;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (blocked(request)) {
            log.info("宽限期只读拦截 method={} uri={}", request.getMethod(), request.getRequestURI());
            errors.write(response, 403, 40306, "宽限期只读，禁止写入，请联系平台续费");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean blocked(HttpServletRequest request) {
        if (!WRITES.contains(HttpMethod.valueOf(request.getMethod()))) {
            return false;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/hospital/") || uri.startsWith(AUTH_PREFIX)) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)
                || !principal.hospital()) {
            return false;
        }
        TenantRecord tenant = tenantLookup.findByCode(principal.tenantCode()).orElse(null);
        return tenant != null && "GRACE".equals(tenant.status()) && tenant.graceReadOnly();
    }
}
