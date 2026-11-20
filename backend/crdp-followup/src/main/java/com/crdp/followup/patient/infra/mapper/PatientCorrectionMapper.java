package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import org.apache.ibatis.annotations.*;

/** 登记实体纠错；所有写入须在范围内患者行锁及版本校验之后执行。 */
@Mapper
public interface PatientCorrectionMapper {
    record Row(
            long id,
            long version,
            String gender,
            LocalDate birthDate,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            String patientType,
            String nameCipher,
            String nameBlind,
            String mrnCipher,
            String mrnBlind,
            String idCardCipher,
            String addressCipher,
            Integer dischargeStatus,
            String followupStatus,
            String wardName,
            String responsibleUserName) {}

    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.id,p.version,p.gender,p.birth_date AS birthDate,p.surgery_date AS surgeryDate,
              p.admission_date AS admissionDate,p.discharge_date AS dischargeDate,p.patient_type AS patientType,
              p.name_cipher AS nameCipher,p.name_blind AS nameBlind,p.mrn_cipher AS mrnCipher,p.mrn_blind AS mrnBlind,
              p.id_card_cipher AS idCardCipher,p.address_cipher AS addressCipher,
              p.discharge_status AS dischargeStatus,p.followup_status AS followupStatus,
              w.name AS wardName,u.display_name AS responsibleUserName
            FROM pt_patient p LEFT JOIN ward w ON w.id=p.ward_id
              LEFT JOIN sys_user u ON u.id=p.responsible_user_id
            WHERE p.id=#{id} AND p.deleted=0
            <if test="locking">FOR UPDATE</if>
            </script>
            """)
    Row find(@Param("id") long id, @Param("locking") boolean locking);

    @Insert("INSERT IGNORE INTO pt_baseline_lock(patient_id,locked) VALUES(#{id},0)")
    int ensureLockRow(@Param("id") long id);

    @DataScope(resource = "patient")
    @Select(
            "<script>SELECT locked FROM pt_baseline_lock WHERE patient_id=#{id}<if test='locking'> FOR UPDATE</if></script>")
    Boolean lockState(@Param("id") long id, @Param("locking") boolean locking);

    @Update(
            """
            UPDATE pt_patient SET gender=#{gender},birth_date=#{birthDate},surgery_date=#{surgeryDate},
            admission_date=#{admissionDate},discharge_date=#{dischargeDate},patient_type=#{patientType},
            version=version+1,updated_by=#{actor}
            WHERE id=#{id} AND version=#{baseVersion} AND deleted=0
            """)
    int updateOrdinary(
            @Param("id") long id,
            @Param("baseVersion") long baseVersion,
            @Param("actor") Long actor,
            @Param("gender") String gender,
            @Param("birthDate") LocalDate birthDate,
            @Param("surgeryDate") LocalDate surgeryDate,
            @Param("admissionDate") LocalDate admissionDate,
            @Param("dischargeDate") LocalDate dischargeDate,
            @Param("patientType") String patientType);

    @Update(
            """
            UPDATE pt_patient SET name_cipher=#{name},name_blind=#{nameBlind},mrn_cipher=#{mrn},mrn_blind=#{mrnBlind},
            id_card_cipher=#{idCard},address_cipher=#{address},version=version+1,updated_by=#{actor}
            WHERE id=#{id} AND version=#{baseVersion} AND deleted=0
            """)
    int updateIdentity(
            @Param("id") long id,
            @Param("baseVersion") long baseVersion,
            @Param("actor") Long actor,
            @Param("name") String name,
            @Param("nameBlind") String nameBlind,
            @Param("mrn") String mrn,
            @Param("mrnBlind") String mrnBlind,
            @Param("idCard") String idCard,
            @Param("address") String address);

    @com.crdp.framework.datascope.IgnoreScope(
            reason = "登记纠错已校验患者范围；仅计数该患者使用指定起算字段的在组计划，禁止清空正在使用的基准，不输出业务数据")
    @Select(
            "SELECT COUNT(*) FROM fu_enrollment e JOIN fu_plan p ON p.id=e.plan_id WHERE e.patient_id=#{id} AND e.status IN ('ACTIVE','PAUSED') AND p.start_date_field=#{field}")
    long countDateUsers(@Param("id") long id, @Param("field") String field);
}
