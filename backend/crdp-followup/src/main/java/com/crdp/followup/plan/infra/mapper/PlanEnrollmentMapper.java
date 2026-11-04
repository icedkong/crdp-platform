package com.crdp.followup.plan.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 患者入组（E14-T01 / A-FU-019、A-PAT-016；表 followup V46 建）。
 *
 * <p>fu_enrollment 自本任务起登记 {@code ControlledTables}（患者面数据，计划患者列表（E14-T08）按 {@code @DataScope}
 * 出行）。本任务只有入组写入与两条存在性 / 计数查询：
 *
 * <ul>
 *   <li>「该患者在该计划族是否已在组」是唯一性约束口径（uk_fu_enrollment_active），与病案号全院查重同思路—— 只取存在性，不输出患者数据，按
 *       {@code @IgnoreScope} 放行；
 *   <li>患者是否可入组（在册、未死亡）同理只取状态位，不输出患者行。
 * </ul>
 */
@Mapper
public interface PlanEnrollmentMapper {

    /** 患者状态位（登记即 ACTIVE；DEAD / TERMINATED / 已删除不可入组，A-PAT-016 跳过「已死亡」）。 */
    record PatientStateRow(Long id, String followupStatus) {}

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
}
