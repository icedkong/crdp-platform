package com.crdp.framework.audit;

/**
 * 请求内的审计标记：@AuditLog 切面已写过 DENIED 记录时置位， {@link DeniedOperationLogger}
 * 全局兜底据此跳过同一条被拒请求，避免一次拒绝写两条审计。切面在 finally 里清位， 请求线程复用不会串请求。
 */
public final class AuditMarkers {

    private static final ThreadLocal<Boolean> DENIED_RECORDED = new ThreadLocal<>();

    private AuditMarkers() {}

    public static void markDeniedRecorded() {
        DENIED_RECORDED.set(Boolean.TRUE);
    }

    public static boolean deniedRecorded() {
        return Boolean.TRUE.equals(DENIED_RECORDED.get());
    }

    public static void clearDenied() {
        DENIED_RECORDED.remove();
    }
}
