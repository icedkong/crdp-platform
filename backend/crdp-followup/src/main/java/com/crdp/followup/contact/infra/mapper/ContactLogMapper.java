package com.crdp.followup.contact.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
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
 * 沟通记录（E15-T04 / A-FU-006、A-FU-025、C-FU-019；租户库 followup V56）。
 *
 * <p>fu_contact_log 在 {@code ControlledTables}：患者面读取按语句上的 {@code @DataScope(resource = "patient")}
 * 出行（越范围患者不可见，AGENTS §5.2）； 单行写入口（改 / 删）由服务层先经 PatientContactGateway 做患者范围校验（40400）后以
 * {@code @IgnoreScope} 读写，与 E15-T01 状态写入口同口径。 录音关联 fu_contact_audio 经
 * sys_file（不在受控表）联查展示信息；sys_file 状态流转（PENDING → ATTACHED / DELETED）复用文件子域台账。
 */
@Mapper
public interface ContactLogMapper {

    /** 行（列表与详情共用；creatorName 联查记录人显示名）。 */
    record ContactLogRow(
            long id,
            long patientId,
            Long taskId,
            String method,
            String result,
            LocalDateTime contactTime,
            Long contactVersionId,
            LocalDateTime nextContactTime,
            String note,
            int lockVersion,
            long createdBy,
            String creatorName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /** 插入行（回填自增 id）。 */
    class ContactLogInsert {
        private Long id;
        private long patientId;
        private Long taskId;
        private String method;
        private String result;
        private LocalDateTime contactTime;
        private Long contactVersionId;
        private LocalDateTime nextContactTime;
        private String note;
        private long createdBy;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getPatientId() {
            return patientId;
        }

        public void setPatientId(long patientId) {
            this.patientId = patientId;
        }

        public Long getTaskId() {
            return taskId;
        }

        public void setTaskId(Long taskId) {
            this.taskId = taskId;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public LocalDateTime getContactTime() {
            return contactTime;
        }

        public void setContactTime(LocalDateTime contactTime) {
            this.contactTime = contactTime;
        }

        public Long getContactVersionId() {
            return contactVersionId;
        }

        public void setContactVersionId(Long contactVersionId) {
            this.contactVersionId = contactVersionId;
        }

        public LocalDateTime getNextContactTime() {
            return nextContactTime;
        }

        public void setNextContactTime(LocalDateTime nextContactTime) {
            this.nextContactTime = nextContactTime;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }
    }

    /** 录音展示行（联查 sys_file：格式、原名与大小；AMR 的 inlinePlayable=false 由服务层按格式判定）。 */
    record AudioRow(
            long contactLogId,
            long fileId,
            String format,
            String originalName,
            String mimeType,
            long sizeBytes,
            int sortNo) {}

    /** 任务列表「最近联系」行（每患者最新一条，服务层取 max(id)）。 */
    record LatestResultRow(long patientId, long id, String result) {}

    // ---------------------------------------------------------------- 读写（服务层已做患者范围校验）

    /** 插入一条沟通记录（服务层校验后调用）。 */
    @Insert(
            """
            INSERT INTO fu_contact_log
              (patient_id, task_id, method, result, contact_time, contact_version_id,
               next_contact_time, note, created_by)
            VALUES (#{patientId}, #{taskId}, #{method}, #{result}, #{contactTime},
                    #{contactVersionId}, #{nextContactTime}, #{note}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ContactLogInsert row);

    /** 按 id 取行（写入口与详情兜底；服务层已做患者范围校验）。 */
    @IgnoreScope(reason = "沟通记录单行读写（E15-T04）：服务层先经 PatientContactGateway 做患者数据范围校验（40400）")
    @Select(
            """
            SELECT c.id, c.patient_id AS patientId, c.task_id AS taskId, c.method, c.result,
                   c.contact_time AS contactTime, c.contact_version_id AS contactVersionId,
                   c.next_contact_time AS nextContactTime, c.note,
                   c.lock_version AS lockVersion, c.created_by AS createdBy,
                   u.display_name AS creatorName, c.created_at AS createdAt,
                   c.updated_at AS updatedAt
            FROM fu_contact_log c
            LEFT JOIN sys_user u ON u.id = c.created_by
            WHERE c.id = #{id}
            """)
    ContactLogRow find(@Param("id") long id);

    /** 修改（乐观锁：lock_version = baseVersion，成功后 +1；落空 40900 由服务层转译）。 */
    @Update(
            """
            UPDATE fu_contact_log
            SET method = #{method}, result = #{result}, contact_time = #{contactTime},
                contact_version_id = #{contactVersionId},
                next_contact_time = #{nextContactTime}, note = #{note},
                lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = #{baseVersion}
            """)
    int update(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("method") String method,
            @Param("result") String result,
            @Param("contactTime") LocalDateTime contactTime,
            @Param("contactVersionId") Long contactVersionId,
            @Param("nextContactTime") LocalDateTime nextContactTime,
            @Param("note") String note);

    /** 删除沟通记录行（录音关联与 sys_file 软删由服务层同事务处理）。 */
    @IgnoreScope(reason = "沟通记录删除（E15-T04）：服务层先经 PatientContactGateway 做患者数据范围校验（40400）")
    @Delete("DELETE FROM fu_contact_log WHERE id = #{id}")
    int delete(@Param("id") long id);

    // ---------------------------------------------------------------- 患者面读取（@DataScope 出行）

    /** 患者沟通记录分页（倒序，最近联系在前；pages.md §4.8 沟通记录页签）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT c.id, c.patient_id AS patientId, c.task_id AS taskId, c.method, c.result,
                   c.contact_time AS contactTime, c.contact_version_id AS contactVersionId,
                   c.next_contact_time AS nextContactTime, c.note,
                   c.lock_version AS lockVersion, c.created_by AS createdBy,
                   u.display_name AS creatorName, c.created_at AS createdAt,
                   c.updated_at AS updatedAt
            FROM fu_contact_log c
            LEFT JOIN sys_user u ON u.id = c.created_by
            WHERE c.patient_id = #{patientId}
            ORDER BY c.contact_time DESC, c.id DESC
            LIMIT #{offset}, #{limit}
            """)
    List<ContactLogRow> pageOf(
            @Param("patientId") long patientId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 患者沟通记录总数（与 {@link #pageOf} 同条件）。 */
    @DataScope(resource = "patient")
    @Select("SELECT COUNT(*) FROM fu_contact_log WHERE patient_id = #{patientId}")
    long countOf(@Param("patientId") long patientId);

    /** 一批患者的全部联系结果（任务列表「最近联系」：服务层按 max(id) 取每患者最新一条，E15-T04）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT patient_id AS patientId, id, result
            FROM fu_contact_log
            WHERE patient_id IN
            <foreach collection="patientIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    List<LatestResultRow> resultsOfPatients(@Param("patientIds") List<Long> patientIds);

    // ---------------------------------------------------------------- 录音附件（fu_contact_audio +
    // sys_file）

    /** 关联一条录音（file_id 唯一键兜底防重复关联；服务层先校验 AUDIO 类别与 PENDING 状态）。 */
    @Insert(
            """
            INSERT INTO fu_contact_audio (contact_log_id, file_id, sort_no)
            VALUES (#{contactLogId}, #{fileId}, #{sortNo})
            """)
    int insertAudio(
            @Param("contactLogId") long contactLogId,
            @Param("fileId") long fileId,
            @Param("sortNo") int sortNo);

    /** 某批沟通记录的录音（联查 sys_file 展示信息；按 sort_no、id 升序）。 */
    @Select(
            """
            <script>
            SELECT ca.contact_log_id AS contactLogId, ca.file_id AS fileId,
                   f.format_code AS format, f.original_name AS originalName,
                   f.mime_type AS mimeType, f.size_bytes AS sizeBytes, ca.sort_no AS sortNo
            FROM fu_contact_audio ca
            JOIN sys_file f ON f.id = ca.file_id
            WHERE ca.contact_log_id IN
            <foreach collection="contactLogIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            ORDER BY ca.sort_no ASC, ca.id ASC
            </script>
            """)
    List<AudioRow> audiosOf(@Param("contactLogIds") List<Long> contactLogIds);

    /** 删除沟通记录的录音关联行（软删 sys_file 由服务层另行调用文件子域台账）。 */
    @IgnoreScope(reason = "沟通记录删除（E15-T04）清理录音关联：服务层先做患者数据范围校验（40400）")
    @Delete("DELETE FROM fu_contact_audio WHERE contact_log_id = #{contactLogId}")
    int deleteAudiosOf(@Param("contactLogId") long contactLogId);

    /**
     * 录音文件归属的患者（预览权限收紧，E15-T04）：文件挂在沟通记录上时按患者数据范围校验（OQ-62① 的 沟通录音部分提前收紧；报告单文件的数据范围仍随 E16）。未挂沟通记录返回
     * null。
     */
    @IgnoreScope(
            reason =
                    "录音归属患者查找（E15-T04 预览权限收紧）：归属是事实查询不能按范围过滤"
                            + "（越范围文件会被误判为未挂沟通而放行），患者可见性由 ContactAudioScopeGuard 显式校验")
    @Select(
            """
            SELECT c.patient_id
            FROM fu_contact_audio ca
            JOIN fu_contact_log c ON c.id = ca.contact_log_id
            WHERE ca.file_id = #{fileId}
            """)
    Long patientIdOfAudioFile(@Param("fileId") long fileId);
}
