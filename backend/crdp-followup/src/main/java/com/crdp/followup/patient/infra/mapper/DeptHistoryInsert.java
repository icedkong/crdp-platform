package com.crdp.followup.patient.infra.mapper;

/** 转科室历史写入行（useGeneratedKeys 回写 id）。 */
public class DeptHistoryInsert {

    private long id;
    private long patientId;
    private Long fromDeptId;
    private long toDeptId;
    private String reason;
    private Long transferredBy;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getPatientId() {
        return patientId;
    }

    public void setPatientId(long patientId) {
        this.patientId = patientId;
    }

    public Long getFromDeptId() {
        return fromDeptId;
    }

    public void setFromDeptId(Long fromDeptId) {
        this.fromDeptId = fromDeptId;
    }

    public long getToDeptId() {
        return toDeptId;
    }

    public void setToDeptId(long toDeptId) {
        this.toDeptId = toDeptId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getTransferredBy() {
        return transferredBy;
    }

    public void setTransferredBy(Long transferredBy) {
        this.transferredBy = transferredBy;
    }
}
