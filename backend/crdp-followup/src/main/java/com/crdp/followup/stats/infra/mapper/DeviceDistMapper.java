package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 器械类型分布卡片查询（E21-T19 / C-DASH-002；docs/api/stats.md §2 cardCode = device_dist）。
 *
 * <p>「一个口径一个查询」：在随访患者计数与指标卡「在随访患者数」（DashboardStatsMapper.countPatientsByStatus 的 ACTIVE
 * 行）同一条件树——在册（deleted = 0）+ followup_status = 'ACTIVE' + 病区；分布键 = 在用植入记录（pt_implant.status =
 * 'ACTIVE'，一位患者至多一条 uk_pt_implant_active）的 device_type / brand（无在用器械行的患者两键为 NULL，归「未登记在用器械」行——
 * 在随访患者数是分母，分布行合计可以小于它）。 数据范围由语句上的 {@code @DataScope(resource = "patient")} 出行。
 */
@Mapper
public interface DeviceDistMapper {

    /** 一行 = 类型 × 品牌的计数（deviceType / brand 为 NULL 表示无在用器械）。 */
    record DistCountRow(String deviceType, String brand, long total) {}

    /** 在随访患者按在用器械类型 × 品牌计数（COUNT(DISTINCT) 防御在用行异常重复）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT i.device_type AS deviceType, i.brand AS brand, COUNT(DISTINCT p.id) AS total
            FROM pt_patient p
            LEFT JOIN pt_implant i ON i.patient_id = p.id AND i.status = 'ACTIVE'
            WHERE p.deleted = 0 AND p.followup_status = 'ACTIVE'
            <if test="wardId != null">AND p.ward_id = #{wardId}</if>
            GROUP BY i.device_type, i.brand
            </script>
            """)
    List<DistCountRow> countByTypeAndBrand(@Param("wardId") Long wardId);
}
