package com.crdp.followup.report.infra.mapper;

import com.crdp.followup.file.app.FileDuplicateCheckPort;
import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 全院报告单列表与检索（E16-T05 / A-ATT-016、C-SCAN-014；docs/api/report.md §2 POST /reports/search）。
 *
 * <p>语句全部 {@code @DataScope(resource = "patient")} 出行（rpt_report / rpt_report_link /
 * rpt_report_file / fu_record 都在 {@code ControlledTables}，E07-T02 改写）：按病区隔离是验收口径——越范围患者的报告单在全院列表
 * 不出现（数据范围改写，非查询条件），范围为空追加 {@code AND (1 = 0)} 返回空页。
 *
 * <p>患者侧条件（姓名 / 病案号）为盲索引精确匹配，随访编号 / 原系统编号为等值匹配； 文字关键词（名称 / 医院 / 所见 / 诊断）为 LIKE 匹配，{@code
 * field_values} 按 JSON 文本匹配（类型字段值如「医院」，C-SCAN-014）。
 */
@Mapper
public interface ReportSearchMapper {

    /** 列表行原始数据：姓名 / 病案号为密文，由服务层解密后脱敏输出（AGENTS §5.3）。 */
    record RowRaw(
            long id,
            long patientId,
            String nameCipher,
            String mrnCipher,
            String visitNo,
            String wardName,
            String typeCode,
            String typeName,
            String nameSnapshot,
            LocalDate reportDate,
            String digitizeStatus,
            String creatorName,
            String createdAt) {}

    /**
     * 检索条件（除 locationScope 外全部可空 = 不过滤）。locationScope：{@code UNASSIGNED} 未指定位置（无任何位置行）、 {@code
     * BASELINE} 含基线位置、{@code FOLLOWUP} 含随访记录位置（A-ATT-016「位置（含『未指定位置』）」）。 nameBlind / mrnBlind /
     * keyword / textKeyword 同源于一个检索词：前两者盲索引精确、keyword 等值匹配随访编号与原系统编号、 textKeyword LIKE 匹配名称 / 所见 /
     * 结论 / 类型字段文字。
     */
    record Criteria(
            String typeCode,
            LocalDate reportDateFrom,
            LocalDate reportDateTo,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            Long creatorId,
            String nameBlind,
            String mrnBlind,
            String keyword,
            String textKeyword,
            String locationScope,
            String digitizeStatus,
            List<Long> wardIds) {

        boolean unassignedOnly() {
            return "UNASSIGNED".equals(locationScope);
        }
    }

    /** 位置行（联随访记录标题；基线分类 / 随访分组的显示名由服务层按模板解析）。 */
    record LinkRowRaw(
            long id,
            long reportId,
            String linkType,
            Long linkRecordId,
            String anchorType,
            String anchorKey,
            Integer templateVersion,
            Integer adjusted,
            Long recordId,
            String nodeName,
            String recordSource,
            LocalDate visitDate,
            LocalDate planDate,
            String formCode,
            Integer recordTemplateVersion) {}

    /** 附件计数行。 */
    record FileCountRow(long reportId, long fileCount) {}

    /** 首个附件行（缩略图列；服务层按 report_id 取 sort_no 最小的一条）。 */
    record FileRowRaw(long reportId, long fileId, String formatCode, String thumbnailKey) {}

    /** 上传人候选（列表筛选下拉；行经 @DataScope，候选即当前范围内出现过的上传人）。 */
    record UserOptionRow(long id, String name) {}

    /** 分页检索一页（默认按上传时间倒序，全院归档视角：最新上传的在最前）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT r.id, r.patient_id AS patientId,
                   pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   pt.visit_no AS visitNo, w.name AS wardName,
                   r.type_code AS typeCode, r.type_name AS typeName,
                   r.name_snapshot AS nameSnapshot, r.report_date AS reportDate,
                   r.digitize_status AS digitizeStatus,
                   u.display_name AS creatorName, r.created_at AS createdAt
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            LEFT JOIN sys_user u ON u.id = r.created_by
            WHERE r.status = 'NORMAL'
            <if test="c.typeCode != null">AND r.type_code = #{c.typeCode}</if>
            <if test="c.reportDateFrom != null">AND r.report_date &gt;= #{c.reportDateFrom}</if>
            <if test="c.reportDateTo != null">AND r.report_date &lt;= #{c.reportDateTo}</if>
            <if test="c.uploadedFrom != null">AND r.created_at &gt;= #{c.uploadedFrom}</if>
            <if test="c.uploadedTo != null">AND r.created_at &lt; DATE_ADD(#{c.uploadedTo}, INTERVAL 1 DAY)</if>
            <if test="c.creatorId != null">AND r.created_by = #{c.creatorId}</if>
            <if test="c.digitizeStatus != null">AND r.digitize_status = #{c.digitizeStatus}</if>
            <if test="c.wardIds != null">
              AND pt.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.locationScope == 'UNASSIGNED'">
              AND NOT EXISTS (SELECT 1 FROM rpt_report_link l WHERE l.report_id = r.id)
            </if>
            <if test="c.locationScope == 'BASELINE'">
              AND EXISTS (SELECT 1 FROM rpt_report_link l
                          WHERE l.report_id = r.id AND l.link_type IN ('baseline', 'implant_record'))
            </if>
            <if test="c.locationScope == 'FOLLOWUP'">
              AND EXISTS (SELECT 1 FROM rpt_report_link l
                          WHERE l.report_id = r.id AND l.link_type = 'followup_record')
            </if>
            <if test="c.textKeyword != null">
              AND (pt.name_blind = #{c.nameBlind}
                   OR pt.mrn_blind = #{c.mrnBlind}
                   OR pt.visit_no = #{c.keyword}
                   OR pt.legacy_no = #{c.keyword}
                   OR r.name_snapshot LIKE CONCAT('%', #{c.textKeyword}, '%')
                   OR r.findings LIKE CONCAT('%', #{c.textKeyword}, '%')
                   OR r.conclusion LIKE CONCAT('%', #{c.textKeyword}, '%')
                   OR r.field_values LIKE CONCAT('%', #{c.textKeyword}, '%'))
            </if>
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<RowRaw> search(
            @Param("c") Criteria criteria, @Param("offset") int offset, @Param("limit") int limit);

    /** 条件总数（与 {@link #search} 同一条件树）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.status = 'NORMAL'
            <if test="c.typeCode != null">AND r.type_code = #{c.typeCode}</if>
            <if test="c.reportDateFrom != null">AND r.report_date &gt;= #{c.reportDateFrom}</if>
            <if test="c.reportDateTo != null">AND r.report_date &lt;= #{c.reportDateTo}</if>
            <if test="c.uploadedFrom != null">AND r.created_at &gt;= #{c.uploadedFrom}</if>
            <if test="c.uploadedTo != null">AND r.created_at &lt; DATE_ADD(#{c.uploadedTo}, INTERVAL 1 DAY)</if>
            <if test="c.creatorId != null">AND r.created_by = #{c.creatorId}</if>
            <if test="c.digitizeStatus != null">AND r.digitize_status = #{c.digitizeStatus}</if>
            <if test="c.wardIds != null">
              AND pt.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.locationScope == 'UNASSIGNED'">
              AND NOT EXISTS (SELECT 1 FROM rpt_report_link l WHERE l.report_id = r.id)
            </if>
            <if test="c.locationScope == 'BASELINE'">
              AND EXISTS (SELECT 1 FROM rpt_report_link l
                          WHERE l.report_id = r.id AND l.link_type IN ('baseline', 'implant_record'))
            </if>
            <if test="c.locationScope == 'FOLLOWUP'">
              AND EXISTS (SELECT 1 FROM rpt_report_link l
                          WHERE l.report_id = r.id AND l.link_type = 'followup_record')
            </if>
            <if test="c.textKeyword != null">
              AND (pt.name_blind = #{c.nameBlind}
                   OR pt.mrn_blind = #{c.mrnBlind}
                   OR pt.visit_no = #{c.keyword}
                   OR pt.legacy_no = #{c.keyword}
                   OR r.name_snapshot LIKE CONCAT('%', #{c.textKeyword}, '%')
                   OR r.findings LIKE CONCAT('%', #{c.textKeyword}, '%')
                   OR r.conclusion LIKE CONCAT('%', #{c.textKeyword}, '%')
                   OR r.field_values LIKE CONCAT('%', #{c.textKeyword}, '%'))
            </if>
            </script>
            """)
    long count(@Param("c") Criteria criteria);

    /** 本页报告单的位置行（位置列标签；联随访记录标题，@DataScope 出行）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT l.id, l.report_id AS reportId, l.link_type AS linkType,
                   l.link_record_id AS linkRecordId, l.anchor_type AS anchorType,
                   l.anchor_key AS anchorKey, l.template_version AS templateVersion,
                   l.adjusted,
                   fr.id AS recordId, n.name AS nodeName, fr.source AS recordSource,
                   fr.visit_date AS visitDate, t.plan_date AS planDate,
                   fr.form_code AS formCode, fr.template_version AS recordTemplateVersion
            FROM rpt_report_link l
            JOIN pt_patient pt ON pt.id = l.patient_id AND pt.deleted = 0
            LEFT JOIN fu_record fr ON fr.id = l.link_record_id
            LEFT JOIN fu_task t ON t.id = fr.task_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE l.report_id IN
            <foreach collection="reportIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            ORDER BY l.report_id ASC, l.id ASC
            </script>
            """)
    List<LinkRowRaw> linksOfReports(@Param("reportIds") List<Long> reportIds);

    /** 本页报告单的附件计数（@DataScope 出行）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT f.report_id AS reportId, COUNT(*) AS fileCount
            FROM rpt_report_file f
            JOIN pt_patient pt ON pt.id = f.patient_id AND pt.deleted = 0
            WHERE f.report_id IN
            <foreach collection="reportIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            GROUP BY f.report_id
            </script>
            """)
    List<FileCountRow> fileCountsOfReports(@Param("reportIds") List<Long> reportIds);

    /** 本页报告单的附件（sort_no 升序；服务层每组取第一条做缩略图）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT f.report_id AS reportId, f.file_id AS fileId,
                   sf.format_code AS formatCode, sf.thumbnail_key AS thumbnailKey
            FROM rpt_report_file f
            JOIN pt_patient pt ON pt.id = f.patient_id AND pt.deleted = 0
            JOIN sys_file sf ON sf.id = f.file_id AND sf.status &lt;&gt; 'DELETED'
            WHERE f.report_id IN
            <foreach collection="reportIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            ORDER BY f.report_id ASC, f.sort_no ASC, f.id ASC
            </script>
            """)
    List<FileRowRaw> firstFilesOfReports(@Param("reportIds") List<Long> reportIds);

    /** 上传人候选（当前数据范围内出现过的上传人，按显示名排序）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT r.created_by AS id, u.display_name AS name
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            JOIN sys_user u ON u.id = r.created_by
            WHERE r.status = 'NORMAL' AND r.created_by IS NOT NULL
            GROUP BY r.created_by, u.display_name
            ORDER BY u.display_name ASC
            """)
    List<UserOptionRow> uploaderOptions();

    /**
     * 重复文件提示的命中患者（E21-T18 / C-SCAN-017）：按文件 SHA-256 找已挂 NORMAL 报告单的患者。 只算已挂报告单（{@code
     * rpt_report_file}）且未删除（sys_file ≠ DELETED）的文件——PENDING 上传没有患者归属不参与（刚上传 的这份自查不能命中自己，见
     * FileDuplicateCheckService 口径）；同一患者多份报告单只出一行；患者 id 升序保证 LIMIT 截断确定。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT pt.id AS patientId,
                   pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher,
                   pt.visit_no AS visitNo, w.name AS wardName
            FROM sys_file sf
            JOIN rpt_report_file rf ON rf.file_id = sf.id
            JOIN rpt_report r ON r.id = rf.report_id AND r.status = 'NORMAL'
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            LEFT JOIN ward w ON w.id = pt.ward_id
            WHERE sf.sha256 = #{sha256} AND sf.status <> 'DELETED'
            GROUP BY pt.id, pt.name_cipher, pt.mrn_cipher, pt.visit_no, w.name
            ORDER BY pt.id ASC
            LIMIT #{limit}
            """)
    List<FileDuplicateCheckPort.DuplicateFilePatientRow> findPatientsByFileSha256(
            @Param("sha256") String sha256, @Param("limit") int limit);
}
