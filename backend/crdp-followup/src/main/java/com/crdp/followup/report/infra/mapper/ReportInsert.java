package com.crdp.followup.report.infra.mapper;

import java.time.LocalDate;

/** 报告单插入参数（生成主键经 {@code setId} 回填，与 {@code TypeInsert} 同口径）。 */
public class ReportInsert {

    private long id;
    private long patientId;
    private String typeCode;
    private String typeName;
    private Long nameId;
    private String nameSnapshot;
    private LocalDate reportDate;
    private String findings;
    private String conclusion;
    private String note;
    private String fieldValues;
    private String digitizeStatus;
    private Long createdBy;

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

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public Long getNameId() {
        return nameId;
    }

    public void setNameId(Long nameId) {
        this.nameId = nameId;
    }

    public String getNameSnapshot() {
        return nameSnapshot;
    }

    public void setNameSnapshot(String nameSnapshot) {
        this.nameSnapshot = nameSnapshot;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public String getFindings() {
        return findings;
    }

    public void setFindings(String findings) {
        this.findings = findings;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getFieldValues() {
        return fieldValues;
    }

    public void setFieldValues(String fieldValues) {
        this.fieldValues = fieldValues;
    }

    public String getDigitizeStatus() {
        return digitizeStatus;
    }

    public void setDigitizeStatus(String digitizeStatus) {
        this.digitizeStatus = digitizeStatus;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public long id() {
        return id;
    }

    public long patientId() {
        return patientId;
    }

    public String typeCode() {
        return typeCode;
    }

    public String typeName() {
        return typeName;
    }

    public Long nameId() {
        return nameId;
    }

    public String nameSnapshot() {
        return nameSnapshot;
    }

    public LocalDate reportDate() {
        return reportDate;
    }

    public String findings() {
        return findings;
    }

    public String conclusion() {
        return conclusion;
    }

    public String note() {
        return note;
    }

    public String fieldValues() {
        return fieldValues;
    }

    public String digitizeStatus() {
        return digitizeStatus;
    }

    public Long createdBy() {
        return createdBy;
    }
}
