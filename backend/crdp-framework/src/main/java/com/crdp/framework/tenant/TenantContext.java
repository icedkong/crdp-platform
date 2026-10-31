package com.crdp.framework.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/** 请求级租户上下文。平台库与租户库共用路由数据源，调用方必须先放入上下文。 没有上下文时路由数据源直接拒绝，避免查询落到错误的库。 */
public final class TenantContext {

    public static final String PLATFORM_KEY = "platform";

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<State> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static State require() {
        State state = CURRENT.get();
        if (state == null) {
            throw new MissingTenantContextException();
        }
        return state;
    }

    public static void usePlatform() {
        CURRENT.set(State.platformState());
    }

    public static void useTenant(long tenantId, String tenantCode) {
        CURRENT.set(State.tenant(tenantId, tenantCode));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String lookupKey() {
        State state = CURRENT.get();
        if (state == null) {
            return null;
        }
        return state.platform() ? PLATFORM_KEY : "tenant:" + state.tenantId();
    }

    public static <T> T callAsPlatform(Supplier<T> supplier) {
        State previous = CURRENT.get();
        CURRENT.set(State.platformState());
        try {
            return supplier.get();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callAsTenant(long tenantId, String tenantCode, Supplier<T> supplier) {
        State previous = CURRENT.get();
        CURRENT.set(State.tenant(tenantId, tenantCode));
        try {
            return supplier.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAsPlatform(Runnable runnable) {
        callAsPlatform(
                () -> {
                    runnable.run();
                    return null;
                });
    }

    public static void runAsTenant(long tenantId, String tenantCode, Runnable runnable) {
        callAsTenant(
                tenantId,
                tenantCode,
                () -> {
                    runnable.run();
                    return null;
                });
    }

    private static void restore(State previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public record State(boolean platform, Long tenantId, String tenantCode) {
        public static State platformState() {
            return new State(true, null, null);
        }

        public static State tenant(long tenantId, String tenantCode) {
            return new State(false, tenantId, tenantCode);
        }
    }
}
