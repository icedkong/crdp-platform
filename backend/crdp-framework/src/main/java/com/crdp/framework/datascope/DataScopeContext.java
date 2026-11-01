package com.crdp.framework.datascope;

import com.crdp.framework.security.ScopeSnapshot;

/**
 * 由 {@link DataScope} 切面在方法执行期间放入。拦截器只认这个上下文，不从会话里隐式放行。
 *
 * <p>E07-T02 起同时携带 userId：「仅本人负责的患者」的等值分支（responsible_user_id / assignee_id = 本人） 在 SQL
 * 改写时需要，快照本身只存清单与开关。
 */
public final class DataScopeContext {

    private record State(ScopeSnapshot snapshot, long userId) {}

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORED = new ThreadLocal<>();

    private DataScopeContext() {}

    public static void set(ScopeSnapshot snapshot, long userId) {
        CURRENT.set(new State(snapshot, userId));
    }

    public static ScopeSnapshot get() {
        State state = CURRENT.get();
        return state == null ? null : state.snapshot();
    }

    /** 当前登录用户 id；无上下文时 0（无会话本就不会走到改写分支）。 */
    public static long userId() {
        State state = CURRENT.get();
        return state == null ? 0L : state.userId();
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
