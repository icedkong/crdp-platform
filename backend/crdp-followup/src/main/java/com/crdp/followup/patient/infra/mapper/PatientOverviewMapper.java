package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 档案概览取数（E13-T05 / A-PAT-004、C-PAT-010；docs/api/patient.md §2 GET summary）。
 *
 * <p>一条 JOIN 取齐 PatientHeader 与概览三块：患者实体列（基线最小必填集 + 脱敏身份密文）、 {@code pt_patient_summary} 派生列（基线 /
 * 随访完整度、下次随访窗口、最近随访关键指标）， 病区 / 科室 / 责任医生名称。
 *
 * <p>越范围（病区 / 科室 / 本人范围外）由语句上的 {@code @DataScope(resource = "patient")} 改写为不可见（E07-T02），上层按 40400
 * 处理（AGENTS.md §5.2，不暴露对象存在）。 手术年龄 = 手术日期 − 出生日期（周岁，followup V36 口径），SQL 内一次算完。
 */
@Mapper
public interface PatientOverviewMapper {

    /** 概览原始行：姓名 / 病案号为密文（应用层解密后脱敏输出）；keyIndicators / endpointEvents 为 JSON 文本。 */
    record OverviewRow(
            Long id,
            String nameCipher,
            String mrnCipher,
            String legacyNo,
            String visitNo,
            String gender,
            Integer age,
            String status,
            Long wardId,
            String wardName,
            String deptName,
            String responsibleName,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            Integer dischargeStatus,
            String patientType,
            Integer baselineCompleteness,
            BigDecimal followupCompleteness,
            LocalDate latestFollowupDate,
            String survivalStatus,
            String keyIndicators,
            String endpointEvents,
            LocalDate nextPlanDate,
            LocalDate nextWindowStart,
            LocalDate nextWindowEnd) {}

    /** 单个患者的概览行（当前用户数据范围内；越范围 / 不存在 null）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT p.id, p.name_cipher AS nameCipher, p.mrn_cipher AS mrnCipher,
                   p.legacy_no AS legacyNo, p.visit_no AS visitNo, p.gender,
                   TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date) AS age,
                   p.followup_status AS status,
                   p.ward_id AS wardId,
                   w.name AS wardName, d.name AS deptName, u.display_name AS responsibleName,
                   p.surgery_date AS surgeryDate, p.admission_date AS admissionDate,
                   p.discharge_date AS dischargeDate, p.discharge_status AS dischargeStatus,
                   p.patient_type AS patientType,
                   s.baseline_completeness AS baselineCompleteness,
                   s.followup_completeness AS followupCompleteness,
                   s.latest_followup_date AS latestFollowupDate,
                   s.survival_status AS survivalStatus,
                   s.key_indicators AS keyIndicators,
                   s.endpoint_events AS endpointEvents,
                   s.next_plan_date AS nextPlanDate,
                   s.next_window_start AS nextWindowStart,
                   s.next_window_end AS nextWindowEnd
            FROM pt_patient p
            LEFT JOIN pt_patient_summary s ON s.patient_id = p.id
            LEFT JOIN ward w ON w.id = p.ward_id
            LEFT JOIN sys_dept d ON d.id = p.dept_id
            LEFT JOIN sys_user u ON u.id = p.responsible_user_id
            WHERE p.id = #{patientId} AND p.deleted = 0
            """)
    OverviewRow findOverview(@Param("patientId") long patientId);
}
