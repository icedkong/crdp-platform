package com.crdp.followup.importer.infra.mapper;

import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.StagedRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * imp_batch / imp_batch_write 与导入提交的落库语句（E18-T05 / tech-plan §12⑥⑦；tasks.csv 验收「按年度节点生成历史记录，
 * 可按批回滚」）。
 *
 * <p>批次：一行 = 约 200 名患者（一个事务一批，失败批次可单独重试）；写数清单记录本导入写下的实体（类型 + id + 患者是否本导入新建），
 * 回滚按它反查删除。导入年度记录（fu_record 锚点列 V73）与患者主档列更新也在这里——历史导入按规格绕过录入侧写入闸门
 * （必填空仅警告、不受窗口限制，A-PAT-018），不复用各子域面向录入流程的语句。
 *
 * <p>查询受控表的语句带 {@code @IgnoreScope}：提交在异步线程执行（用户会话不随租户上下文复制），「只能导入本人可见病区」 按确认时冻结的
 * imp_job.scope_json 在应用层逐行校验（与 job_export.scope_json 同口径），越范围行跳过并计数，不靠查询改写。
 */
@Mapper
public interface ImportCommitMapper {

    /** 确认提交：固化选项（重复策略 / 兜底病区）与操作人数据范围快照（E18-T05；scope_json 见 V73 注释）。 */
    @Update(
            """
            UPDATE imp_job SET options_json = #{optionsJson}, scope_json = #{scopeJson}
            WHERE id = #{id}
            """)
    int saveCommitOptions(
            @Param("id") long id,
            @Param("optionsJson") String optionsJson,
            @Param("scopeJson") String scopeJson);

    // ---------------------------------------------------------------- 暂存行取数（提交侧）

    /** 患者暂存行引用（PATIENT / BASELINE；按 id 升序切批用）。病案号为空的行已被 ERROR 门控拦在确认前，这里只取有盲索引的。 */
    record PatientRowRef(long id, String mrnBlind) {}

    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入提交读取本作业暂存的患者行引用（job_id 限定，E18-T05）：只取行 id 与盲索引切批，"
                            + "不输出患者数据；病区范围按确认时冻结的 imp_job.scope_json 在应用层逐行校验")
    @Select(
            """
            SELECT id, mrn_blind AS mrnBlind FROM imp_row
            WHERE job_id = #{jobId} AND row_type IN ('PATIENT', 'BASELINE') AND mrn_blind IS NOT NULL
            ORDER BY id
            """)
    List<PatientRowRef> findPatientRowRefs(@Param("jobId") long jobId);

    /** 按批读患者暂存行（(afterId, upToId] 闭区间上界；载荷密文由应用层解密）。 */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入提交读取本作业暂存的患者行（job_id + id 区间限定，E18-T05）：行内患者数据只用于落库，"
                            + "不越过操作人冻结范围输出（scope_json 应用层校验）")
    @Select(
            """
            SELECT id, sheet_name AS sheetName, row_no AS rowNo, row_type AS rowType,
                   mrn_blind AS mrnBlind, payload_cipher AS payloadCipher
            FROM imp_row
            WHERE job_id = #{jobId} AND row_type IN ('PATIENT', 'BASELINE')
              AND id > #{afterId} AND id <= #{upToId}
            ORDER BY id LIMIT #{limit}
            """)
    List<StagedRow> findPatientRows(
            @Param("jobId") long jobId,
            @Param("afterId") long afterId,
            @Param("upToId") long upToId,
            @Param("limit") int limit);

    /** 按批读长表随访暂存行（按本批患者行的盲索引取；(afterId 无跨批重复，按 id 游标分页)。 */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入提交读本作业暂存的随访行（job_id + 本批盲索引限定，E18-T05）：行内患者数据只用于落库，"
                            + "不越过操作人冻结范围输出（scope_json 应用层校验）")
    @Select(
            """
            <script>
            SELECT id, sheet_name AS sheetName, row_no AS rowNo, row_type AS rowType,
                   mrn_blind AS mrnBlind, payload_cipher AS payloadCipher
            FROM imp_row
            WHERE job_id = #{jobId} AND row_type = 'FOLLOWUP' AND id > #{afterId}
              AND mrn_blind IN (<foreach collection="blinds" item="b" separator=",">#{b}</foreach>)
            ORDER BY id LIMIT #{limit}
            </script>
            """)
    List<StagedRow> findFollowupRows(
            @Param("jobId") long jobId,
            @Param("blinds") List<String> blinds,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    // ---------------------------------------------------------------- 批次（imp_batch）

    /** 建批插入行（id 自增回填；patientCount = 本批患者行数）。 */
    class BatchInsert {
        private Long id;
        private long jobId;
        private int batchNo;
        private long firstPatientRowId;
        private long lastPatientRowId;
        private int patientCount;

        public BatchInsert(
                long jobId, int batchNo, long firstRowId, long lastRowId, int patientCount) {
            this.jobId = jobId;
            this.batchNo = batchNo;
            this.firstPatientRowId = firstRowId;
            this.lastPatientRowId = lastRowId;
            this.patientCount = patientCount;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getJobId() {
            return jobId;
        }

        public int getBatchNo() {
            return batchNo;
        }

        public long getFirstPatientRowId() {
            return firstPatientRowId;
        }

        public long getLastPatientRowId() {
            return lastPatientRowId;
        }

        public int getPatientCount() {
            return patientCount;
        }
    }

    /** imp_batch 行。 */
    record BatchRow(
            long id,
            long jobId,
            int batchNo,
            long firstPatientRowId,
            long lastPatientRowId,
            int patientCount,
            String status,
            int createdPatients,
            int updatedPatients,
            int skippedPatients,
            int recordsCreated,
            String failReason,
            LocalDateTime rolledBackAt) {}

    @Insert(
            """
            INSERT INTO imp_batch (job_id, batch_no, first_patient_row_id, last_patient_row_id, patient_count)
            VALUES (#{jobId}, #{batchNo}, #{firstPatientRowId}, #{lastPatientRowId}, #{patientCount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBatch(BatchInsert row);

    /** 作业全部批次（向导按批展示与回滚入口用）。 */
    @Select(
            """
            SELECT id, job_id AS jobId, batch_no AS batchNo,
                   first_patient_row_id AS firstPatientRowId, last_patient_row_id AS lastPatientRowId,
                   patient_count AS patientCount, status,
                   created_patients AS createdPatients, updated_patients AS updatedPatients,
                   skipped_patients AS skippedPatients, records_created AS recordsCreated,
                   fail_reason AS failReason, rolled_back_at AS rolledBackAt
            FROM imp_batch WHERE job_id = #{jobId} ORDER BY batch_no
            """)
    List<BatchRow> findBatches(@Param("jobId") long jobId);

    /** 单批（回滚入口按作业 + 批次号定位）。 */
    @Select(
            """
            SELECT id, job_id AS jobId, batch_no AS batchNo,
                   first_patient_row_id AS firstPatientRowId, last_patient_row_id AS lastPatientRowId,
                   patient_count AS patientCount, status,
                   created_patients AS createdPatients, updated_patients AS updatedPatients,
                   skipped_patients AS skippedPatients, records_created AS recordsCreated,
                   fail_reason AS failReason, rolled_back_at AS rolledBackAt
            FROM imp_batch WHERE job_id = #{jobId} AND batch_no = #{batchNo}
            """)
    BatchRow findBatch(@Param("jobId") long jobId, @Param("batchNo") int batchNo);

    /** 开跑（PENDING 首跑 / FAILED 重试都经它；0 行 = 状态不符由服务层 40901）。 */
    @Update(
            "UPDATE imp_batch SET status = 'RUNNING' WHERE id = #{id} AND status IN ('PENDING', 'FAILED')")
    int markBatchRunning(@Param("id") long id);

    /** 批次完成：落四类计数（ tech-plan §12⑥「按批记录进度」；跳过含 SKIP 策略 / 越范围 / 必填实体列缺失）。 */
    @Update(
            """
            UPDATE imp_batch SET status = 'DONE', created_patients = #{created},
                   updated_patients = #{updated}, skipped_patients = #{skipped},
                   records_created = #{records}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markBatchDone(
            @Param("id") long id,
            @Param("created") int created,
            @Param("updated") int updated,
            @Param("skipped") int skipped,
            @Param("records") int records);

    /** 批次失败（事务回滚后由服务层另行落库；failReason 非敏感摘要，截断由调用方保证）。 */
    @Update(
            "UPDATE imp_batch SET status = 'FAILED', fail_reason = #{failReason} WHERE id = #{id} AND status = 'RUNNING'")
    int markBatchFailed(@Param("id") long id, @Param("failReason") String failReason);

    /** 批次回滚完成（写数已删；见 rollbackBatch）。 */
    @Update("UPDATE imp_batch SET status = 'ROLLED_BACK', rolled_back_at = NOW(3) WHERE id = #{id}")
    int markBatchRolledBack(@Param("id") long id);

    // ---------------------------------------------------------------- 写数清单（imp_batch_write）

    /** 写数插入行（createdFlag：患者是否本导入新建——回滚时新建患者整体删除、更新患者保留主档，OQ-104）。 */
    record WriteInsert(
            long batchId, long jobId, String entityType, long entityId, boolean createdFlag) {}

    /** 写数行（回滚反查：按 id 逆序即写入逆序）。 */
    record WriteRow(
            long id,
            long batchId,
            long jobId,
            String entityType,
            long entityId,
            boolean createdFlag) {}

    @Insert(
            """
            <script>
            INSERT INTO imp_batch_write (batch_id, job_id, entity_type, entity_id, created_flag)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{row.batchId}, #{row.jobId}, #{row.entityType}, #{row.entityId}, #{row.createdFlag})
            </foreach>
            </script>
            """)
    int insertWrites(@Param("rows") List<WriteInsert> rows);

    @Select(
            """
            SELECT id, batch_id AS batchId, job_id AS jobId, entity_type AS entityType,
                   entity_id AS entityId, created_flag AS createdFlag
            FROM imp_batch_write WHERE batch_id = #{batchId} ORDER BY id
            """)
    List<WriteRow> findWrites(@Param("batchId") long batchId);

    /** 回滚完成后清掉本批写数（批次行保留 ROLLED_BACK 状态与计数，作为回滚凭据）。 */
    @Delete("DELETE FROM imp_batch_write WHERE batch_id = #{batchId}")
    int deleteWrites(@Param("batchId") long batchId);

    // ---------------------------------------------------------------- 患者主档 / 分节 / 联系方式（提交侧读写）

    /** 患者实体列（更新比对与当前病区 / 状态检查；只取非敏感列）。 */
    record PatientEntityRow(
            String gender,
            LocalDate birthDate,
            Long wardId,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            String legacyNo,
            String followupStatus) {}

    @com.crdp.framework.datascope.IgnoreScope(
            reason = "导入提交读既有患者实体列（id 限定，E18-T05）：比对「只改动有变化的字段」与越范围检查，" + "异步线程无用户会话，作业归属与冻结范围已校验")
    @Select(
            """
            SELECT gender, birth_date AS birthDate, ward_id AS wardId,
                   surgery_date AS surgeryDate, admission_date AS admissionDate,
                   discharge_date AS dischargeDate, legacy_no AS legacyNo,
                   followup_status AS followupStatus
            FROM pt_patient WHERE id = #{id} AND deleted = 0
            """)
    PatientEntityRow findPatientEntity(@Param("id") long id);

    /** 既有基线分节（更新合并与完整度重算取数；只取行骨架与 data）。 */
    record SectionRow(
            long id,
            String categoryKey,
            int templateVersion,
            String data,
            String dataMeta,
            int version) {}

    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入提交读既有基线分节（patient_id 限定，E18-T05）：合并「只改动有变化的字段」与完整度重算，"
                            + "异步线程无用户会话，作业归属与冻结范围已校验")
    @Select(
            """
            SELECT id, category_key AS categoryKey, template_version AS templateVersion,
                   data, data_meta AS dataMeta, version
            FROM pt_baseline_section WHERE patient_id = #{patientId}
            """)
    List<SectionRow> findSections(@Param("patientId") long patientId);

    /** 分节数据更新（重复导入合并写；version 前进留乐观锁口径，字段历史由应用层同事务写）。 */
    @Update(
            """
            UPDATE pt_baseline_section
            SET data = #{data}, data_meta = #{dataMeta}, version = version + 1,
                has_draft = 0, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateSectionData(
            @Param("id") long id,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    /** 某槽位当前联系方式版本（导入新增版本前的「同值不新增」比对）。 */
    record ContactRow(long id, String phoneCipher, int version) {}

    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入提交读某槽位当前联系方式版本（patient_id + slot 限定，E18-T05）："
                            + "同值不新增版本，异步线程无用户会话，作业归属与冻结范围已校验")
    @Select(
            """
            SELECT id, phone_cipher AS phoneCipher, version
            FROM pt_contact WHERE patient_id = #{patientId} AND slot = #{slot}
            ORDER BY version DESC LIMIT 1
            """)
    ContactRow findCurrentContact(@Param("patientId") long patientId, @Param("slot") String slot);

    // ---------------------------------------------------------------- 患者主档更新（重复策略 UPDATE）

    /** 重复患者更新：只改动有值且不同的实体列（历史导入「空单元格不清掉既有值」，OQ-104；version 随乐观锁口径 +1）。 */
    @Update(
            """
            <script>
            UPDATE pt_patient
            SET version = version + 1, updated_by = #{updatedBy}
            <if test="gender != null">, gender = #{gender}</if>
            <if test="birthDate != null">, birth_date = #{birthDate}</if>
            <if test="wardId != null">, ward_id = #{wardId}</if>
            <if test="surgeryDate != null">, surgery_date = #{surgeryDate}</if>
            <if test="admissionDate != null">, admission_date = #{admissionDate}</if>
            <if test="dischargeDate != null">, discharge_date = #{dischargeDate}</if>
            <if test="legacyNo != null">, legacy_no = #{legacyNo}</if>
            WHERE id = #{id} AND deleted = 0
            </script>
            """)
    int updatePatientFromImport(
            @Param("id") long id,
            @Param("gender") String gender,
            @Param("birthDate") LocalDate birthDate,
            @Param("wardId") Long wardId,
            @Param("surgeryDate") LocalDate surgeryDate,
            @Param("admissionDate") LocalDate admissionDate,
            @Param("dischargeDate") LocalDate dischargeDate,
            @Param("legacyNo") String legacyNo,
            @Param("updatedBy") long updatedBy);

    // ---------------------------------------------------------------- 导入年度记录（fu_record 锚点列 V73）

    /** 导入年度记录插入行（HIST_IMPORT 有数据年度 / HIST_MISSED 窗口已结束无数据年度，Q06）。 */
    class ImportedRecordInsert {
        private Long id;
        private long patientId;
        private Long enrollmentId;
        private Long nodeId;
        private Integer occurrence;
        private Integer followupYear;
        private LocalDate planDate;
        private LocalDate windowStart;
        private LocalDate windowEnd;
        private String source;
        private LocalDate visitDate;
        private String formCode;
        private int templateVersion;
        private String status;
        private String data;
        private String dataMeta;
        private Long createdBy;

        public ImportedRecordInsert(
                long patientId,
                Long enrollmentId,
                Long nodeId,
                Integer occurrence,
                Integer followupYear,
                LocalDate planDate,
                LocalDate windowStart,
                LocalDate windowEnd,
                String source,
                LocalDate visitDate,
                String formCode,
                int templateVersion,
                String status,
                String data,
                String dataMeta,
                Long createdBy) {
            this.patientId = patientId;
            this.enrollmentId = enrollmentId;
            this.nodeId = nodeId;
            this.occurrence = occurrence;
            this.followupYear = followupYear;
            this.planDate = planDate;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.source = source;
            this.visitDate = visitDate;
            this.formCode = formCode;
            this.templateVersion = templateVersion;
            this.status = status;
            this.data = data;
            this.dataMeta = dataMeta;
            this.createdBy = createdBy;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getPatientId() {
            return patientId;
        }

        public Long getEnrollmentId() {
            return enrollmentId;
        }

        public Long getNodeId() {
            return nodeId;
        }

        public Integer getOccurrence() {
            return occurrence;
        }

        public Integer getFollowupYear() {
            return followupYear;
        }

        public LocalDate getPlanDate() {
            return planDate;
        }

        public LocalDate getWindowStart() {
            return windowStart;
        }

        public LocalDate getWindowEnd() {
            return windowEnd;
        }

        public String getSource() {
            return source;
        }

        public LocalDate getVisitDate() {
            return visitDate;
        }

        public String getFormCode() {
            return formCode;
        }

        public int getTemplateVersion() {
            return templateVersion;
        }

        public String getStatus() {
            return status;
        }

        public String getData() {
            return data;
        }

        public String getDataMeta() {
            return dataMeta;
        }

        public Long getCreatedBy() {
            return createdBy;
        }
    }

    /** 既有导入年度记录（重复导入更新 / 历史未随访补数据时定位；只认导入来源的记录，不碰任务录入记录）。 */
    record ImportedRecordRow(
            long id,
            String source,
            String status,
            int version,
            LocalDate visitDate,
            String data,
            String dataMeta) {}

    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入提交按患者 + 年度定位既有导入记录（patient_id + followup_year 限定，E18-T05）："
                            + "作业归属与冻结范围已校验，异步线程无用户会话")
    @Select(
            """
            SELECT id, source, status, version, visit_date AS visitDate, data, data_meta AS dataMeta
            FROM fu_record
            WHERE patient_id = #{patientId} AND followup_year = #{year}
              AND source IN ('HIST_IMPORT', 'HIST_MISSED')
            ORDER BY id LIMIT 1
            """)
    ImportedRecordRow findImportedYearRecord(
            @Param("patientId") long patientId, @Param("year") int year);

    @Insert(
            """
            INSERT INTO fu_record (patient_id, enrollment_id, node_id, occurrence, followup_year,
                                   plan_date, window_start, window_end, task_id, source, visit_date,
                                   form_code, template_version, status, version, data, data_meta, created_by)
            VALUES (#{patientId}, #{enrollmentId}, #{nodeId}, #{occurrence}, #{followupYear},
                    #{planDate}, #{windowStart}, #{windowEnd}, NULL, #{source}, #{visitDate},
                    #{formCode}, #{templateVersion}, #{status}, 0, #{data}, #{dataMeta}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertImportedRecord(ImportedRecordInsert row);

    /** 重新导入更新年度记录（历史未随访年度补了数据 → 升级为 HIST_IMPORT + SUBMITTED；version 前进留乐观锁口径）。 */
    @Update(
            """
            UPDATE fu_record
            SET source = #{source}, status = #{status}, visit_date = #{visitDate},
                data = #{data}, data_meta = #{dataMeta}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateImportedRecord(
            @Param("id") long id,
            @Param("source") String source,
            @Param("status") String status,
            @Param("visitDate") LocalDate visitDate,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    // ---------------------------------------------------------------- 回滚删除（按批，E18-T05）

    /*
     * 回滚只删「本导入写下的行」（imp_batch_write 反查）：新建患者连主档一起删（含状态时间线 / 摘要卫星行）；
     * 更新患者保留主档与基线既有值（fe_field_history 已留痕，OQ-104），只删导入新建的记录 / 联系方式 / 分节 /
     * 入组行。删除在回滚事务内按写入逆序执行（记录 → 联系方式 → 入组 → 分节 → 患者）。
     */

    @Delete("DELETE FROM fu_record WHERE id = #{id} AND source IN ('HIST_IMPORT', 'HIST_MISSED')")
    int deleteImportedRecord(@Param("id") long id);

    @Delete("DELETE FROM pt_contact WHERE id = #{id} AND source = 'IMPORT'")
    int deleteImportedContact(@Param("id") long id);

    @Delete("DELETE FROM fu_enrollment WHERE id = #{id}")
    int deleteEnrollment(@Param("id") long id);

    @Delete("DELETE FROM pt_baseline_section WHERE id = #{id}")
    int deleteBaselineSection(@Param("id") long id);

    /** 新建患者回滚：硬删主档（软删除语义不适用——导入回滚等于「这批没导过」；卫星行在下方两条先清）。 */
    @Delete("DELETE FROM pt_patient WHERE id = #{id}")
    int deleteCreatedPatient(@Param("id") long id);

    @Delete("DELETE FROM pt_status_history WHERE patient_id = #{patientId}")
    int deleteStatusHistoryOf(@Param("patientId") long patientId);

    @Delete("DELETE FROM pt_patient_summary WHERE patient_id = #{patientId}")
    int deleteSummaryOf(@Param("patientId") long patientId);
}
