package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 医护负责患者数的计数查询（E19-T07 / A-DASH-009、C-DASH-010；docs/api/stats.md §2 {@code GET
 * /stats/doctor-workload}）。
 *
 * <p>「按责任人统计」= 按<b>患者责任医生</b>（{@code pt_patient.responsible_user_id}）归口：本表是 「负责患者数」面板口径（行锚点 = 至少负责
 * 1 名在册患者的医生），患者两列与患者列表检索 （{@code PatientSearchMapper}：{@code deleted = 0} + {@code
 * responsible_user_id} 筛选）、任务两列与 指标卡（{@link
 * com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper}：常规任务口径与 {@code status =
 * 'OVERDUE'}）、每年随访率与每年随访率卡片（{@link NodeOccurrenceMapper}：Q38 口径、 患者 ×
 * 年度去重）逐条件一致——「一个口径一个查询」（stats.md §1）。任务负责人是他人的任务仍计入 患者责任医生行（暂行口径见 OQ-118）。 数据范围由语句上的
 * {@code @DataScope(resource = "patient")} 按登录快照出行（范围内无患者 → 空行集，不报错）；wardId 为范围内再收窄。
 */
@Mapper
public interface DoctorWorkloadMapper {

    /** 患者两列行：totalPatients 含全部随访状态（在册；Q01「患者总数含已死亡 / 已终止」同口径）。 */
    record DoctorPatientRow(
            long doctorId, String doctorName, long totalPatients, long activePatients) {}

    /** 任务计数行（本月应随访 / 逾期共用）。 */
    record DoctorTaskRow(long doctorId, long total) {}

    /** 每年随访率行：患者 × 随访年度去重计数（多计划并行同一年度只计一次，OQ-109② 同口径）。 */
    record DoctorRateRow(
            long doctorId, long dueCount, long followedCount, LocalDate refreshedDate) {}

    /**
     * 按责任医生统计患者（负责患者数 / 在随访；在随访 = followup_status ACTIVE）： 行锚点——只列出至少负责 1 名在册患者的医生，按负责患者数降序、医生 id
     * 升序（行序稳定）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.responsible_user_id AS doctorId, u.display_name AS doctorName,
                   COUNT(*) AS totalPatients,
                   COALESCE(SUM(p.followup_status = 'ACTIVE'), 0) AS activePatients
            FROM pt_patient p
            JOIN sys_user u ON u.id = p.responsible_user_id
            WHERE p.deleted = 0 AND p.responsible_user_id IS NOT NULL
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY p.responsible_user_id, u.display_name
            ORDER BY totalPatients DESC, p.responsible_user_id
            </script>
            """)
    List<DoctorPatientRow> countPatientsByDoctor(@Param("wardId") Long wardId);

    /**
     * 本月应随访（与指标卡 {@code countMonthDue} 同条件树 + 按责任医生分组）： 计划日期落在 [from, to] 的常规随访任务（occurrence &gt; 0
     * 且 followup_seq = 0，排除跟进 / 临时任务）， 入组仍在组（ACTIVE / PAUSED；已移除不计，OQ-107）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.responsible_user_id AS doctorId, COUNT(*) AS total
            FROM fu_task t
            JOIN fu_enrollment e ON e.id = t.enrollment_id
                AND e.status IN ('ACTIVE', 'PAUSED')
            JOIN pt_patient p ON p.id = t.patient_id AND p.deleted = 0
            WHERE t.occurrence &gt; 0 AND t.followup_seq = 0
              AND t.plan_date &gt;= #{from} AND t.plan_date &lt;= #{to}
              AND p.responsible_user_id IS NOT NULL
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY p.responsible_user_id
            </script>
            """)
    List<DoctorTaskRow> countMonthDueByDoctor(
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("wardId") Long wardId);

    /**
     * 当前逾期（与指标卡 {@code countTasksByStatus('OVERDUE')} 同条件树 + 按责任医生分组）： 钻取目标任务列表的 doctorId
     * 为「任务负责人或患者责任医生」（E15-T02），本表按面板口径只计责任医生行（OQ-118）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.responsible_user_id AS doctorId, COUNT(*) AS total
            FROM fu_task t
            JOIN pt_patient p ON p.id = t.patient_id AND p.deleted = 0
            WHERE t.status = 'OVERDUE' AND p.responsible_user_id IS NOT NULL
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY p.responsible_user_id
            </script>
            """)
    List<DoctorTaskRow> countOverdueByDoctor(@Param("wardId") Long wardId);

    /**
     * 每年随访率按责任医生汇总（与每年随访率卡片 {@code aggregateByYear} 同事实表同口径 Q38： 年度节点 followup_year 非空且 due =
     * 1；逾期完成 / 补录 / 历史导入都计入已随访）： 一名医生的汇总 = 其各年度行相加 （分母 / 分子都按患者 × 年度去重）；refreshedDate 供
     * asOf（最近刷新日）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.responsible_user_id AS doctorId,
                   COUNT(DISTINCT o.patient_id, o.followup_year) AS dueCount,
                   COUNT(DISTINCT CASE WHEN o.followed = 1 THEN o.patient_id END,
                            CASE WHEN o.followed = 1 THEN o.followup_year END) AS followedCount,
                   MAX(o.refreshed_date) AS refreshedDate
            FROM st_node_occurrence o
            JOIN pt_patient p ON p.id = o.patient_id AND p.deleted = 0
            WHERE o.due = 1 AND o.followup_year IS NOT NULL
              AND p.responsible_user_id IS NOT NULL
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY p.responsible_user_id
            </script>
            """)
    List<DoctorRateRow> aggregateYearlyRateByDoctor(@Param("wardId") Long wardId);
}
