package com.crdp.followup.report.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 报告单运行时表 rpt_report / rpt_report_link / rpt_report_file / rpt_indicator_value（E16-T02 /
 * A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010；表 followup V63）。
 *
 * <p>四张表都在 {@code ControlledTables}：清单查询经 {@code @DataScope(resource = "patient")} 按当前用户范围出行（子表按
 * patient_id EXISTS 改写，E07-T02）；单行读写由服务层先做患者范围校验 （越范围 40400）再 {@code @IgnoreScope} 进表，与 {@code
 * ContactLogMapper} 同口径。配置删除守卫的 全租户计数同样 {@code @IgnoreScope}（只回计数，不吐患者数据）。
 */
@Mapper
public interface ReportMapper {

    /** 报告单行（患者内清单，报告时间倒序）。 */
    record ReportRow(
            Long id,
            Long patientId,
            String typeCode,
            String typeName,
            Long nameId,
            String nameSnapshot,
            LocalDate reportDate,
            String findings,
            String conclusion,
            String note,
            String digitizeStatus,
            Integer version,
            Long createdBy,
            String creatorName,
            String createdAt) {}

    /** 位置行（报告单联查，anchor 快照 + 展示标签由服务层拼）。 */
    record LinkRow(
            Long id,
            Long reportId,
            String linkType,
            Long linkRecordId,
            String anchorType,
            String anchorKey,
            Integer templateVersion,
            Integer adjusted) {}

    /** 附件计数行。 */
    record FileCountRow(Long reportId, Long fileCount) {}

    /** 首个附件行（E16-T14 左栏 / 内联缩略图数据源；服务层按 report_id 取 sort_no 最小一条）。 */
    record FirstFileRow(long reportId, long fileId, String formatCode) {}

    /** 报告单头行（E16-T07 映射写入：类型 / 名称快照 / 报告时间）。 */
    record ReportHeadRow(
            Long id,
            Long patientId,
            String typeCode,
            String typeName,
            String nameSnapshot,
            LocalDate reportDate) {}

    /** 指标结果行（E16-T07 映射写入取值，按模板顺序）。 */
    record IndicatorRow(String itemCode, String result) {}

    /** 患者手术 / 出院日期（基线映射时相边界校验，A-ATT-013 / V64 remark）。 */
    record PatientDatesRow(LocalDate surgeryDate, LocalDate dischargeDate) {}

    /** 随访记录行（位置树用：label 取任务节点名或补录 / 计划外来源，date 取计划或实际日期）。 */
    record RecordRow(
            Long id,
            Long taskId,
            String nodeName,
            String source,
            LocalDate visitDate,
            LocalDate planDate,
            String formCode,
            Integer templateVersion,
            String status) {}

    /** 新建报告单（生成 id 回填到 {@link ReportInsert#setId}）。 */
    @Insert(
            """
            INSERT INTO rpt_report (patient_id, type_code, type_name, name_id, name_snapshot,
                                    report_date, findings, conclusion, note, field_values,
                                    digitize_status, status, version, created_by)
            VALUES (#{patientId}, #{typeCode}, #{typeName}, #{nameId}, #{nameSnapshot},
                    #{reportDate}, #{findings}, #{conclusion}, #{note}, #{fieldValues},
                    #{digitizeStatus}, 'NORMAL', 0, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertReport(ReportInsert row);

    /** 位置插入参数（同一报告单同一位置只一行：服务层去重 + uk 兜底）。 */
    record LinkInsert(
            long reportId,
            long patientId,
            String linkType,
            Long linkRecordId,
            String anchorType,
            String anchorKey,
            int templateVersion) {}

    @Insert(
            """
            INSERT INTO rpt_report_link (report_id, patient_id, link_type, link_record_id,
                                         anchor_type, anchor_key, template_version)
            VALUES (#{reportId}, #{patientId}, #{linkType}, #{linkRecordId},
                    #{anchorType}, #{anchorKey}, #{templateVersion})
            """)
    int insertLink(LinkInsert row);

    /** 附件插入参数。 */
    record FileInsert(long reportId, long patientId, long fileId, int sortNo) {}

    @Insert(
            """
            INSERT INTO rpt_report_file (report_id, patient_id, file_id, sort_no)
            VALUES (#{reportId}, #{patientId}, #{fileId}, #{sortNo})
            """)
    int insertFile(FileInsert row);

    /** 指标结果插入参数（显示快照 + 服务端计算的 ↑ / ↓ 标记）。 */
    record IndicatorInsert(
            long reportId,
            long patientId,
            String itemCode,
            String itemName,
            String unit,
            String refRange,
            String result,
            String flag,
            int sortNo) {}

    @Insert(
            """
            INSERT INTO rpt_indicator_value (report_id, patient_id, item_code, item_name, unit,
                                             ref_range, result, flag, sort_no)
            VALUES (#{reportId}, #{patientId}, #{itemCode}, #{itemName}, #{unit},
                    #{refRange}, #{result}, #{flag}, #{sortNo})
            """)
    int insertIndicator(IndicatorInsert row);

    // ---------------------------------------------------------------- 查询

    /** 患者内报告单清单（@DataScope 出行；越范围空清单）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT r.id, r.patient_id AS patientId, r.type_code AS typeCode, r.type_name AS typeName,
                   r.name_id AS nameId, r.name_snapshot AS nameSnapshot, r.report_date AS reportDate,
                   r.findings, r.conclusion, r.note, r.digitize_status AS digitizeStatus,
                   r.version, r.created_by AS createdBy, u.display_name AS creatorName,
                   r.created_at AS createdAt
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            LEFT JOIN sys_user u ON u.id = r.created_by
            WHERE r.patient_id = #{patientId} AND r.status = 'NORMAL'
            ORDER BY r.report_date DESC, r.id DESC
            """)
    List<ReportRow> reportsOfPatient(@Param("patientId") long patientId);

    /** 某患者的全部位置行（@DataScope 出行；服务层按报告单分组）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT l.id, l.report_id AS reportId, l.link_type AS linkType,
                   l.link_record_id AS linkRecordId, l.anchor_type AS anchorType,
                   l.anchor_key AS anchorKey, l.template_version AS templateVersion,
                   l.adjusted
            FROM rpt_report_link l
            JOIN pt_patient pt ON pt.id = l.patient_id AND pt.deleted = 0
            WHERE l.patient_id = #{patientId}
            ORDER BY l.id ASC
            """)
    List<LinkRow> linksOfPatient(@Param("patientId") long patientId);

    /**
     * 某患者的附件计数（@DataScope 出行；按报告单分组）。文件可见性以 sys_file.status 为准——
     * 删除单个文件（E16-T09）软删台账后不再计数，关联行保留作归属台账。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT f.report_id AS reportId, COUNT(*) AS fileCount
            FROM rpt_report_file f
            JOIN pt_patient pt ON pt.id = f.patient_id AND pt.deleted = 0
            JOIN sys_file sf ON sf.id = f.file_id AND sf.status <> 'DELETED'
            WHERE f.patient_id = #{patientId}
            GROUP BY f.report_id
            """)
    List<FileCountRow> fileCountsOfPatient(@Param("patientId") long patientId);

    /**
     * 某患者的首个附件行（@DataScope 出行，越范围空清单）。文件可见性以 sys_file.status 为准 （与附件计数同口径）；无附件的报告单不出行，服务层置 null。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT f.report_id AS reportId, f.file_id AS fileId, sf.format_code AS formatCode
            FROM rpt_report_file f
            JOIN pt_patient pt ON pt.id = f.patient_id AND pt.deleted = 0
            JOIN sys_file sf ON sf.id = f.file_id AND sf.status <> 'DELETED'
            WHERE f.patient_id = #{patientId}
            ORDER BY f.report_id ASC, f.sort_no ASC, f.id ASC
            """)
    List<FirstFileRow> firstFilesOfPatient(@Param("patientId") long patientId);

    /** 患者的随访记录行（位置树用；含 DRAFT——整页表单录入中即可上传，pages.md §6.1 入口）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT r.id, t.id AS taskId, n.name AS nodeName, r.source, r.visit_date AS visitDate,
                   t.plan_date AS planDate, r.form_code AS formCode,
                   r.template_version AS templateVersion, r.status
            FROM fu_record r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            LEFT JOIN fu_task t ON t.id = r.task_id
            LEFT JOIN fu_plan_node n ON n.id = t.node_id
            WHERE r.patient_id = #{patientId}
            ORDER BY COALESCE(t.plan_date, r.visit_date) ASC, r.id ASC
            """)
    List<RecordRow> recordsOfPatient(@Param("patientId") long patientId);

    // ---------------------------------------------------------------- 单报告单读取（E16-T07 映射写入）

    /** 单份报告单头（@DataScope 出行：越范围 / 已删 / 不存在 null → 服务层 40400）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT r.id, r.patient_id AS patientId, r.type_code AS typeCode,
                   r.type_name AS typeName, r.name_snapshot AS nameSnapshot,
                   r.report_date AS reportDate
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.id = #{id} AND r.status = 'NORMAL'
            """)
    ReportHeadRow findReport(@Param("id") long id);

    /** 某报告单的位置行（映射目标必须是其中之一，A-ATT-013）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT l.id, l.report_id AS reportId, l.link_type AS linkType,
                   l.link_record_id AS linkRecordId, l.anchor_type AS anchorType,
                   l.anchor_key AS anchorKey, l.template_version AS templateVersion,
                   l.adjusted
            FROM rpt_report_link l
            JOIN pt_patient pt ON pt.id = l.patient_id AND pt.deleted = 0
            WHERE l.report_id = #{reportId}
            ORDER BY l.id ASC
            """)
    List<LinkRow> linksOfReport(@Param("reportId") long reportId);

    /** 某报告单的指标结果（映射按 item_code 取值；同一项目只一行——创建时按模板校验）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT v.item_code AS itemCode, v.result
            FROM rpt_indicator_value v
            JOIN pt_patient pt ON pt.id = v.patient_id AND pt.deleted = 0
            WHERE v.report_id = #{reportId}
            ORDER BY v.sort_no, v.id
            """)
    List<IndicatorRow> indicatorsOfReport(@Param("reportId") long reportId);

    /**
     * 患者手术 / 出院日期（时相边界：术前最后一次 ≤ 手术日期、基线 ≤ 出院日期）。 调用方已经 findReport（@DataScope）确认可见性，本句只取日期、 不吐患者数据。
     */
    @IgnoreScope(reason = "报告单映射时相边界（E16-T07）：调用方已经 findReport（@DataScope）确认报告单可见，只取日期不吐患者数据")
    @Select(
            """
            SELECT p.surgery_date AS surgeryDate, p.discharge_date AS dischargeDate
            FROM pt_patient p
            WHERE p.id = #{patientId} AND p.deleted = 0
            """)
    PatientDatesRow patientDates(@Param("patientId") long patientId);

    // ---------------------------------------------------------------- 删除（E16-T09 /
    // A-ATT-007、C-SCAN-016）

    /** 报告单附件行（删除整份时连带软删 sys_file；original_name 进审计描述）。 */
    record ReportFileRow(long fileId, String originalName) {}

    /** 某报告单的全部附件行（删除整份报告单用）。调用方已经 findReport（@DataScope）确认报告单可见， 本句只取附件清单、不吐患者数据。 */
    @IgnoreScope(
            reason =
                    "报告单附件清单（E16-T09 删除）：调用方已经 findReport（@DataScope）确认报告单可见，"
                            + "只取附件 id 与文件名不吐患者数据")
    @Select(
            """
            SELECT f.file_id AS fileId, sf.original_name AS originalName
            FROM rpt_report_file f
            JOIN sys_file sf ON sf.id = f.file_id
            WHERE f.report_id = #{reportId}
            ORDER BY f.sort_no, f.id
            """)
    List<ReportFileRow> fileRowsOfReport(@Param("reportId") long reportId);

    /**
     * 报告单的某个附件行（删除单个文件用）：不在该报告单 / 文件已删（sys_file DELETED）→ null。 调用方已经
     * findReport（@DataScope）确认报告单可见。
     */
    @IgnoreScope(
            reason =
                    "报告单单附件定位（E16-T09 删除单个文件）：调用方已经 findReport（@DataScope）确认报告单可见，"
                            + "只取附件 id 与文件名不吐患者数据")
    @Select(
            """
            SELECT f.file_id AS fileId, sf.original_name AS originalName
            FROM rpt_report_file f
            JOIN sys_file sf ON sf.id = f.file_id AND sf.status <> 'DELETED'
            WHERE f.report_id = #{reportId} AND f.file_id = #{fileId}
            """)
    ReportFileRow findFileOfReport(@Param("reportId") long reportId, @Param("fileId") long fileId);

    /**
     * 软删除报告单（E16-T09 / A-ATT-007）：status NORMAL → DELETED，原因 / 删除人 / 删除时间落列 （V65）；0 行更新 =
     * 并发已被他人删除（40901 由服务层转译）。调用方已经 findReport（@DataScope） 确认报告单可见。
     */
    @IgnoreScope(reason = "报告单软删除（E16-T09）：调用方已经 findReport（@DataScope）确认报告单可见（越范围 40400）")
    @Update(
            """
            UPDATE rpt_report
            SET status = 'DELETED', delete_reason = #{reason}, deleted_by = #{operator},
                deleted_at = CURRENT_TIMESTAMP(3), updated_by = #{operator}
            WHERE id = #{id} AND status = 'NORMAL'
            """)
    int softDeleteReport(
            @Param("id") long id, @Param("reason") String reason, @Param("operator") long operator);

    // ---------------------------------------------------------------- 修改位置（E16-T13 /
    // A-ATT-020、C-SCAN-020）

    /**
     * 移除一条位置行（修改位置 remove，E16-T13）：按四元组（link_type / link_record_id / anchor_type /
     * anchor_key）定位，模板版本不参与（锚点可能来自旧版本或已退回，E16-T15）。 位置行的留痕在操作日志（RPT_LOCATION_CHANGE 的
     * beforeJson），表本身无软删列——历史行物理删除。 调用方已经 findReport（@DataScope）确认报告单可见。
     */
    @IgnoreScope(
            reason =
                    "报告单位置移除（E16-T13 修改位置）：调用方已经 findReport（@DataScope）确认报告单可见"
                            + "（越范围 40400），且 remove 清单已逐条与在册位置行匹配")
    @Delete(
            """
            DELETE FROM rpt_report_link
            WHERE report_id = #{reportId} AND link_type = #{linkType}
              AND ((#{linkRecordId} IS NULL AND link_record_id IS NULL)
                   OR link_record_id = #{linkRecordId})
              AND ((#{anchorType} IS NULL AND anchor_type IS NULL) OR anchor_type = #{anchorType})
              AND ((#{anchorKey} IS NULL AND anchor_key IS NULL) OR anchor_key = #{anchorKey})
            """)
    int deleteLink(
            @Param("reportId") long reportId,
            @Param("linkType") String linkType,
            @Param("linkRecordId") Long linkRecordId,
            @Param("anchorType") String anchorType,
            @Param("anchorKey") String anchorKey);

    /**
     * 位置变更推进乐观锁版本（V63 version 列注释「编辑 / 修改位置随 E16-T13」），供详情 / 后续编辑的 409 判定。调用方已经
     * findReport（@DataScope）确认报告单可见。
     */
    @IgnoreScope(reason = "报告单位置变更推进版本（E16-T13）：调用方已经 findReport（@DataScope）确认报告单可见（越范围 40400）")
    @Update(
            """
            UPDATE rpt_report
            SET version = version + 1, updated_by = #{operator}
            WHERE id = #{id} AND status = 'NORMAL'
            """)
    int bumpVersion(@Param("id") long id, @Param("operator") long operator);

    /**
     * 文件挂的报告单上下文（E16-T09 下载 / 预览权限收紧，OQ-62① 的报告单部分）：归属是事实查询
     * **不能按数据范围过滤**（越范围文件会被误判为未挂报告单而放行），患者可见性由 ReportFileScopeGuard 显式校验；文件未挂任何在册报告单（含报告单已删）→
     * null，维持文件服务既有口径。
     */
    @IgnoreScope(
            reason =
                    "报告单附件归属查找（E16-T09 预览 / 下载范围收紧）：归属是事实查询不能按范围过滤"
                            + "（越范围文件会被误判为未挂报告单而放行），患者可见性由 ReportFileScopeGuard 显式校验")
    @Select(
            """
            SELECT f.report_id AS reportId, r.patient_id AS patientId, r.type_code AS typeCode,
                   r.type_name AS typeName, r.name_snapshot AS nameSnapshot
            FROM rpt_report_file f
            JOIN rpt_report r ON r.id = f.report_id AND r.status = 'NORMAL'
            WHERE f.file_id = #{fileId}
            """)
    ReportFileContextRow reportFileContext(@Param("fileId") long fileId);

    /** 文件归属报告单的上下文行（下载审计「哪份报告单的哪个文件」，A-ATT-006）。 */
    record ReportFileContextRow(
            long reportId, long patientId, String typeCode, String typeName, String nameSnapshot) {}

    // ---------------------------------------------------------------- 配置删除守卫（OQ-95②）

    /** 类型被报告单引用数（全租户，含已删报告单——守卫不因软删除失效）。 */
    @IgnoreScope(reason = "配置删除守卫计数（E16-T02 / OQ-95②）：全租户口径，只回计数不吐患者数据")
    @Select("SELECT COUNT(*) FROM rpt_report WHERE type_code = #{typeCode}")
    long countReportsOfType(@Param("typeCode") String typeCode);

    /** 名称被报告单引用数（全租户，含已删报告单）。 */
    @IgnoreScope(reason = "配置删除守卫计数（E16-T02 / OQ-95②）：全租户口径，只回计数不吐患者数据")
    @Select("SELECT COUNT(*) FROM rpt_report WHERE name_id = #{nameId}")
    long countReportsOfName(@Param("nameId") long nameId);
}
