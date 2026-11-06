package com.crdp.followup.report.infra.mapper;

/** 名称字典新建行（useGeneratedKeys 回写 id）。 */
public class NameInsert {

    private long id;
    private String typeCode;
    private String name;
    private Long indicatorTplId;
    private String digitizeStatusOverride;
    private int sortNo;
    private Long updatedBy;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getIndicatorTplId() {
        return indicatorTplId;
    }

    public void setIndicatorTplId(Long indicatorTplId) {
        this.indicatorTplId = indicatorTplId;
    }

    public String getDigitizeStatusOverride() {
        return digitizeStatusOverride;
    }

    public void setDigitizeStatusOverride(String digitizeStatusOverride) {
        this.digitizeStatusOverride = digitizeStatusOverride;
    }

    public int getSortNo() {
        return sortNo;
    }

    public void setSortNo(int sortNo) {
        this.sortNo = sortNo;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
