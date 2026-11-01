package com.crdp.framework.web;

import com.crdp.framework.audit.AuditMarkers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TraceIds.HEADER);
        String traceId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : newId();
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
            // 请求收尾复位审计 DENIED 标记，防止线程池复用把标记带进下一个请求（E08-T01）
            AuditMarkers.clearDenied();
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
