package com.crdp.server.security;

import com.crdp.framework.audit.DeniedOperationLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.ReauthStore;
import com.crdp.framework.security.RequireReauth;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证闸门（docs/api/auth.md §1 /auth/reauth）。
 *
 * <p>标注 {@link RequireReauth} 的处理方法（或所在控制器），请求必须携带有效的 {@code X-Reauth-Token} 请求头——由 {@code POST
 * /auth/reauth} 密码验证后签发，5 分钟内有效且绑定当前会话（{@link ReauthStore}）。 缺失、过期或换个会话携带一律
 * <b>40304</b>「敏感操作需二次认证」，并按 audit.md §1 写 result=DENIED 操作日志 （拦截器层不经过全局异常处理器，与
 * ApiResourceGuardFilter 同一口径）。
 *
 * <p>顺序：本拦截器在 Spring Security 过滤器链之后执行——权限码（40300）在 ApiResourceGuardFilter 已校验，
 * 二次认证（40304）排在其后：有权限但未重新验证身份才被这里拦下。匿名请求到不了本层（40100）。
 */
@Component
public class ReauthInterceptor implements HandlerInterceptor {

    /** 敏感接口携带二次认证令牌的请求头（与 docs/api/auth.md、平台端约定一致）。 */
    public static final String REAUTH_HEADER = "X-Reauth-Token";

    static final String REAUTH_REQUIRED_MESSAGE = "敏感操作需二次认证，请重新验证身份";

    private final ReauthStore reauthStore;
    private final ApiErrorWriter errors;
    private final DeniedOperationLogger deniedLogger;

    public ReauthInterceptor(
            ReauthStore reauthStore, ApiErrorWriter errors, DeniedOperationLogger deniedLogger) {
        this.reauthStore = reauthStore;
        this.errors = errors;
        this.deniedLogger = deniedLogger;
    }

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (requireReauthOf(handlerMethod) == null) {
            return true;
        }
        SessionPrincipal principal = CurrentSession.current().orElse(null);
        if (principal == null) {
            // 防御式放行：未认证请求由 SecurityConfig 统一 40100，不能在这里被 40304 掩盖
            return true;
        }
        if (reauthStore.isValid(request.getHeader(REAUTH_HEADER), principal)) {
            return true;
        }
        ApiException denied = new ApiException(403, 40304, REAUTH_REQUIRED_MESSAGE);
        deniedLogger.onDenied(denied, request.getMethod(), request.getRequestURI());
        errors.write(response, denied.httpStatus(), denied.code(), denied.getMessage());
        return false;
    }

    /** 方法上的 {@link RequireReauth} 优先，其次类上（整控制器敏感时用）。 */
    private static RequireReauth requireReauthOf(HandlerMethod handlerMethod) {
        RequireReauth onMethod =
                AnnotatedElementUtils.findMergedAnnotation(
                        handlerMethod.getMethod(), RequireReauth.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), RequireReauth.class);
    }
}
