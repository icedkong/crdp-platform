package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 接近 ERI 器械卡片查询（E21-T19 / C-DASH-005；docs/api/stats.md §2 cardCode = eri_list）。
 *
 * <p>口径（C-DASH-005「最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命低于阈值的患者」）： 每患者取<b>最近一条</b>含 ERI 字段（f323 /
 * f324，键契约 OQ-130①）的已提交随访记录，判定在服务层用 {@code com.crdp.followup.plan.domain.EriEval}（与计划切换 SW-7 / SW-8
 * 同一谓词）。 排序键 = 有效随访日期（visit_date ?? 表单随访日期 f303 ?? 任务计划日期，OQ-134① 同源口径；JSON 路径为 OQ-130①
 * 稳定键字面量，不是请求参数）。 患者收窄到随访中（followup_status = 'ACTIVE'，在册 deleted = 0）——已死亡 / 已终止 / 失访患者无随访动作，不进列表；
 * 数据范围由语句上的 {@code @DataScope(resource = "patient")} 按登录快照出行（改写器进入派生表内层追加条件）。
 */
@Mapper
public interface EriListMapper {

    /** 每患者最近一条含 ERI 字段的已提交记录（visitDate = 该记录的有效随访日期）。 */
    record LatestEriRecordRow(long patientId, long recordId, String data, LocalDate visitDate) {}

    /** 每患者最近一条含 ERI 字段的已提交记录（窗口函数按有效随访日期 + id 取最新）。 A / B 表单无 f323 / f324 键，行天然为空。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT ranked.patient_id AS patientId, ranked.record_id AS recordId,
                   ranked.data AS data, ranked.effective_date AS visitDate
            FROM (
              SELECT r.id AS record_id, r.patient_id, r.data,
                     COALESCE(
                         r.visit_date,
                         STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(r.data, '$.f303')), '%Y-%m-%d'),
                         t.plan_date) AS effective_date,
                     ROW_NUMBER() OVER (
                         PARTITION BY r.patient_id
                         ORDER BY COALESCE(
                                      r.visit_date,
                                      STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(r.data, '$.f303')), '%Y-%m-%d'),
                                      t.plan_date) DESC,
                                      r.id DESC) AS rn
              FROM fu_record r
              JOIN pt_patient p ON p.id = r.patient_id AND p.deleted = 0
                  AND p.followup_status = 'ACTIVE'
              LEFT JOIN fu_task t ON t.id = r.task_id
              WHERE r.status = 'SUBMITTED' AND r.deleted = 0
                AND (JSON_EXTRACT(r.data, '$.f323') IS NOT NULL
                     OR JSON_EXTRACT(r.data, '$.f324') IS NOT NULL)
              <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            ) ranked
            WHERE ranked.rn = 1
            </script>
            """)
    List<LatestEriRecordRow> findLatestEriRecords(@Param("wardId") Long wardId);

    /** 命中患者的器械与摘要行（在用器械一行 uk 保证至多一条；无在用器械 / 无摘要为 NULL 列）。 */
    record PatientDeviceRow(
            long id,
            String nameCipher,
            String visitNo,
            LocalDate latestFollowupDate,
            LocalDate nextPlanDate,
            String deviceType,
            String brand,
            String model) {}

    /** 命中患者的展示列（器械、最近随访日期、下次计划日期；空清单不出查询）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.id, p.name_cipher AS nameCipher, p.visit_no AS visitNo,
                   s.latest_followup_date AS latestFollowupDate,
                   s.next_plan_date AS nextPlanDate,
                   i.device_type AS deviceType, i.brand AS brand, i.model AS model
            FROM pt_patient p
            LEFT JOIN pt_patient_summary s ON s.patient_id = p.id
            LEFT JOIN pt_implant i ON i.patient_id = p.id AND i.status = 'ACTIVE'
            WHERE p.deleted = 0 AND p.id IN
            <foreach collection="patientIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<PatientDeviceRow> findPatientDevices(@Param("patientIds") List<Long> patientIds);
}
