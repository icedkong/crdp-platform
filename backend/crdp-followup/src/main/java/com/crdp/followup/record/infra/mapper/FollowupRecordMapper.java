package com.crdp.followup.record.infra.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fu_record 读写（E15-T07 / A-FU-014、C-FU-013；OQ-83① 载体迁移后的随访记录业务表）。
 *
 * <p>fu_record 在 {@code ControlledTables}：语句带 {@code @DataScope(resource = "patient")} 由拦截器按 {@code
 * patient_id} EXISTS 锚点出行（越范围返回 null / 空列表，AGENTS §5.2）；data / data_meta 为 JSON 文本，
 * 应用层解析。写入闸门与版本口径经 {@link com.crdp.form.record.app.FormService}（writeGateData / validateForSubmit /
 * stripHiddenFields），本 mapper 只做持久化。
 */
@Mapper
public interface FollowupRecordMapper {

    /** fu_record 行。 */
    record RecordRowRaw(
            long id,
            long patientId,
            Long taskId,
            String source,
            LocalDate visitDate,
            String formCode,
            int templateVersion,
            String status,
            int version,
            String data,
            String dataMeta,
            Long createdBy,
            LocalDateTime createdAt,
            Long updatedBy,
            LocalDateTime updatedAt) {}

    /** 单条记录（越范围 / 不存在 null——40400 由服务层抛，不暴露存在）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, task_id AS taskId, source,
                   visit_date AS visitDate, form_code AS formCode,
                   template_version AS templateVersion, status, version,
                   data, data_meta AS dataMeta,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fu_record WHERE id = #{id}
            """)
    RecordRowRaw findRecord(@Param("id") long id);

    /** 新建 DRAFT 记录（template_version 创建时固化 = 当时发布版）。 */
    @Insert(
            """
            INSERT INTO fu_record (patient_id, task_id, source, visit_date, form_code,
                                   template_version, status, version, data, data_meta, created_by)
            VALUES (#{patientId}, #{taskId}, #{source}, #{visitDate}, #{formCode},
                    #{templateVersion}, 'DRAFT', 0, #{data}, #{dataMeta}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRecord(FollowupRecordInsert record);

    /** 自动保存草稿（乐观锁：baseVersion 不匹配 0 行，服务层区分 40400 / 40900；已提交守卫在下方 submit 的同款条件里）。 */
    @Update(
            """
            UPDATE fu_record
            SET data = #{data}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND status = 'DRAFT'
            """)
    int updateDraft(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("updatedBy") long updatedBy);

    /** 提交（DRAFT → SUBMITTED 并 version + 1；并发已提交 / 版本前进 0 行，服务层区分 40901 与 40900）。 */
    @Update(
            """
            UPDATE fu_record
            SET data = #{data}, data_meta = #{dataMeta}, version = version + 1,
                status = 'SUBMITTED', updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND status = 'DRAFT'
            """)
    int submitRecord(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    /**
     * 报告单映射写入（E16-T07 / C-SCAN-005「录入即写入」）：把映射合并值经写入闸门后写回， 状态不变（DRAFT 仍是 DRAFT、SUBMITTED 仍是
     * SUBMITTED——修改已提交记录的专门流程随 E21-T11），乐观锁 version + 1； 0 行 = 版本已前进（服务层 40900）。
     */
    @Update(
            """
            UPDATE fu_record
            SET data = #{data}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion}
            """)
    int applyMappingWrite(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("updatedBy") long updatedBy);

    /** 患者的无任务记录（历史补录 / 计划外；任务录入记录经任务节点展示，A-FU-023 时间轴口径）：按随访日期升序。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, task_id AS taskId, source,
                   visit_date AS visitDate, form_code AS formCode,
                   template_version AS templateVersion, status, version,
                   data, data_meta AS dataMeta,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fu_record
            WHERE patient_id = #{patientId} AND task_id IS NULL
            ORDER BY visit_date ASC, id ASC
            """)
    List<RecordRowRaw> findTasklessByPatient(@Param("patientId") long patientId);

    /** 更新人显示名（40900 冲突详情「附更新人」，与 fe_record 同口径；取不到 null）。 */
    @Select("SELECT display_name FROM sys_user WHERE id = #{userId}")
    String findDisplayName(@Param("userId") Long userId);

    /**
     * 患者随访状态（计划外随访「患者在随访中」校验，E15-T06 / C-FU-022；pages.md §4.10 已终止〔死亡 / 停止 / 失访〕置灰「患者不在随访中」）：
     * 只取状态位；调用方（RecordBackfillService）已做患者数据范围校验（40400）。
     */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "计划外随访的患者状态校验（E15-T06）：只取状态位（不输出患者数据）；"
                            + "调用方（RecordBackfillService）已做患者数据范围校验（40400）")
    @Select("SELECT followup_status FROM pt_patient WHERE id = #{patientId} AND deleted = 0")
    String findPatientFollowupStatus(@Param("patientId") long patientId);

    /**
     * 患者在组计划的首个 FORM 节点表单（历史补录缺省表单解析，E15-T07 / A-FU-014）：入组按创建时间、节点按 sort_no / id 取第一个 FORM
     * 节点；无在组入组或无表单节点 null。
     */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT n.form_code
            FROM fu_enrollment e
            JOIN pt_patient pt ON pt.id = e.patient_id AND pt.deleted = 0
            JOIN fu_plan_node n ON n.plan_id = e.plan_id
            WHERE e.patient_id = #{patientId} AND e.status = 'ACTIVE'
              AND n.node_type = 'NODE' AND n.task_type = 'FORM' AND n.form_code IS NOT NULL
            ORDER BY e.created_at ASC, n.sort_no ASC, n.id ASC
            LIMIT 1
            """)
    String findEnrollmentFormCode(@Param("patientId") long patientId);
}
