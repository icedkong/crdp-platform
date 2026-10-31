package com.fu.framework.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTaskDecoratorTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void copiesTenantContextIntoTheAsyncTaskAndClearsItAfterwards() {
        TenantContext.useTenant(7L, "demoa");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task =
                new TenantContextTaskDecorator()
                        .decorate(() -> seen.set(TenantContext.lookupKey()));

        TenantContext.clear();
        task.run();

        assertEquals("tenant:7", seen.get());
        assertTrue(TenantContext.current().isEmpty());
    }
}
