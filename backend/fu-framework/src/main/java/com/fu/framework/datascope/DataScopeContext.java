package com.fu.framework.datascope;

import com.fu.framework.security.ScopeSnapshot;

/** 由 {@link DataScope} 切面在方法执行期间放入。拦截器只认这个上下文，不从会话里隐式放行。 */
public final class DataScopeContext {

    private static final ThreadLocal<ScopeSnapshot> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORED = new ThreadLocal<>();

    private DataScopeContext() {}

    public static void set(ScopeSnapshot snapshot) {
        CURRENT.set(snapshot);
    }

    public static ScopeSnapshot get() {
        return CURRENT.get();
    }

    public static void ignore() {
        IGNORED.set(Boolean.TRUE);
    }

    public static boolean ignored() {
        return Boolean.TRUE.equals(IGNORED.get());
    }

    public static void clear() {
        CURRENT.remove();
        IGNORED.remove();
    }
}
