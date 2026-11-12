package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 补录进度卡片计数查询（E21-T15 / C-DASH-007；docs/api/stats.md §2 cardCode = scan_progress）。
 *
 * <p>「一个口径一个查询」：待录入 / 已电子化 / 无需录入计数与全院报告单检索（E16-T05）同一条件树—— {@code r.status = 'NORMAL'} +
 * 病区（pt.ward_id）/ 上传人（r.created_by）/ 文档年份（YEAR(r.report_date)） 逐条件一致；数据范围由语句上的
 * {@code @DataScope(resource = "patient")} 按登录快照出行（范围内无患者 返回空集，不报错）。{@code digitize_status IS NOT
 * NULL} 排除未启用电子化状态的报告单（A 租户行恒 NULL）。
 */
@Mapper
public interface ScanProgressMapper {

    /** 电子化状态计数行（status ∈ PENDING / DONE / NO_ENTRY）。 */
    record StatusCountRow(String digitizeStatus, long total) {}

    /** 上传人候选（当前数据范围内有电子化状态报告单的上传人，按显示名排序）。 */
    record UserOptionRow(long id, String name) {}

    /** 按电子化状态计数（待录入 / 已电子化 / 无需录入；筛选全部可空 = 不过滤）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT r.digitize_status AS digitizeStatus, COUNT(*) AS total
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.status = 'NORMAL' AND r.digitize_status IS NOT NULL
            <if test="wardId != null">AND pt.ward_id = #{wardId}</if>
            <if test="uploaderId != null">AND r.created_by = #{uploaderId}</if>
            <if test="year != null">AND YEAR(r.report_date) = #{year}</if>
            GROUP BY r.digitize_status
            </script>
            """)
    List<StatusCountRow> countByStatus(
            @Param("wardId") Long wardId,
            @Param("uploaderId") Long uploaderId,
            @Param("year") Integer year);

    /**
     * 补录随访记录条数（C-DASH-007「另显示」）：已提交的历史补录记录（fu_record.source = BACKFILL， A-FU-014 /
     * C-FU-013）。只收病区筛选——上传人 / 文档年份是扫描件侧筛选，记录无对应语义（口径见服务注释）。
     */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM fu_record rec
            JOIN pt_patient pt ON pt.id = rec.patient_id AND pt.deleted = 0
            WHERE rec.source = 'BACKFILL' AND rec.status = 'SUBMITTED'
            <if test="wardId != null">AND pt.ward_id = #{wardId}</if>
            </script>
            """)
    long countBackfillRecords(@Param("wardId") Long wardId);

    /** 上传人候选（病区收窄后的范围内候选；不叠加上传人 / 年份筛选自身）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT r.created_by AS id, u.display_name AS name
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            JOIN sys_user u ON u.id = r.created_by
            WHERE r.status = 'NORMAL' AND r.digitize_status IS NOT NULL
              AND r.created_by IS NOT NULL
            <if test="wardId != null">AND pt.ward_id = #{wardId}</if>
            GROUP BY r.created_by, u.display_name
            ORDER BY u.display_name ASC
            </script>
            """)
    List<UserOptionRow> uploaderOptions(@Param("wardId") Long wardId);

    /** 文档年份候选（report_date 年份，降序；只列有电子化状态报告单的年份）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT DISTINCT YEAR(r.report_date) AS year
            FROM rpt_report r
            JOIN pt_patient pt ON pt.id = r.patient_id AND pt.deleted = 0
            WHERE r.status = 'NORMAL' AND r.digitize_status IS NOT NULL
              AND r.report_date IS NOT NULL
            <if test="wardId != null">AND pt.ward_id = #{wardId}</if>
            ORDER BY year DESC
            </script>
            """)
    List<Integer> yearOptions(@Param("wardId") Long wardId);
}
