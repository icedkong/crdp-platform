package com.crdp.followup.compare.infra.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对比标红规则表 fu_compare_rule（E17-T01 / A-FU-027、C-FU-011 随 E21；followup V67）。 调用前必须已在租户上下文。
 *
 * <p>规则条目由预置迁移落库（A 的 Q41 全量随 E17-T05），本 Mapper 只做读取与「开关 + 阈值」更新（不提供增删， docs/api/followup.md
 * §4）；显示名（表单 / 字段 / 单位 / 基线字段）直接关联 fe_form / fe_field 带出，前端不再二次查询。
 */
@Mapper
public interface CompareRuleMapper {

    /** 规则清单（form_code, status, sort_no, id 序）；formCode 为空返回全部表单的规则。 */
    @Select(
            """
            <script>
            SELECT r.id, r.form_code AS formCode, f.name AS formName,
                   r.rule_code AS ruleCode, r.name, r.category_key AS categoryKey,
                   r.target_field AS targetField, tf.label AS fieldLabel, tf.unit AS fieldUnit,
                   r.rule_type AS ruleType, r.params, r.baseline_form_code AS baselineFormCode,
                   r.baseline_field AS baselineField, bf.label AS baselineFieldLabel,
                   r.message, r.sort_no AS sortNo, r.status, r.updated_by AS updatedBy,
                   r.updated_at AS updatedAt
            FROM fu_compare_rule r
            LEFT JOIN fe_form f ON f.code = r.form_code
            LEFT JOIN fe_field tf ON tf.form_code = r.form_code AND tf.field_key = r.target_field
            LEFT JOIN fe_field bf ON bf.form_code = r.baseline_form_code
                 AND bf.field_key = r.baseline_field
            <where>
              <if test="formCode != null and formCode != ''">r.form_code = #{formCode}</if>
            </where>
            ORDER BY r.form_code, r.status, r.sort_no, r.id
            </script>
            """)
    List<CompareRuleRow> findAll(@Param("formCode") String formCode);

    /** 单条规则（含显示名）；不存在返回空（应用层转 40400）。 */
    @Select(
            """
            SELECT r.id, r.form_code AS formCode, f.name AS formName,
                   r.rule_code AS ruleCode, r.name, r.category_key AS categoryKey,
                   r.target_field AS targetField, tf.label AS fieldLabel, tf.unit AS fieldUnit,
                   r.rule_type AS ruleType, r.params, r.baseline_form_code AS baselineFormCode,
                   r.baseline_field AS baselineField, bf.label AS baselineFieldLabel,
                   r.message, r.sort_no AS sortNo, r.status, r.updated_by AS updatedBy,
                   r.updated_at AS updatedAt
            FROM fu_compare_rule r
            LEFT JOIN fe_form f ON f.code = r.form_code
            LEFT JOIN fe_field tf ON tf.form_code = r.form_code AND tf.field_key = r.target_field
            LEFT JOIN fe_field bf ON bf.form_code = r.baseline_form_code
                 AND bf.field_key = r.baseline_field
            WHERE r.id = #{id}
            """)
    Optional<CompareRuleRow> find(@Param("id") long id);

    /** 更新开关与阈值参数（PUT 唯一写入口；status: ENABLED/DISABLED，params 为 JSON 文本）。 */
    @Update(
            """
            UPDATE fu_compare_rule
            SET status = #{status}, params = #{params}, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int update(
            @Param("id") long id,
            @Param("status") String status,
            @Param("params") String params,
            @Param("updatedBy") long updatedBy);
}
