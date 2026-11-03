package com.crdp.followup.patient.infra.mapper;

/** pt_patient 插入行（E13-T01）。可变行对象（{@code useGeneratedKeys} 回填 id，仓库既有口径：FormRecordInsert 同款）。 */
public class PatientInsert {

    private Long id;
    private String mrnCipher;
    private String mrnBlind;
    private String nameCipher;
    private String nameBlind;
    private String idCardCipher;
    private String addressCipher;
    private String gender;
    private java.time.LocalDate birthDate;
    private Long deptId;
    private Long wardId;
    private java.time.LocalDate surgeryDate;
    private java.time.LocalDate admissionDate;
    private java.time.LocalDate dischargeDate;
    private Integer dischargeStatus;
    private String legacyNo;
    private Long createdBy;

    public PatientInsert() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMrnCipher() {
        return mrnCipher;
    }

    public void setMrnCipher(String mrnCipher) {
        this.mrnCipher = mrnCipher;
    }

    public String getMrnBlind() {
        return mrnBlind;
    }

    public void setMrnBlind(String mrnBlind) {
        this.mrnBlind = mrnBlind;
    }

    public String getNameCipher() {
        return nameCipher;
    }

    public void setNameCipher(String nameCipher) {
        this.nameCipher = nameCipher;
    }

    public String getNameBlind() {
        return nameBlind;
    }

    public void setNameBlind(String nameBlind) {
        this.nameBlind = nameBlind;
    }

    public String getIdCardCipher() {
        return idCardCipher;
    }

    public void setIdCardCipher(String idCardCipher) {
        this.idCardCipher = idCardCipher;
    }

    public String getAddressCipher() {
        return addressCipher;
    }

    public void setAddressCipher(String addressCipher) {
        this.addressCipher = addressCipher;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public java.time.LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(java.time.LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public Long getWardId() {
        return wardId;
    }

    public void setWardId(Long wardId) {
        this.wardId = wardId;
    }

    public java.time.LocalDate getSurgeryDate() {
        return surgeryDate;
    }

    public void setSurgeryDate(java.time.LocalDate surgeryDate) {
        this.surgeryDate = surgeryDate;
    }

    public java.time.LocalDate getAdmissionDate() {
        return admissionDate;
    }

    public void setAdmissionDate(java.time.LocalDate admissionDate) {
        this.admissionDate = admissionDate;
    }

    public java.time.LocalDate getDischargeDate() {
        return dischargeDate;
    }

    public void setDischargeDate(java.time.LocalDate dischargeDate) {
        this.dischargeDate = dischargeDate;
    }

    public Integer getDischargeStatus() {
        return dischargeStatus;
    }

    public void setDischargeStatus(Integer dischargeStatus) {
        this.dischargeStatus = dischargeStatus;
    }

    public String getLegacyNo() {
        return legacyNo;
    }

    public void setLegacyNo(String legacyNo) {
        this.legacyNo = legacyNo;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
