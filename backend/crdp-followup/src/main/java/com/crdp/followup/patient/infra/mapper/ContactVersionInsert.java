package com.crdp.followup.patient.infra.mapper;

import java.time.LocalDate;

/** pt_contact 插入行（E13-T03）。可变行对象（{@code useGeneratedKeys} 回填 id，与 PatientInsert 同款）。 */
public class ContactVersionInsert {

    private Long id;
    private long patientId;
    private String slot;
    private String phoneCipher;
    private String phoneBlind;
    private Boolean wechatAdded;
    private LocalDate effectiveDate;
    private String source;
    private Long sourceRefId;
    private int version;
    private String remark;
    private Long createdBy;

    public ContactVersionInsert() {}

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

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public String getPhoneCipher() {
        return phoneCipher;
    }

    public void setPhoneCipher(String phoneCipher) {
        this.phoneCipher = phoneCipher;
    }

    public String getPhoneBlind() {
        return phoneBlind;
    }

    public void setPhoneBlind(String phoneBlind) {
        this.phoneBlind = phoneBlind;
    }

    public Boolean getWechatAdded() {
        return wechatAdded;
    }

    public void setWechatAdded(Boolean wechatAdded) {
        this.wechatAdded = wechatAdded;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getSourceRefId() {
        return sourceRefId;
    }

    public void setSourceRefId(Long sourceRefId) {
        this.sourceRefId = sourceRefId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
