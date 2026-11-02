package com.crdp.form.record.infra.mapper;

/** fe_record 插入行（E11-T02）。可变行对象（{@code useGeneratedKeys} 回填 id，仓库既有口径： OrgRow / SysFileRow 同款）。 */
public class FormRecordInsert {

    private Long id;
    private String formCode;
    private int templateVersion;
    private String data;
    private String dataMeta;
    private Long createdBy;

    public FormRecordInsert() {}

    public FormRecordInsert(
            String formCode, int templateVersion, String data, String dataMeta, Long createdBy) {
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
