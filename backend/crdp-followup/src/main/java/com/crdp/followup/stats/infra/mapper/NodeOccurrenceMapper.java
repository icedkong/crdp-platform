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

    /**
     * 刷新输入：常规任务行（跟进 / 临时任务由查询过滤；关联记录的 visit_date / 表单 JSON / 模板版本随行—— G2
     * 起窗口内完成按**实际随访日期**判定，visit_date 列为空的任务录入记录按表单字段 followup_date 解析）。
     */
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
            String completeKind,
            LocalDate recordVisitDate,
            String recordFormCode,
            Integer recordTemplateVersion,
            String recordData) {}

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

    /**
     * 按随访年度聚合结果（E19-T04 / A-DASH-006 每年随访率）：患者级去重计数（COUNT DISTINCT —— 多计划并行时 同一患者同一年度只计一次分母 /
     * 分子，暂行口径见 OQ-109②）；refreshedDate 随行供 asOf（最近刷新日）。
     */
    record YearCountRow(
            int followupYear,
            long dueCount,
            long followedCount,
            long inWindowCount,
            long lostCount,
            long deathCount,
            LocalDate refreshedDate) {}

    /**
     * 节点随访率卡片行（E19-T04 / A-DASH-012、C-DASH-004）：一行 = 一个节点 × 第 N 次（入组行计数）； repeatMode / followupYear
     * 供服务层拼行标签（固定节点 = 节点名、年度循环 = 第 N 年、其余循环 = 第 N 次）。
     */
    record NodeRateRow(
            long nodeId,
            String nodeName,
            String repeatMode,
            int occurrence,
            Integer followupYear,
            long dueCount,
            long followedCount,
            long inWindowCount,
            long lostCount,
            long deathCount,
            LocalDate refreshedDate) {}

    /** 随访计划候选（节点随访率卡片「先选随访计划」，A-DASH-012：配置表读取，不输出患者数据）。 */
    record PlanOptionRow(long id, String name) {}

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
                    "每日刷新（E19-T01）读取常规任务行与关联记录（实际随访日期解析用列）重建事实表："
                            + "作业无用户上下文，行内无患者明细；任务查询侧（E15-T01）按 @DataScope 出行")
    @Select(
            """
            SELECT t.id AS taskId, t.record_id AS recordId, t.enrollment_id AS enrollmentId,
                   t.node_id AS nodeId, t.occurrence, t.plan_date AS planDate,
                   t.window_start AS windowStart, t.window_end AS windowEnd,
                   t.create_type AS createType, t.status, t.complete_kind AS completeKind,
                   r.visit_date AS recordVisitDate, r.form_code AS recordFormCode,
                   r.template_version AS recordTemplateVersion, r.data AS recordData
            FROM fu_task t
            LEFT JOIN fu_record r ON r.id = t.record_id
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

    // ---------------------------------------------------------------- 卡片查询（E19-T04：@DataScope 出行 +
    // 病区 / 队列收窄）

    /**
     * 按随访年度聚合（A-DASH-006 每年随访率 = 年度节点的节点随访率，口径 Q38）： 只计年度节点（followup_year 非空）与 due = 1
     * 的行；患者级去重（多计划并行时同一患者同一年度只计一次，OQ-109②）。 病区按患者当前病区收窄（join pt_patient），队列为起算年份。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT o.followup_year AS followupYear,
                   COUNT(DISTINCT o.patient_id) AS dueCount,
                   COUNT(DISTINCT CASE WHEN o.followed = 1 THEN o.patient_id END) AS followedCount,
                   COUNT(DISTINCT CASE WHEN o.in_window = 1 THEN o.patient_id END) AS inWindowCount,
                   COUNT(DISTINCT CASE WHEN o.lost = 1 THEN o.patient_id END) AS lostCount,
                   COUNT(DISTINCT CASE WHEN o.death_confirmed = 1 THEN o.patient_id END) AS deathCount,
                   MAX(o.refreshed_date) AS refreshedDate
            FROM st_node_occurrence o
            JOIN pt_patient p ON p.id = o.patient_id AND p.deleted = 0
            WHERE o.due = 1 AND o.followup_year IS NOT NULL
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            <if test="cohortYear != null">AND o.cohort_year = #{cohortYear}</if>
            GROUP BY o.followup_year
            ORDER BY o.followup_year
            </script>
            """)
    List<YearCountRow> aggregateByYear(
            @Param("wardId") Long wardId, @Param("cohortYear") Integer cohortYear);

    /**
     * 节点随访率卡片聚合（A-DASH-012、C-DASH-004；口径与 aggregateByNode 同、加病区 / 队列筛选与标签列）： 一行 = 一个节点 × 第 N
     * 次的入组行计数；repeatMode / followupYear 供服务层拼行标签。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT o.node_id AS nodeId, n.name AS nodeName, n.repeat_mode AS repeatMode,
                   o.occurrence, o.followup_year AS followupYear,
                   COUNT(*) AS dueCount,
                   COALESCE(SUM(o.followed), 0) AS followedCount,
                   COALESCE(SUM(o.in_window), 0) AS inWindowCount,
                   COALESCE(SUM(o.lost), 0) AS lostCount,
                   COALESCE(SUM(o.death_confirmed), 0) AS deathCount,
                   MAX(o.refreshed_date) AS refreshedDate
            FROM st_node_occurrence o
            JOIN fu_plan_node n ON n.id = o.node_id
            JOIN pt_patient p ON p.id = o.patient_id AND p.deleted = 0
            WHERE o.plan_id = #{planId} AND o.due = 1
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            <if test="cohortYear != null">AND o.cohort_year = #{cohortYear}</if>
            GROUP BY o.node_id, n.name, n.repeat_mode, n.sort_no, o.occurrence, o.followup_year
            ORDER BY n.sort_no, o.node_id, o.occurrence
            </script>
            """)
    List<NodeRateRow> aggregateNodeRates(
            @Param("planId") long planId,
            @Param("wardId") Long wardId,
            @Param("cohortYear") Integer cohortYear);

    /** 队列年份候选（起算日期年份，A-DASH-006「出院年份队列」/ A-DASH-012「队列」筛选的下拉候选，倒序）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT DISTINCT o.cohort_year
            FROM st_node_occurrence o
            WHERE o.due = 1 AND o.cohort_year IS NOT NULL
            <if test="planId != null">AND o.plan_id = #{planId}</if>
            ORDER BY o.cohort_year DESC
            </script>
            """)
    List<Integer> findCohortYears(@Param("planId") Long planId);

    /**
     * 已发布计划候选（A-DASH-012「先选随访计划（默认本租户的预置计划）」；id 升序 = 预置计划在前）。 fu_plan 是配置表且不带在组人数子查询，不触达受控表，无需范围声明。
     */
    @Select(
            """
            SELECT p.id, p.name
            FROM fu_plan p
            WHERE p.status = 'PUBLISHED'
            ORDER BY p.id
            """)
    List<PlanOptionRow> findPublishedPlans();
}
