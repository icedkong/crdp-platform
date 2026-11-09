package com.crdp.server.security;

import com.crdp.followup.report.app.MobileUploadTokenStore;
import com.crdp.followup.report.app.MobileUploadTokenStore.Payload;
import com.crdp.followup.report.infra.mapper.MobileUploadMapper;
import com.crdp.framework.audit.DeniedOperationLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.server.auth.TenantLookupCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 一次性上传令牌的会话装载（E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006；docs/api/report.md §5）。
 *
 * <p>{@code /api/h5/upload/{token}/**} 公开口（SecurityConfig permitAll）凭**令牌本身**鉴权：令牌载荷绑定签发会话 +
 * 患者，本过滤器按载荷里的 签发会话令牌取**实时会话**（权限 / 数据范围随会话现场判定——权限被收走、会话结束、账号停用都立即失效），把该会话装进 SecurityContext（附加
 * {@link #SESSION_H5} 端别权限，只能进 /api/h5/upload/**，进不了 /api/hospital/**）并设置租户上下文。
 * 服务层随后的 @DataScope、附件归属（上传人 = 签发人）、位置模板版本校验与电脑端创建走同一套。
 *
 * <p>过滤器层的闸门（拿不到控制器注解，直接写错误响应）：令牌不存在 / 已用 → 放行未认证（服务层统一 40101「二维码已过期」）； C 租户 cfg_feature {@code
 * h5_upload_login} 打开后要求手机端已登录本院账号（Bearer 会话，须同租户；未登录 40100 引导登录，C-NFR-006； {@code /context}
 * 引导口豁免——登录表单要先拿租户编码）；签发人模块未开通 40301、无 followup:scan:upload 40300（DENIED 审计）、 强制改密 40305、宽限期只读租户写请求
 * 40306（与 MustChangePasswordFilter / GraceReadOnlyFilter 同口径——它们只认 /api/hospital/** 与既有会话）。
 */
@Component
public class H5UploadTokenFilter extends OncePerRequestFilter {

    /** H5 一次性令牌会话的端别权限：SecurityConfig 只对 /api/h5/upload/** 放行该权限。 */
    public static final String SESSION_H5_AUTHORITY = "SESSION_H5";

    /** 引导口（登录表单租户预填）：豁免手机端登录要求。 */
    private static final String CONTEXT_SUFFIX = "/context";

    private static final String PREFIX = "/api/h5/upload/";

    private static final String UPLOAD_PERMISSION = "followup:scan:upload";

    private static final Logger log = LoggerFactory.getLogger(H5UploadTokenFilter.class);

    private final MobileUploadTokenStore tokens;
    private final SessionStore sessionStore;
    private final MobileUploadMapper mapper;
    private final TenantLookupCache tenantLookup;
    private final ApiErrorWriter errors;
    private final DeniedOperationLogger deniedLogger;

    public H5UploadTokenFilter(
            MobileUploadTokenStore tokens,
            SessionStore sessionStore,
            MobileUploadMapper mapper,
            TenantLookupCache tenantLookup,
            ApiErrorWriter errors,
            DeniedOperationLogger deniedLogger) {
        this.tokens = tokens;
        this.sessionStore = sessionStore;
        this.mapper = mapper;
        this.tenantLookup = tenantLookup;
        this.errors = errors;
        this.deniedLogger = deniedLogger;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = tokenOf(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Payload payload = tokens.find(token);
        // 令牌不存在 / 已提交：不装载会话，放行给服务层统一 40101（防探测口径）
        if (payload == null || !MobileUploadTokenStore.STATUS_PENDING.equals(payload.status())) {
            filterChain.doFilter(request, response);
            return;
        }
        SessionPrincipal issuer =
                sessionStore
                        .find(payload.issuerToken())
                        .filter(
                                p ->
                                        p.hospital()
                                                && Objects.equals(p.tenantId(), payload.tenantId()))
                        .orElse(null);
        if (issuer == null) {
            // 签发人会话已结束（登出 / 过期）：二维码随会话失效，同样按过期口径放行
            filterChain.doFilter(request, response);
            return;
        }
        boolean loginRequired =
                Boolean.TRUE.equals(
                        TenantContext.callAsTenant(
                                payload.tenantId(),
                                payload.tenantCode(),
                                mapper::findLoginRequired));
        if (loginRequired && !request.getRequestURI().endsWith(CONTEXT_SUFFIX)) {
            Authentication phone = SecurityContextHolder.getContext().getAuthentication();
            if (!sameTenantHospitalSession(phone, payload)) {
                log.info("H5 上传要求手机端登录本院账号被拒 uri={}", request.getRequestURI());
                errors.write(response, 401, 40100, "请先登录本院账号后再上传");
                return;
            }
        }
        if (!issuer.openedModules().contains("followup")) {
            deniedLogger.onDenied(
                    new ApiException(403, 40301, "模块未开通，请联系平台"),
                    request.getMethod(),
                    request.getRequestURI());
            errors.write(response, 403, 40301, "模块未开通，请联系平台");
            return;
        }
        if (!issuer.permissionCodes().contains(UPLOAD_PERMISSION)) {
            log.info("H5 上传签发人权限被收回 uri={} userId={}", request.getRequestURI(), issuer.userId());
            deniedLogger.onDenied(
                    new ApiException(403, 40300, "没有权限"),
                    request.getMethod(),
                    request.getRequestURI());
            errors.write(response, 403, 40300, "没有权限");
            return;
        }
        if (issuer.mustChangePassword()) {
            errors.write(response, 403, 40305, "首次登录或密码过期，必须先修改密码");
            return;
        }
        if (graceReadOnlyBlocked(request, payload)) {
            deniedLogger.onDenied(
                    new ApiException(403, 40306, "宽限期只读，禁止写入"),
                    request.getMethod(),
                    request.getRequestURI());
            errors.write(response, 403, 40306, "宽限期只读，禁止写入，请联系平台续费");
            return;
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(SESSION_H5_AUTHORITY));
        issuer.permissionCodes().stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(issuer, null, authorities));
        TenantContext.useTenant(issuer.tenantId(), issuer.tenantCode());
        filterChain.doFilter(request, response);
    }

    /** 取路径令牌：/api/h5/upload/{token} 与 /api/h5/upload/{token}/context|files|submit。 */
    private static String tokenOf(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())
                && !HttpMethod.POST.matches(request.getMethod())) {
            return null;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith(PREFIX)) {
            return null;
        }
        String rest = uri.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String token = slash < 0 ? rest : rest.substring(0, slash);
        return token.isBlank() ? null : token;
    }

    private static boolean sameTenantHospitalSession(Authentication phone, Payload payload) {
        return phone != null
                && phone.getPrincipal() instanceof SessionPrincipal principal
                && principal.hospital()
                && Objects.equals(principal.tenantId(), payload.tenantId());
    }

    /** 宽限期只读：写请求（files / submit）拒绝（与 GraceReadOnlyFilter 同口径，令牌租户）。 */
    private boolean graceReadOnlyBlocked(HttpServletRequest request, Payload payload) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        TenantRecord tenant = tenantLookup.findByCode(payload.tenantCode()).orElse(null);
        return tenant != null && "GRACE".equals(tenant.status()) && tenant.graceReadOnly();
    }
}
