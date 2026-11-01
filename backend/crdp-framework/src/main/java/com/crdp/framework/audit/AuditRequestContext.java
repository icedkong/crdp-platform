package com.crdp.framework.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计用的请求上下文：IP 与终端信息（platform-spec §9 通用字段「IP、终端信息」必填）。
 *
 * <p>IP 口径与登录侧一致（OQ-42）：X-Forwarded-For 首段优先，remoteAddr 兜底； 生产经 ELB/WAF
 * 时由代理改写该头。非请求线程（定时作业等）返回空，审计行 IP 记 NULL。
 */
public final class AuditRequestContext {

    private AuditRequestContext() {}

    public static Optional<HttpServletRequest> request() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(ServletRequestAttributes::getRequest);
    }

    public static String clientIp() {
        return request()
                .map(
                        request -> {
                            String forwarded = request.getHeader("X-Forwarded-For");
                            if (forwarded != null && !forwarded.isBlank()) {
                                return forwarded.split(",")[0].trim();
                            }
                            return request.getRemoteAddr();
                        })
                .orElse(null);
    }

    public static String userAgent() {
        return request().map(request -> request.getHeader("User-Agent")).orElse(null);
    }
}
