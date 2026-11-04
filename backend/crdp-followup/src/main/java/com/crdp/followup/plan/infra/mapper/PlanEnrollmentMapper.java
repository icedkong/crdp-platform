package com.crdp.followup.plan.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 患者入组（E14-T01 / A-FU-019、A-PAT-016；表 followup V46 建）。
 *
 * <p>fu_enrollment 自 E14-T01 起登记 {@code ControlledTables}（患者面数据）。计划患者列表（E14-T08 / A-FU-022）输出患者行， 按
 * {@code @DataScope(resource = "patient")} 出行：拦截器对 fu_enrollment 追加 EXISTS（经 patient_id 锚定患者）、对
 * pt_patient 直接落病区 / 科室 / 本人条件，范围为空追加 {@code AND (1 = 0)} 返回空列表（AGENTS §5.2）。
 *
 * <p>存在性 / 计数查询（E14-T01 起）只取状态位或聚合计数、不输出患者数据，按 {@code @IgnoreScope} 放行：
 *
 * <ul>
 *   <li>「该患者在该计划族是否已在组」是唯一性约束口径（uk_fu_enrollment_active），与病案号全院查重同思路；
 *   <li>患者是否可入组（在册、未死亡）同理只取状态位；
 *   <li>「随访完成次数」是对 fu_task 的管理口径聚合计数（A-FU-022 列），与计划列表「在组人数」同口径（PlanMapper）。
 * </ul>
 */
@Mapper
public interface PlanEnrollmentMapper {

    /** 患者状态位（登记即 ACTIVE；DEAD / TERMINATED / 已删除不可入组，A-PAT-016 跳过「已死亡」）。 */
    record PatientStateRow(Long id, String followupStatus) {}

    /**
     * 计划患者列表行（A-FU-022）：加入时间、状态（ACTIVE / PAUSED / REMOVED）；姓名 / 病案号为密文， 应用层解密后脱敏输出（AGENTS §5.3，明文只走
     * E07-T05 专用接口）。
     */
    record EnrollmentPatientRow(
            long id,
            long patientId,
            String nameCipher,
            String mrnCipher,
            String legacyNo,
            String gender,
            String wardName,
            String mode,
            String status,
            String pauseReason,
            String removeReason,
            LocalDateTime enrolledAt) {}

    /** 完成次数聚合行（fu_task 管理口径计数，不输出患者数据）。 */
    record CompletedCountRow(long enrollmentId, long completed) {}

    /** 患者状态位（存在性 + 随访状态，不输出患者数据）。 */
    @IgnoreScope(
            reason =
                    "入组前置检查（A-PAT-016 批量入组的「已死亡跳过」同口径）：只取患者在册与否与"
                            + "随访状态位，不输出患者数据；入组是管理动作，操作者未必有患者数据范围")
    @Select(
            "SELECT id, followup_status AS followupStatus FROM pt_patient"
                    + " WHERE id = #{patientId} AND deleted = 0")
    PatientStateRow findPatientState(long patientId);

    /** 该患者在该计划族（active_key = plan_code）是否已有在组入组（唯一性口径，只取存在性）。 */
    @IgnoreScope(
            reason =
                    "「已在组」是 uk_fu_enrollment_active 的唯一性口径（患者 × 计划族），只取存在性，"
                            + "不输出患者或入组明细（与病案号全院查重同思路）")
    @Select(
            "SELECT id FROM fu_enrollment WHERE patient_id = #{patientId}"
                    + " AND active_key = #{planCode} LIMIT 1")
    Long findActiveIdByPatientAndPlanCode(
            @Param("patientId") long patientId, @Param("planCode") String planCode);

    /** 新建入组（status = ACTIVE，起算日期快照可空——E14-T03/T05 计算任务日期时回填）。 */
    @Insert(
            """
            INSERT INTO fu_enrollment
              (patient_id, plan_id, plan_code, start_date, mode, status, created_by)
            VALUES
              (#{patientId}, #{planId}, #{planCode}, #{startDate}, #{mode}, #{status}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertEnrollment(PlanEnrollmentInsertRow row);

    /**
     * 计划患者列表（E14-T08 / A-FU-022）：该计划版本的全部入组（含已暂停 / 已移除，按加入时间倒序）。 患者面数据按 {@code @DataScope}
     * 出行；软删患者不入列表（deleted = 0）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT e.id, e.patient_id AS patientId, p.name_cipher AS nameCipher,
                   p.mrn_cipher AS mrnCipher, p.legacy_no AS legacyNo, p.gender,
                   w.name AS wardName, e.mode, e.status,
                   e.pause_reason AS pauseReason, e.remove_reason AS removeReason,
                   e.created_at AS enrolledAt
            FROM fu_enrollment e
            JOIN pt_patient p ON p.id = e.patient_id AND p.deleted = 0
            LEFT JOIN ward w ON w.id = p.ward_id
            WHERE e.plan_id = #{planId}
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT #{offset}, #{limit}
            """)
    List<EnrollmentPatientRow> findPatientsByPlanId(
            @Param("planId") long planId, @Param("offset") int offset, @Param("limit") int limit);

    /** 计划患者总数（与 {@link #findPatientsByPlanId} 同条件同范围）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT COUNT(*)
            FROM fu_enrollment e
            JOIN pt_patient p ON p.id = e.patient_id AND p.deleted = 0
            WHERE e.plan_id = #{planId}
            """)
    long countPatientsByPlanId(@Param("planId") long planId);

    /**
     * 各入组的随访完成次数（fu_task 中状态 COMPLETED 的任务数；E15-T01 状态流转后开始累计，当前恒 0）。 管理口径聚合计数、不输出患者行，按
     * {@code @IgnoreScope} 放行（与计划列表「在组人数」同口径）。
     */
    @IgnoreScope(
            reason =
                    "随访完成次数（A-FU-022 列）是对 fu_task 的管理口径聚合计数，不输出患者或任务行；"
                            + "计划患者列表的查看者（plan:view / plan:edit）未必有患者数据范围（与 PlanMapper 在组人数同口径）")
    @Select(
            """
            <script>
            SELECT enrollment_id AS enrollmentId, COUNT(*) AS completed
            FROM fu_task
            WHERE status = 'COMPLETED' AND enrollment_id IN
              <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
            GROUP BY enrollment_id
            </script>
            """)
    List<CompletedCountRow> countCompletedByEnrollmentIds(@Param("ids") List<Long> ids);
}
