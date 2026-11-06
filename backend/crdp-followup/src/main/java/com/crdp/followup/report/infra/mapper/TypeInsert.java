package com.crdp.followup.report.infra.mapper;

/** 资料类型新建行（useGeneratedKeys 回写 id）。 */
public class TypeInsert {

    private long id;
    private String code;
    private String name;
    private String formCode;
    private boolean nameRequired;
    private String defaultDigitizeStatus;
    private int sortNo;
    private String remark;
    private Long updatedBy;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFormCode() {
        return formCode;
    }

    public void setFormCode(String formCode) {
        this.formCode = formCode;
    }

    public boolean isNameRequired() {
        return nameRequired;
    }

    public void setNameRequired(boolean nameRequired) {
        this.nameRequired = nameRequired;
    }

    public String getDefaultDigitizeStatus() {
        return defaultDigitizeStatus;
    }

    public void setDefaultDigitizeStatus(String defaultDigitizeStatus) {
        this.defaultDigitizeStatus = defaultDigitizeStatus;
    }

    public int getSortNo() {
        return sortNo;
    }

    public void setSortNo(int sortNo) {
        this.sortNo = sortNo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
