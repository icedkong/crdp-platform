package com.crdp.followup.compare.infra.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 上一次记录解析的读侧（E17-T02 / A-FU-027；docs/api/followup.md §3 {@code previous}）。
 *
 * <p>对比对象口径（hospital-a-spec 第 5 章「录入时对比提示规则」）：只取<b>已提交</b>的同表单随访记录（含逾期完成、 补录、历史导入），
 * <b>计划外随访与草稿不作对比对象</b>（UNPLANNED 由 source 过滤、草稿由 status 过滤）； 排序键「对比时点」= {@code visit_date ?? 关联任务
 * plan_date}（TASK 记录不落 visit_date，年度时点即计划日期）， 取早于本次、最近的一条。
 *
 * <p>任务计划日期<b>不与候选查询 JOIN</b>：受限范围下改写器对 JOIN 里的每张受控表各挂一条 EXISTS（E07-02）， LEFT JOIN fu_task 会被
 * EXISTS 收紧成内连接，把无任务的补录候选整行滤掉——改为候选行带 task_id、 由应用层对 visit_date 为空的行批量补查（{@link
 * #findTaskPlanDates}）。
 *
 * <p>无上一次随访时回落基线分节 {@code pt_baseline_section}（第 1 年与基线出院前超声对比，Q41）——基线无 提交语义，分节当前值即回落值。 语句带
 * {@code @DataScope(resource = "patient")}，越范围行不可见（AGENTS §5.2）；data 为 JSON 文本，应用层解析。
 */
@Mapper
public interface CompareSourceMapper {

    /** 已提交随访候选行（task_id 供应用层补查任务计划日期；implantId 供 E21-T11 同一器械过滤）。 */
    record CandidateRow(
            long id,
            String source,
            LocalDate visitDate,
            Long taskId,
            Long implantId,
            String data) {}

    /**
     * 患者的已提交同表单随访候选（排除自身；含 TASK / BACKFILL 及历史导入，不含 UNPLANNED 与 DRAFT；软删行不算，E21-T11）。
     * 时点定序在应用层完成（对比时点 = visit_date ?? 任务计划日期）。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT r.id, r.source, r.visit_date AS visitDate, r.task_id AS taskId,
                   r.implant_id AS implantId, r.data
            FROM fu_record r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.patient_id = #{patientId}
              AND r.id <> #{recordId}
              AND r.form_code = #{formCode}
              AND r.status = 'SUBMITTED'
              AND r.deleted = 0
              AND IFNULL(r.source, '') <> 'UNPLANNED'
            ORDER BY r.visit_date DESC, r.id DESC
            """)
    List<CandidateRow> findSubmittedCandidates(
            @Param("patientId") long patientId,
            @Param("recordId") long recordId,
            @Param("formCode") String formCode);

    /**
     * 当前记录的器械维度（E21-T11 / C-FU-010、OQ-130③）：fu_record.implant_id（创建 / 提交时按随访日期解析落列， followup
     * V87）；implant 开关关闭的租户（A / B）与历史空行 null——null 时对比候选不做器械过滤（既有行为）。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT r.implant_id
            FROM fu_record r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.id = #{recordId} AND r.deleted = 0
            """)
    Long findRecordImplant(@Param("recordId") long recordId);

    /** 任务计划日期行（TASK 候选的对比时点）。 */
    record TaskPlanRow(long taskId, LocalDate planDate) {}

    /** 批量取任务计划日期（visit_date 为空的候选补查；越范围任务的行不可见）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT t.id AS taskId, t.plan_date AS planDate
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            WHERE t.id IN
            <foreach collection="taskIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    List<TaskPlanRow> findTaskPlanDates(@Param("taskIds") List<Long> taskIds);

    /** 当前记录关联任务的计划日期（TASK 记录的对比时点；无任务 / 越范围 null）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT t.plan_date
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            WHERE t.record_id = #{recordId}
            """)
    LocalDate findTaskPlanDate(@Param("recordId") long recordId);

    /** 基线分节行（data 为 JSON 文本，应用层按规则的回落字段取值）。 */
    record BaselineSectionRow(String categoryKey, String data) {}

    /** 患者某基线表单的全部分节（回落值来源；无分节返回空列表）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT s.category_key AS categoryKey, s.data
            FROM pt_baseline_section s
            JOIN pt_patient pt ON pt.id = s.patient_id AND pt.deleted = 0
            WHERE s.patient_id = #{patientId} AND s.form_code = #{formCode}
            """)
    List<BaselineSectionRow> findBaselineSections(
            @Param("patientId") long patientId, @Param("formCode") String formCode);
}
