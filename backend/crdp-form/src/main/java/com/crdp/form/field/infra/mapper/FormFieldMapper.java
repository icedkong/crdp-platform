package com.crdp.form.field.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 字段字典表 fe_form / fe_field / fe_option（E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001）。
 *
 * <p>调用前必须已在租户上下文（租户库路由）。字段编码、稳定键、类型、存储方式、敏感标记、硬范围不可改 （A-FLD-008「编码不可改」；更新语句只含可维护列，键与编码只在 WHERE 里）。
 */
@Mapper
public interface FormFieldMapper {

    // ---------------------------------------------------------------- fe_form

    /** 全部表单定义，按编码排序。 */
    @Select(
            """
            SELECT id, code, name, usage_type AS usageType, current_version AS currentVersion,
                   categories, status
            FROM fe_form ORDER BY code
            """)
    List<FormRow> findForms();

    /** 单个表单定义。 */
    @Select(
            """
            SELECT id, code, name, usage_type AS usageType, current_version AS currentVersion,
                   categories, status
            FROM fe_form WHERE code = #{code}
            """)
    FormRow findForm(@Param("code") String code);

    // ---------------------------------------------------------------- fe_field

    /** 表单下的字段（categoryKey 可空 = 全部分类），按分类与排序号。 */
    @Select(
            """
            <script>
            SELECT id, form_code AS formCode, field_key AS fieldKey, field_code AS fieldCode,
                   label, category_key AS categoryKey, type, unit, decimals,
                   hard_range AS hardRange, soft_range AS softRange,
                   option_set_code AS optionSetCode, required,
                   visible_when AS visibleWhen, required_when AS requiredWhen,
                   derived_def AS derivedDef, sensitive_flag AS sensitiveFlag, storage_type AS storageType,
                   help_text AS helpText, sort_no AS sortNo, status,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fe_field
            WHERE form_code = #{formCode}
              <if test="categoryKey != null">AND category_key = #{categoryKey}</if>
            ORDER BY category_key, sort_no, field_key
            </script>
            """)
    List<FieldRow> findFields(
            @Param("formCode") String formCode, @Param("categoryKey") String categoryKey);

    /** 单个字段（表单内键唯一）。 */
    @Select(
            """
            SELECT id, form_code AS formCode, field_key AS fieldKey, field_code AS fieldCode,
                   label, category_key AS categoryKey, type, unit, decimals,
                   hard_range AS hardRange, soft_range AS softRange,
                   option_set_code AS optionSetCode, required,
                   visible_when AS visibleWhen, required_when AS requiredWhen,
                   derived_def AS derivedDef, sensitive_flag AS sensitiveFlag, storage_type AS storageType,
                   help_text AS helpText, sort_no AS sortNo, status,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fe_field WHERE form_code = #{formCode} AND field_key = #{fieldKey}
            """)
    FieldRow findField(@Param("formCode") String formCode, @Param("fieldKey") String fieldKey);

    /**
     * 维护字段（form.md §3 可改列）：显示名、帮助说明、单位显示、软范围、必填、状态（启停， E11-T01
     * 验收「字段可启停」）。键、编码、类型、存储方式、敏感标记、硬范围不在更新列上。
     */
    @Update(
            """
            UPDATE fe_field
            SET label = #{label}, help_text = #{helpText}, unit = #{unit},
                soft_range = #{softRange}, required = #{required}, status = #{status},
                updated_by = #{updatedBy}
            WHERE form_code = #{formCode} AND field_key = #{fieldKey}
            """)
    int updateField(
            @Param("formCode") String formCode,
            @Param("fieldKey") String fieldKey,
            @Param("label") String label,
            @Param("helpText") String helpText,
            @Param("unit") String unit,
            @Param("softRange") String softRange,
            @Param("required") boolean required,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);

    // ---------------------------------------------------------------- fe_option

    /** 选项集下的选项，按排序号。 */
    @Select(
            """
            SELECT id, set_code AS setCode, option_code AS optionCode, label, ordinal,
                   exclusive, needs_detail AS needsDetail, aliases, sort_no AS sortNo,
                   status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM fe_option WHERE set_code = #{setCode} ORDER BY sort_no, option_code
            """)
    List<OptionRow> findOptions(@Param("setCode") String setCode);

    /** 维护选项：显示名与排序可改、可停用（编码不可改，已有数据的选项不删除，form.md §3）。 */
    @Update(
            """
            UPDATE fe_option
            SET label = #{label}, sort_no = #{sortNo}, status = #{status},
                updated_by = #{updatedBy}
            WHERE set_code = #{setCode} AND option_code = #{optionCode}
            """)
    int updateOption(
            @Param("setCode") String setCode,
            @Param("optionCode") int optionCode,
            @Param("label") String label,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);

    /** 新增选项（编码由租户管理员指定且集内唯一，A-FLD-008「可新增选项」）。 */
    @Insert(
            """
            INSERT INTO fe_option (set_code, option_code, label, sort_no, status, updated_by)
            VALUES (#{setCode}, #{optionCode}, #{label}, #{sortNo}, #{status}, #{updatedBy})
            """)
    int insertOption(
            @Param("setCode") String setCode,
            @Param("optionCode") int optionCode,
            @Param("label") String label,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);
}
