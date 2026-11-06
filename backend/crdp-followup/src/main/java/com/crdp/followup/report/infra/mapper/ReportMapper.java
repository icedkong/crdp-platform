package com.crdp.followup.report.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /** 某患者的附件计数（@DataScope 出行；按报告单分组）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT f.report_id AS reportId, COUNT(*) AS fileCount
            FROM rpt_report_file f
            JOIN pt_patient pt ON pt.id = f.patient_id AND pt.deleted = 0
            WHERE f.patient_id = #{patientId}
            GROUP BY f.report_id
            """)
    List<FileCountRow> fileCountsOfPatient(@Param("patientId") long patientId);

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
