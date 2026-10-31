package com.crdp.framework.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 定时任务跑在复用的调度线程上，天然没有请求侧租户：执行前先清掉遗留上下文，防止上一次任务的泄漏被当成
 * 当前租户；执行后（含任务抛错）再清一次，不污染同线程的下一次执行。任务本身不带租户，需要访问数据库时用 {@link TenantContext#runAsTenant}
 * 按租户逐个执行。放在切面链最外层（@Order 最小），保证清理先于、后于其他切面。
 */
@Aspect
@Component
@Order(5)
public class TenantScheduledAspect {

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object aroundScheduledJob(ProceedingJoinPoint joinPoint) throws Throwable {
        TenantContext.clear();
        try {
            return joinPoint.proceed();
        } finally {
            TenantContext.clear();
        }
    }
}
