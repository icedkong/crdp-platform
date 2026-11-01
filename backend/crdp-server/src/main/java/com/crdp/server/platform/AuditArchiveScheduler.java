package com.crdp.server.platform;

import com.crdp.framework.job.TenantJobResult;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 归档作业的定时注册（E08-T03 / PLT-AUD-010「在线保留 1 年后转归档」）。 每天北京时间 04:10 跑（03:40 验链完成后，删除分区前
 * 当天链况已知）；启动就绪后再补跑一次（新装环境先补齐月分区）。 幂等：分区维护无缺月不执行 DDL，归档按登记续跑。
 */
@Component
public class AuditArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveScheduler.class);

    private final AuditArchiveService archiveService;

    public AuditArchiveScheduler(AuditArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Shanghai")
    public void daily() {
        run("daily");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        run("startup");
    }

    private void run(String trigger) {
        try {
            List<TenantJobResult> results = archiveService.runForDate(LocalDate.now());
            long failed = results.stream().filter(result -> !result.success()).count();
            log.info(
                    "审计归档完成 trigger={} tenants={} failedTenants={}",
                    trigger,
                    results.size(),
                    failed);
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢；失败分区已记 ERROR（作业结果 FAILED），人工介入
            log.error("审计归档执行失败 trigger={}", trigger, ex);
        }
    }
}
