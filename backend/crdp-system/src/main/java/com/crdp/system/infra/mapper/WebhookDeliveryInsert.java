package com.crdp.system.infra.mapper;

/**
 * sys_webhook_delivery 插入行（E09-T06）。可变行对象（{@code useGeneratedKeys} 回填 id，仓库既有口径： FormRecordInsert
 * 同款）。 status / attempt / next_attempt_at 由 SQL 定为 PENDING / 0 / NOW(3)。
 */
public class WebhookDeliveryInsert {

    private Long id;
    private long webhookId;
    private Long outboxEventId;
    private String eventType;
    private String objectType;
    private String objectId;
    private String payload;

    public WebhookDeliveryInsert() {}

    public WebhookDeliveryInsert(
            long webhookId,
            Long outboxEventId,
            String eventType,
            String objectType,
            String objectId,
            String payload) {
        this.webhookId = webhookId;
        this.outboxEventId = outboxEventId;
        this.eventType = eventType;
        this.objectType = objectType;
        this.objectId = objectId;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getWebhookId() {
        return webhookId;
    }

    public void setWebhookId(long webhookId) {
        this.webhookId = webhookId;
    }

    public Long getOutboxEventId() {
        return outboxEventId;
    }

    public void setOutboxEventId(Long outboxEventId) {
        this.outboxEventId = outboxEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
