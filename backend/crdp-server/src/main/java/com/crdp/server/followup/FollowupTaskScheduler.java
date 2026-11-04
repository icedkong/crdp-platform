package com.crdp.server.followup;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskCreationService;
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
 * 随访任务按需创建的定时注册（E14-T04 / A-FU-001、A-NFR-006、C-NFR-007）。每天北京时间 00:10
 * 跑一次（规格建议值：业务时段前创建当天开启的任务）；错过的时间窗由补跑区间兜底（上次成功日 + 1 至当天，ADR 0003）。避开 00:20 的租户到期处理（E05-T03）。
 *
 * <p>应用启动就绪后再补跑一次：停机跨过 00:10 的场景下，当天开启的任务不必等到次日（幂等，uk（入组 + 节点 + 第 N 次）保证重复执行无副作用）。调度线程复用，租户上下文清理由
 * TenantScheduledAspect 保证。
 */
@Component
public class FollowupTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskScheduler.class);

    private final FollowupTaskCreationService creationService;

    public FollowupTaskScheduler(FollowupTaskCreationService creationService) {
        this.creationService = creationService;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Shanghai")
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
                    creationService.runForDate(PlanDateCalculator.businessToday(Instant.now()));
            log.info(
                    "任务创建调度完成 trigger={} tenants={} failed={}",
                    trigger,
                    results.size(),
                    results.stream().filter(result -> !result.success()).count());
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢（不影响调度线程的后续任务），失败详情已按租户记 WARN / job_tenant_run
            log.error("任务创建调度执行失败 trigger={}", trigger, ex);
        }
    }
}
