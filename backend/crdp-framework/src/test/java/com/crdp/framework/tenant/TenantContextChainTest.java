package com.crdp.framework.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** E02-T01 链路验证：@Async 走带租户装饰器的线程池，@Scheduled 每次执行都从干净上下文开始。 */
class TenantContextChainTest {

    private static AnnotationConfigApplicationContext context;
    private static Probe probe;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext(ChainConfig.class);
        probe = context.getBean(Probe.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void asyncJobsRunOnTheDecoratedPoolAndCarryTheSubmittingContext() {
        TenantContext.useTenant(1L, "demoa");
        CompletableFuture<String> future = probe.captureOnAsyncThread();
        TenantContext.clear();

        String[] threadAndKey = future.join().split("\\|", 2);
        assertTrue(
                threadAndKey[0].startsWith("crdp-async-"),
                "应在带租户装饰器的线程池上执行，实际线程：" + threadAndKey[0]);
        assertEquals("tenant:1", threadAndKey[1]);
    }

    @Test
    void scheduledJobsAlwaysStartFromACleanContextEvenWhenThePreviousRunLeaked() throws Exception {
        assertTrue(probe.awaitTwoRuns(), "定时任务应至少执行两次");

        assertTrue(
                probe.entryKeySnapshot().stream().allMatch(Objects::isNull),
                "每次执行开始时都不应有租户上下文（含上一次泄漏的），实际：" + probe.entryKeySnapshot());
    }

    @Configuration
    @EnableAsync
    @EnableScheduling
    @EnableAspectJAutoProxy
    @Import({AsyncConfiguration.class, SchedulingConfiguration.class, TenantScheduledAspect.class})
    static class ChainConfig {

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    static class Probe {

        private final List<String> entryKeys = new CopyOnWriteArrayList<>();
        private final CountDownLatch twoRuns = new CountDownLatch(2);

        /** 故意不清理租户上下文，模拟写得糟糕的定时任务；切面必须保证下一次执行仍然干净。 */
        @Scheduled(fixedDelay = 20)
        void leakyJob() {
            entryKeys.add(TenantContext.lookupKey());
            TenantContext.useTenant(999L, "leftover");
            twoRuns.countDown();
        }

        @Async
        CompletableFuture<String> captureOnAsyncThread() {
            return CompletableFuture.completedFuture(
                    Thread.currentThread().getName() + "|" + TenantContext.lookupKey());
        }

        boolean awaitTwoRuns() throws InterruptedException {
            return twoRuns.await(5, TimeUnit.SECONDS);
        }

        List<String> entryKeySnapshot() {
            return new ArrayList<>(entryKeys);
        }
    }
}
