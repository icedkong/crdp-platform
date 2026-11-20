package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 异常标红记录卡片查询（E21-T19 / C-DASH-006；docs/api/stats.md §2 cardCode = abnormal_records）。
 *
 * <p>「一个口径一个查询」：条件与指标卡「近 30 天含标红参数的记录数」
 * （DashboardStatsMapper.countRecentHighlightRecords）同一棵条件树——已提交 （status = 'SUBMITTED'）+
 * 软删行不在册（deleted = 0）+ highlight 非空 + first_submitted_at ≥ 窗口起点；
 * 未知首次提交时间不进入日期窗口（OQR-05A）。病区为页面级筛选；数据范围由语句上的 @DataScope 出行。命中明细（标红项目与变化率）由服务层解析 highlight
 * JSON（E17-T04 求值器写入的命中数组），不在 SQL 里拆 JSON。
 */
@Mapper
public interface AbnormalRecordsMapper {

    /** 一行 = 一条含标红参数的已提交记录（highlight 为命中数组 JSON）。 */
    record HighlightRecordRow(
            long id,
            long patientId,
            String nameCipher,
            String visitNo,
            LocalDate visitDate,
            LocalDateTime submittedAt,
            String highlight) {}

    /**
     * 近窗口内含标红参数的已提交记录（首次提交时间倒序取前 limit 行；total 用同一条件树由
     * DashboardStatsMapper.countRecentHighlightRecords 计数）。visitDate = visit_date ??
     * 任务计划日期（近似，OQ-134①）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT r.id, r.patient_id AS patientId,
                   p.name_cipher AS nameCipher, p.visit_no AS visitNo,
                   COALESCE(r.visit_date, t.plan_date) AS visitDate,
                   r.first_submitted_at AS submittedAt, r.highlight AS highlight
            FROM fu_record r
            JOIN pt_patient p ON p.id = r.patient_id AND p.deleted = 0
            LEFT JOIN fu_task t ON t.id = r.task_id
            WHERE r.status = 'SUBMITTED' AND r.deleted = 0
              AND r.highlight IS NOT NULL
              AND r.first_submitted_at &gt;= #{since}
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            ORDER BY r.first_submitted_at DESC, r.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<HighlightRecordRow> findRecentHighlights(
            @Param("since") LocalDateTime since,
            @Param("wardId") Long wardId,
            @Param("limit") int limit);
}
