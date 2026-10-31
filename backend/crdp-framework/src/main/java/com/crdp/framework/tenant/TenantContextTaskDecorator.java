package com.crdp.framework.tenant;

import org.springframework.core.task.TaskDecorator;

/** 把当前租户上下文复制到异步线程，执行完清理，避免线程池串租户。 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.State captured = TenantContext.current().orElse(null);
        return () -> {
            TenantContext.State previous = TenantContext.current().orElse(null);
            apply(captured);
            try {
                runnable.run();
            } finally {
                apply(previous);
            }
        };
    }

    private static void apply(TenantContext.State state) {
        if (state == null) {
            TenantContext.clear();
        } else if (state.platform()) {
            TenantContext.usePlatform();
        } else {
            TenantContext.useTenant(state.tenantId(), state.tenantCode());
        }
    }
}
