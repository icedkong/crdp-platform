package com.crdp.followup.task.infra.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 我的任务 / 任务列表（E15-T02 / A-FU-004、C-FU-020；docs/api/followup.md §2，页面 /followup/task，pages.md §7）。
 *
 * <p>患者面数据（fu_task / pt_patient / fu_enrollment 均在 {@code ControlledTables}）按语句上的
 * {@code @DataScope(resource = "patient")} 出行：拦截器改写后越范围患者的任务直接不可见，范围为空追加 {@code AND (1 = 0)}
 * 返回空列表（AGENTS §5.2）； 「仅本人负责的患者」快照另含任务负责人分支（fu_task.assignee_id，A-WARD-003）。
 *
 * <p>姓名 / 病案号检索词（keyword）为盲索引精确匹配（与患者列表 E13-T04 同口径，不做模糊）；行内姓名 / 病案号只输出脱敏值（应用层解密后掩码，AGENTS §5.3）。
 *
 * <p>页签（A-FU-004「默认显示我的待办」）：MINE 我的待办 = 负责人或责任医生是本人且未完成；WARD 本病区 = 患者病区 ∈ 本人病区（sys_user_ward）； ALL
 * 全部。三个页签的计数（表单页「上一条 / 下一条」与页签徽标）与行查询共用同一条件树。
 */
@Mapper
public interface FollowupTaskQueryMapper {

    /** 列表行原始数据：姓名 / 病案号为密文，由应用层解密后脱敏输出。 */
    record TaskRowRaw(
            long id,
            long patientId,
            String nameCipher,
            String mrnCipher,
            String legacyNo,
            String wardName,
            String nodeName,
            int occurrence,
            String taskType,
            String formCode,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate openDate,
            String status,
            String completeKind,
            String createType,
            Long recordId,
            Long assigneeId,
            String assigneeName,
            LocalDate nextPlanDate) {}

    /**
     * 检索条件（除 scope 外全部可空 = 不过滤）。scope：MINE / WARD / ALL（页签语义，userId 由服务层填）； nameBlind / mrnBlind
     * 同源于一个检索词，OR 匹配；status 为空时 MINE 页签默认未完成五态（PENDING / IN_PROGRESS / OVERDUE / LOST_CANDIDATE /
     * SKIPPED_SUSPENDED——失访候选仍需医生确认，E15-T05）；unassigned 只看待认领（assignee NULL，病区公共池，A-FU-009 /
     * E15-T03）； windowFrom / windowTo（窗口包含区间，E19-T03「今日任务」钻取）与 completedFrom /
     * completedTo（完成时间区间，看板已完成列钻取） 为钻取专用筛选，页面筛选条暂不提供。
     */
    record TaskCriteria(
            String scope,
            long userId,
            List<String> status,
            boolean openOnly,
            List<Long> wardIds,
            Long doctorId,
            LocalDate dueFrom,
            LocalDate dueTo,
            LocalDate windowFrom,
            LocalDate windowTo,
            LocalDate completedFrom,
            LocalDate completedTo,
            String nodeName,
            String nameBlind,
            String mrnBlind,
            boolean unassigned) {}

    /** 页签计数（MINE / WARD / ALL 三态一次算完，非 scope 过滤共用；空表 SUM 为 null，由服务层归零）。 */
    record TabCountRow(Long mine, Long ward, Long total) {}

    /** 分页检索一页（默认按计划日期升序——最早的随访最先做，A-FU-004 列表口径）。 */
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
                   u.display_name AS assigneeName, e.next_plan_date AS nextPlanDate
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN sys_user u ON u.id = t.assignee_id
            LEFT JOIN fu_enrollment e ON e.id = t.enrollment_id
            WHERE 1 = 1
            <if test="c.nameBlind != null">
              AND (pt.name_blind = #{c.nameBlind} OR pt.mrn_blind = #{c.mrnBlind})
            </if>
            <if test="c.scope == 'MINE'">
              AND (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
            </if>
            <if test="c.scope == 'WARD'">
              AND pt.ward_id IN (SELECT uw.ward_id FROM sys_user_ward uw WHERE uw.user_id = #{c.userId})
            </if>
            <if test="c.openOnly">
              AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            </if>
            <if test="c.status != null">
              AND t.status IN
              <foreach collection="c.status" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.wardIds != null">
              AND pt.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.doctorId != null">
              AND (t.assignee_id = #{c.doctorId} OR pt.responsible_user_id = #{c.doctorId})
            </if>
            <if test="c.unassigned">AND t.assignee_id IS NULL</if>
            <if test="c.dueFrom != null">AND t.plan_date &gt;= #{c.dueFrom}</if>
            <if test="c.dueTo != null">AND t.plan_date &lt;= #{c.dueTo}</if>
            <if test="c.windowFrom != null">
              AND t.window_end &gt;= #{c.windowFrom} AND t.window_start &lt;= #{c.windowTo}
            </if>
            <if test="c.completedFrom != null">
              AND DATE(t.completed_at) &gt;= #{c.completedFrom}
              AND DATE(t.completed_at) &lt;= #{c.completedTo}
            </if>
            <if test="c.nodeName != null">AND n.name LIKE CONCAT('%', #{c.nodeName}, '%')</if>
            ORDER BY t.plan_date ASC, t.id ASC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<TaskRowRaw> search(
            @Param("c") TaskCriteria criteria,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 条件总数（与 {@link #search} 同一条件树）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE 1 = 1
            <if test="c.nameBlind != null">
              AND (pt.name_blind = #{c.nameBlind} OR pt.mrn_blind = #{c.mrnBlind})
            </if>
            <if test="c.scope == 'MINE'">
              AND (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
            </if>
            <if test="c.scope == 'WARD'">
              AND pt.ward_id IN (SELECT uw.ward_id FROM sys_user_ward uw WHERE uw.user_id = #{c.userId})
            </if>
            <if test="c.openOnly">
              AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            </if>
            <if test="c.status != null">
              AND t.status IN
              <foreach collection="c.status" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.wardIds != null">
              AND pt.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.doctorId != null">
              AND (t.assignee_id = #{c.doctorId} OR pt.responsible_user_id = #{c.doctorId})
            </if>
            <if test="c.unassigned">AND t.assignee_id IS NULL</if>
            <if test="c.dueFrom != null">AND t.plan_date &gt;= #{c.dueFrom}</if>
            <if test="c.dueTo != null">AND t.plan_date &lt;= #{c.dueTo}</if>
            <if test="c.windowFrom != null">
              AND t.window_end &gt;= #{c.windowFrom} AND t.window_start &lt;= #{c.windowTo}
            </if>
            <if test="c.completedFrom != null">
              AND DATE(t.completed_at) &gt;= #{c.completedFrom}
              AND DATE(t.completed_at) &lt;= #{c.completedTo}
            </if>
            <if test="c.nodeName != null">AND n.name LIKE CONCAT('%', #{c.nodeName}, '%')</if>
            </script>
            """)
    long count(@Param("c") TaskCriteria criteria);

    /** 三个页签的计数（同一非 scope 条件树一次算完，MINE 恒按未完成口径）——页签徽标「我的待办 12 / 本病区 40 / 全部」。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT
              SUM(CASE WHEN (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
                        AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
                   THEN 1 ELSE 0 END) AS mine,
              SUM(CASE WHEN pt.ward_id IN
                        (SELECT uw.ward_id FROM sys_user_ward uw WHERE uw.user_id = #{c.userId})
                   THEN 1 ELSE 0 END) AS ward,
              COUNT(*) AS total
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE 1 = 1
            <if test="c.nameBlind != null">
              AND (pt.name_blind = #{c.nameBlind} OR pt.mrn_blind = #{c.mrnBlind})
            </if>
            <if test="c.status != null">
              AND t.status IN
              <foreach collection="c.status" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.wardIds != null">
              AND pt.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.doctorId != null">
              AND (t.assignee_id = #{c.doctorId} OR pt.responsible_user_id = #{c.doctorId})
            </if>
            <if test="c.unassigned">AND t.assignee_id IS NULL</if>
            <if test="c.dueFrom != null">AND t.plan_date &gt;= #{c.dueFrom}</if>
            <if test="c.dueTo != null">AND t.plan_date &lt;= #{c.dueTo}</if>
            <if test="c.windowFrom != null">
              AND t.window_end &gt;= #{c.windowFrom} AND t.window_start &lt;= #{c.windowTo}
            </if>
            <if test="c.completedFrom != null">
              AND DATE(t.completed_at) &gt;= #{c.completedFrom}
              AND DATE(t.completed_at) &lt;= #{c.completedTo}
            </if>
            <if test="c.nodeName != null">AND n.name LIKE CONCAT('%', #{c.nodeName}, '%')</if>
            </script>
            """)
    TabCountRow tabCounts(@Param("c") TaskCriteria criteria);

    /** 按记录 id 反查任务行（工作台表单页上下文与提交完成联动，E15-T02）；越范围 / 未关联任务的记录返回 null。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT t.id, t.patient_id AS patientId,
                   pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   pt.legacy_no AS legacyNo,
                   w.name AS wardName, n.name AS nodeName,
                   t.occurrence, t.task_type AS taskType, t.form_code AS formCode,
                   t.plan_date AS planDate, t.window_start AS windowStart,
                   t.window_end AS windowEnd, t.open_date AS openDate,
                   t.status, t.complete_kind AS completeKind, t.create_type AS createType,
                   t.record_id AS recordId, t.assignee_id AS assigneeId,
                   u.display_name AS assigneeName, e.next_plan_date AS nextPlanDate
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN sys_user u ON u.id = t.assignee_id
            LEFT JOIN fu_enrollment e ON e.id = t.enrollment_id
            WHERE t.record_id = #{recordId}
            """)
    TaskRowRaw taskContextByRecordId(@Param("recordId") long recordId);

    /**
     * cursor 任务的计划日期（队列 keyset 定位锚，E15-T02）：范围内可见才返回（越范围 / 不存在 null → 队列两头置空，按钮置灰）。
     * 不带筛选条件——提交后当前任务已离开「未完成」筛选，仍要能按顺序定位前后任务。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT t.plan_date
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            WHERE t.id = #{id}
            """)
    LocalDate cursorPlanDate(@Param("id") long id);

    /**
     * 队列相邻任务（表单页「上一条 / 下一条」，按当前筛选的列表顺序，pages.md §5）：keyset 取 cursor（plan_date, id）前后第一条； direction
     * = ASC 下一条 / DESC 上一条。条件树与 {@link #search} 一致（含页签 scope），保证顺着当前列表走。
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
                   u.display_name AS assigneeName, e.next_plan_date AS nextPlanDate
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN sys_user u ON u.id = t.assignee_id
            LEFT JOIN fu_enrollment e ON e.id = t.enrollment_id
            WHERE 1 = 1
            <if test="c.nameBlind != null">
              AND (pt.name_blind = #{c.nameBlind} OR pt.mrn_blind = #{c.mrnBlind})
            </if>
            <if test="c.scope == 'MINE'">
              AND (t.assignee_id = #{c.userId} OR pt.responsible_user_id = #{c.userId})
            </if>
            <if test="c.scope == 'WARD'">
              AND pt.ward_id IN (SELECT uw.ward_id FROM sys_user_ward uw WHERE uw.user_id = #{c.userId})
            </if>
            <if test="c.openOnly">
              AND t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            </if>
            <if test="c.status != null">
              AND t.status IN
              <foreach collection="c.status" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.wardIds != null">
              AND pt.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.doctorId != null">
              AND (t.assignee_id = #{c.doctorId} OR pt.responsible_user_id = #{c.doctorId})
            </if>
            <if test="c.unassigned">AND t.assignee_id IS NULL</if>
            <if test="c.dueFrom != null">AND t.plan_date &gt;= #{c.dueFrom}</if>
            <if test="c.dueTo != null">AND t.plan_date &lt;= #{c.dueTo}</if>
            <if test="c.windowFrom != null">
              AND t.window_end &gt;= #{c.windowFrom} AND t.window_start &lt;= #{c.windowTo}
            </if>
            <if test="c.completedFrom != null">
              AND DATE(t.completed_at) &gt;= #{c.completedFrom}
              AND DATE(t.completed_at) &lt;= #{c.completedTo}
            </if>
            <if test="c.nodeName != null">AND n.name LIKE CONCAT('%', #{c.nodeName}, '%')</if>
            <if test="after">
              AND (t.plan_date &gt; #{cursorPlanDate}
                   OR (t.plan_date = #{cursorPlanDate} AND t.id &gt; #{cursorId}))
            </if>
            <if test="!after">
              AND (t.plan_date &lt; #{cursorPlanDate}
                   OR (t.plan_date = #{cursorPlanDate} AND t.id &lt; #{cursorId}))
            </if>
            ORDER BY t.plan_date <if test="!after">DESC</if>, t.id <if test="!after">DESC</if>
            LIMIT 1
            </script>
            """)
    TaskRowRaw neighbor(
            @Param("c") TaskCriteria criteria,
            @Param("cursorPlanDate") LocalDate cursorPlanDate,
            @Param("cursorId") long cursorId,
            @Param("after") boolean after);

    /** 患者摘要行（补录记录的表单页头幅，E15-T07）：姓名 / 病案号为密文，应用层解密后脱敏输出；越范围 null。 */
    record PatientBriefRow(long id, String nameCipher, String mrnCipher, String wardName) {}

    /** 患者摘要（@DataScope 出行；越范围 / 不存在 null）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT pt.id, pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   w.name AS wardName
            FROM pt_patient pt
            LEFT JOIN ward w ON w.id = pt.ward_id
            WHERE pt.id = #{patientId} AND pt.deleted = 0
            """)
    PatientBriefRow patientBrief(@Param("patientId") long patientId);

    // ---------------------------------------------------------------- 患者时间轴（E15-T07 /
    // A-FU-023、C-FU-023）

    /**
     * 时间轴任务行：某患者的全部<b>主任务</b>（create_type != FOLLOWUP——跟进任务不单列节点，窗口进度并入主任务口径），
     * 按计划日期升序；子计划（fu_plan_node parent 行）名随行带出（A「第 N 年」的展示位，数据驱动不写死）。
     */
    record TimelineTaskRow(
            long id,
            long enrollmentId,
            long nodeId,
            String nodeName,
            String subplanName,
            int occurrence,
            String taskType,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            String status,
            String completeKind,
            String createType,
            Long recordId,
            java.time.LocalDateTime completedAt) {}

    /** 患者的主任务（@DataScope 出行；含全部状态——时间轴按时间顺序展示历史）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT t.id, t.enrollment_id AS enrollmentId, t.node_id AS nodeId,
                   n.name AS nodeName, sn.name AS subplanName,
                   t.occurrence, t.task_type AS taskType,
                   t.plan_date AS planDate, t.window_start AS windowStart, t.window_end AS windowEnd,
                   t.status, t.complete_kind AS completeKind, t.create_type AS createType,
                   t.record_id AS recordId, t.completed_at AS completedAt
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            LEFT JOIN fu_plan_node sn ON sn.id = n.parent_id
            WHERE t.patient_id = #{patientId} AND t.create_type <> 'FOLLOWUP'
            ORDER BY t.plan_date ASC, t.id ASC
            """)
    List<TimelineTaskRow> timelineTasks(@Param("patientId") long patientId);

    /**
     * 时间轴在组入组行：「计划中」节点（下次计划日期实时缓存，A-FU-001「未来年度只在时间轴上显示下次计划日期，按当前起算日期实时计算」；
     * fu_enrollment.next_plan_date 由调度 / 起算日期重算维护，E14-T04 / E14-T07）。
     */
    record TimelineEnrollmentRow(long id, String planName, LocalDate nextPlanDate) {}

    /** 在组（ACTIVE）且有下次计划日期缓存的入组（@DataScope 出行）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT e.id, p.name AS planName, e.next_plan_date AS nextPlanDate
            FROM fu_enrollment e
            JOIN pt_patient pt ON pt.id = e.patient_id AND pt.deleted = 0
            LEFT JOIN fu_plan p ON p.id = e.plan_id
            WHERE e.patient_id = #{patientId} AND e.status = 'ACTIVE'
              AND e.next_plan_date IS NOT NULL
            ORDER BY e.next_plan_date ASC, e.id ASC
            """)
    List<TimelineEnrollmentRow> timelineEnrollments(@Param("patientId") long patientId);
}
