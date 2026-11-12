package com.crdp.followup.plan.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 器械类型 → 计划映射（E21-T05 / C-FU-003；表 followup V86 建）。
 *
 * <p>fu_plan_mapping 是纯配置行（不含患者面数据），不登记 {@code ControlledTables}，读取按 {@code @IgnoreScope} 放行——与
 * fu_plan / fu_compare_rule 同口径：配置页维护者（followup:plan:edit）未必有患者 数据范围。
 */
@Mapper
public interface DevicePlanMappingMapper {

    /** 映射行（deviceType / planCode 为稳定编码，显示名由服务层随字典与计划族解析）。 */
    record MappingRow(Long id, String deviceType, String planCode, boolean enabled) {}

    /** 全部映射行（按 device_type 排序稳定输出）。 */
    @IgnoreScope(reason = "映射配置行读取（C-FU-003 配置页与自动入组解析）：纯配置不含患者数据")
    @Select(
            """
            SELECT id, device_type AS deviceType, plan_code AS planCode, enabled
            FROM fu_plan_mapping ORDER BY device_type
            """)
    List<MappingRow> findAll();

    /** 单个类型的映射行（无映射由服务层判空跳过）。 */
    @IgnoreScope(reason = "自动入组解析映射配置行（C-FU-003）：纯配置不含患者数据")
    @Select(
            """
            SELECT id, device_type AS deviceType, plan_code AS planCode, enabled
            FROM fu_plan_mapping WHERE device_type = #{deviceType}
            """)
    MappingRow findByDeviceType(String deviceType);

    /** 整体替换的清空（PUT 保存映射在同一事务内 deleteAll + insert，V86 注释）。 */
    @IgnoreScope(reason = "映射配置整组重写（C-FU-003 配置保存）：纯配置不含患者数据")
    @Delete("DELETE FROM fu_plan_mapping")
    int deleteAll();

    /** 整体替换的写入（uk_fu_plan_mapping_device 兜底并发保存；保存后按 findAll 回读）。 */
    @IgnoreScope(reason = "映射配置整组重写（C-FU-003 配置保存）：纯配置不含患者数据")
    @Insert(
            """
            INSERT INTO fu_plan_mapping (device_type, plan_code, enabled, created_by)
            VALUES (#{deviceType}, #{planCode}, #{enabled}, #{createdBy})
            """)
    void insert(
            @Param("deviceType") String deviceType,
            @Param("planCode") String planCode,
            @Param("enabled") boolean enabled,
            @Param("createdBy") Long createdBy);
}
