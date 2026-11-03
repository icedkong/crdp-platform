package com.crdp.followup.patient.infra.mapper;

/** 转病区历史写入行（useGeneratedKeys 回写 id）。 */
public class WardHistoryInsert {

    private long id;
    private long patientId;
    private Long fromWardId;
    private long toWardId;
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

    public Long getFromWardId() {
        return fromWardId;
    }

    public void setFromWardId(Long fromWardId) {
        this.fromWardId = fromWardId;
    }

    public long getToWardId() {
        return toWardId;
    }

    public void setToWardId(long toWardId) {
        this.toWardId = toWardId;
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
