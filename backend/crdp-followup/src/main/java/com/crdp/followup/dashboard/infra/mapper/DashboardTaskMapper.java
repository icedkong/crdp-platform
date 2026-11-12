package com.crdp.followup.dashboard.infra.mapper;

import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 首页任务看板与任务表的取数（E19-T03 / A-DASH-003、A-DASH-004、A-DASH-008、C-DASH-003；docs/api/stats.md §3 cardCode
 * = task_board / task_table）。
 *
 * <p>「一个口径一个查询」（stats.md §1）：看板各列计数与任务列表检索（{@link FollowupTaskQueryMapper}）共用同一条件树——scope（MINE 我的 /
 * WARD 本病区 / ALL 全部，A-DASH-004）、病区（页面级 wardId，A-DASH-005）、负责人（assigneeId ↔ 列表 doctorId，
 * 同一「任务负责人或患者责任医生」口径）逐条件一致； 已完成列另加完成时间窗口（A 本月 / C 本周，C-DASH-003），任务表「今日任务」加窗口包含当日 （A 规格 §9
 * 任务表页签口径）。 数据范围由语句上的 {@code @DataScope(resource = "patient")} 出行（fu_task 在
 * ControlledTables，越范围患者的任务直接不可见；范围为空追加 {@code AND (1 = 0)}，返回全 0 / 空列表，不报错）。
 *
 * <p>行结构与任务列表共用 {@link FollowupTaskQueryMapper.TaskRowRaw}（姓名 / 病案号为密文，应用层解密后脱敏输出，AGENTS §5.3）。
 */
@Mapper
public interface DashboardTaskMapper {

    /**
     * 看板 / 任务表共用条件（全部可空 = 不过滤）：
     *
     * <ul>
     *   <li>scope：MINE / WARD / ALL（页签语义，userId 由服务层填，A-DASH-004）；
     *   <li>status：单状态等值（看板按列取，列表按 IN；等值与列表单值 IN 同条件树）；
     *   <li>openOnly：未完成五态（PENDING / IN_PROGRESS / OVERDUE / LOST_CANDIDATE / SKIPPED_SUSPENDED，与列表
     *       MINE 默认口径一致——「今日任务」「我负责的任务」的未完成语义）；
     *   <li>wardId：页面级病区筛选（A-DASH-005，单值——列表 wardIds 的单值形态）；
     *   <li>assigneeId：负责人筛选（A-DASH-004「按负责人筛选」，↔ 列表 doctorId：assignee_id 或 responsible_user_id）；
     *   <li>doneFrom / doneTo：已完成列的完成时间窗口（DATE(completed_at) 闭区间；A 本月 / C 本周）；
     *   <li>windowOn：窗口包含该日（window_start ≤ 当日 ≤ window_end，A 规格 §9「今日任务 = 今天处于窗口内且未完成的任务」）。
     * </ul>
     */
    record TaskCardCriteria(
            String scope,
            long userId,
            String status,
            boolean openOnly,
            Long wardId,
            Long assigneeId,
            LocalDate doneFrom,
            LocalDate doneTo,
            LocalDate windowOn) {}

    /** 任务表页签计数（TODAY 今日 / OVERDUE 超时 / MINE 我负责，一次算完；空表 SUM 为 null 由服务层归零）。 */
    record TableCountRow(Long today, Long overdue, Long mine) {}

    /** 病区候选行（ward 为独立字典，不在 ControlledTables；行本身不敏感）。 */
    record WardOptionRow(long id, String name) {}

    /** 按状态计数（看板一列的总数；与 {@link #cardTasks} 同一条件树）——列表 status 单值 IN 的总数与这里恒等。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            WHERE 1 = 1
            <if test="c.scope == 'MINE'">
              AND (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
            </if>
            <if test="c.scope == 'WARD'">
              AND pt.ward_id IN (SELECT uw.ward_id FROM sys_user_ward uw WHERE uw.user_id = #{c.userId})
            </if>
            <if test="c.openOnly">
              AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            </if>
            <if test="c.status != null">AND t.status = #{c.status}</if>
            <if test="c.wardId != null">AND pt.ward_id = #{c.wardId}</if>
            <if test="c.assigneeId != null">
              AND (t.assignee_id = #{c.assigneeId} OR pt.responsible_user_id = #{c.assigneeId})
            </if>
            <if test="c.doneFrom != null">
              AND DATE(t.completed_at) &gt;= #{c.doneFrom} AND DATE(t.completed_at) &lt;= #{c.doneTo}
            </if>
            <if test="c.windowOn != null">
              AND t.window_start &lt;= #{c.windowOn} AND t.window_end &gt;= #{c.windowOn}
            </if>
            </script>
            """)
    long countByStatus(@Param("c") TaskCardCriteria criteria);

    /**
     * 看板一列的前 N 张卡片（A 规格 §9「每列默认显示最紧急的 20 张卡片（按窗口结束日排序）」）： 未完成列按窗口结束日升序（最紧急在前）；
     * 已完成列按完成时间降序（最近完成在前——窗口结束日对已完成列无紧急语义，见 stats.md §3）。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT t.id, t.patient_id AS patientId,
                   pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   pt.legacy_no AS legacyNo,
                   w.name AS wardName, n.name AS nodeName,
                   t.occurrence, t.task_type AS taskType, t.form_code AS formCode,
                   t.plan_date AS planDate, t.window_start AS windowStart,
                   t.window_end AS windowEnd, t.open_date AS openDate,
                   t.status, t.complete_kind AS completeKind, t.create_type AS createType,
                   t.record_id AS recordId, t.assignee_id AS assigneeId,
                   u.display_name AS assigneeName, COALESCE(e.doctor_next_date, e.next_plan_date) AS nextPlanDate
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN sys_user u ON u.id = t.assignee_id
            LEFT JOIN fu_enrollment e ON e.id = t.enrollment_id
            WHERE 1 = 1
            <if test="c.scope == 'MINE'">
              AND (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
            </if>
            <if test="c.scope == 'WARD'">
              AND pt.ward_id IN (SELECT uw.ward_id FROM sys_user_ward uw WHERE uw.user_id = #{c.userId})
            </if>
            <if test="c.openOnly">
              AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            </if>
            <if test="c.status != null">AND t.status = #{c.status}</if>
            <if test="c.wardId != null">AND pt.ward_id = #{c.wardId}</if>
            <if test="c.assigneeId != null">
              AND (t.assignee_id = #{c.assigneeId} OR pt.responsible_user_id = #{c.assigneeId})
            </if>
            <if test="c.doneFrom != null">
              AND DATE(t.completed_at) &gt;= #{c.doneFrom} AND DATE(t.completed_at) &lt;= #{c.doneTo}
            </if>
            <if test="c.windowOn != null">
              AND t.window_start &lt;= #{c.windowOn} AND t.window_end &gt;= #{c.windowOn}
            </if>
            ORDER BY
            <choose>
              <when test="doneOrder">t.completed_at DESC, t.id DESC</when>
              <otherwise>t.window_end ASC, t.id ASC</otherwise>
            </choose>
            LIMIT #{limit}
            </script>
            """)
    List<FollowupTaskQueryMapper.TaskRowRaw> cardTasks(
            @Param("c") TaskCardCriteria criteria,
            @Param("doneOrder") boolean doneOrder,
            @Param("limit") int limit);

    /**
     * 任务表三个页签的计数（A 规格 §9 任务表页签口径一次算完）： TODAY = 窗口包含当日且未完成五态（含当天到期的跟进任务与站内提醒）、 OVERDUE = 状态为逾期、MINE
     * = 负责人或责任医生为本人且未完成五态。病区筛选（wardId）与数据范围同条件树出行。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT
              SUM(CASE WHEN t.window_start &lt;= #{c.windowOn} AND t.window_end &gt;= #{c.windowOn}
                        AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
                   THEN 1 ELSE 0 END) AS today,
              SUM(CASE WHEN t.status = 'OVERDUE' THEN 1 ELSE 0 END) AS overdue,
              SUM(CASE WHEN (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
                        AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
                   THEN 1 ELSE 0 END) AS mine
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            WHERE 1 = 1
            <if test="c.wardId != null">AND pt.ward_id = #{c.wardId}</if>
            </script>
            """)
    TableCountRow tableTabCounts(@Param("c") TaskCardCriteria criteria);

    /**
     * 任务表当前页签的前 N 行（tab = TODAY / OVERDUE / MINE；各页签条件与 {@link #tableTabCounts} 分支逐项一致， 按 plan_date
     * 升序——与任务列表默认顺序相同）。「可直接进入随访工作台」（A-DASH-008）：行携带 recordId / taskType / status，前端沿用列表的录入与完成提醒入口。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT t.id, t.patient_id AS patientId,
                   pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   pt.legacy_no AS legacyNo,
                   w.name AS wardName, n.name AS nodeName,
                   t.occurrence, t.task_type AS taskType, t.form_code AS formCode,
                   t.plan_date AS planDate, t.window_start AS windowStart,
                   t.window_end AS windowEnd, t.open_date AS openDate,
                   t.status, t.complete_kind AS completeKind, t.create_type AS createType,
                   t.record_id AS recordId, t.assignee_id AS assigneeId,
                   u.display_name AS assigneeName, COALESCE(e.doctor_next_date, e.next_plan_date) AS nextPlanDate
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN sys_user u ON u.id = t.assignee_id
            LEFT JOIN fu_enrollment e ON e.id = t.enrollment_id
            WHERE 1 = 1
            <if test="c.wardId != null">AND pt.ward_id = #{c.wardId}</if>
            <choose>
              <when test="tab == 'TODAY'">
                AND t.window_start &lt;= #{c.windowOn} AND t.window_end &gt;= #{c.windowOn}
                AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
              </when>
              <when test="tab == 'OVERDUE'">
                AND t.status = 'OVERDUE'
              </when>
              <otherwise>
                AND (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
                AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
              </otherwise>
            </choose>
            ORDER BY t.plan_date ASC, t.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<FollowupTaskQueryMapper.TaskRowRaw> tableTasks(
            @Param("c") TaskCardCriteria criteria,
            @Param("tab") String tab,
            @Param("limit") int limit);

    /**
     * 病区筛选候选（A-DASH-005「默认本人全部可见病区」）：ward 为独立字典（不在 ControlledTables，候选本身不敏感）； 行级隔离仍由各卡片
     * 查询的 @DataScope 出行（候选之外的病区数据本就不可见）。ids 为空 = 全部启用病区（全院 / 科室 / 本人快照——可见病区不止 snapshot.wardIds
     * 一种来源，回落全集，选出的病区仍受行级范围约束）；非空 = 按登录快照的病区清单取交集。
     */
    @Select(
            """
            <script>
            SELECT id, name FROM ward
            WHERE status = 'ENABLED'
            <if test="ids != null">
              AND id IN
              <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            ORDER BY sort_no ASC, id ASC
            </script>
            """)
    List<WardOptionRow> findWardOptions(@Param("ids") List<Long> ids);
}
