package com.crdp.followup.plan.infra.mapper;

import java.time.LocalDate;

/** 患者入组新建行（useGeneratedKeys 回写 id，E14-T01；plan_id = 入组时固定的计划版本）。 */
public class PlanEnrollmentInsertRow {

    private long id;
    private Long patientId;
    private Long planId;
    private String planCode;
    private LocalDate startDate;
    private String mode;
    private String status;
    private Long createdBy;

    public PlanEnrollmentInsertRow(
            Long patientId,
            Long planId,
            String planCode,
            LocalDate startDate,
            String mode,
            String status,
            Long createdBy) {
        this.patientId = patientId;
        this.planId = planId;
        this.planCode = planCode;
        this.startDate = startDate;
        this.mode = mode;
        this.status = status;
        this.createdBy = createdBy;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public String getMode() {
        return mode;
    }

    public String getStatus() {
        return status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
