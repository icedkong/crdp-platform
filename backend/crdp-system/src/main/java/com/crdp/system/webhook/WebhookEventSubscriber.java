package com.crdp.system.webhook;

import com.crdp.framework.outbox.OutboxEvent;
import com.crdp.framework.outbox.OutboxSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * sys_outbox 的 Webhook 订阅方（E09-T06 / A-NTF-008、C-NTF-008）：把订阅事件排入 Webhook 投递队列。
 *
 * <p>与 {@code NotifyEventSubscriber}（站内信 / 短信）平级、互不影响——同一事件两边各自幂等（本侧幂等键是 (webhook_id,
 * outbox_event_id)）。只做入队（INSERT），HTTP 出站在 {@code WebhookDeliveryScheduler}
 * 的轮询里执行，不阻塞分发线程（OutboxSubscriber 契约第 3 条）。
 *
 * <p>RECORD_SUBMITTED / PATIENT_DEAD 的事件产生方随 E15 / 患者域落地后自动接入（事件编码即订阅编码）； 本院是否关心某事件（如 C
 * 不订阅患者死亡）是配置面的事，代码不写租户分支（AGENTS §5.7）。
 */
@Component
public class WebhookEventSubscriber implements OutboxSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventSubscriber.class);

    private final WebhookDeliveryService delivery;

    public WebhookEventSubscriber(WebhookDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Override
    public boolean supports(String eventType) {
        return WebhookEventType.fromCode(eventType)
                .filter(type -> type != WebhookEventType.WEBHOOK_TEST)
                .isPresent();
    }

    @Override
    public void onEvent(OutboxEvent event) {
        log.debug("Webhook 订阅方收到事件 type={} tenantEventId={}", event.eventType(), event.id());
        // 入队按 (webhook_id, outbox_event_id) 幂等，分发器重放不会重复排队
        delivery.enqueueForEvent(
                event.id(), event.eventType(), event.payload(), event.occurredAt());
    }
}
