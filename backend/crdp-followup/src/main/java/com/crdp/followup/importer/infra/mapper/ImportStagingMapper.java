package com.crdp.followup.importer.infra.mapper;

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
 * imp_job / imp_row 读写（E18-T02 / tech-plan §12③）。预检阶段（E18-T03 起）从本表取数： 暂存行按 job_id 顺序读、 payload
 * 由应用层经 CryptoService 解密——mapper 只做持久化。导入作业非受控表（ControlledTables）： 病区范围在导入确认时校验 （E18-T04 /
 * T06），状态查询按作业归属人。
 */
@Mapper
public interface ImportStagingMapper {

    /** 活跃（运行中）状态：同一租户同一时间只允许一个导入作业（tech-plan §12）。 */
    List<String> ACTIVE_STATUSES = List.of("UPLOADED", "PRECHECKING", "IMPORTING");

    /** imp_job 行。 */
    record JobRow(
            long id,
            String fileName,
            long fileSize,
            String fileSha256,
            String storageKey,
            String layout,
            String status,
            long stagedRows,
            String layoutProblems,
            String failReason,
            long operatorId,
            String operatorName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /** imp_row 插入行（payload 为密文、mrnBlind 为盲索引，应用层算好）。 */
    record RowInsert(
            long jobId,
            String sheetName,
            int rowNo,
            String rowType,
            String mrnBlind,
            String payloadCipher,
            String problems) {}

    /** 建作业插入行（id 由自增回填；可变 POJO，MyBatis 回写主键用——与 ContactLogInsert 同款）。 */
    class JobInsert {
        private Long id;
        private String fileName;
        private long fileSize;
        private String fileSha256;
        private long operatorId;
        private String operatorName;

        public JobInsert(
                String fileName,
                long fileSize,
                String fileSha256,
                long operatorId,
                String operatorName) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileSha256 = fileSha256;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getFileName() {
            return fileName;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getFileSha256() {
            return fileSha256;
        }

        public long getOperatorId() {
            return operatorId;
        }

        public String getOperatorName() {
            return operatorName;
        }
    }

    /** 建导入作业（状态 UPLOADED）。 */
    @Insert(
            """
            INSERT INTO imp_job (file_name, file_size, file_sha256, status, operator_id, operator_name)
            VALUES (#{fileName}, #{fileSize}, #{fileSha256}, 'UPLOADED', #{operatorId}, #{operatorName})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertJob(JobInsert row);

    /** 活跃作业数（0 = 可建新作业；导入频率低 + 上传端点串行派发，检查-插入足够）。 */
    @Select(
            "<script>SELECT COUNT(*) FROM imp_job WHERE status IN ("
                    + " <foreach collection='statuses' item='s' separator=','>#{s}</foreach>)</script>")
    long countActive(@Param("statuses") List<String> statuses);

    /** 取作业（不存在 null；存在性 / 归属检查在服务层）。 */
    @Select(
            """
            SELECT id, file_name AS fileName, file_size AS fileSize, file_sha256 AS fileSha256,
                   storage_key AS storageKey, layout, status, staged_rows AS stagedRows,
                   layout_problems AS layoutProblems, fail_reason AS failReason,
                   operator_id AS operatorId, operator_name AS operatorName,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM imp_job WHERE id = #{id}
            """)
    JobRow findJob(@Param("id") long id);

    /** CAS 状态迁移（返回 0 = 当前状态不符，服务层给 40901）。 */
    @Update("UPDATE imp_job SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int casStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    /** 进度：staged_rows 增量累加（写暂存行的批次粒度）。 */
    @Update("UPDATE imp_job SET staged_rows = staged_rows + #{delta} WHERE id = #{id}")
    int addStagedRows(@Param("id") long id, @Param("delta") long delta);

    /** 预检完成：PRECHECKING → PRECHECKED，落版面与最终行数。 */
    @Update(
            """
            UPDATE imp_job SET status = 'PRECHECKED', layout = #{layout}, staged_rows = #{stagedRows}
            WHERE id = #{id} AND status = 'PRECHECKING'
            """)
    int finishPrecheck(
            @Param("id") long id,
            @Param("layout") String layout,
            @Param("stagedRows") long stagedRows);

    /** 预检失败：PRECHECKING → PRECHECK_FAILED，落整体错误清单（版面 / 表头）或失败原因。 */
    @Update(
            """
            UPDATE imp_job SET status = 'PRECHECK_FAILED', layout = #{layout},
                   layout_problems = #{layoutProblems}, fail_reason = #{failReason}
            WHERE id = #{id} AND status = 'PRECHECKING'
            """)
    int failPrecheck(
            @Param("id") long id,
            @Param("layout") String layout,
            @Param("layoutProblems") String layoutProblems,
            @Param("failReason") String failReason);

    /** 批量写暂存行（流式解析按批 flush，内存只有一批）。 */
    @Insert(
            """
            <script>
            INSERT INTO imp_row (job_id, sheet_name, row_no, row_type, mrn_blind, payload_cipher, problems)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{row.jobId}, #{row.sheetName}, #{row.rowNo}, #{row.rowType}, #{row.mrnBlind},
               #{row.payloadCipher}, #{row.problems})
            </foreach>
            </script>
            """)
    int insertRows(@Param("rows") List<RowInsert> rows);

    /** 清掉该作业已暂存的行（预检失败不留半成品；重跑预检前也先清）。 */
    @Delete("DELETE FROM imp_row WHERE job_id = #{jobId}")
    int deleteRows(@Param("jobId") long jobId);
}
