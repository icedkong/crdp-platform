package com.crdp.followup.task.infra.mapper;

import java.time.LocalDate;

/**
 * 随访任务新建行（E14-T04；useGeneratedKeys 回写 id，仅新增时有效——幂等命中 uk（入组 + 节点 + 第 N 次 + 跟进序号）时 affected = 0，id
 * 不回填）。创建方式固定 NORMAL（补录随 E14-T05、暂停 / 停用补偿随 E14-T06、跟进 FOLLOWUP 随 E15-T05、临时随 E15-T06）。
 */
public class FollowupTaskInsertRow {

    private long id;
    private final Long enrollmentId;
    private final Long patientId;
    private final Long planId;
    private final Long nodeId;
    private final Integer occurrence;
    private final String taskType;
    private final String formCode;
    private final String ownerType;
    private final Long assigneeId;
    private final LocalDate planDate;
    private final LocalDate remindStart;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;
    private final LocalDate openDate;
    private final String createType;
    private final String status;
    private final Integer followupSeq;
    private final LocalDate createdLogicalDate;

    public FollowupTaskInsertRow(
            Long enrollmentId,
            Long patientId,
            Long planId,
            Long nodeId,
            Integer occurrence,
            String taskType,
            String formCode,
            String ownerType,
            Long assigneeId,
            LocalDate planDate,
            LocalDate remindStart,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate openDate,
            String createType,
            String status,
            Integer followupSeq,
            LocalDate createdLogicalDate) {
        this.enrollmentId = enrollmentId;
        this.patientId = patientId;
        this.planId = planId;
        this.nodeId = nodeId;
        this.occurrence = occurrence;
        this.taskType = taskType;
        this.formCode = formCode;
        this.ownerType = ownerType;
        this.assigneeId = assigneeId;
        this.planDate = planDate;
        this.remindStart = remindStart;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.openDate = openDate;
        this.createType = createType;
        this.status = status;
        this.followupSeq = followupSeq;
        this.createdLogicalDate = createdLogicalDate;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getEnrollmentId() {
        return enrollmentId;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getPlanId() {
        return planId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public Integer getOccurrence() {
        return occurrence;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getFormCode() {
        return formCode;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public LocalDate getRemindStart() {
        return remindStart;
    }

    public LocalDate getWindowStart() {
        return windowStart;
    }

    public LocalDate getWindowEnd() {
        return windowEnd;
    }

    public LocalDate getOpenDate() {
        return openDate;
    }

    public String getCreateType() {
        return createType;
    }

    public String getStatus() {
        return status;
    }

    /** 窗口内跟进序号（0 = 常规任务；1–5 = 未联系到自动跟进，A-FU-024，followup V57）。 */
    public Integer getFollowupSeq() {
        return followupSeq;
    }

    public LocalDate getCreatedLogicalDate() {
        return createdLogicalDate;
    }
}
