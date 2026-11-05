package com.crdp.server.notify;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.system.webhook.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Webhook 投递轮询作业（E09-T06）：每 tick（{@code crdp.webhook.delivery.tick-ms}，默认 15 秒）逐租户投递 到点的
 * sys_webhook_delivery 行（PENDING 且 next_attempt_at 已到）。租户清单经 {@link TenantDirectory}（启用 / 试用 /
 * 宽限期，停用租户不投），单个租户失败只记 WARN 不影响其他租户——与 {@link NotifyDigestScheduler} 同口径。
 */
@Component
public class WebhookDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);

    private final WebhookDeliveryService delivery;
    private final TenantDirectory directory;

    public WebhookDeliveryScheduler(WebhookDeliveryService delivery, TenantDirectory directory) {
        this.delivery = delivery;
        this.directory = directory;
    }

    @Scheduled(fixedDelayString = "${crdp.webhook.delivery.tick-ms:15000}")
    public void tick() {
        for (TenantRef tenant : directory.enabledTenants()) {
            try {
                int delivered =
                        TenantContext.callAsTenant(
                                tenant.tenantId(), tenant.tenantCode(), delivery::poll);
                if (delivered > 0) {
                    log.info("Webhook 投递轮询完成 tenant={} rows={}", tenant.tenantCode(), delivered);
                }
            } catch (RuntimeException ex) {
                log.warn("Webhook 投递轮询失败，本租户本轮跳过 tenant={}", tenant.tenantCode(), ex);
            }
        }
    }
}
