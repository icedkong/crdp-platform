package com.crdp.framework.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务调度线程池。线程数为 1，任务串行执行，与 Spring Boot 默认一致；调度线程复用，因此任务开始与 结束时的租户上下文清理由 {@link
 * TenantScheduledAspect} 保证。任务按租户逐个执行用 {@code TenantContext.runAsTenant}；执行记录与补跑框架是
 * E02-T06（job_tenant_run）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("crdp-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
