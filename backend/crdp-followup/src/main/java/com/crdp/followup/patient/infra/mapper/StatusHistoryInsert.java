package com.crdp.followup.patient.infra.mapper;

import java.time.LocalDate;

/** pt_status_history 插入行（E13-T03）。可变行对象（与 PatientInsert 同款）。 */
public class StatusHistoryInsert {

    private Long id;
    private long patientId;
    private String status;
    private LocalDate effectiveDate;
    private String reason;
    private String source;
    private Long createdBy;

    public StatusHistoryInsert() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getPatientId() {
        return patientId;
    }

    public void setPatientId(long patientId) {
        this.patientId = patientId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
