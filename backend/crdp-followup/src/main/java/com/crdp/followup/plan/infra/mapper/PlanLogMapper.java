package com.crdp.followup.plan.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 计划变更记录（E14-T08 / A-FU-021；表 followup V49 建）。
 *
 * <p>fu_plan_log 是配置面只追加表（不含患者数据），与 aud_log 审计同源双写——审计只追加且缺 (object_type, object_id)
 * 索引，「查看变更记录」页（pages.md §8 计划详情 · 变更记录）从本表按 plan_id 倒序出，口径同 fe_field_dict_log（OQ-69③）。
 */
@Mapper
public interface PlanLogMapper {

    /** 变更记录行（operatorName 联查 sys_user.display_name）。 */
    record PlanLogRow(
            long id,
            long planId,
            String planCode,
            String action,
            String description,
            String beforeJson,
            String afterJson,
            Long operatorId,
            String operatorName,
            LocalDateTime createdAt) {}

    /** 追加一条变更记录（每次成功改动一条，只追加）。 */
    @Insert(
            """
            INSERT INTO fu_plan_log
              (plan_id, plan_code, action, description, before_json, after_json, operator_id)
            VALUES
              (#{planId}, #{planCode}, #{action}, #{description}, #{beforeJson}, #{afterJson},
               #{operatorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertLog(PlanLogInsertRow row);

    /** 指定计划版本的变更记录（倒序最近 100 条；operatorName 联查 sys_user）。 */
    @Select(
            """
            SELECT l.id, l.plan_id AS planId, l.plan_code AS planCode, l.action,
                   l.description, l.before_json AS beforeJson, l.after_json AS afterJson,
                   l.operator_id AS operatorId, u.display_name AS operatorName,
                   l.created_at AS createdAt
            FROM fu_plan_log l
            LEFT JOIN sys_user u ON u.id = l.operator_id
            WHERE l.plan_id = #{planId}
            ORDER BY l.id DESC
            LIMIT #{limit}
            """)
    List<PlanLogRow> findLogsByPlanId(@Param("planId") long planId, @Param("limit") int limit);
}
