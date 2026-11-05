package com.crdp.followup.record.infra.mapper;

/** fu_record 插入行（E15-T07 / A-FU-014）。可变行对象（{@code useGeneratedKeys} 回填 id，与 fe_record 同款）。 */
public class FollowupRecordInsert {

    private Long id;
    private long patientId;
    private Long taskId;
    private String source;
    private java.time.LocalDate visitDate;
    private String formCode;
    private int templateVersion;
    private String data;
    private String dataMeta;
    private Long createdBy;

    public FollowupRecordInsert() {}

    public FollowupRecordInsert(
            long patientId,
            Long taskId,
            String source,
            java.time.LocalDate visitDate,
            String formCode,
            int templateVersion,
            String data,
            String dataMeta,
            Long createdBy) {
        this.patientId = patientId;
        this.taskId = taskId;
        this.source = source;
        this.visitDate = visitDate;
        this.formCode = formCode;
        this.templateVersion = templateVersion;
        this.data = data;
        this.dataMeta = dataMeta;
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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public java.time.LocalDate getVisitDate() {
        return visitDate;
    }

    public void setVisitDate(java.time.LocalDate visitDate) {
        this.visitDate = visitDate;
    }

    public String getFormCode() {
        return formCode;
    }

    public void setFormCode(String formCode) {
        this.formCode = formCode;
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
