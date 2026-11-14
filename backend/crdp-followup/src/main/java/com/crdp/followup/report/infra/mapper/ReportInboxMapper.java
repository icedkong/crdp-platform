package com.crdp.followup.report.infra.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 待归档区运行时表 rpt_inbox_file（E21-T16 / C-SCAN-009，followup V138）。
 *
 * <p>隔离说明（C-WARD-005「待归档区同样按科室病区隔离」）：rpt_inbox_file <b>不在</b> {@code
 * ControlledTables}——受控表改写按「子表.patient_id → pt_patient」EXISTS 锚定，而待归档行没有患者
 * （这正是待归档的定义），登记进清单反而会让受限用户一行都看不到。科室病区隔离由语句内的 {@link Scope} 条件承担（docs/api/report.md §4
 * 暂行口径「按上传人所在科室」）：ALL 范围全见； 否则可见 = 上传人负责科室（sys_user_dept_scope）∩ 操作者科室 ∪ 上传人负责病区（sys_user_ward）∩
 * 操作者病区 ∪ 上传人本人（「仅本人负责的患者」范围用户上传后自己仍能分拣，不滞留）。 {@link Scope} 由服务层从会话范围快照冻结，语句本身不读会话（可单测、口径唯一）。
 *
 * <p>rpt_inbox_file 不含患者面数据（文件名 / 格式 / 大小来自 sys_file 台账），归档生成的报告单 走 {@code
 * ReportService.create}（其查询照常 @DataScope），本 Mapper 不触碰患者受控表。
 */
@Mapper
public interface ReportInboxMapper {

    /** 可见性条件（服务层从 {@code CurrentSession.scope()} 冻结；all=true 时语句不加任何限制）。 */
    record Scope(boolean all, long userId, List<Long> wardIds, List<Long> deptIds) {

        public Scope {
            wardIds = wardIds == null ? List.of() : List.copyOf(wardIds);
            deptIds = deptIds == null ? List.of() : List.copyOf(deptIds);
        }
    }

    /** 检索条件（可见性 + 上传日期 / 上传人过滤；全部可空 = 只按可见性）。 */
    record Criteria(Scope scope, LocalDate uploadedFrom, LocalDate uploadedTo, Long creatorId) {}

    /** 列表行（文件名 / 格式 / 大小来自 sys_file；fileId 供前端换预览短时链接）。 */
    record InboxRowRaw(
            long id,
            long fileId,
            String originalName,
            String formatCode,
            long sizeBytes,
            Integer pages,
            long uploadedBy,
            String uploaderName,
            LocalDateTime createdAt) {}

    /** 上传人候选（当前可见范围内出现过的上传人，筛选下拉用）。 */
    record UploaderOptionRow(long id, String name) {}

    /** 可见性条件片段（search / count / findVisible / uploaderOptions 共用同一拼装，口径唯一）。 */
    String VISIBILITY =
            """
            <if test="s.all">
              AND 1 = 1
            </if>
            <if test="!s.all">
              AND (
                i.uploaded_by = #{s.userId}
                <if test="s.wardIds != null and !s.wardIds.isEmpty()">
                  OR EXISTS (
                    SELECT 1 FROM sys_user_ward uw
                    WHERE uw.user_id = i.uploaded_by AND uw.ward_id IN
                    <foreach collection="s.wardIds" item="w" open="(" separator="," close=")">#{w}</foreach>
                  )
                </if>
                <if test="s.deptIds != null and !s.deptIds.isEmpty()">
                  OR EXISTS (
                    SELECT 1 FROM sys_user_dept_scope ds
                    WHERE ds.user_id = i.uploaded_by AND ds.dept_id IN
                    <foreach collection="s.deptIds" item="d" open="(" separator="," close=")">#{d}</foreach>
                  )
                </if>
              )
            </if>
            """;

    /** 过滤条件片段（上传日期 / 上传人；与 VISIBILITY 组合使用）。 */
    String FILTERS =
            """
            <if test="c.uploadedFrom != null">AND i.created_at &gt;= #{c.uploadedFrom}</if>
            <if test="c.uploadedTo != null">AND i.created_at &lt; DATE_ADD(#{c.uploadedTo}, INTERVAL 1 DAY)</if>
            <if test="c.creatorId != null">AND i.uploaded_by = #{c.creatorId}</if>
            """;

    /** 分页检索一页（待分拣行按进区时间倒序：最新扫描的排最前，先处理最新批次）。 */
    @Select(
            """
            <script>
            SELECT i.id, i.file_id AS fileId, sf.original_name AS originalName,
                   sf.format_code AS formatCode, sf.size_bytes AS sizeBytes, sf.pages,
                   i.uploaded_by AS uploadedBy, u.display_name AS uploaderName, i.created_at AS createdAt
            FROM rpt_inbox_file i
            JOIN sys_file sf ON sf.id = i.file_id AND sf.status &lt;&gt; 'DELETED'
            LEFT JOIN sys_user u ON u.id = i.uploaded_by
            WHERE i.status = 'PENDING'
            """
                    + FILTERS
                    + VISIBILITY
                    + """
            ORDER BY i.created_at DESC, i.id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<InboxRowRaw> search(
            @Param("c") Criteria criteria,
            @Param("s") Scope scope,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 条件总数（与 {@link #search} 同一条件树）。 */
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM rpt_inbox_file i
            JOIN sys_file sf ON sf.id = i.file_id AND sf.status &lt;&gt; 'DELETED'
            WHERE i.status = 'PENDING'
            """
                    + FILTERS
                    + VISIBILITY
                    + """
            </script>
            """)
    long count(@Param("c") Criteria criteria, @Param("s") Scope scope);

    /** 单条可见的待分拣行（归档入口的范围校验：不可见 / 不存在 / 已归档返回 null，上层 40400）。 */
    @Select(
            """
            <script>
            SELECT i.id, i.file_id AS fileId, sf.original_name AS originalName,
                   sf.format_code AS formatCode, sf.size_bytes AS sizeBytes, sf.pages,
                   i.uploaded_by AS uploadedBy, u.display_name AS uploaderName, i.created_at AS createdAt
            FROM rpt_inbox_file i
            JOIN sys_file sf ON sf.id = i.file_id AND sf.status &lt;&gt; 'DELETED'
            LEFT JOIN sys_user u ON u.id = i.uploaded_by
            WHERE i.status = 'PENDING' AND i.id = #{id}
            """
                    + VISIBILITY
                    + """
            LIMIT 1
            </script>
            """)
    InboxRowRaw findVisible(@Param("id") long id, @Param("s") Scope scope);

    /** 上传人候选（当前可见范围内出现过的上传人，按显示名排序）。 */
    @Select(
            """
            <script>
            SELECT DISTINCT i.uploaded_by AS id, u.display_name AS name
            FROM rpt_inbox_file i
            LEFT JOIN sys_user u ON u.id = i.uploaded_by
            WHERE i.status = 'PENDING'
            """
                    + VISIBILITY
                    + """
            ORDER BY u.display_name ASC
            </script>
            """)
    List<UploaderOptionRow> uploaderOptions(@Param("s") Scope scope);

    /** 文件当前所在待归档行 id（进区去重用；不在区 / 已归档返回 null）。 */
    @Select("SELECT id FROM rpt_inbox_file WHERE file_id = #{fileId} AND status = 'PENDING'")
    Long findIdByFileId(@Param("fileId") long fileId);

    /** 插入参数（一行 = 一个待分拣文件；uploaded_by 冻结进区操作人作隔离锚点）。 */
    record InboxInsert(long fileId, long uploadedBy) {}

    @Insert(
            """
            INSERT INTO rpt_inbox_file (file_id, uploaded_by) VALUES (#{fileId}, #{uploadedBy})
            """)
    int insert(InboxInsert row);

    /** 归档落账：行置 ARCHIVED 并记生成的报告单与操作人（行保留作溯源，不删除）。 */
    @Update(
            """
            UPDATE rpt_inbox_file
            SET status = 'ARCHIVED', archived_report_id = #{reportId},
                archived_by = #{operator}, archived_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markArchived(
            @Param("id") long id,
            @Param("reportId") long reportId,
            @Param("operator") long operator);
}
