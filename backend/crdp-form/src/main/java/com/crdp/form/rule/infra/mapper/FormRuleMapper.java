package com.crdp.form.rule.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨字段校验规则表 fe_rule（E11-T04 / A-FLD-006、C-FLD-007；tech-plan §6「元数据模型」）。
 *
 * <p>调用前必须已在租户上下文（租户库路由）。规则<b>不进模板版本快照</b>（form.md §1：FormSchema 的 rules 为 fe_rule
 * 实时读取，记录按字段快照渲染、按当前生效规则校验）；停用（DISABLED）即不生效，不删除。
 */
@Mapper
public interface FormRuleMapper {

    /** 表单下启用中的规则，按排序号（FormSchema 组装与提交校验都用它）。 */
    @Select(
            """
            SELECT id, form_code AS formCode, rule_id AS ruleId, category_key AS categoryKey,
                   target_field AS targetField, severity, rule_type AS ruleType,
                   params, message, sort_no AS sortNo
            FROM fe_rule
            WHERE form_code = #{formCode} AND status = 'ENABLED'
            ORDER BY sort_no, rule_id
            """)
    List<RuleRow> findRules(@Param("formCode") String formCode);
}
