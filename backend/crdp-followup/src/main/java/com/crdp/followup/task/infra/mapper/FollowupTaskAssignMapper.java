package com.crdp.followup.task.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 认领与改派（E15-T03 / A-FU-009）的租户库读写。
 *
 * <p>批量改派沿用患者批量操作（E13-T07）的口径：一次批量查询以 {@code @DataScope(resource = "patient")} 取操作者数据范围内的任务行， 范围外 /
 * 不存在的 id 不出现（上层按 TASK_NOT_FOUND 逐条列明，不暴露存在，AGENTS §5.2）；写入为守卫 UPDATE（状态未完成四态 + 负责人快照）， 并发变化返回 0
 * 由服务层按跳过处理。认领单条走 {@code @IgnoreScope} 读取 + 调用方患者范围校验（40400，与 E15-T01 / E15-T02 写入口同口径）。
 *
 * <p>新负责人候选校验（sys_user / sys_user_ward / sys_role_scope）不在 {@code ControlledTables}——组织信息而非患者数据，与
 * {@code PatientBatchMapper} / {@code NotifyRecipientMapper} 同口径不拦截。
 */
@Mapper
public interface FollowupTaskAssignMapper {

    /** 批量改派行：患者姓名 / 病案号为密文（应用层解密后脱敏输出）；wardId 为病区归属校验锚点。 */
    record AssignTaskRow(
            long id,
            long patientId,
            String status,
            Long assigneeId,
            Long wardId,
            String nameCipher,
            String mrnCipher,
            String wardName,
            String nodeName,
            LocalDate windowStart,
            LocalDate windowEnd) {}

    /** 批量取操作者数据范围内的任务行（范围外 id 不返回；软删患者不可见）。 只取改派判定、TASK_REASSIGNED 事件与逐条结果摘要所需列。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT t.id, t.patient_id AS patientId, t.status, t.assignee_id AS assigneeId,
                   pt.ward_id AS wardId, pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   w.name AS wardName, n.name AS nodeName,
                   t.window_start AS windowStart, t.window_end AS windowEnd
            FROM fu_task t
            JOIN pt_patient pt ON pt.id = t.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE t.id IN
              <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    List<AssignTaskRow> findAssignTasksInScope(@Param("ids") List<Long> ids);

    /** 认领目标行（id / 患者 / 状态 / 当前负责人）；调用方先做患者数据范围校验（40400）。 */
    record ClaimTaskRow(long id, long patientId, String status, Long assigneeId) {}

    /** 认领读取（与状态流转写入口同口径：@IgnoreScope 单行 + 调用方患者范围校验，不输出患者明细）。 */
    @IgnoreScope(reason = "认领（E15-T03）读取任务行（id / 状态 / 负责人，不输出患者明细）；" + "调用方先做任务对应患者的数据范围校验（40400）")
    @Select(
            """
            SELECT id, patient_id AS patientId, status, assignee_id AS assigneeId
            FROM fu_task WHERE id = #{id}
            """)
    ClaimTaskRow findClaimTaskById(@Param("id") long id);

    /** 新负责人存在且启用（改派的整体校验，A-FU-009）。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 'ENABLED'")
    int countEnabledAssignee(@Param("userId") long userId);

    /** 新负责人是否负责患者病区（sys_user_ward，A-PAT-017「只能选该病区的医生」同口径）。 */
    @Select(
            """
            SELECT COUNT(*) FROM sys_user_ward WHERE user_id = #{userId} AND ward_id = #{wardId}
            """)
    int countWardMembership(@Param("userId") long userId, @Param("wardId") long wardId);

    /** 新负责人是否持全院范围角色（租户管理员等不受病区限制，A-WARD-007；与通知接收人复核同口径）。 */
    @Select(
            """
            SELECT COUNT(*)
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 'ENABLED'
            JOIN sys_role_scope rs ON rs.role_id = ur.role_id
            WHERE ur.user_id = #{userId} AND rs.scope_type = 'ALL'
            """)
    int countHospitalWideScope(@Param("userId") long userId);

    /**
     * 改派（A-FU-009）：只改 assignee_id 并递增乐观锁版本；守卫 = 未完成四态 + 负责人快照（{@code <=>} NULL 安全相等， MySQL）——并发完成 /
     * 终止 / 他人先改派时落空（返回 0），由调用方按 TASK_CONFLICT 跳过且不发通知。 owner_type 是节点模板快照（V48），人工改派不改写。
     */
    @IgnoreScope(
            reason =
                    "任务改派（E15-T03）：按任务 id 定向 UPDATE（未完成四态 + 负责人快照守卫），"
                            + "数据范围前置校验由批量查询（@DataScope）承担；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET assignee_id = #{newAssigneeId}, lock_version = lock_version + 1
            WHERE id = #{id}
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'SKIPPED_SUSPENDED')
              AND assignee_id <=> #{oldAssigneeId}
            """)
    int reassignTask(
            @Param("id") long id,
            @Param("oldAssigneeId") Long oldAssigneeId,
            @Param("newAssigneeId") long newAssigneeId);

    /** 认领（A-FU-009「从公共池认领」）：守卫 = 待认领（assignee IS NULL）且未完成四态—— 已有负责人或并发被认领 / 完成时落空（返回 0）。 */
    @IgnoreScope(
            reason =
                    "公共池认领（E15-T03）：按任务 id 定向 UPDATE（assignee IS NULL 守卫），"
                            + "调用方已做患者数据范围校验（40400）；无 SELECT 输出")
    @Update(
            """
            UPDATE fu_task
            SET assignee_id = #{operatorId}, lock_version = lock_version + 1
            WHERE id = #{id}
              AND assignee_id IS NULL
              AND status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE', 'SKIPPED_SUSPENDED')
            """)
    int claimTask(@Param("id") long id, @Param("operatorId") long operatorId);
}
