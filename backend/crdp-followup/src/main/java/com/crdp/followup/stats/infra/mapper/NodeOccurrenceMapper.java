package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 节点随访事实表（E19-T01 / A-DASH-012）的租户库读写。
 *
 * <p>刷新加载与重建侧全部 {@code @IgnoreScope}：每日 00:10 作业无用户上下文（AGENTS §5.2 调度入口白名单口径），
 * 只在本租户库内取聚合前原始行重建派生表；查询侧（E19-T04 节点随访率卡片、E19-T02/T03 首页）按语句上的 {@code @DataScope(resource =
 * "patient")} 以患者病区出行（st_node_occurrence 在 ControlledTables）。
 */
@Mapper
public interface NodeOccurrenceMapper {

    /** 刷新输入：入组行（全部状态——移除的入组窗口结束前行仍可能应随访，由状态回看判定）。 */
    record EnrollmentRow(
            long id,
            long patientId,
            long planId,
            LocalDate startDate,
            String mode,
            LocalDate enrolledOn) {}

    /** 刷新输入：常规任务行（跟进 / 临时任务由查询过滤；recordStatus = 关联记录状态）。 */
    record TaskRow(
            long taskId,
            Long recordId,
            long enrollmentId,
            long nodeId,
            int occurrence,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            String createType,
            String status,
            String completeKind) {}

    /** 刷新输入：锚点记录行（E18 历史导入 / 历史未随访，V73 锚点列；任务录入记录经任务行携带）。 */
    record AnchoredRecordRow(
            long recordId,
            long enrollmentId,
            long nodeId,
            int occurrence,
            String source,
            String status,
            LocalDate visitDate) {}

    /** 刷新输入：无锚点的已提交补录记录（A-FU-014 手工历史补录，按计划日期最近归属）。 */
    record FloatingRecordRow(long recordId, long patientId, LocalDate visitDate) {}

    /** 刷新输入：患者状态时间线（pt_status_history；统计按窗口开始日回看，V39）。 */
    record StatusPointRow(long patientId, String status, LocalDate effectiveDate) {}

    /** 按节点聚合结果（验收：按节点出应随访、已随访数；失访 / 死亡 / 窗口内完成随行供 E19-T04 卡片）。 */
    record NodeCountRow(
            long nodeId,
            String nodeName,
            int occurrence,
            long dueCount,
            long followedCount,
            long inWindowCount,
            long lostCount,
            long deathCount) {}

    // ---------------------------------------------------------------- 刷新加载（@IgnoreScope：每日作业）

    @IgnoreScope(reason = "每日刷新（E19-T01）无用户上下文：只取入组定位列与起算日期重建派生事实表，" + "不输出患者明细；查询侧按 @DataScope 出行")
    @Select(
            """
            SELECT e.id, e.patient_id AS patientId, e.plan_id AS planId,
                   e.start_date AS startDate, e.mode, DATE(e.created_at) AS enrolledOn
            FROM fu_enrollment e
            ORDER BY e.id
            """)
    List<EnrollmentRow> findAllEnrollments();

    @IgnoreScope(
            reason =
                    "每日刷新（E19-T01）读取常规任务行与关联记录状态重建事实表：作业无用户上下文，"
                            + "行内无患者明细；任务查询侧（E15-T01）按 @DataScope 出行")
    @Select(
            """
            SELECT t.id AS taskId, t.record_id AS recordId, t.enrollment_id AS enrollmentId,
                   t.node_id AS nodeId, t.occurrence, t.plan_date AS planDate,
                   t.window_start AS windowStart, t.window_end AS windowEnd,
                   t.create_type AS createType, t.status, t.complete_kind AS completeKind
            FROM fu_task t
            WHERE t.occurrence > 0 AND t.followup_seq = 0
            ORDER BY t.enrollment_id, t.node_id, t.occurrence
            """)
    List<TaskRow> findRegularTasks();

    @IgnoreScope(
            reason =
                    "每日刷新（E19-T01）读取历史导入 / 历史未随访锚点记录（E18-T05）重建事实表："
                            + "作业无用户上下文，行内无患者明细；记录查询侧按 @DataScope 出行")
    @Select(
            """
            SELECT r.id AS recordId, r.enrollment_id AS enrollmentId, r.node_id AS nodeId,
                   r.occurrence, r.source, r.status, r.visit_date AS visitDate
            FROM fu_record r
            WHERE r.enrollment_id IS NOT NULL AND r.node_id IS NOT NULL AND r.occurrence IS NOT NULL
              AND r.task_id IS NULL
            ORDER BY r.enrollment_id, r.node_id, r.occurrence
            """)
    List<AnchoredRecordRow> findAnchoredRecords();

    @IgnoreScope(reason = "每日刷新（E19-T01）读取无锚点补录记录做归属（A-FU-014）：作业无用户上下文，" + "只取 id 与随访日期")
    @Select(
            """
            SELECT r.id AS recordId, r.patient_id AS patientId, r.visit_date AS visitDate
            FROM fu_record r
            WHERE r.source = 'BACKFILL' AND r.status = 'SUBMITTED'
              AND r.enrollment_id IS NULL AND r.task_id IS NULL
            ORDER BY r.patient_id, r.visit_date
            """)
    List<FloatingRecordRow> findFloatingBackfills();

    @IgnoreScope(reason = "每日刷新（E19-T01）读取患者状态时间线按窗口开始日回看（V39 设计口径）：" + "作业无用户上下文，只取状态与生效日期")
    @Select(
            """
            SELECT h.patient_id AS patientId, h.status, h.effective_date AS effectiveDate
            FROM pt_status_history h
            ORDER BY h.patient_id, h.effective_date, h.id
            """)
    List<StatusPointRow> findStatusHistory();

    // ---------------------------------------------------------------- 重建（@IgnoreScope：派生表整表重写）

    @IgnoreScope(reason = "每日刷新（E19-T01）整表重建派生事实表：无用户上下文，表本身不可人工编辑")
    @Delete("DELETE FROM st_node_occurrence")
    int deleteAll();

    @IgnoreScope(reason = "每日刷新（E19-T01）写入派生事实表：无用户上下文，表本身不可人工编辑")
    @Insert(
            """
            INSERT INTO st_node_occurrence
              (patient_id, enrollment_id, plan_id, plan_code, plan_version, node_id, occurrence,
               followup_year, plan_date, window_start, window_end, cohort_year, due, due_reason,
               task_id, record_id, followed, complete_kind, in_window, lost, death_confirmed,
               refreshed_date)
            VALUES
              (#{patientId}, #{enrollmentId}, #{planId}, #{planCode}, #{planVersion}, #{nodeId},
               #{occurrence}, #{followupYear}, #{planDate}, #{windowStart}, #{windowEnd},
               #{cohortYear}, #{due}, #{dueReason}, #{taskId}, #{recordId}, #{followed},
               #{completeKind}, #{inWindow}, #{lost}, #{deathConfirmed}, #{refreshedDate})
            """)
    int insertFact(NodeOccurrenceInsertRow row);

    // ---------------------------------------------------------------- 查询侧（@DataScope 出行）

    /**
     * 按节点 × 第 N 次聚合（E19-T01 验收「按节点出应随访、已随访数」；只计 due = 1 的行， 窗口未结束 /
     * 不符合条件的行不进分母）。按计划内排序（fu_plan_node.sort_no）输出。 E19-T04 节点随访率卡片与每年随访率共用本查询。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT o.node_id AS nodeId, n.name AS nodeName, o.occurrence,
                   COUNT(*) AS dueCount,
                   COALESCE(SUM(o.followed), 0) AS followedCount,
                   COALESCE(SUM(o.in_window), 0) AS inWindowCount,
                   COALESCE(SUM(o.lost), 0) AS lostCount,
                   COALESCE(SUM(o.death_confirmed), 0) AS deathCount
            FROM st_node_occurrence o
            JOIN fu_plan_node n ON n.id = o.node_id
            WHERE o.plan_id = #{planId} AND o.due = 1
            GROUP BY o.node_id, n.name, n.sort_no, o.occurrence
            ORDER BY n.sort_no, o.node_id, o.occurrence
            """)
    List<NodeCountRow> aggregateByNode(@Param("planId") long planId);
}
