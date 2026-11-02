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

    /**
     * 新增字段（E11-T09 导入）：全列写入，字段由实施批量录入（E23-T01 / C 字段字典）而不是页面手工建。 键、编码、类型、敏感标记、存储方式只在此处落定，创建后不可改。
     */
    @Insert(
            """
            INSERT INTO fe_field
              (form_code, field_key, field_code, label, category_key, type, unit, decimals,
               hard_range, soft_range, option_set_code, required, visible_when, required_when,
               derived_def, sensitive_flag, storage_type, help_text, sort_no, status, updated_by)
            VALUES (#{formCode}, #{fieldKey}, #{fieldCode}, #{label}, #{categoryKey}, #{type},
                    #{unit}, #{decimals}, #{hardRange}, #{softRange}, #{optionSetCode}, #{required},
                    #{visibleWhen}, #{requiredWhen}, #{derivedDef}, #{sensitive}, #{storageType},
                    #{helpText}, #{sortNo}, #{status}, #{updatedBy})
            """)
    int insertField(
            @Param("formCode") String formCode,
            @Param("fieldKey") String fieldKey,
            @Param("fieldCode") String fieldCode,
            @Param("label") String label,
            @Param("categoryKey") String categoryKey,
            @Param("type") String type,
            @Param("unit") String unit,
            @Param("decimals") Integer decimals,
            @Param("hardRange") String hardRange,
            @Param("softRange") String softRange,
            @Param("optionSetCode") String optionSetCode,
            @Param("required") boolean required,
            @Param("visibleWhen") String visibleWhen,
            @Param("requiredWhen") String requiredWhen,
            @Param("derivedDef") String derivedDef,
            @Param("sensitive") boolean sensitive,
            @Param("storageType") String storageType,
            @Param("helpText") String helpText,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);

    /**
     * 导入更新字段（E11-T09）：行是字段的完整定义，可维护与实施列全量覆盖（含分类、硬范围、条件与派生 —— 这些租户维护页不可改、实施经导入调整）；
     * 字段编码、类型、存储方式、敏感标记不可改（A-FLD-008），更新列上没有它们。
     */
    @Update(
            """
            UPDATE fe_field
            SET label = #{label}, category_key = #{categoryKey}, unit = #{unit},
                decimals = #{decimals}, hard_range = #{hardRange}, soft_range = #{softRange},
                option_set_code = #{optionSetCode}, required = #{required},
                visible_when = #{visibleWhen}, required_when = #{requiredWhen},
                derived_def = #{derivedDef}, help_text = #{helpText}, sort_no = #{sortNo},
                status = #{status}, updated_by = #{updatedBy}
            WHERE form_code = #{formCode} AND field_key = #{fieldKey}
            """)
    int updateFieldForImport(
            @Param("formCode") String formCode,
            @Param("fieldKey") String fieldKey,
            @Param("label") String label,
            @Param("categoryKey") String categoryKey,
            @Param("unit") String unit,
            @Param("decimals") Integer decimals,
            @Param("hardRange") String hardRange,
            @Param("softRange") String softRange,
            @Param("optionSetCode") String optionSetCode,
            @Param("required") boolean required,
            @Param("visibleWhen") String visibleWhen,
            @Param("requiredWhen") String requiredWhen,
            @Param("derivedDef") String derivedDef,
            @Param("helpText") String helpText,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);

    // ---------------------------------------------------------------- fe_option

    /** 单个选项集定义（E11-T09 导入自动建集前查重）。 */
    @Select("SELECT id, code, name FROM fe_option_set WHERE code = #{code}")
    OptionSetRow findOptionSet(@Param("code") String code);

    /** 新建选项集（E11-T09 导入：集不存在时自动创建，名称先取编码，后续随字典维护调整）。 */
    @Insert("INSERT INTO fe_option_set (code, name) VALUES (#{code}, #{name})")
    int insertOptionSet(@Param("code") String code, @Param("name") String name);

    /** 选项集下的选项，按排序号。 */
    @Select(
            """
            SELECT id, set_code AS setCode, option_code AS optionCode, label, ordinal,
                   exclusive, needs_detail AS needsDetail, aliases, sort_no AS sortNo,
                   status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM fe_option WHERE set_code = #{setCode} ORDER BY sort_no, option_code
            """)
    List<OptionRow> findOptions(@Param("setCode") String setCode);

    /**
     * 新增选项（E11-T09 导入）：与 {@link #insertOption} 的差别是带序数 / 互斥 / 需补充说明 / 别名 —— 这四列实施录入（导入）可写，
     * 租户维护页不提供（form.md §3）。
     */
    @Insert(
            """
            INSERT INTO fe_option
              (set_code, option_code, label, ordinal, exclusive, needs_detail, aliases,
               sort_no, status, updated_by)
            VALUES (#{setCode}, #{optionCode}, #{label}, #{ordinal}, #{exclusive}, #{needsDetail},
                    #{aliases}, #{sortNo}, #{status}, #{updatedBy})
            """)
    int insertOptionFull(
            @Param("setCode") String setCode,
            @Param("optionCode") int optionCode,
            @Param("label") String label,
            @Param("ordinal") Integer ordinal,
            @Param("exclusive") boolean exclusive,
            @Param("needsDetail") boolean needsDetail,
            @Param("aliases") String aliases,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);

    /** 导入维护选项（E11-T09）：改显示名 / 序数 / 互斥 / 需补充说明 / 别名；编码、排序与状态不动（状态的启停走字典维护页）。 */
    @Update(
            """
            UPDATE fe_option
            SET label = #{label}, ordinal = #{ordinal}, exclusive = #{exclusive},
                needs_detail = #{needsDetail}, aliases = #{aliases}, updated_by = #{updatedBy}
            WHERE set_code = #{setCode} AND option_code = #{optionCode}
            """)
    int updateOptionForImport(
            @Param("setCode") String setCode,
            @Param("optionCode") int optionCode,
            @Param("label") String label,
            @Param("ordinal") Integer ordinal,
            @Param("exclusive") boolean exclusive,
            @Param("needsDetail") boolean needsDetail,
            @Param("aliases") String aliases,
            @Param("updatedBy") Long updatedBy);

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

    // --------------------------------------------------------------- 字段字典变更记录（E11-T08 /
    // A-FLD-009、C-FLD-009）

    /** 追加一条字段字典变更记录（每次「维护字段」一条，含字段启停与选项调整；只追加）。 */
    @Insert(
            """
            INSERT INTO fe_field_dict_log
              (form_code, field_key, action, before_json, after_json, operator_id)
            VALUES (#{formCode}, #{fieldKey}, #{action}, #{beforeJson}, #{afterJson}, #{operatorId})
            """)
    int insertFieldDictLog(
            @Param("formCode") String formCode,
            @Param("fieldKey") String fieldKey,
            @Param("action") String action,
            @Param("beforeJson") String beforeJson,
            @Param("afterJson") String afterJson,
            @Param("operatorId") Long operatorId);

    /** 字段字典变更记录（倒序最近 100 条；operatorName 联查 sys_user）。 */
    @Select(
            """
            SELECT l.id, l.form_code AS formCode, l.field_key AS fieldKey, l.action,
                   l.before_json AS beforeJson, l.after_json AS afterJson,
                   l.operator_id AS operatorId, u.display_name AS operatorName,
                   l.created_at AS createdAt
            FROM fe_field_dict_log l
            LEFT JOIN sys_user u ON u.id = l.operator_id
            WHERE l.form_code = #{formCode} AND l.field_key = #{fieldKey}
            ORDER BY l.id DESC
            LIMIT #{limit}
            """)
    List<FieldDictLogRow> findFieldDictLogs(
            @Param("formCode") String formCode,
            @Param("fieldKey") String fieldKey,
            @Param("limit") int limit);

    /** fe_field_dict_log 行（只追加；操作人姓名联查 sys_user.display_name）。 */
    record FieldDictLogRow(
            long id,
            String formCode,
            String fieldKey,
            String action,
            String beforeJson,
            String afterJson,
            Long operatorId,
            String operatorName,
            java.time.LocalDateTime createdAt) {}

    /** fe_option_set 行（E11-T09 导入建集查重用）。 */
    record OptionSetRow(Long id, String code, String name) {}
}
