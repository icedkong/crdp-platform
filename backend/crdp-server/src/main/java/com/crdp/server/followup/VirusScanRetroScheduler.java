package com.crdp.server.followup;

import com.crdp.followup.file.app.VirusScanRetroService;
import com.crdp.framework.job.TenantJobResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存量文件病毒补扫的定时注册（E22-T15 / 等保 D-01 整改，tech-plan §10）。每天北京时间 03:20 一趟（ 避开 00:10 任务创建、00:20 租户到期、03:40
 * 审计验链、04:10 审计归档），按租户补扫 {@code sys_file.virus_scan_status='UNSCANNED'} 的存量（ 每趟每租户上限 {@code
 * crdp.file.virus-scan.retro-batch-limit}，默认 200；大存量逐晚推进）。
 *
 * <p>不做启动补跑：补扫不赶时效（存量是扫描启用前的历史文件），避免每次发版都全量触发对象读； 作业幂等（状态守卫 WHERE
 * virus_scan_status='UNSCANNED'），漏趟由下一晚自然接续。扫描未启用（mode=off）时作业体 no-op。
 */
@Component
public class VirusScanRetroScheduler {

    private static final Logger log = LoggerFactory.getLogger(VirusScanRetroScheduler.class);

    private final VirusScanRetroService retroService;

    public VirusScanRetroScheduler(VirusScanRetroService retroService) {
        this.retroService = retroService;
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Shanghai")
    public void daily() {
        try {
            List<TenantJobResult> results = retroService.runRetroScan();
            long failed = results.stream().filter(result -> !result.success()).count();
            if (failed > 0) {
                log.warn("存量病毒补扫有失败租户 count={} 详情={}", failed, results);
            } else {
                log.info("存量病毒补扫调度完成 tenants={}", results.size());
            }
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢（不影响调度线程的后续任务），失败详情已按租户记 job_tenant_run
            log.error("存量病毒补扫调度执行失败", ex);
        }
    }
}
