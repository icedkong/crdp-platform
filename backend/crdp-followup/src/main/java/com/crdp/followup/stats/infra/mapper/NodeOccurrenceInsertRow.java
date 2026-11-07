package com.crdp.followup.stats.infra.mapper;

import java.time.LocalDate;

/**
 * 节点随访事实表新建行（E19-T01；每日重建整表，DELETE 后批量 INSERT，不回写 id）。 字段语义见 followup V76 与 {@link
 * com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.FactRow}。
 */
public class NodeOccurrenceInsertRow {

    private final Long patientId;
    private final Long enrollmentId;
    private final Long planId;
    private final String planCode;
    private final Integer planVersion;
    private final Long nodeId;
    private final Integer occurrence;
    private final Integer followupYear;
    private final LocalDate planDate;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;
    private final Integer cohortYear;
    private final boolean due;
    private final String dueReason;
    private final Long taskId;
    private final Long recordId;
    private final boolean followed;
    private final String completeKind;
    private final boolean inWindow;
    private final boolean lost;
    private final boolean deathConfirmed;
    private final LocalDate refreshedDate;

    public NodeOccurrenceInsertRow(
            Long patientId,
            Long enrollmentId,
            Long planId,
            String planCode,
            Integer planVersion,
            Long nodeId,
            Integer occurrence,
            Integer followupYear,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            Integer cohortYear,
            boolean due,
            String dueReason,
            Long taskId,
            Long recordId,
            boolean followed,
            String completeKind,
            boolean inWindow,
            boolean lost,
            boolean deathConfirmed,
            LocalDate refreshedDate) {
        this.patientId = patientId;
        this.enrollmentId = enrollmentId;
        this.planId = planId;
        this.planCode = planCode;
        this.planVersion = planVersion;
        this.nodeId = nodeId;
        this.occurrence = occurrence;
        this.followupYear = followupYear;
        this.planDate = planDate;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.cohortYear = cohortYear;
        this.due = due;
        this.dueReason = dueReason;
        this.taskId = taskId;
        this.recordId = recordId;
        this.followed = followed;
        this.completeKind = completeKind;
        this.inWindow = inWindow;
        this.lost = lost;
        this.deathConfirmed = deathConfirmed;
        this.refreshedDate = refreshedDate;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getEnrollmentId() {
        return enrollmentId;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public Integer getOccurrence() {
        return occurrence;
    }

    public Integer getFollowupYear() {
        return followupYear;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public LocalDate getWindowStart() {
        return windowStart;
    }

    public LocalDate getWindowEnd() {
        return windowEnd;
    }

    public Integer getCohortYear() {
        return cohortYear;
    }

    public boolean isDue() {
        return due;
    }

    public String getDueReason() {
        return dueReason;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getRecordId() {
        return recordId;
    }

    public boolean isFollowed() {
        return followed;
    }

    public String getCompleteKind() {
        return completeKind;
    }

    public boolean isInWindow() {
        return inWindow;
    }

    public boolean isLost() {
        return lost;
    }

    public boolean isDeathConfirmed() {
        return deathConfirmed;
    }

    public LocalDate getRefreshedDate() {
        return refreshedDate;
    }
}
