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
}
