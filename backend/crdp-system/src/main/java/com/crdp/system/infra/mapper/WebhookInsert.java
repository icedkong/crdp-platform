package com.crdp.system.infra.mapper;

/** sys_webhook 插入行（E09-T06）。可变行对象（{@code useGeneratedKeys} 回填 id，仓库既有口径： FormRecordInsert 同款）。 */
public class WebhookInsert {

    private Long id;
    private String name;
    private String url;
    private String secretCipher;
    private String events;
    private boolean enabled;
    private Long createdBy;
    private Long updatedBy;

    public WebhookInsert() {}

    public WebhookInsert(
            String name,
            String url,
            String secretCipher,
            String events,
            boolean enabled,
            Long createdBy,
            Long updatedBy) {
        this.name = name;
        this.url = url;
        this.secretCipher = secretCipher;
        this.events = events;
        this.enabled = enabled;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecretCipher() {
        return secretCipher;
    }

    public void setSecretCipher(String secretCipher) {
        this.secretCipher = secretCipher;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
