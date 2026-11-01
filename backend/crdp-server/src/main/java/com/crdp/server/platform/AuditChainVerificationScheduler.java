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
 * 验链作业的定时注册（E08-T02 / PLT-AUD-009「定期校验哈希链，断链告警」）。 每天北京时间 03:40 跑一次（避开 00:10 随访任务与 00:20
 * 到期处理）；启动就绪后再补跑一次，停机跨过时间窗也不必等次日。 只读校验 + 追加写结果，幂等，重复执行无副作用；调度线程复用，租户上下文清理由 TenantScheduledAspect
 * 保证。
 */
@Component
public class AuditChainVerificationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AuditChainVerificationScheduler.class);

    private final AuditChainVerificationService verificationService;

    public AuditChainVerificationScheduler(AuditChainVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Shanghai")
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
                    verificationService.runForDate(java.time.LocalDate.now());
            long broken = results.stream().filter(result -> !result.success()).count();
            log.info(
                    "审计哈希链校验完成 trigger={} tenants={} brokenTenants={}",
                    trigger,
                    results.size(),
                    broken);
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢（不影响调度线程的后续任务）；断链详情已按租户 / 表记 ERROR
            log.error("审计哈希链校验执行失败 trigger={}", trigger, ex);
        }
    }
}
