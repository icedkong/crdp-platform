package com.crdp.form.history.infra.mapper;

/**
 * fe_field_history 插入行（E11-T06）。可变行对象（仓库既有口径：FormRecordInsert / OrgRow 同款）。
 *
 * <p>old_value / new_value 存值编码口径的 JSON 文本（null 表示空：新填的旧行旧值为 NULL，清空的新值为 NULL）； trigger_fields
 * 为逗号分隔稳定键（系统重算记依赖来源字段，隐藏字段清空记条件引用字段）。
 */
public class FieldHistoryInsert {

    private Long id;
    private String recordType;
    private long recordId;
    private String formCode;
    private Integer templateVersion;
    private String fieldKey;
    private String oldValue;
    private String newValue;
    private String source;
    private String triggerFields;
    private String reason;
    private Long operatorId;
    private String operatorName;

    public FieldHistoryInsert() {}

    public FieldHistoryInsert(
            String recordType,
            long recordId,
            String formCode,
            Integer templateVersion,
            String fieldKey,
            String oldValue,
            String newValue,
            String source,
            String triggerFields,
            String reason,
            Long operatorId,
            String operatorName) {
        this.recordType = recordType;
        this.recordId = recordId;
        this.formCode = formCode;
        this.templateVersion = templateVersion;
        this.fieldKey = fieldKey;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.source = source;
        this.triggerFields = triggerFields;
        this.reason = reason;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public long getRecordId() {
        return recordId;
    }

    public void setRecordId(long recordId) {
        this.recordId = recordId;
    }

    public String getFormCode() {
        return formCode;
    }

    public void setFormCode(String formCode) {
        this.formCode = formCode;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(Integer templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public void setFieldKey(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTriggerFields() {
        return triggerFields;
    }

    public void setTriggerFields(String triggerFields) {
        this.triggerFields = triggerFields;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }
}
