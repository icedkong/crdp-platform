package com.crdp.followup.patient.infra.mapper;

/** pt_baseline_section 插入行（E13-T02 / A-PAT-003）。可变行对象（{@code useGeneratedKeys} 回填 id）。 */
public class BaselineSectionInsert {

    private Long id;
    private long patientId;
    private String formCode;
    private String categoryKey;
    private int templateVersion;
    private String data;
    private String dataMeta;
    private int version;
    private boolean hasDraft;
    private Long createdBy;

    public BaselineSectionInsert() {}

    public BaselineSectionInsert(
            long patientId,
            String formCode,
            String categoryKey,
            int templateVersion,
            String data,
            String dataMeta,
            int version,
            boolean hasDraft,
            Long createdBy) {
        this.patientId = patientId;
        this.formCode = formCode;
        this.categoryKey = categoryKey;
        this.templateVersion = templateVersion;
        this.data = data;
        this.dataMeta = dataMeta;
        this.version = version;
        this.hasDraft = hasDraft;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getPatientId() {
        return patientId;
    }

    public void setPatientId(long patientId) {
        this.patientId = patientId;
    }

    public String getFormCode() {
        return formCode;
    }

    public void setFormCode(String formCode) {
        this.formCode = formCode;
    }

    public String getCategoryKey() {
        return categoryKey;
    }

    public void setCategoryKey(String categoryKey) {
        this.categoryKey = categoryKey;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(int templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getDataMeta() {
        return dataMeta;
    }

    public void setDataMeta(String dataMeta) {
        this.dataMeta = dataMeta;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isHasDraft() {
        return hasDraft;
    }

    public void setHasDraft(boolean hasDraft) {
        this.hasDraft = hasDraft;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
