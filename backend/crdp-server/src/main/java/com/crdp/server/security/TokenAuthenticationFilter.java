package com.crdp.server.security;

import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionRefresher;
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
import java.util.Optional;
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
 *
 * <p>E04-T06 / PLT-PERM-010 权限变更生效：会话里的权限版本号落后于 {@link PermissionVersionStore} 当前版本时，先按数据库最新授权重算会话
 * （同一令牌、新权限），旧令牌里的权限快照自此失效；账号已停用 / 不存在则会话删除、按未认证处理。 E04-T06 / PLT-AUTH-008 滑动续期：每个已认证请求刷新最后活跃时间，空闲
 * / 绝对超时的会话由 {@link SessionStore#find} 判定失效（40100）。
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** 平台会话的端别权限，SecurityConfig 用它约束 /api/platform/** 只接受平台会话。 */
    public static final String SESSION_PLATFORM_AUTHORITY = "SESSION_PLATFORM";

    /** 医院会话的端别权限，SecurityConfig 用它约束 /api/hospital/** 只接受医院会话。 */
    public static final String SESSION_HOSPITAL_AUTHORITY = "SESSION_HOSPITAL";

    /**
     * 租户视角会话的端别权限（E20-T04 / PLT-VIEW-002、003）：SecurityConfig 只对医院端「装壳」接口 （me / codes / menus /
     * view/session）放行该权限；其余医院端业务接口仍要求医院会话—— 视角令牌对业务接口保持默认拒绝（E20-T03 执行点放开）。
     */
    public static final String SESSION_VIEW_AUTHORITY = "SESSION_VIEW";

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    private final SessionStore sessionStore;
    private final TenantIdentifier tenantIdentifier;
    private final PermissionVersionStore permissionVersions;
    private final SessionRefresher sessionRefresher;

    public TokenAuthenticationFilter(
            SessionStore sessionStore,
            TenantIdentifier tenantIdentifier,
            PermissionVersionStore permissionVersions,
            SessionRefresher sessionRefresher) {
        this.sessionStore = sessionStore;
        this.tenantIdentifier = tenantIdentifier;
        this.permissionVersions = permissionVersions;
        this.sessionRefresher = sessionRefresher;
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
                    .map(this::refreshStalePermissions)
                    .filter(java.util.Objects::nonNull)
                    .map(sessionStore::touch)
                    .ifPresent(
                            principal -> {
                                List<SimpleGrantedAuthority> authorities = authorities(principal);
                                var authentication =
                                        new UsernamePasswordAuthenticationToken(
                                                principal, null, authorities);
                                SecurityContextHolder.getContext()
                                        .setAuthentication(authentication);
                                if (principal.hospital() || principal.tenantView()) {
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

    /** 权限版本落后：按数据库当前授权重算会话内容（令牌不变），随后请求即以新权限执行； 重算发现账号已停用 / 不存在 → 删除会话并返回 null（本次及后续请求都按未认证处理）。 */
    private SessionPrincipal refreshStalePermissions(SessionPrincipal principal) {
        long current = permissionVersions.current(principal);
        if (principal.permissionVersion() >= current) {
            return principal;
        }
        Optional<SessionPrincipal> refreshed = sessionRefresher.refresh(principal);
        if (refreshed.isEmpty()) {
            sessionStore.delete(principal.token());
            log.info(
                    "session dropped after permission change side={} userId={} reason=account-unavailable",
                    principal.sessionType(),
                    principal.userId());
            return null;
        }
        SessionPrincipal active = refreshed.get();
        sessionStore.save(active);
        log.info(
                "session permissions refreshed side={} userId={} version {} -> {}",
                principal.sessionType(),
                principal.userId(),
                principal.permissionVersion(),
                active.permissionVersion());
        return active;
    }

    /** 权限码 + 会话端别权限；端别权限供 SecurityConfig 做跨端拒绝（PLT-AUTH-005）。 */
    private static List<SimpleGrantedAuthority> authorities(SessionPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (principal.tenantView()) {
            // 租户视角会话（E20-T04）：只带 SESSION_VIEW——SecurityConfig 仅对医院端装壳接口
            // （me / codes / menus / view/session）放行；业务接口仍要求医院会话（fail closed，E20-T03 放开）。
            authorities.add(new SimpleGrantedAuthority(SESSION_VIEW_AUTHORITY));
        } else {
            authorities.add(
                    new SimpleGrantedAuthority(
                            principal.hospital()
                                    ? SESSION_HOSPITAL_AUTHORITY
                                    : SESSION_PLATFORM_AUTHORITY));
        }
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
