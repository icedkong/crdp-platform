package com.crdp.followup.patient.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 当前状态摘要 pt_patient_summary（E13-T03 / tech-plan §7「每名患者一行，每次提交后重算，不可直接修改」； 表 followup V39）。
 *
 * <p>本任务只有登记建行与重算触发（recomputed_*）；随访派生列由 E15 提交后重算、下次窗口由 E14 写入、完整度列分别随 E13-T02 / E15 写入（重算 SQL
 * 由对应任务扩展 {@code PatientSummaryService}）。INSERT 不走查询拦截（E07 只拦 SELECT）；表无直接修改接口。
 */
@Mapper
public interface PatientSummaryMapper {

    /** 建行 / 触达重算（登记与后续提交共用）：行不存在则建行；已存在只刷新重算时间与触发人，派生列由 各写入方在同一重算里更新。 */
    @Insert(
            """
            INSERT INTO pt_patient_summary (patient_id, recomputed_by)
            VALUES (#{patientId}, #{recomputedBy})
            ON DUPLICATE KEY UPDATE
              recomputed_at = CURRENT_TIMESTAMP(3),
              recomputed_by = VALUES(recomputed_by)
            """)
    int upsertRecompute(
            @Param("patientId") long patientId, @Param("recomputedBy") Long recomputedBy);

    /**
     * 写入基线资料完整度（E13-T02 / A-PAT-019：已填基线必填 ÷ 应填，0–100；分类 1–8 的 JSON 必填字段口径见
     * FormProgressEngine）。行不存在则建行（登记建行外的兜底），已存在只更新本列与重算时间——各写入方各管各的列。
     */
    @Insert(
            """
            INSERT INTO pt_patient_summary (patient_id, baseline_completeness, recomputed_by)
            VALUES (#{patientId}, #{completeness}, #{recomputedBy})
            ON DUPLICATE KEY UPDATE
              baseline_completeness = VALUES(baseline_completeness),
              recomputed_at = CURRENT_TIMESTAMP(3),
              recomputed_by = VALUES(recomputed_by)
            """)
    int updateBaselineCompleteness(
            @Param("patientId") long patientId,
            @Param("completeness") int completeness,
            @Param("recomputedBy") Long recomputedBy);

    /** 随访提交、修改、删除及映射后的派生摘要；无有效记录时清空旧摘要。 */
    @Insert(
            """
            INSERT INTO pt_patient_summary (patient_id, latest_followup_date, survival_status,
                key_indicators, endpoint_events, followup_completeness, recomputed_by)
            VALUES (#{patientId}, #{date}, #{survival}, #{indicators}, #{events}, #{completeness}, #{operator})
            ON DUPLICATE KEY UPDATE latest_followup_date = VALUES(latest_followup_date),
              survival_status = VALUES(survival_status), key_indicators = VALUES(key_indicators),
              endpoint_events = VALUES(endpoint_events), followup_completeness = VALUES(followup_completeness),
              recomputed_at = CURRENT_TIMESTAMP(3), recomputed_by = VALUES(recomputed_by)
            """)
    int updateFollowupSummary(
            @Param("patientId") long patientId,
            @Param("date") java.time.LocalDate date,
            @Param("survival") String survival,
            @Param("indicators") String indicators,
            @Param("events") String events,
            @Param("completeness") Integer completeness,
            @Param("operator") Long operator);

    /** 内部派生重算输入；调用方已校验患者范围（导入使用冻结范围），不向接口输出原始行。 */
    record SummaryRecord(
            long id,
            java.time.LocalDate visitDate,
            String formCode,
            int templateVersion,
            String data,
            java.time.LocalDate planDate,
            boolean annual) {}

    @com.crdp.framework.datascope.IgnoreScope(reason = "仅供派生摘要写入：记录写入口已校验患者范围，导入逐行按冻结范围校验；不输出原始记录")
    @org.apache.ibatis.annotations.Select(
            """
            SELECT r.id, r.visit_date AS visitDate, r.form_code AS formCode,
                r.template_version AS templateVersion, r.data, t.plan_date AS planDate,
                COALESCE(((r.task_id IS NOT NULL AND t.occurrence > 0 AND sp.repeat_mode = 'CYCLE' AND sp.cycle_unit = 'YEAR')
                  OR (r.task_id IS NULL AND r.enrollment_id IS NOT NULL AND r.followup_year > 0)),0) AS annual
            FROM fu_record r
            LEFT JOIN fu_task t ON t.id = r.task_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN fu_plan_node sp ON sp.id = COALESCE(n.parent_id, n.id)
            WHERE r.patient_id = #{patientId} AND r.deleted = 0 AND r.status = 'SUBMITTED'
            """)
    java.util.List<SummaryRecord> recordsForRecompute(@Param("patientId") long patientId);

    @com.crdp.framework.datascope.IgnoreScope(reason = "导入回滚仅定位已登记写数的记录归属，用于删除后刷新派生摘要；不向接口输出")
    @org.apache.ibatis.annotations.Select("SELECT patient_id FROM fu_record WHERE id = #{recordId}")
    Long recordPatientId(@Param("recordId") long recordId);
}
