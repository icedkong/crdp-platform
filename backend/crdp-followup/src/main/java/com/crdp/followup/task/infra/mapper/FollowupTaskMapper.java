package com.crdp.followup.task.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 每日任务调度（E14-T04 / A-FU-001、C-FU-004）的租户库读写。全部 {@code @IgnoreScope}：调度作业没有用户上下文，
 * 按「在组入组」逐患者创建任务（AGENTS §5.2 的调度入口白名单口径）；任务行的数据范围过滤由查询侧（E15-T01 列表 / 看板，按 fu_task 登记的
 * ControlledTables 以 {@code @DataScope} 出行）承担。
 */
@Mapper
public interface FollowupTaskMapper {

    /**
     * 可调入组（患者与计划版本快照；enrolledOn = 入组日，作为「入组前已过窗口」的下界；pauseFrom / pauseTo =
     * 最近一次暂停区间，停用补偿归属判定用，E14-T06）。
     */
    record EnrollmentRow(
            long id,
            long patientId,
            long planId,
            LocalDate startDate,
            String mode,
            LocalDate enrolledOn,
            LocalDate pauseFrom,
            LocalDate pauseTo) {}

    /** 负责人解析上下文（A-FU-009 回落链：责任医生 → 病区默认负责人 → 待认领池）。 */
    record PatientOwnerRow(Long responsibleUserId, Long wardDefaultOwnerId) {}

    /**
     * 状态为「已启动」的入组（A-FU-022 暂停 / 移除的不创建，恢复补偿随 E14-T06）。计划版本：PUBLISHED 正常创建； DISABLED =
     * 被新版本顶替的旧版本，在组患者不迁移、沿用原版本继续按需创建（A-FU-020 延后，OQ-76①）； PAUSED 计划「不开启新任务、不发通知」（A-FU-018）。患者死亡 /
     * 终止随访后不再创建（A-FU-007）。
     */
    @IgnoreScope(
            reason =
                    "每日调度（E14-T04）无用户上下文：只取在组入组与其计划版本状态位创建任务，"
                            + "不输出患者明细；任务查询侧（E15-T01）按 @DataScope 出行")
    @Select(
            """
            SELECT e.id, e.patient_id AS patientId, e.plan_id AS planId,
                   e.start_date AS startDate, e.mode, DATE(e.created_at) AS enrolledOn,
                   DATE(e.pause_from) AS pauseFrom, DATE(e.pause_to) AS pauseTo
            FROM fu_enrollment e
            JOIN fu_plan p ON p.id = e.plan_id
            JOIN pt_patient pt ON pt.id = e.patient_id
            WHERE e.status = 'ACTIVE'
              AND p.status IN ('PUBLISHED', 'DISABLED')
              AND pt.deleted = 0
              AND pt.followup_status NOT IN ('DEAD', 'TERMINATED')
            ORDER BY e.id
            """)
    List<EnrollmentRow> findSchedulableEnrollments();

    /** 起算日期快照为空时按计划的起算字段解析（fu_enrollment.start_date 由本任务回填，V46 注释预留）。 */
    @IgnoreScope(
            reason =
                    "调度解析起算日期（只取起算字段对应的日期值，不输出患者数据）："
                            + "IMPLANT_DATE（E21）/ CUSTOM 暂不可解析，返回 NULL 由服务层跳过")
    @Select(
            """
            SELECT CASE p.start_date_field
                     WHEN 'DISCHARGE_DATE' THEN pt.discharge_date
                     WHEN 'SURGERY_DATE' THEN pt.surgery_date
                     WHEN 'REGISTRATION_DATE' THEN DATE(pt.created_at)
                     ELSE NULL
                   END
            FROM fu_plan p
            JOIN pt_patient pt ON pt.id = #{patientId}
            WHERE p.id = #{planId}
            """)
    LocalDate resolveStartDate(@Param("planId") long planId, @Param("patientId") long patientId);

    /** 回填起算日期快照（只补空值，不覆盖已有快照；A-FU-011 变更重算随 E14-T07）。 */
    @IgnoreScope(reason = "调度回填起算日期快照（V46 预留）：单入组单列写入，无患者数据输出")
    @Update(
            "UPDATE fu_enrollment SET start_date = #{startDate} WHERE id = #{id} AND start_date IS NULL")
    int backfillStartDate(@Param("id") long id, @Param("startDate") LocalDate startDate);

    /** 负责人解析（只取 id）：责任医生（A-PAT-017）与病区默认负责人（OQ-60）。 */
    @IgnoreScope(reason = "调度解析任务负责人（A-FU-009 回落链）：只取两个 id，不输出患者数据")
    @Select(
            """
            SELECT pt.responsible_user_id AS responsibleUserId, w.default_owner_id AS wardDefaultOwnerId
            FROM pt_patient pt
            JOIN ward w ON w.id = pt.ward_id
            WHERE pt.id = #{patientId}
            """)
    PatientOwnerRow findOwnerContext(long patientId);

    /** 该次任务是否已存在（uk 口径：入组 + 节点 + 第 N 次；存在即跳过，重复执行 / 补跑重放不重复创建）。 */
    @IgnoreScope(reason = "调度幂等判定（uk 入组 + 节点 + 第 N 次的存在性）：只取任务 id 存在性，" + "不输出患者或任务明细")
    @Select(
            """
            SELECT id FROM fu_task
            WHERE enrollment_id = #{enrollmentId} AND node_id = #{nodeId} AND occurrence = #{occurrence}
            """)
    Long findTaskId(
            @Param("enrollmentId") long enrollmentId,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence);

    /**
     * 创建任务（PENDING 待随访，A-FU-003 初始态）。普通 INSERT：affected 恒为 1——Connector/J 默认 CLIENT_FOUND_ROWS，
     * {@code ON DUPLICATE KEY UPDATE} 的 no-op 也会返回 1，不能当幂等信号；已存在由 {@link #findTaskId} 前置排除， 并发撞 uk
     * 由服务层捕 {@code DuplicateKeyException} 当已存在处理。
     */
    @IgnoreScope(
            reason =
                    "调度写入任务行（E14-T04 本体；跟进任务随 E15-T05 写沟通记录事务内创建）：无用户上下文，创建即 PENDING 待随访（A-FU-003）")
    @Insert(
            """
            INSERT INTO fu_task
              (enrollment_id, patient_id, plan_id, node_id, occurrence, task_type, form_code,
               owner_type, assignee_id, plan_date, remind_start, window_start, window_end,
               open_date, create_type, status, followup_seq, created_logical_date)
            VALUES
              (#{enrollmentId}, #{patientId}, #{planId}, #{nodeId}, #{occurrence}, #{taskType},
               #{formCode}, #{ownerType}, #{assigneeId}, #{planDate}, #{remindStart}, #{windowStart},
               #{windowEnd}, #{openDate}, #{createType}, #{status}, #{followupSeq}, #{createdLogicalDate})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTask(FollowupTaskInsertRow row);

    /** 刷新下次计划日期 / 开启日缓存（next 为 null = 该计划已排完，两列清空；V46 预留列）。 */
    @IgnoreScope(reason = "调度维护入组的下次计划日期 / 开启日缓存（V46 预留列）：无患者数据输出")
    @Update(
            """
            UPDATE fu_enrollment
            SET next_plan_date = #{nextPlanDate}, next_open_date = #{nextOpenDate}
            WHERE id = #{id}
            """)
    int updateNextCache(
            @Param("id") long id,
            @Param("nextPlanDate") LocalDate nextPlanDate,
            @Param("nextOpenDate") LocalDate nextOpenDate);

    // ---------------------------------------------------------------- 起算日期变更重算（E14-T07 /
    // A-FU-011）

    /**
     * 患者在组（ACTIVE / PAUSED）入组与其起算日期快照（E14-T07 重算对象；REMOVED 的任务已终止不重算）。 只取 id 与状态位、不输出患者明细，
     * 患者存在性已由调用方先行校验（40400）。
     */
    record RecalcEnrollmentRow(
            long id,
            long patientId,
            long planId,
            LocalDate startDate,
            String mode,
            LocalDate enrolledOn) {}

    @IgnoreScope(
            reason = "起算日期变更重算（E14-T07）只取在组入组行与起算快照（id 与状态位），" + "不输出患者数据；患者存在性校验由服务层（40400）完成")
    @Select(
            """
            SELECT e.id, e.patient_id AS patientId, e.plan_id AS planId,
                   e.start_date AS startDate, e.mode, DATE(e.created_at) AS enrolledOn
            FROM fu_enrollment e
            JOIN pt_patient pt ON pt.id = e.patient_id
            WHERE e.patient_id = #{patientId} AND e.status IN ('ACTIVE', 'PAUSED') AND pt.deleted = 0
            ORDER BY e.id
            """)
    List<RecalcEnrollmentRow> findInGroupEnrollmentsByPatient(@Param("patientId") long patientId);

    /**
     * 该入组「已创建但尚无联系记录」的任务（A-FU-011 重算口径）：status = PENDING 待随访（fu_contact_log 随 E15 落地前以状态位判定，
     * OQ-81②；进行中 / 已完成 / 逾期 / 失访 / 已终止 / 期间未执行均不重算）。 只重排常规任务（NORMAL / BACKFILL）——未联系到自动跟进任务
     * （FOLLOWUP，E15-T05）由沟通记录产生、窗口继承源任务，不按节点排期重排（OQ-86④）。只取重算所需列。
     */
    record UnstartedTaskRow(long id, long nodeId, int occurrence, String createType) {}

    @IgnoreScope(reason = "起算日期变更重算（E14-T07）读取该入组的未开始任务行（日期组重算用），" + "不输出患者数据；调用方已做患者存在性与范围校验")
    @Select(
            """
            SELECT id, node_id AS nodeId, occurrence, create_type AS createType
            FROM fu_task
            WHERE enrollment_id = #{enrollmentId} AND status = 'PENDING'
              AND create_type IN ('NORMAL', 'BACKFILL')
            ORDER BY node_id, occurrence
            """)
    List<UnstartedTaskRow> findUnstartedTasks(@Param("enrollmentId") long enrollmentId);

    /**
     * 未开始任务按新日期重排窗口（A-FU-011「已创建但尚无联系记录的当期任务按新日期重算窗口」）： 只改五个日期列并递增乐观锁版本； 状态守卫 PENDING——并发写入联系记录（E15
     * 起置进行中）后本列不覆盖，返回 0 由调用方按未重排计。
     */
    @IgnoreScope(
            reason =
                    "起算日期变更重算（E14-T07）：按任务 id 定向 UPDATE（状态守卫 PENDING），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET plan_date = #{planDate}, remind_start = #{remindStart},
                window_start = #{windowStart}, window_end = #{windowEnd},
                open_date = #{openDate}, lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int rescheduleTask(
            @Param("id") long id,
            @Param("planDate") LocalDate planDate,
            @Param("remindStart") LocalDate remindStart,
            @Param("windowStart") LocalDate windowStart,
            @Param("windowEnd") LocalDate windowEnd,
            @Param("openDate") LocalDate openDate);

    /**
     * 未开始任务撤回为「计划中」（A-FU-011「新窗口尚未开启的撤回为计划中」；A-FU-003 该显示态不对应实际任务）： 删任务行——每日调度到新开启日按新日期重建， uk（入组 +
     * 节点 + 第 N 次）允许；状态守卫 PENDING（并发进入联系后不撤回）。
     */
    @IgnoreScope(
            reason =
                    "起算日期变更重算（E14-T07）撤回未开始任务：按任务 id 定向 DELETE（状态守卫 PENDING），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Delete("DELETE FROM fu_task WHERE id = #{id} AND status = 'PENDING'")
    int withdrawTask(@Param("id") long id);

    /** 更新入组起算日期快照（A-FU-011「下次计划日期按新日期实时重算」的基准；V46 预留列，调度回填只补空值，本入口整体覆盖）。 */
    @IgnoreScope(
            reason =
                    "起算日期变更重算（E14-T07）：按入组 id 定向 UPDATE 起算快照，" + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update("UPDATE fu_enrollment SET start_date = #{startDate} WHERE id = #{id}")
    int updateStartDate(@Param("id") long id, @Param("startDate") LocalDate startDate);

    // ---------------------------------------------------------------- 终止（E14-T06 /
    // A-FU-007、A-FU-008）

    /**
     * 终止一条入组的全部未完成任务（移出计划，A-FU-008 / A-PAT-011）：未完成 = 尚未终止且未完成（含待随访、 进行中、逾期、失访候选
     * 〔E15-T05，待医生确认的失访同样随计划移出关闭〕、 暂停 / 停用期间未执行）；已完成与已失访保留原状（历史事实）。返回终止条数。 数据范围前置校验由调用方
     * （PlanEnrollmentService.remove，经 PatientContactGateway 40400）完成。
     */
    @IgnoreScope(
            reason = "移出计划终止未完成任务（E14-T06）：按入组 id 定向 UPDATE，调用方已做患者数据范围校验" + "（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'TERMINATED', terminate_reason = #{reason},
                terminated_at = #{terminatedAt}, terminated_by = #{terminatedBy}
            WHERE enrollment_id = #{enrollmentId}
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            """)
    int terminateUnfinishedByEnrollment(
            @Param("enrollmentId") long enrollmentId,
            @Param("reason") String reason,
            @Param("terminatedAt") java.time.LocalDateTime terminatedAt,
            @Param("terminatedBy") Long terminatedBy);

    // ---------------------------------------------------------------- 状态流转（E15-T01 /
    // A-FU-003、A-FU-010）

    /**
     * 单条任务行（流转写入口的读取面）：id、归属（入组 / 患者 / 计划 / 节点 / 第 N 次）、创建方式、跟进序号（E15-T05）、 当前状态与窗口日期组
     * （完成方式判定用）。只取流转所需列，不输出患者明细；调用方（提交流程 / 沟通流程）先做任务对应患者的数据范围校验（40400）。
     */
    record TaskRow(
            long id,
            long enrollmentId,
            long patientId,
            long planId,
            long nodeId,
            int occurrence,
            String createType,
            String status,
            int followupSeq,
            LocalDate windowStart,
            LocalDate windowEnd) {}

    @IgnoreScope(
            reason =
                    "状态流转写入口（E15-T01 / E15-T05）读取任务行（id 与日期组，不输出患者明细）；"
                            + "调用方先做任务对应患者的数据范围校验（40400）")
    @Select(
            """
            SELECT id, enrollment_id AS enrollmentId, patient_id AS patientId, plan_id AS planId,
                   node_id AS nodeId, occurrence, create_type AS createType, status,
                   followup_seq AS followupSeq, window_start AS windowStart, window_end AS windowEnd
            FROM fu_task
            WHERE id = #{id}
            """)
    TaskRow findTaskById(@Param("id") long id);

    /** 入组起算日期快照（完成「直接录入期」判定要算下一次数开启日，E15-T07 重算同源）。 */
    @IgnoreScope(reason = "状态流转（E15-T01）读取入组起算快照（单列）：调用方已做患者数据范围校验（40400）")
    @Select("SELECT start_date FROM fu_enrollment WHERE id = #{id}")
    LocalDate findEnrollmentStartDate(@Param("id") long id);

    /**
     * 待逾期刷新候选（A-FU-010「窗口结束仍未完成即逾期」，窗口结束日次日由 asOf 判定：window_end 早于 asOf）： 未完成（待随访 /
     * 进行中）且非补录——补录任务（create_type = BACKFILL，E14-T05）建任务时窗口就已结束， 语义是补录历史，不进逾期与逾期通知（followup.md §1
     * 已定口径）；跟进 / 临时任务（E15-T05 / T06）落地后按各自窗口同口径参与。 只取逾期标记与 TASK_OVERDUE 事件所需列（负责人复核锚点 wardId、站内信密文名
     * patientNameCipher、文案 nodeName）。
     */
    record OverdueCandidateRow(
            long id,
            long patientId,
            Long assigneeId,
            Long wardId,
            String patientNameCipher,
            String nodeName,
            LocalDate windowStart,
            LocalDate windowEnd) {}

    @IgnoreScope(
            reason =
                    "每日逾期刷新（E15-T01，00:10 调度内）无用户上下文：只取逾期标记与通知事件所需列"
                            + "（患者姓名为密文，明文经 CryptoService 解密后只进站内信密文列，不落日志）；"
                            + "任务查询侧（E15-T02 列表）按 @DataScope 出行")
    @Select(
            """
            SELECT t.id, t.patient_id AS patientId, t.assignee_id AS assigneeId,
                   pt.ward_id AS wardId, pt.name_cipher AS patientNameCipher,
                   n.name AS nodeName, t.window_start AS windowStart, t.window_end AS windowEnd
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE t.status IN ('PENDING', 'IN_PROGRESS')
              AND t.create_type <> 'BACKFILL'
              AND t.window_end < #{asOf}
            ORDER BY t.id
            """)
    List<OverdueCandidateRow> findOverdueCandidates(@Param("asOf") LocalDate asOf);

    /**
     * 单条置逾期（A-FU-010；进入时间 = 窗口结束日次日，由调用方按 asOf 选出候选）：状态守卫待随访 / 进行中—— 并发完成 / 终止 / 失访后本 UPDATE 落空（返回
     * 0），调用方按未标计且不发事件。幂等：已 OVERDUE 不在守卫集，重放落空。
     */
    @IgnoreScope(
            reason =
                    "每日逾期刷新（E15-T01）：按任务 id 定向 UPDATE（状态守卫），同一事务内随后写 TASK_OVERDUE 事件；"
                            + "无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'OVERDUE', lock_version = lock_version + 1
            WHERE id = #{id} AND status IN ('PENDING', 'IN_PROGRESS')
            """)
    int markOverdue(@Param("id") long id);

    /**
     * 待随访 → 进行中（A 规格任务状态表「记录第一次联系或暂存」；第一次联系随 E15-T04、暂存草稿随提交流程 E15-T02 接入）： 状态守卫 PENDING——逾期 / 已完成
     * / 已终止等一律不动（逾期不退回进行中，OQ-82①），返回 0 由调用方按未流转计。
     */
    @IgnoreScope(
            reason =
                    "任务进入进行中（E15-T01 第一次联系 / 暂存草稿触发）：按任务 id 定向 UPDATE（状态守卫 PENDING），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'IN_PROGRESS', lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int startProgress(@Param("id") long id);

    /**
     * → 已完成（随访记录提交，A 规格任务状态表；complete_kind 见 followup V53）：状态守卫未完成三态 + 失访候选
     * （E15-T05：直接录入期内联系上患者，候选照常完成），并发已终态时落空（返回 0）由调用方按冲突处理（40900）； 终态任务（已完成 / 失访 / 已终止 /
     * 期间未执行）不经本入口（服务层先校验，40901）。
     */
    @IgnoreScope(
            reason =
                    "任务完成（E15-T01 / A-FU-005 提交联动；E15-T05 起含失访候选）：按任务 id 定向 UPDATE"
                            + "（状态守卫），调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'COMPLETED', complete_kind = #{completeKind},
                completed_at = #{completedAt}, completed_by = #{completedBy},
                lock_version = lock_version + 1
            WHERE id = #{id} AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE')
            """)
    int completeTask(
            @Param("id") long id,
            @Param("completeKind") String completeKind,
            @Param("completedAt") java.time.LocalDateTime completedAt,
            @Param("completedBy") Long completedBy);

    /**
     * 「停用 / 暂停期间未执行」补做（PLT-TEN-017、A 的 Q32）：状态守卫 SKIPPED_SUSPENDED，目标状态由服务层按窗口是否已结束定 （PENDING /
     * OVERDUE，OQ-82②）；返回 0 = 并发已被处理。
     */
    @IgnoreScope(
            reason =
                    "期间未执行任务补做（E15-T01 / PLT-TEN-017）：按任务 id 定向 UPDATE（状态守卫 SKIPPED_SUSPENDED），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = #{targetStatus}, lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'SKIPPED_SUSPENDED'
            """)
    int redoSkippedTask(@Param("id") long id, @Param("targetStatus") String targetStatus);

    /**
     * 「停用 / 暂停期间未执行」关闭（PLT-TEN-017、A 的 Q32「由医生选择补做或关闭」）：置 TERMINATED 并落终止三列（复用 E14-T06 的终止列，关闭 =
     * 人工停止该次随访）；状态守卫 SKIPPED_SUSPENDED。
     */
    @IgnoreScope(
            reason =
                    "期间未执行任务关闭（E15-T01 / PLT-TEN-017）：按任务 id 定向 UPDATE（状态守卫 SKIPPED_SUSPENDED），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'TERMINATED', terminate_reason = #{reason},
                terminated_at = #{terminatedAt}, terminated_by = #{terminatedBy},
                lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'SKIPPED_SUSPENDED'
            """)
    int closeSkippedTask(
            @Param("id") long id,
            @Param("reason") String reason,
            @Param("terminatedAt") java.time.LocalDateTime terminatedAt,
            @Param("terminatedBy") Long terminatedBy);

    /**
     * 终止一位患者全部入组的未完成任务（死亡自动终止，A-FU-007 / tasks.csv 验收「告知已故后未开始任务全部终止」）。 返回终止条数。
     * 数据范围前置校验由调用方（FollowupTerminationService，经 PatientContactGateway 40400）完成。
     */
    @IgnoreScope(
            reason =
                    "死亡自动终止未完成任务（E14-T06 / A-FU-007；含失访候选，E15-T05）：按患者 id 定向 UPDATE，"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'TERMINATED', terminate_reason = #{reason},
                terminated_at = #{terminatedAt}, terminated_by = #{terminatedBy}
            WHERE patient_id = #{patientId}
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE', 'SKIPPED_SUSPENDED')
            """)
    int terminateUnfinishedByPatient(
            @Param("patientId") long patientId,
            @Param("reason") String reason,
            @Param("terminatedAt") java.time.LocalDateTime terminatedAt,
            @Param("terminatedBy") Long terminatedBy);

    // ---------------------------------------------------------------- 失访判定与未联系到跟进（E15-T05 /
    // A-FU-012、A-FU-024）

    /**
     * 窗口（入组 + 节点 + 第 N 次）已建的跟进序号最大值（A-FU-024「计数存任务上」：常规任务恒 0， 跟进任务 1–5）。达到 {@code
     * FollowupPolicyService.MAX_FOLLOWUPS_PER_WINDOW} 即不再创建。
     */
    @IgnoreScope(
            reason =
                    "未联系到跟进计数（E15-T05 / A-FU-024）：只取窗口内跟进序号最大值，不输出患者数据；"
                            + "调用方（沟通记录写入口）已做患者数据范围校验（40400）")
    @Select(
            """
            SELECT COALESCE(MAX(followup_seq), 0) FROM fu_task
            WHERE enrollment_id = #{enrollmentId} AND node_id = #{nodeId}
              AND occurrence = #{occurrence} AND followup_seq > 0
            """)
    int findMaxFollowupSeq(
            @Param("enrollmentId") long enrollmentId,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence);

    /**
     * 失访候选判定候选行（A-FU-012，每日 00:10 调度在逾期刷新后执行；tech-plan §9）：窗口已结束（asOf 晚于 window_end） 仍未完成（待随访 / 进行中
     * / 逾期 / 失访候选重放落空）且非补录（BACKFILL 豁免，E14-T05 口径——建任务时窗口已过， 语义是补录历史）的<b>主任务</b>（followup_seq =
     * 0；跟进任务不重复标候选）；EXISTS 预过滤 至少有一条窗口内「未联系到 / 号码错误」，零联系的任务（从未尝试）不进候选， 减少逐行计数查询。 只取标记与
     * LOST_CANDIDATE 事件所需列（同 {@link OverdueCandidateRow} 口径）。
     */
    @IgnoreScope(
            reason =
                    "每日失访候选刷新（E15-T05 / A-FU-012，00:10 调度内）无用户上下文：只取候选标记与通知事件"
                            + "所需列（患者姓名为密文，明文经 CryptoService 解密后只进站内信密文列，不落日志）；"
                            + "任务查询侧（E15-T02 列表）按 @DataScope 出行")
    @Select(
            """
            SELECT t.id, t.patient_id AS patientId, t.assignee_id AS assigneeId,
                   pt.ward_id AS wardId, pt.name_cipher AS patientNameCipher,
                   n.name AS nodeName, t.window_start AS windowStart, t.window_end AS windowEnd
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE t.status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE')
              AND t.create_type <> 'BACKFILL'
              AND t.followup_seq = 0
              AND t.window_end < #{asOf}
              AND EXISTS (
                SELECT 1 FROM fu_contact_log c
                WHERE c.patient_id = t.patient_id
                  AND DATE(c.contact_time) BETWEEN t.window_start AND t.window_end
                  AND c.result IN ('NOT_REACHED', 'WRONG_NUMBER'))
            ORDER BY t.id
            """)
    List<OverdueCandidateRow> findLostCandidates(@Param("asOf") LocalDate asOf);

    /**
     * 窗口内沟通记录（新→旧；连续未联系到的计数口径，A-FU-012「窗口内不同日期连续 3 次」）： 调用方从最新往前走「未联系到 / 号码错误」连续段，遇到其他结果（已联系 / 家属代答
     * / 拒访〔不计入失访〕/ 告知已故）即断。
     */
    record ContactAttemptRow(long id, String result, java.time.LocalDateTime contactTime) {}

    /**
     * 窗口内沟通记录（新→旧）。fu_contact_log 属沟通子域，本查询是失访判定的计数读取（联表只取 id / 结果 / 时间， 不输出患者明细，与
     * OverdueCandidateRow 联查 pt_patient 同口径，OQ-86①）。
     */
    @IgnoreScope(
            reason =
                    "失访候选判定的窗口内沟通计数（E15-T05 / A-FU-012）：只取结果与时间（新→旧），"
                            + "不输出患者数据；候选行来自每日调度（无用户上下文）")
    @Select(
            """
            SELECT id, result, contact_time AS contactTime
            FROM fu_contact_log
            WHERE patient_id = #{patientId}
              AND DATE(contact_time) BETWEEN #{from} AND #{to}
            ORDER BY contact_time DESC, id DESC
            """)
    List<ContactAttemptRow> contactsOfPatientInWindow(
            @Param("patientId") long patientId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * 单条置失访候选（A-FU-012；窗口结束次日由每日刷新调用）：状态守卫待随访 / 进行中 / 逾期—— 并发完成 / 终止 / 确认失访后本 UPDATE 落空（返回
     * 0），调用方按未标计且不发事件。幂等：已 LOST_CANDIDATE / LOST 不在守卫集，重放落空。
     */
    @IgnoreScope(
            reason =
                    "每日失访候选刷新（E15-T05）：按任务 id 定向 UPDATE（状态守卫），同一事务内随后写"
                            + " LOST_CANDIDATE 事件；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'LOST_CANDIDATE', lock_version = lock_version + 1
            WHERE id = #{id} AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE')
            """)
    int markLostCandidate(@Param("id") long id);

    /**
     * 失访候选 → 失访（医生确认，A-FU-012；HTTP 随 E15-T05 confirm-lost）：状态守卫 LOST_CANDIDATE， 并发已被处理（确认 / 完成 /
     * 终止）返回 0 由调用方按冲突处理（40900 / 40901）。
     */
    @IgnoreScope(
            reason =
                    "确认失访（E15-T05 / A-FU-012）：按任务 id 定向 UPDATE（状态守卫 LOST_CANDIDATE），"
                            + "调用方已做任务对应患者的数据范围校验（40400）；无 SELECT 输出")
    @Update(
            "UPDATE fu_task SET status = 'LOST', lock_version = lock_version + 1 WHERE id = #{id} AND status = 'LOST_CANDIDATE'")
    int confirmLostTask(@Param("id") long id);

    /**
     * 窗口内主任务（followup_seq = 0）随跟进任务完成（A-FU-024：跟进是同一窗口的继续，完成传播—— 医生经跟进任务联系上患者并提交，
     * 主任务同步完成，统计〔E19〕按主任务口径不漏计；完成方式与触发任务同日同窗口取同值）。 状态守卫同 {@link #completeTask}；excludeTaskId =
     * 触发完成的任务本身。
     */
    @IgnoreScope(
            reason =
                    "窗口完成传播（E15-T05 / A-FU-024）：按窗口定向 UPDATE 主任务（状态守卫），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'COMPLETED', complete_kind = #{completeKind},
                completed_at = #{completedAt}, completed_by = #{completedBy},
                lock_version = lock_version + 1
            WHERE enrollment_id = #{enrollmentId} AND node_id = #{nodeId}
              AND occurrence = #{occurrence} AND id <> #{excludeTaskId} AND followup_seq = 0
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE')
            """)
    int completeWindowMainTask(
            @Param("enrollmentId") long enrollmentId,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence,
            @Param("excludeTaskId") long excludeTaskId,
            @Param("completeKind") String completeKind,
            @Param("completedAt") java.time.LocalDateTime completedAt,
            @Param("completedBy") Long completedBy);

    /** 关闭窗口内其余未完成跟进任务（followup_seq &gt; 0）：主任务完成或确认失访后，窗口工作结束， 其余跟进任务终止留痕（原因随调用方传入）。 */
    @IgnoreScope(
            reason =
                    "窗口跟进任务关闭（E15-T05 / A-FU-024）：按窗口定向 UPDATE（状态守卫），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'TERMINATED', terminate_reason = #{reason},
                terminated_at = #{terminatedAt}, terminated_by = #{terminatedBy},
                lock_version = lock_version + 1
            WHERE enrollment_id = #{enrollmentId} AND node_id = #{nodeId}
              AND occurrence = #{occurrence} AND id <> #{excludeTaskId} AND followup_seq > 0
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'LOST_CANDIDATE')
            """)
    int terminateWindowFollowups(
            @Param("enrollmentId") long enrollmentId,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence,
            @Param("excludeTaskId") long excludeTaskId,
            @Param("reason") String reason,
            @Param("terminatedAt") java.time.LocalDateTime terminatedAt,
            @Param("terminatedBy") Long terminatedBy);

    /**
     * 入组主任务窗口序（新→旧，含跟进序号 0 的全部次数；「连续 2 个年度失访」按窗口顺序数连续 LOST， A-FU-012 / tech-plan
     * §9「确认失访时检查」）。只取窗口与状态两列。
     */
    record WindowStatusRow(LocalDate windowStart, String status) {}

    @IgnoreScope(
            reason =
                    "连续失访年度检查（E15-T05 / A-FU-012）：只取入组主任务的窗口与状态位，不输出患者数据；" + "调用方已做患者数据范围校验（40400）")
    @Select(
            """
            SELECT window_start AS windowStart, status
            FROM fu_task
            WHERE enrollment_id = #{enrollmentId} AND followup_seq = 0
            ORDER BY window_start DESC, id DESC
            """)
    List<WindowStatusRow> findWindowStatuses(@Param("enrollmentId") long enrollmentId);

    /**
     * 任务的通知上下文（E15-T05：跟进任务创建时发 UNREACHED_FOLLOWUP 事件用）：同 {@link OverdueCandidateRow} 口径的投影视图
     * （负责人复核锚点 wardId、站内信密文名 patientNameCipher、文案 nodeName / 窗口）。
     */
    @IgnoreScope(reason = "通知事件负载拼装（E15-T05）：只取事件所需列（患者姓名为密文），" + "调用方已做患者数据范围校验（40400）")
    @Select(
            """
            SELECT t.id, t.patient_id AS patientId, t.assignee_id AS assigneeId,
                   pt.ward_id AS wardId, pt.name_cipher AS patientNameCipher,
                   n.name AS nodeName, t.window_start AS windowStart, t.window_end AS windowEnd
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE t.id = #{id}
            """)
    OverdueCandidateRow findTaskNotifyRow(@Param("id") long id);

    /** 跟进任务继承的任务形态与负责人（E15-T05 / A-FU-024：跟进是同一窗口随访的继续——任务类型、表单、 负责人类型与当前负责人都随源任务走，不重新解析回落链）。 */
    record FollowupTemplateRow(
            String taskType, String formCode, String ownerType, Long assigneeId) {}

    @IgnoreScope(
            reason =
                    "未联系到跟进任务继承源任务形态（E15-T05）：只取任务类型 / 表单 / 负责人四列，"
                            + "不输出患者数据；调用方已做患者数据范围校验（40400）")
    @Select(
            """
            SELECT task_type AS taskType, form_code AS formCode,
                   owner_type AS ownerType, assignee_id AS assigneeId
            FROM fu_task
            WHERE id = #{id}
            """)
    FollowupTemplateRow findFollowupTemplate(@Param("id") long id);

    // ---------------------------------------------------------------- 工作台（E15-T02 /
    // A-FU-005、C-FU-020）

    /**
     * 开始录入用任务行（E15-T02）：患者（范围校验锚点）、任务类型与表单码（REMINDER 无表单 40901）、当前状态与已关联记录。 只取流转所需列，不输出患者明文；
     * 调用方（FollowupTaskStartService）先做患者数据范围校验（40400）。
     */
    record StartTaskRow(
            long id,
            long patientId,
            String taskType,
            String formCode,
            String status,
            Long recordId) {}

    @IgnoreScope(
            reason = "开始录入（E15-T02）读取任务行（任务类型 / 表单码 / 状态，不输出患者明文）；" + "调用方先做任务对应患者的数据范围校验（40400）")
    @Select(
            """
            SELECT id, patient_id AS patientId, task_type AS taskType, form_code AS formCode,
                   status, record_id AS recordId
            FROM fu_task
            WHERE id = #{id}
            """)
    StartTaskRow findStartTaskById(@Param("id") long id);

    /**
     * 回填任务 ↔ 草稿记录关联（followup V54，pages.md §5「任务开启时已创建草稿记录，统一用 recordId」）。 只在 record_id IS NULL
     * 时写入（重复点击「录入」幂等返回已有记录）；uk_fu_task_record 兜底并发。
     */
    @IgnoreScope(
            reason =
                    "开始录入（E15-T02）回填任务与草稿记录关联：按任务 id 定向 UPDATE（record_id IS NULL 守卫），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task SET record_id = #{recordId}, lock_version = lock_version + 1
            WHERE id = #{id} AND record_id IS NULL
            """)
    int linkRecord(@Param("id") long id, @Param("recordId") long recordId);

    /** 记录关联的任务位（联动判定用，OQ-77② 草稿保存与 E15-T02 提交完成联动）。 */
    record RecordLinkedTaskRow(long id, String status) {}

    /** 按记录 id 找任务（草稿保存「任务变进行中」联动与提交完成联动；只取 id 与状态）。 */
    @IgnoreScope(
            reason =
                    "记录联动（E15-T02）按 record_id 找任务行（id 与状态位，不输出患者数据）；"
                            + "入口（草稿保存 / 提交）的记录可见性由创建时的范围校验承担")
    @Select("SELECT id, status FROM fu_task WHERE record_id = #{recordId}")
    RecordLinkedTaskRow findTaskByRecordId(@Param("recordId") long recordId);
}
