package com.crdp.system.webhook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.crdp.framework.outbox.OutboxEvent;
import com.crdp.framework.tenant.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** E09-T06：Webhook 订阅方事件路由单元测试（投递细节在 WebhookDeliveryServiceTest）。 */
class WebhookEventSubscriberTest {

    private final WebhookDeliveryService delivery = mock(WebhookDeliveryService.class);
    private final WebhookEventSubscriber subscriber = new WebhookEventSubscriber(delivery);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void supportsSubscribableEventsOnly() {
        for (WebhookEventType type : WebhookEventType.subscribable()) {
            assertTrue(subscriber.supports(type.name()), "订阅事件目录全部接管");
        }
        assertFalse(subscriber.supports("WEBHOOK_TEST"), "测试载荷不是事件");
        assertFalse(subscriber.supports("DAILY_DIGEST"), "不订阅纯站内信类事件");
        assertFalse(subscriber.supports("NOT_AN_EVENT"));
    }

    @Test
    void onEventDelegatesToQueueing() {
        TenantContext.useTenant(1L, "demoa");
        OutboxEvent event = new OutboxEvent(9001L, "TASK_OPENED", "{\"taskId\":7}", Instant.now());

        subscriber.onEvent(event);

        verify(delivery)
                .enqueueForEvent(9001L, "TASK_OPENED", "{\"taskId\":7}", event.occurredAt());
    }
}
