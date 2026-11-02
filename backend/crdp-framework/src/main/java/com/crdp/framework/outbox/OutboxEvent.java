package com.crdp.framework.outbox;

import java.time.Instant;

/** 分发器交给订阅方的一条 outbox 事件：{@code sys_outbox} 的行（id 序即产生序），occurredAt 为落表时间。 */
public record OutboxEvent(long id, String eventType, String payload, Instant occurredAt) {}
