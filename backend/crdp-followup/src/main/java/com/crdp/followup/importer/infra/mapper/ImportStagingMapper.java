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
 * imp_job / imp_row / imp_issue 读写（E18-T02 暂存、E18-T03 校验 / tech-plan §12③④⑤）。 校验阶段从本 mapper
 * 取暂存行（payload 由应用层经 CryptoService 解密——mapper 只做持久化）并写回校验问题。 导入作业非受控表（ControlledTables）：
 * 病区范围在导入确认时校验 （E18-T04 / T06），状态查询按作业归属人。
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
            String optionsJson,
            String scopeJson,
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
                   storage_key AS storageKey, layout, status,
                   options_json AS optionsJson, scope_json AS scopeJson, staged_rows AS stagedRows,
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

    // ---------------------------------------------------------------- 校验问题（E18-T03，imp_issue）

    /** 暂存行读取行（校验阶段按 id 游标分批读，payload 为密文由应用层解密）。 */
    record StagedRow(
            long id,
            String sheetName,
            int rowNo,
            String rowType,
            String mrnBlind,
            String payloadCipher) {}

    /** 校验问题插入行（colNo / colLetters / header 可空：无法定位列的问题）。 */
    record IssueInsert(
            long jobId,
            String sheetName,
            int rowNo,
            Integer colNo,
            String colLetters,
            String header,
            String level,
            String code,
            String message) {

        public static IssueInsert of(
                long jobId, com.crdp.followup.importer.domain.ImportIssue issue) {
            return new IssueInsert(
                    jobId,
                    issue.sheetName(),
                    issue.rowNo(),
                    issue.colNo(),
                    issue.columnLetters(),
                    issue.header(),
                    issue.level().name(),
                    issue.code(),
                    issue.message());
        }
    }

    /**
     * 按 id 游标分批读暂存行（keyset 分页：内存只有一批，E18-T03 校验与大文件口径一致）。
     *
     * <p>IgnoreScope 理由：预检校验在作业维度读<strong>本作业自己暂存</strong>的行（job_id 限定）， 病案号查重走全院盲索引存在性（同 {@code
     * PatientMapper.findLiveMrnBlinds} 口径）； 病区数据范围在导入确认时校验（E18-T04 / T06，与 imp_row 登记进
     * ControlledTables 的口径一致——暂存行含患者数据，后续行级确认读数必须带范围或作业归属校验）。
     */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "导入预检校验读本作业暂存行（job_id 限定，E18-T03）：暂存行尚无病区归属，"
                            + "病区数据范围在导入确认时校验（E18-T04 / T06）；查重只做全院盲索引存在性，不输出患者数据")
    @Select(
            """
            SELECT id, sheet_name AS sheetName, row_no AS rowNo, row_type AS rowType,
                   mrn_blind AS mrnBlind, payload_cipher AS payloadCipher
            FROM imp_row WHERE job_id = #{jobId} AND id > #{afterId}
            ORDER BY id LIMIT #{limit}
            """)
    List<StagedRow> findRows(
            @Param("jobId") long jobId, @Param("afterId") long afterId, @Param("limit") int limit);

    /** 批量写校验问题（校验按批 flush）。 */
    @Insert(
            """
            <script>
            INSERT INTO imp_issue (job_id, sheet_name, row_no, col_no, col_letters, header, level, code, message)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{row.jobId}, #{row.sheetName}, #{row.rowNo}, #{row.colNo}, #{row.colLetters},
               #{row.header}, #{row.level}, #{row.code}, #{row.message})
            </foreach>
            </script>
            """)
    int insertIssues(@Param("rows") List<IssueInsert> rows);

    /** 清掉该作业的校验问题（重新校验前先清，幂等）。 */
    @Delete("DELETE FROM imp_issue WHERE job_id = #{jobId}")
    int deleteIssues(@Param("jobId") long jobId);

    /** 按级别数问题（null = 全部；确认门控「有 ERROR 时 40001」用，E18-T05）。 */
    @Select(
            "<script>SELECT COUNT(*) FROM imp_issue WHERE job_id = #{jobId}"
                    + " <if test='level != null'>AND level = #{level}</if></script>")
    long countIssues(@Param("jobId") long jobId, @Param("level") String level);
}
