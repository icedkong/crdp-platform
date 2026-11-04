package com.crdp.followup.plan.infra.mapper;

/** 计划新建行（useGeneratedKeys 回写 id，E14-T01）。 */
public class PlanInsertRow {

    private long id;
    private String code;
    private String name;
    private int version;
    private String status;
    private String startDateField;
    private String cycleType;
    private Integer cycleYears;
    private String remark;
    private Long createdBy;

    public PlanInsertRow(
            String code,
            String name,
            int version,
            String status,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            String remark,
            Long createdBy) {
        this.code = code;
        this.name = name;
        this.version = version;
        this.status = status;
        this.startDateField = startDateField;
        this.cycleType = cycleType;
        this.cycleYears = cycleYears;
        this.remark = remark;
        this.createdBy = createdBy;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getStatus() {
        return status;
    }

    public String getStartDateField() {
        return startDateField;
    }

    public String getCycleType() {
        return cycleType;
    }

    public Integer getCycleYears() {
        return cycleYears;
    }

    public String getRemark() {
        return remark;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
