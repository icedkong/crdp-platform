package com.crdp.followup.dashboard.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 首页指标卡与趋势图的计数查询（E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001；docs/api/stats.md §2 卡片接口）。
 *
 * <p>「一个口径一个查询」（stats.md §1）：指标卡的数字与列表钻取共用同一条件树——患者状态计数与 患者列表检索 （{@code PatientSearchMapper}：{@code
 * deleted = 0} + {@code followup_status} 筛选）、任务计数与任务列表检索 （{@code FollowupTaskQueryMapper}：{@code
 * status} / {@code plan_date} 筛选）逐条件一致； 数据范围由语句上的 {@code @DataScope(resource = "patient")}
 * 按登录快照出行（范围内无患者时计数为 0，不报错）。
 *
 * <p>趋势图的分桶 / 分组表达式（${bucketExpr} / ${groupExpr} / 维度列）由 {@link
 * com.crdp.followup.dashboard.app.DashboardTrendService} 从**枚举白名单**生成后注入—— 取值不透传请求原文，与
 * PatientSearchMapper 高级检索条件树（E13-T06）同一防注入口径；请求参数只经 {@code #{}} 预编译位进 SQL。
 */
@Mapper
public interface DashboardStatsMapper {

    /** 患者状态计数行（A-DASH-001：患者总数 / 在随访 / 已死亡 / 已终止 / 失访一次取全）。 */
    record PatientStatusCountRow(String status, long total) {}

    /** 趋势分桶计数行：bucket 为周期键（2026-01 / 2026-Q1 / 2026），groupKey 为分组取值（不分组为 null）。 */
    record TrendCountRow(String bucket, String groupKey, long total) {}

    /** 按随访状态计数（在册患者；含已死亡 / 已终止——患者总数口径 Q01「包含已死亡、已终止患者」）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.followup_status AS status, COUNT(*) AS total
            FROM pt_patient p
            WHERE p.deleted = 0
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY p.followup_status
            </script>
            """)
    List<PatientStatusCountRow> countPatientsByStatus(@Param("wardId") Long wardId);

    /**
     * 本月应随访（A-DASH-001 口径「计划日期在本月的年度随访数，按在组患者的下次计划日期实时计算」）： 计划日期落在 [from, to] 的常规随访任务（occurrence
     * &gt; 0 且 followup_seq = 0，排除跟进 / 临时任务）， 入组仍在组（ACTIVE / PAUSED；已移除不计）。窗口开启日不晚于计划日期（fu_task
     * 窗口口径），本月计划日期的任务已由每日作业或入组即建落库， 窗口未结束前始终在册；「任务尚未创建」的未来次数只影响以后月份（暂行口径见 OQ-107）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM fu_task t
            JOIN fu_enrollment e ON e.id = t.enrollment_id
                AND e.status IN ('ACTIVE', 'PAUSED')
            JOIN pt_patient p ON p.id = t.patient_id AND p.deleted = 0
            WHERE t.occurrence &gt; 0 AND t.followup_seq = 0
              AND t.plan_date &gt;= #{from} AND t.plan_date &lt;= #{to}
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            </script>
            """)
    long countMonthDue(
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("wardId") Long wardId);

    /** 按任务状态计数（当前逾期 = OVERDUE、C 的当前待随访 = PENDING；与任务列表 status 筛选同条件树， 钻取数字一致）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM fu_task t
            JOIN pt_patient p ON p.id = t.patient_id AND p.deleted = 0
            WHERE t.status = #{status}
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            </script>
            """)
    long countTasksByStatus(@Param("status") String status, @Param("wardId") Long wardId);

    /**
     * 近 N 天含标红参数的已提交随访记录数（C-DASH-001；标红命中由 E17-T04 复算落在 fu_record.highlight）。 「近 N 天提交」以 updated_at
     * 近似（提交 / 重算都会触碰该列；fu_record 无独立提交时间列，OQ-107）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM fu_record r
            JOIN pt_patient p ON p.id = r.patient_id AND p.deleted = 0
            WHERE r.status = 'SUBMITTED' AND r.highlight IS NOT NULL
              AND r.updated_at &gt;= #{since}
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            </script>
            """)
    long countRecentHighlightRecords(
            @Param("since") LocalDateTime since, @Param("wardId") Long wardId);

    /**
     * 按电子化状态计数的在册报告单数（E21-T15 / C-SCAN-011：C 指标「待录入扫描件数」= status = 'PENDING'）： 条件与全院报告单检索
     * （E16-T05）同一条件树（NORMAL 在册 + 病区），只统计电子化状态非空的报告单（A 租户行恒 NULL，A-ATT-018）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.status = 'NORMAL' AND r.digitize_status = #{digitizeStatus}
            <if test="wardId != null">AND pt.ward_id = #{wardId}</if>
            </script>
            """)
    long countReportsByDigitizeStatus(
            @Param("digitizeStatus") String digitizeStatus, @Param("wardId") Long wardId);

    /**
     * 趋势图分桶计数（A-DASH-002「按时间维度 × 粒度 × 分组统计患者数」）：一行 = 一个周期 × 一个分组取值。 bucketExpr / groupExpr /
     * dimColumn / joinClause 由服务层从枚举白名单拼装（见类注释），from / to 闭区间。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT ${bucketExpr} AS bucket,
            <choose>
              <when test="groupExpr != null">${groupExpr} AS groupKey</when>
              <otherwise>NULL AS groupKey</otherwise>
            </choose>
            , COUNT(*) AS total
            FROM pt_patient p
            ${joinClause}
            WHERE p.deleted = 0 AND ${dimColumn} &gt;= #{from} AND ${dimColumn} &lt;= #{to}
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY ${bucketExpr}
            <if test="groupExpr != null">, ${groupExpr}</if>
            ORDER BY ${bucketExpr}
            </script>
            """)
    List<TrendCountRow> countTrend(
            @Param("bucketExpr") String bucketExpr,
            @Param("groupExpr") String groupExpr,
            @Param("dimColumn") String dimColumn,
            @Param("joinClause") String joinClause,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("wardId") Long wardId);
}
