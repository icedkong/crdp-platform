package com.crdp.framework.audit;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 被拒操作的全局兜底（audit.md §1：40300/40301/40302/40303 也要写 result=DENIED）。
 *
 * <p>{@link com.crdp.framework.web.GlobalExceptionHandler} 在返回错误响应前调用 {@link
 * #onDenied(ApiException)}：已登录会话的越权请求补一条 DENIED 操作日志（模块取请求路径， 描述为「方法 路径」）。 已被 @AuditLog
 * 切面记录过（{@link AuditMarkers}）或匿名请求不重复写。 写失败只告警——兜底写失败不能把 403 响应换成 500，那会掩盖真实的拒绝原因。
 */
@Component
public class DeniedOperationLogger {

    private static final Logger log = LoggerFactory.getLogger(DeniedOperationLogger.class);

    /** 视为「被拒绝的操作」的错误码：无权限、模块未开通、数据范围缺陷、需要二次认证、宽限期只读。 */
    public static final Set<Integer> DENIED_CODES =
            Set.of(40300, 40301, 40302, 40303, 40304, 40306);

    private final AuditLogger auditLogger;

    public DeniedOperationLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public void onDenied(ApiException ex) {
        if (!DENIED_CODES.contains(ex.code()) || AuditMarkers.deniedRecorded()) {
            return;
        }
        if (CurrentSession.current().isEmpty()) {
            // 匿名请求无操作者可归属；登录失败（40101）也不是「被拒绝的操作」
            return;
        }
        AuditRequestContext.request()
                .ifPresentOrElse(
                        request ->
                                append(
                                        moduleOf(request.getRequestURI()),
                                        request.getMethod() + " " + request.getRequestURI(),
                                        ex),
                        () -> append("unknown", "offline", ex));
    }

    /** 过滤器层拒绝（ApiResourceGuardFilter 的 40300、GraceReadOnlyFilter 的 40306）不经过 全局异常处理器，直接带方法与路径调用。 */
    public void onDenied(ApiException ex, String method, String uri) {
        if (!DENIED_CODES.contains(ex.code()) || AuditMarkers.deniedRecorded()) {
            return;
        }
        if (CurrentSession.current().isEmpty()) {
            return;
        }
        append(moduleOf(uri), method + " " + uri, ex);
    }

    private void append(String module, String description, ApiException ex) {
        try {
            auditLogger.append(
                    AuditEntry.operation(module, "ACCESS_DENIED")
                            .description("被拒绝的请求：" + description)
                            .result(AuditResult.DENIED)
                            .errorCode(ex.code())
                            .reason(ex.getMessage())
                            .build());
        } catch (RuntimeException writeFailure) {
            log.error("denied audit append failed code={}", ex.code(), writeFailure);
        }
    }

    /** /api/{hospital|platform}/{module}/... 取第二段作为模块；解析不出用 unknown。 */
    private static String moduleOf(String uri) {
        if (uri == null) {
            return "unknown";
        }
        String[] segments = uri.split("/");
        // ["", "api", side, module, ...]
        if (segments.length >= 4 && "api".equals(segments[1])) {
            return segments[3];
        }
        return "unknown";
    }
}
