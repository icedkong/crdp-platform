package com.crdp.server.notify;

import com.crdp.followup.notice.app.NotifyDigestService;
import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日汇总作业的定时注册（E09-T05 / A-NTF-005、C-NTF-003、C-NTF-005；验收「08:30 汇总」）。
 *
 * <p>每分钟级 tick（{@code crdp.notify.digest.tick-ms}，默认 60 秒）：逐租户调 {@link
 * NotifyDigestService#poll}，由服务读本院 DAILY_DIGEST 规则的 send_time（默认 08:30）判定是否到点， 到点执行一轮——按人合并 QUEUED
 * 短信、免打扰（默认 21:00–08:00 不发）与每人每日上限（默认 3，超出并入次日）在服务内执行。 租户清单经 {@link TenantDirectory}（启用 / 试用 /
 * 宽限期；停用即不发，PLT-TEN-016），单个租户失败只记 WARN 不影响其他租户。
 *
 * <p>与 {@link OutboxDispatchScheduler} 同口径：定时入口不让异常外溢。
 */
@Component
public class NotifyDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotifyDigestScheduler.class);

    private final NotifyDigestService digest;
    private final TenantDirectory directory;

    public NotifyDigestScheduler(NotifyDigestService digest, TenantDirectory directory) {
        this.digest = digest;
        this.directory = directory;
    }

    @Scheduled(fixedDelayString = "${crdp.notify.digest.tick-ms:60000}")
    public void tick() {
        for (TenantRef tenant : directory.enabledTenants()) {
            try {
                NotifyDigestService.DigestOutcome outcome =
                        TenantContext.callAsTenant(
                                tenant.tenantId(), tenant.tenantCode(), digest::poll);
                if (outcome.status() == NotifyDigestService.DigestOutcome.RunStatus.DONE) {
                    log.info(
                            "每日汇总作业完成 tenant={} quietHours={} recipients={} smsSent={}"
                                    + " smsFailed={} smsDeferred={} smsSkipped={}",
                            tenant.tenantCode(),
                            outcome.quietHours(),
                            outcome.recipients(),
                            outcome.smsSent(),
                            outcome.smsFailed(),
                            outcome.smsDeferred(),
                            outcome.smsSkipped());
                }
            } catch (RuntimeException ex) {
                log.warn("每日汇总作业失败，本租户本轮跳过 tenant={}", tenant.tenantCode(), ex);
            }
        }
    }
}
