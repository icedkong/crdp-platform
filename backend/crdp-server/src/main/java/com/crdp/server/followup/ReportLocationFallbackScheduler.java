package com.crdp.server.followup;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.report.app.ReportLocationFallbackService;
import com.crdp.framework.job.TenantJobResult;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 模板调整后的位置退回作业的定时注册（E16-T15 / A-ATT-021、C-SCAN-021）。每天北京时间 00:25 跑一次（避开 00:10 的任务创建与 00:20
 * 的租户到期处理）；应用启动就绪后再补跑一次——模板调整当天不必等到次日（巡检幂等，重复执行结果不变）。 调度线程复用，租户上下文清理由 TenantScheduledAspect 保证。
 */
@Component
public class ReportLocationFallbackScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ReportLocationFallbackScheduler.class);

    private final ReportLocationFallbackService fallbackService;

    public ReportLocationFallbackScheduler(ReportLocationFallbackService fallbackService) {
        this.fallbackService = fallbackService;
    }

    @Scheduled(cron = "0 25 0 * * *", zone = "Asia/Shanghai")
    public void daily() {
        run("daily");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        run("startup");
    }

    private void run(String trigger) {
        try {
            List<TenantJobResult> results =
                    fallbackService.runForDate(PlanDateCalculator.businessToday(Instant.now()));
            log.info(
                    "报告单位置退回调度完成 trigger={} tenants={} failed={}",
                    trigger,
                    results.size(),
                    results.stream().filter(result -> !result.success()).count());
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢（不影响调度线程的后续任务），失败详情已按租户记 WARN / job_tenant_run
            log.error("报告单位置退回调度执行失败 trigger={}", trigger, ex);
        }
    }
}
