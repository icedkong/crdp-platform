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
            Long deptId,
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
                   w.name AS wardName, p.dept_id AS deptId, d.name AS deptName,
                   u.display_name AS responsibleName,
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

    /**
     * 患者最近一条含 ERI 字段（f323 / f324，键契约 OQ-130①）的已提交随访记录表单值（E21-T22 / C-PAT-010 概览 ERI 标记）。
     *
     * <p>排序键 = 有效随访日期（visit_date ?? 表单随访日期 f303，OQ-134① 同源口径；JSON 路径为稳定键字面量，不是请求参数）。
     * 不再回落任务计划日期：{@code fu_task} 是受控表，LEFT JOIN 会被数据范围改写器追加 {@code EXISTS(t.patient_id = …)}，
     * 把无任务记录（历史补录——C 含 ERI 字段记录的主要来源）整体过滤掉；visit_date / f303 已覆盖两类记录。 判定在服务层用 {@code
     * plan.domain.EriEval}（与接近 ERI 卡片、计划切换 SW-7 / SW-8 同一谓词族）。 A / B 表单无 f323 / f324 键，行天然为空；无返回
     * null（视为「正常」）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT r.data
            FROM fu_record r
            JOIN pt_patient p ON p.id = r.patient_id AND p.deleted = 0
            WHERE r.patient_id = #{patientId} AND r.status = 'SUBMITTED' AND r.deleted = 0
              AND (JSON_EXTRACT(r.data, '$.f323') IS NOT NULL
                   OR JSON_EXTRACT(r.data, '$.f324') IS NOT NULL)
            ORDER BY COALESCE(
                         r.visit_date,
                         STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(r.data, '$.f303')), '%Y-%m-%d')) DESC,
                     r.id DESC
            LIMIT 1
            </script>
            """)
    String findLatestEriRecordData(@Param("patientId") long patientId);

    /**
     * 患者在册「待录入」扫描件数（E21-T22 / C-PAT-010、C-SCAN-011）：rpt_report 在册（status = 'NORMAL'）且 电子化状态 PENDING
     * 的行数。条件与首页指标「待录入扫描件数」（DashboardStatsMapper.countReportsByDigitizeStatus）同一条件树按患者收窄； A 租户行
     * digitize_status 恒 NULL，计数天然为 0（A-ATT-018）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT COUNT(*)
            FROM rpt_report r
            JOIN pt_patient p ON p.id = r.patient_id AND p.deleted = 0
            WHERE r.patient_id = #{patientId} AND r.status = 'NORMAL'
              AND r.digitize_status = 'PENDING'
            """)
    Long countPendingDigitizeReports(@Param("patientId") long patientId);
}
