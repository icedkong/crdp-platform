package com.crdp.followup.plan.infra.mapper;

/** 计划变更记录新建行（E14-T08 / A-FU-021；只追加，无修改与删除）。 */
public class PlanLogInsertRow {

    private long id;
    private final Long planId;
    private final String planCode;
    private final String action;
    private final String description;
    private final String beforeJson;
    private final String afterJson;
    private final Long operatorId;

    public PlanLogInsertRow(
            Long planId,
            String planCode,
            String action,
            String description,
            String beforeJson,
            String afterJson,
            Long operatorId) {
        this.planId = planId;
        this.planCode = planCode;
        this.action = action;
        this.description = description;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.operatorId = operatorId;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public Long getOperatorId() {
        return operatorId;
    }
}
