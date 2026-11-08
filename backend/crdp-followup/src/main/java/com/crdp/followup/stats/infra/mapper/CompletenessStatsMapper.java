package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 完整度统计的取数查询（E19-T05 / A-DASH-010、A-DASH-011、C-DASH-009；口径 Q39 / Q40，「按当前数据实时计算」）。
 *
 * <p>三路取数：①基线 = 范围内患者全集（分母，未保存过分节的患者计 0%）+ 各患者基线分节 JSON（应用层合并后按 FormProgressEngine 字段级口径求值）；②随访 =
 * 已提交的<b>年度</b>随访记录（任务行经年度循环节点取随访年度 = 任务次数； E18 导入锚点行直接取 followup_year；计划外与无锚点补录不计，Q40）；③患者类型候选（范围内
 * DISTINCT 值）。
 *
 * <p>数据范围由语句上的 {@code @DataScope(resource = "patient")} 按患者病区出行（范围内无患者返回空集，不报错）； 病区 / 患者类型 /
 * 时间范围为范围内再收窄。dateColumn / visitDate 过滤见各语句注释—— 实际随访日期（表单字段）与随访年度 过滤在应用层做（字段口径随模板版本，SQL 不硬编码表单键）。
 */
@Mapper
public interface CompletenessStatsMapper {

    /** 基线分节行（patientId + 分节 data JSON；一名患者多行 = 多分类分节，应用层按键合并）。 */
    record SectionRow(long patientId, String data) {}

    /**
     * 随访年度记录行：followupYear = 记录锚点年度（导入行）或年度循环节点的任务次数（任务行， V73「A 医院年度随访 = occurrence」）；visitDate
     * 为空表示实际随访日期在表单字段里（应用层按模板版本解析）； dischargeDate 供「按出院日期」时间筛选。
     */
    record AnnualRecordRow(
            long recordId,
            long patientId,
            String formCode,
            int templateVersion,
            String data,
            LocalDate visitDate,
            LocalDate dischargeDate,
            Integer followupYear) {}

    /** 患者类型候选行（范围内 DISTINCT 非空值；label = 编码，A-FLD-012 字典项对医生端暂无读取口，见 OQ-110⑤）。 */
    record PatientTypeRow(String patientType) {}

    /**
     * 基线患者全集（A-DASH-010 汇总分母：范围内全部在册患者——未保存过分节的按 0 计入， 与患者列表基线完整度列同口径）。 dateColumn
     * 只由服务层白名单生成（discharge_date / created_at），取值不透传请求原文。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.id
            FROM pt_patient p
            WHERE p.deleted = 0
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            <if test="patientType != null">AND p.patient_type = #{patientType}</if>
            <if test="dateColumn != null and from != null">AND ${dateColumn} &gt;= #{from}</if>
            <if test="dateColumn != null and to != null">AND ${dateColumn} &lt;= #{to}</if>
            ORDER BY p.id
            </script>
            """)
    List<Long> selectBaselinePatientIds(
            @Param("wardId") Long wardId,
            @Param("patientType") String patientType,
            @Param("dateColumn") String dateColumn,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 基线分节数据（筛选与 {@link #selectBaselinePatientIds} 同一棵条件树，保证卡片数字与钻取一致）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT s.patient_id AS patientId, s.data
            FROM pt_baseline_section s
            JOIN pt_patient p ON p.id = s.patient_id
            WHERE p.deleted = 0
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            <if test="patientType != null">AND p.patient_type = #{patientType}</if>
            <if test="dateColumn != null and from != null">AND ${dateColumn} &gt;= #{from}</if>
            <if test="dateColumn != null and to != null">AND ${dateColumn} &lt;= #{to}</if>
            ORDER BY s.patient_id
            </script>
            """)
    List<SectionRow> selectBaselineSections(
            @Param("wardId") Long wardId,
            @Param("patientType") String patientType,
            @Param("dateColumn") String dateColumn,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * 已提交的年度随访记录（A-DASH-011「计入的记录」，Q40）：已提交（含窗口内完成、逾期完成、补录、历史导入、随访中确认
     * 死亡的记录）；只取年度锚点行——任务行须挂在年度循环节点（子计划 CYCLE + YEAR，随访年度 = 任务次数 occurrence， V73「A 医院年度随访 =
     * occurrence」；repeat_mode / cycle_unit 声明在子计划行，任务挂的 NODE 行经 parent 取）， 导入锚点行须带
     * followup_year；计划外（UNPLANNED）、无锚点手工补录（BACKFILL 无任务）、草稿与历史未随访不计（Q40 / OQ-110③）。 病区 / 患者类型在 SQL
     * 收窄；随访年度、实际随访日期（表单字段口径随模板）与「按出院日期」时间范围在应用层过滤 （年度候选要先出全量）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT r.id AS recordId, r.patient_id AS patientId, r.form_code AS formCode,
                   r.template_version AS templateVersion, r.data, r.visit_date AS visitDate,
                   p.discharge_date AS dischargeDate,
                   COALESCE(r.followup_year,
                            CASE WHEN sp.repeat_mode = 'CYCLE' AND sp.cycle_unit = 'YEAR'
                                 THEN t.occurrence END) AS followupYear
            FROM fu_record r
            JOIN pt_patient p ON p.id = r.patient_id AND p.deleted = 0
            LEFT JOIN fu_task t ON t.id = r.task_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN fu_plan_node sp ON sp.id = COALESCE(n.parent_id, n.id)
            WHERE r.status = 'SUBMITTED'
              AND (
                (r.task_id IS NOT NULL AND sp.repeat_mode = 'CYCLE' AND sp.cycle_unit = 'YEAR')
                OR (r.task_id IS NULL AND r.enrollment_id IS NOT NULL AND r.followup_year IS NOT NULL)
              )
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            <if test="patientType != null">AND p.patient_type = #{patientType}</if>
            ORDER BY r.patient_id, r.id
            </script>
            """)
    List<AnnualRecordRow> selectAnnualRecords(
            @Param("wardId") Long wardId, @Param("patientType") String patientType);

    /** 患者类型候选（范围内实际出现的值；按值排序稳定）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT DISTINCT p.patient_type AS patientType
            FROM pt_patient p
            WHERE p.deleted = 0 AND p.patient_type IS NOT NULL AND p.patient_type != ''
            ORDER BY p.patient_type
            """)
    List<PatientTypeRow> selectPatientTypes();
}
