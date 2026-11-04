package com.crdp.followup.task.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
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
    @IgnoreScope(reason = "调度写入任务行（E14-T04 本体）：无用户上下文，创建即 PENDING 待随访（A-FU-003）")
    @Insert(
            """
            INSERT INTO fu_task
              (enrollment_id, patient_id, plan_id, node_id, occurrence, task_type, form_code,
               owner_type, assignee_id, plan_date, remind_start, window_start, window_end,
               open_date, create_type, status, created_logical_date)
            VALUES
              (#{enrollmentId}, #{patientId}, #{planId}, #{nodeId}, #{occurrence}, #{taskType},
               #{formCode}, #{ownerType}, #{assigneeId}, #{planDate}, #{remindStart}, #{windowStart},
               #{windowEnd}, #{openDate}, #{createType}, #{status}, #{createdLogicalDate})
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

    // ---------------------------------------------------------------- 终止（E14-T06 /
    // A-FU-007、A-FU-008）

    /**
     * 终止一条入组的全部未完成任务（移出计划，A-FU-008 / A-PAT-011）：未完成 = 尚未终止且未完成（含待随访、 进行中、逾期、暂停 /
     * 停用期间未执行）；已完成与已失访保留原状（历史事实）。返回终止条数。 数据范围前置校验由调用方 （PlanEnrollmentService.remove，经
     * PatientContactGateway 40400）完成。
     */
    @IgnoreScope(
            reason = "移出计划终止未完成任务（E14-T06）：按入组 id 定向 UPDATE，调用方已做患者数据范围校验" + "（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'TERMINATED', terminate_reason = #{reason},
                terminated_at = #{terminatedAt}, terminated_by = #{terminatedBy}
            WHERE enrollment_id = #{enrollmentId}
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'SKIPPED_SUSPENDED')
            """)
    int terminateUnfinishedByEnrollment(
            @Param("enrollmentId") long enrollmentId,
            @Param("reason") String reason,
            @Param("terminatedAt") java.time.LocalDateTime terminatedAt,
            @Param("terminatedBy") Long terminatedBy);

    /**
     * 终止一位患者全部入组的未完成任务（死亡自动终止，A-FU-007 / tasks.csv 验收「告知已故后未开始任务全部终止」）。 返回终止条数。
     * 数据范围前置校验由调用方（FollowupTerminationService，经 PatientContactGateway 40400）完成。
     */
    @IgnoreScope(
            reason =
                    "死亡自动终止未完成任务（E14-T06 / A-FU-007）：按患者 id 定向 UPDATE，调用方已做患者数据"
                            + "范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET status = 'TERMINATED', terminate_reason = #{reason},
                terminated_at = #{terminatedAt}, terminated_by = #{terminatedBy}
            WHERE patient_id = #{patientId}
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'SKIPPED_SUSPENDED')
            """)
    int terminateUnfinishedByPatient(
            @Param("patientId") long patientId,
            @Param("reason") String reason,
            @Param("terminatedAt") java.time.LocalDateTime terminatedAt,
            @Param("terminatedBy") Long terminatedBy);
}
