package com.crdp.server.platform;

import com.crdp.framework.job.TenantJobResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期处理的定时注册（E05-T03 / PLT-TEN-012）。每天北京时间 00:20 跑一次：到期转宽限与提醒尽量赶在业务时段前完成， 也避开 00:10 的随访任务调度（E14-T04
 * 建议值）；错过的时间窗由补跑区间兜底（上次成功日 + 1 至当天，ADR 0003）。
 *
 * <p>应用启动就绪后再补跑一次：停机跨过 00:20 的场景下，宽限期满的自动停用不必等到次日（幂等，重复执行无副作用）。 调度线程复用，租户上下文清理由
 * TenantScheduledAspect 保证。
 */
@Component
public class TenantExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenantExpiryScheduler.class);

    private final TenantExpiryService expiryService;

    public TenantExpiryScheduler(TenantExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Shanghai")
    public void daily() {
        run("daily");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        run("startup");
    }

    private void run(String trigger) {
        try {
            List<TenantJobResult> results = expiryService.runDaily();
            log.info(
                    "到期处理完成 trigger={} tenants={} failed={}",
                    trigger,
                    results.size(),
                    results.stream().filter(result -> !result.success()).count());
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢（不影响调度线程的后续任务），失败详情已按租户记 WARN
            log.error("到期处理执行失败 trigger={}", trigger, ex);
        }
    }
}
