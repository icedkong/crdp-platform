package com.crdp.framework.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantScheduledAspectTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void clearsLeftoverContextBeforeAndAfterTheJob() throws Throwable {
        TenantContext.useTenant(999L, "leftover");
        List<String> seenInside = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            seenInside.add(TenantContext.lookupKey());
                            return null;
                        });

        new TenantScheduledAspect().aroundScheduledJob(joinPoint);

        assertEquals(1, seenInside.size());
        assertNull(seenInside.get(0), "任务执行时不得看到调度线程上遗留的租户");
        assertTrue(TenantContext.current().isEmpty(), "执行完必须清理");
    }

    @Test
    void clearsContextEvenWhenTheJobThrows() throws Throwable {
        TenantContext.useTenant(1L, "demoa");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("job failed"));

        assertThrows(
                IllegalStateException.class,
                () -> new TenantScheduledAspect().aroundScheduledJob(joinPoint));

        assertTrue(TenantContext.current().isEmpty(), "任务抛错也必须清理");
    }

    @Test
    void jobVisitsEachTenantExplicitlyAndLeavesNoResidueBetweenVisits() throws Throwable {
        List<String> visits = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            TenantContext.runAsTenant(
                                    1L, "demoa", () -> visits.add(TenantContext.lookupKey()));
                            visits.add(TenantContext.lookupKey());
                            TenantContext.runAsTenant(
                                    2L, "demob", () -> visits.add(TenantContext.lookupKey()));
                            visits.add(TenantContext.lookupKey());
                            return null;
                        });

        new TenantScheduledAspect().aroundScheduledJob(joinPoint);

        assertEquals(
                Arrays.asList("tenant:1", null, "tenant:2", null),
                visits,
                "按租户逐个执行，两次访问之间与结束后都不残留租户");
    }
}
