package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;

/**
 * sys_webhook_delivery 一行（E09-T06 投递台账与重试队列，PLT-CFG-007「投递记录」）。
 *
 * <p>status：PENDING（待投 / 退避重试中，next_attempt_at 到点由轮询作业取出）/ SENT（接收方 2xx）/ FAILED（重试耗尽）。
 * outbox_event_id 是幂等锚点（与 webhook_id 联合唯一）；测试投递（WEBHOOK_TEST）无来源事件，为 NULL 不占唯一键。
 */
public record WebhookDeliveryRow(
        Long id,
        long webhookId,
        Long outboxEventId,
        String eventType,
        String objectType,
        String objectId,
        String payload,
        String status,
        int attempt,
        LocalDateTime nextAttemptAt,
        Integer responseStatus,
        String error,
        LocalDateTime sentAt,
        LocalDateTime createdAt) {}
