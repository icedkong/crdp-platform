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
 *
 * <p>E21-T11 / C-FU-026：软删除列（deleted / delete_reason / deleted_by / deleted_at，followup
 * V87）——读取语句一律 {@code deleted = 0}，软删行对读取侧等同不存在（40400，不暴露存在）；删除只置标记与原因，数据长期保留。
 *
 * <p>E21-T11 / C-FU-008（OQ-130③、OQ-134②）：器械维度列 implant_id（按随访日期匹配当时在用的器械，创建 /
 * 提交时解析落列），对比标红「上一次」按同一器械过滤（CompareSourceMapper 候选行随行带出）。
 *
 * <p>E21-T09 / C-FU-018：随访序号列 sequence_no（同一患者已提交记录按有效随访日期升序 1..N； 提交 / 修改 / 删除后由
 * FollowupSequenceService 同一事务重排并写 fe_field_history）。
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
            Long implantId,
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

    /** 单条记录（越范围 / 不存在 / 已软删 null——40400 由服务层抛，不暴露存在）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, task_id AS taskId, source,
                   visit_date AS visitDate, implant_id AS implantId,
                   form_code AS formCode, template_version AS templateVersion,
                   status, version, data, data_meta AS dataMeta,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fu_record WHERE id = #{id} AND deleted = 0
            """)
    RecordRowRaw findRecord(@Param("id") long id);

    /** 新建 DRAFT 记录（template_version 创建时固化 = 当时发布版；implant_id 按随访日期解析，开关关闭为 NULL）。 */
    @Insert(
            """
            INSERT INTO fu_record (patient_id, task_id, source, visit_date, implant_id, form_code,
                                   template_version, status, version, data, data_meta, created_by)
            VALUES (#{patientId}, #{taskId}, #{source}, #{visitDate}, #{implantId}, #{formCode},
                    #{templateVersion}, 'DRAFT', 0, #{data}, #{dataMeta}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRecord(FollowupRecordInsert record);

    /** 自动保存草稿（乐观锁：baseVersion 不匹配 0 行，服务层区分 40400 / 40900；已提交守卫在下方 submit 的同款条件里）。 */
    @Update(
            """
            UPDATE fu_record
            SET data = #{data}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND status = 'DRAFT' AND deleted = 0
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
                status = 'SUBMITTED', implant_id = #{implantId}, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND status = 'DRAFT' AND deleted = 0
            """)
    int submitRecord(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("implantId") Long implantId,
            @Param("updatedBy") long updatedBy);

    /**
     * 保存修改已提交记录（E21-T11 / C-FU-026）：SUBMITTED 原地更新并 version + 1（「保存后生成 v2」）；并发版本前进 0 行（服务层
     * 40900），DRAFT / 已软删 0 行（服务层 40901 / 40400）。
     */
    @Update(
            """
            UPDATE fu_record
            SET data = #{data}, data_meta = #{dataMeta}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND status = 'SUBMITTED' AND deleted = 0
            """)
    int updateSubmittedRecord(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    /** 软删除（E21-T11 / C-FU-026）：置 deleted / 原因 / 删除人 / 时间，数据长期保留；已删 0 行（服务层 40400）。 */
    @Update(
            """
            UPDATE fu_record
            SET deleted = 1, delete_reason = #{reason}, deleted_by = #{deletedBy},
                deleted_at = CURRENT_TIMESTAMP(3), updated_by = #{deletedBy}
            WHERE id = #{id} AND deleted = 0
            """)
    int softDelete(
            @Param("id") long id,
            @Param("reason") String reason,
            @Param("deletedBy") long deletedBy);

    /** 删除记录后断开任务链接（任务终态不动：删除不重开任务，重录走补录 / 计划外入口）。 */
    @Update("UPDATE fu_task SET record_id = NULL WHERE record_id = #{recordId}")
    int clearTaskRecordLink(@Param("recordId") long recordId);

    /**
     * 报告单映射写入（E16-T07 / C-SCAN-005「录入即写入」）：把映射合并值经写入闸门后写回， 状态不变（DRAFT 仍是 DRAFT、SUBMITTED 仍是
     * SUBMITTED——修改已提交记录走 E21-T11 的 updateSubmittedRecord），乐观锁 version + 1；0 行 = 版本已前进（服务层 40900）。
     */
    @Update(
            """
            UPDATE fu_record
            SET data = #{data}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND deleted = 0
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
                   visit_date AS visitDate, implant_id AS implantId,
                   form_code AS formCode, template_version AS templateVersion,
                   status, version, data, data_meta AS dataMeta,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fu_record
            WHERE patient_id = #{patientId} AND task_id IS NULL AND deleted = 0
            ORDER BY visit_date ASC, id ASC
            """)
    List<RecordRowRaw> findTasklessByPatient(@Param("patientId") long patientId);

    /**
     * 患者随访记录元信息行（E21-T11：重复提示 C-FU-024 与随访序号 C-FU-008 的派生口径；data 供表单随访日期回退； sequenceNo 供 E21-T09 /
     * C-FU-018 重排比较）。
     */
    record PatientRecordMetaRow(
            long id,
            Long taskId,
            String source,
            LocalDate visitDate,
            String formCode,
            int templateVersion,
            String status,
            String data,
            Integer sequenceNo) {}

    /** 患者的在册记录元信息（DRAFT + SUBMITTED，软删行排除；重复提示含草稿——同一日期已有草稿同样提示）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, task_id AS taskId, source, visit_date AS visitDate,
                   form_code AS formCode, template_version AS templateVersion,
                   status, data, sequence_no AS sequenceNo
            FROM fu_record
            WHERE patient_id = #{patientId} AND deleted = 0
            ORDER BY visit_date ASC, id ASC
            """)
    List<PatientRecordMetaRow> findPatientRecordMeta(@Param("patientId") long patientId);

    /** 单条记录的落库随访序号（E21-T09 / C-FU-018 详情读侧：开关开启的已提交记录优先落库值； 未编号 / 越范围 / 已软删 null——回落派生预演由调用方处理）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select("SELECT sequence_no FROM fu_record WHERE id = #{id} AND deleted = 0")
    Integer findRecordSequenceNo(@Param("id") long id);

    /**
     * 写一条重排后的随访序号（E21-T09 / C-FU-018）。只写派生列 sequence_no：不动 version（重算不是编辑， 不触发乐观锁）、不动
     * updated_by；{@code @IgnoreScope} 原因与对比标红重算同款（E17-T04）：序号是派生数据、不输出患者行， 触发入口（提交 / 修改 /
     * 删除）已对患者做过数据范围校验（40400）。
     */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "随访序号自动重排（E21-T09 / C-FU-018）只写派生列 sequence_no：不触碰业务列与 version；"
                            + "触发入口（记录提交 / 修改 / 删除）已对患者做过数据范围校验（40400）")
    @Update("UPDATE fu_record SET sequence_no = #{sequenceNo} WHERE id = #{id} AND deleted = 0")
    int updateSequenceNo(@Param("id") long id, @Param("sequenceNo") Integer sequenceNo);

    /** 患者随访编号（C-PAT-003 / E21-T10；A / B 租户与历史患者未生成时 null）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select("SELECT visit_no FROM pt_patient WHERE id = #{patientId} AND deleted = 0")
    String findPatientVisitNo(@Param("patientId") long patientId);

    /** 更新人显示名（40900 冲突详情「附更新人」，与 fe_record 同口径；取不到 null）。 */
    @Select("SELECT display_name FROM sys_user WHERE id = #{userId}")
    String findDisplayName(@Param("userId") Long userId);

    /**
     * 患者随访状态（计划外随访「患者在随访中」校验，E15-T06 / C-FU-022；pages.md §4.10 已终止〔死亡 / 停止 /
     * 失访〕置灰「患者不在随访中」）：只取状态位；调用方（RecordBackfillService）已做患者数据范围校验（40400）。
     */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "计划外随访的患者状态校验（E15-T06）：只取状态位（不输出患者数据）；"
                            + "调用方（RecordBackfillService）已做患者数据范围校验（40400）")
    @Select("SELECT followup_status FROM pt_patient WHERE id = #{patientId} AND deleted = 0")
    String findPatientFollowupStatus(@Param("patientId") long patientId);

    /**
     * 患者在组计划的首个 FORM 节点表单（历史补录缺省表单解析，E15-T07 / A-FU-014）：入组按创建时间、 节点按 sort_no / id 取第一个 FORM
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
