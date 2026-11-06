package com.crdp.followup.report.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 报告单 / 资料租户配置 rpt_type / rpt_name / rpt_indicator_tpl / rpt_indicator_item（E16-T01 /
 * A-ATT-003、A-ATT-011、A-ATT-012；C-SCAN-002、003、004；表 followup V62）。
 *
 * <p>字典表，不在 {@code ControlledTables}（非患者数据），查询不做数据范围拦截。字段集校验（form_code 必须是 usage_type = REPORT
 * 的表单）经 {@code crdp-form} 的 {@code FormFieldMapper}（followup → form 依赖方向）。
 */
@Mapper
public interface ReportConfigMapper {

    /** 资料类型行（nameCount = 类型下名称数，含停用；管理页全量视图）。 */
    record TypeRow(
            Long id,
            String code,
            String name,
            String formCode,
            String formName,
            boolean nameRequired,
            String defaultDigitizeStatus,
            Integer sortNo,
            String status,
            String remark,
            Long nameCount) {}

    /** 名称字典行（typeName / tplName / itemCount 联查展示用）。 */
    record NameRow(
            Long id,
            String typeCode,
            String typeName,
            String name,
            Long indicatorTplId,
            String indicatorTplName,
            String digitizeStatusOverride,
            Integer sortNo,
            String status) {}

    /** 指标模板行（itemCount = 项目数、boundNameCount = 绑定的名称数，均含停用）。 */
    record TplRow(
            Long id,
            String name,
            String status,
            String remark,
            Long itemCount,
            Long boundNameCount) {}

    /** 指标模板项目行。 */
    record ItemRow(
            Long id,
            Long tplId,
            String itemName,
            String itemCode,
            String unit,
            String refRange,
            String refRangeMale,
            String refRangeFemale,
            Integer sortNo) {}

    // ---------------------------------------------------------------- rpt_type

    /** 全部类型（含停用；按 sort_no、id 升序）。 */
    @Select(
            """
            SELECT t.id, t.code, t.name, t.form_code AS formCode, f.name AS formName,
                   t.name_required AS nameRequired,
                   t.default_digitize_status AS defaultDigitizeStatus,
                   t.sort_no AS sortNo, t.status, t.remark,
                   (SELECT COUNT(*) FROM rpt_name n WHERE n.type_code = t.code) AS nameCount
            FROM rpt_type t
            LEFT JOIN fe_form f ON f.code = t.form_code
            ORDER BY t.sort_no, t.id
            """)
    List<TypeRow> findTypes();

    /** 单个类型（不存在 null）。 */
    @Select(
            """
            SELECT t.id, t.code, t.name, t.form_code AS formCode, f.name AS formName,
                   t.name_required AS nameRequired,
                   t.default_digitize_status AS defaultDigitizeStatus,
                   t.sort_no AS sortNo, t.status, t.remark,
                   (SELECT COUNT(*) FROM rpt_name n WHERE n.type_code = t.code) AS nameCount
            FROM rpt_type t
            LEFT JOIN fe_form f ON f.code = t.form_code
            WHERE t.id = #{id}
            """)
    TypeRow findType(@Param("id") long id);

    /** 按编码取类型 id（本院唯一，含停用；不存在 null）。 */
    @Select("SELECT id FROM rpt_type WHERE code = #{code} LIMIT 1")
    Long findTypeIdByCode(@Param("code") String code);

    /** 类型编码是否存在名称（删除前置检查，含停用名称——停用名称仍在字典里）。 */
    @Select("SELECT COUNT(*) FROM rpt_name WHERE type_code = #{code}")
    long countNamesByTypeCode(@Param("code") String code);

    /** 新建类型；编码唯一键兜底并发（DuplicateKeyException → 40902）。 */
    @Insert(
            """
            INSERT INTO rpt_type
              (code, name, form_code, name_required, default_digitize_status, sort_no, status, remark, updated_by)
            VALUES
              (#{row.code}, #{row.name}, #{row.formCode}, #{row.nameRequired},
               #{row.defaultDigitizeStatus}, #{row.sortNo}, 'ENABLED', #{row.remark}, #{row.updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertType(@Param("row") TypeInsert row);

    /** 编辑类型（编码不在更新列上：创建后不可改）；受影响行数为 0 表示不存在。 */
    @Update(
            """
            UPDATE rpt_type
            SET name = #{name}, form_code = #{formCode}, name_required = #{nameRequired},
                default_digitize_status = #{defaultDigitizeStatus},
                sort_no = #{sortNo}, status = #{status}, remark = #{remark}, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateType(
            @Param("id") long id,
            @Param("name") String name,
            @Param("formCode") String formCode,
            @Param("nameRequired") boolean nameRequired,
            @Param("defaultDigitizeStatus") String defaultDigitizeStatus,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("remark") String remark,
            @Param("updatedBy") Long updatedBy);

    /** 删除类型（应用层先校验类型下无名称；报告单引用检查随 E16-T02 rpt_report 落地）。 */
    @Delete("DELETE FROM rpt_type WHERE id = #{id}")
    int deleteType(@Param("id") long id);

    // ---------------------------------------------------------------- rpt_name

    /** 名称清单（typeCode 可空 = 全部；含停用，按类型、sort_no、id 升序）。 */
    @Select(
            """
            <script>
            SELECT n.id, n.type_code AS typeCode, t.name AS typeName, n.name,
                   n.indicator_tpl_id AS indicatorTplId, p.name AS indicatorTplName,
                   n.digitize_status_override AS digitizeStatusOverride,
                   n.sort_no AS sortNo, n.status
            FROM rpt_name n
            JOIN rpt_type t ON t.code = n.type_code
            LEFT JOIN rpt_indicator_tpl p ON p.id = n.indicator_tpl_id
            <where>
              <if test="typeCode != null">n.type_code = #{typeCode}</if>
              <if test="enabledOnly">AND n.status = 'ENABLED'</if>
            </where>
            ORDER BY n.type_code, n.sort_no, n.id
            </script>
            """)
    List<NameRow> findNames(
            @Param("typeCode") String typeCode, @Param("enabledOnly") boolean enabledOnly);

    /** 单个名称（不存在 null）。 */
    @Select(
            """
            SELECT n.id, n.type_code AS typeCode, t.name AS typeName, n.name,
                   n.indicator_tpl_id AS indicatorTplId, p.name AS indicatorTplName,
                   n.digitize_status_override AS digitizeStatusOverride,
                   n.sort_no AS sortNo, n.status
            FROM rpt_name n
            JOIN rpt_type t ON t.code = n.type_code
            LEFT JOIN rpt_indicator_tpl p ON p.id = n.indicator_tpl_id
            WHERE n.id = #{id}
            """)
    NameRow findName(@Param("id") long id);

    /** 同类型下同名名称 id（含停用；不存在 null）。 */
    @Select("SELECT id FROM rpt_name WHERE type_code = #{typeCode} AND name = #{name} LIMIT 1")
    Long findNameId(@Param("typeCode") String typeCode, @Param("name") String name);

    /** 新建名称；(type_code, name) 唯一键兜底并发。 */
    @Insert(
            """
            INSERT INTO rpt_name
              (type_code, name, indicator_tpl_id, digitize_status_override, sort_no, status, updated_by)
            VALUES
              (#{row.typeCode}, #{row.name}, #{row.indicatorTplId}, #{row.digitizeStatusOverride},
               #{row.sortNo}, 'ENABLED', #{row.updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertName(@Param("row") NameInsert row);

    /** 编辑名称（所属类型不变更：跨类型移动语义不清，删除后在新类型下重建）。 */
    @Update(
            """
            UPDATE rpt_name
            SET name = #{name}, indicator_tpl_id = #{indicatorTplId},
                digitize_status_override = #{digitizeStatusOverride},
                sort_no = #{sortNo}, status = #{status}, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateName(
            @Param("id") long id,
            @Param("name") String name,
            @Param("indicatorTplId") Long indicatorTplId,
            @Param("digitizeStatusOverride") String digitizeStatusOverride,
            @Param("sortNo") int sortNo,
            @Param("status") String status,
            @Param("updatedBy") Long updatedBy);

    /** 删除名称（报告单引用检查随 E16-T02 rpt_report 落地，届时历史报告单存名称快照不联动）。 */
    @Delete("DELETE FROM rpt_name WHERE id = #{id}")
    int deleteName(@Param("id") long id);

    // ---------------------------------------------------------------- rpt_indicator_tpl / item

    /** 全部模板（含停用；按名称排序）。 */
    @Select(
            """
            SELECT t.id, t.name, t.status, t.remark,
                   (SELECT COUNT(*) FROM rpt_indicator_item i WHERE i.tpl_id = t.id) AS itemCount,
                   (SELECT COUNT(*) FROM rpt_name n WHERE n.indicator_tpl_id = t.id) AS boundNameCount
            FROM rpt_indicator_tpl t
            ORDER BY t.name, t.id
            """)
    List<TplRow> findTpls();

    /** 单个模板（不存在 null）。 */
    @Select(
            """
            SELECT t.id, t.name, t.status, t.remark,
                   (SELECT COUNT(*) FROM rpt_indicator_item i WHERE i.tpl_id = t.id) AS itemCount,
                   (SELECT COUNT(*) FROM rpt_name n WHERE n.indicator_tpl_id = t.id) AS boundNameCount
            FROM rpt_indicator_tpl t
            WHERE t.id = #{id}
            """)
    TplRow findTpl(@Param("id") long id);

    /** 按名称取模板 id（本院唯一，含停用；不存在 null）。 */
    @Select("SELECT id FROM rpt_indicator_tpl WHERE name = #{name} LIMIT 1")
    Long findTplIdByName(@Param("name") String name);

    /** 模板项目（按 sort_no、id 升序）。 */
    @Select(
            """
            SELECT id, tpl_id AS tplId, item_name AS itemName, item_code AS itemCode,
                   unit, ref_range AS refRange, ref_range_male AS refRangeMale,
                   ref_range_female AS refRangeFemale, sort_no AS sortNo
            FROM rpt_indicator_item
            WHERE tpl_id = #{tplId}
            ORDER BY sort_no, id
            """)
    List<ItemRow> findItems(@Param("tplId") long tplId);

    /** 新建模板；名称唯一键兜底并发。 */
    @Insert(
            """
            INSERT INTO rpt_indicator_tpl (name, status, remark, updated_by)
            VALUES (#{row.name}, 'ENABLED', #{row.remark}, #{row.updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertTpl(@Param("row") TplInsert row);

    /** 编辑模板基本信息（项目走全量替换）。 */
    @Update(
            """
            UPDATE rpt_indicator_tpl
            SET name = #{name}, status = #{status}, remark = #{remark}, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateTpl(
            @Param("id") long id,
            @Param("name") String name,
            @Param("status") String status,
            @Param("remark") String remark,
            @Param("updatedBy") Long updatedBy);

    /** 删除模板（应用层先校验无名称绑定）。 */
    @Delete("DELETE FROM rpt_indicator_tpl WHERE id = #{id}")
    int deleteTpl(@Param("id") long id);

    /** 全量替换模板项目：先清后插（C-SCAN-004「可临时增删行」的模板维护口径，整组替换）。 */
    @Delete("DELETE FROM rpt_indicator_item WHERE tpl_id = #{tplId}")
    int deleteItems(@Param("tplId") long tplId);

    /** 插入单个项目；(tpl_id, item_code) 唯一键兜底。 */
    @Insert(
            """
            INSERT INTO rpt_indicator_item
              (tpl_id, item_name, item_code, unit, ref_range, ref_range_male, ref_range_female, sort_no)
            VALUES
              (#{tplId}, #{itemName}, #{itemCode}, #{unit}, #{refRange},
               #{refRangeMale}, #{refRangeFemale}, #{sortNo})
            """)
    int insertItem(
            @Param("tplId") long tplId,
            @Param("itemName") String itemName,
            @Param("itemCode") String itemCode,
            @Param("unit") String unit,
            @Param("refRange") String refRange,
            @Param("refRangeMale") String refRangeMale,
            @Param("refRangeFemale") String refRangeFemale,
            @Param("sortNo") int sortNo);
}
