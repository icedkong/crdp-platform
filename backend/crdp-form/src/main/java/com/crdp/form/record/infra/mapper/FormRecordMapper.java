package com.crdp.form.record.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 表单模板版本与 JSON 记录表 fe_form_version / fe_form_version_field / fe_record（E11-T02 /
 * A-NFR-002、C-NFR-002）。
 *
 * <p>调用前必须已在租户上下文（租户库路由）。版本快照发布后不可变：只有 INSERT，没有 UPDATE / DELETE； 字段快照直接从 fe_field SELECT
 * 拷贝（发布时的启用字段），不经应用层倒手。
 */
@Mapper
public interface FormRecordMapper {

    // ---------------------------------------------------------------- fe_form_version

    /** 单个发布版本（版本不存在返回 null → 40400「模板版本不存在」）。 */
    @Select(
            """
            SELECT id, form_code AS formCode, version, categories,
                   field_count AS fieldCount, published_by AS publishedBy,
                   published_at AS publishedAt
            FROM fe_form_version WHERE form_code = #{formCode} AND version = #{version}
            """)
    FormVersionRow findVersion(@Param("formCode") String formCode, @Param("version") int version);

    /** 全部发布版本（新到旧，维护端展示用）。 */
    @Select(
            """
            SELECT id, form_code AS formCode, version, categories,
                   field_count AS fieldCount, published_by AS publishedBy,
                   published_at AS publishedAt
            FROM fe_form_version WHERE form_code = #{formCode} ORDER BY version DESC
            """)
    List<FormVersionRow> findVersions(@Param("formCode") String formCode);

    /** 发布：插入新版本行（categories 为发布时的 fe_form.categories 原文）。 */
    @Insert(
            """
            INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)
            VALUES (#{formCode}, #{version}, #{categories}, #{fieldCount}, #{publishedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertVersion(FormVersionInsert version);

    /** 发布：把当前启用字段拷贝进版本快照（INSERT…SELECT，字段列与 fe_field 同名）。 返回拷贝行数（= 快照字段数）。 */
    @Insert(
            """
            INSERT INTO fe_form_version_field
              (version_id, form_code, version, field_key, field_code, label, category_key, type,
               unit, decimals, hard_range, soft_range, option_set_code, dict_code, carry_over,
               required,
               visible_when, required_when, derived_def, sensitive_flag, storage_type,
               help_text, sort_no)
            SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label,
                   f.category_key, f.type, f.unit, f.decimals, f.hard_range, f.soft_range,
                   f.option_set_code, f.dict_code, f.carry_over, f.required, f.visible_when,
                   f.required_when, f.derived_def,
                   f.sensitive_flag, f.storage_type, f.help_text, f.sort_no
            FROM fe_field f
            JOIN fe_form_version v
              ON v.form_code = f.form_code AND v.form_code = #{formCode} AND v.version = #{version}
            WHERE f.form_code = #{formCode} AND f.status = 'ENABLED'
            """)
    int snapshotFields(@Param("formCode") String formCode, @Param("version") int version);

    /** 版本内字段快照，按分类与排序号（FormSchema 组装与记录校验都用它）。 */
    @Select(
            """
            SELECT id, version_id AS versionId, form_code AS formCode, version,
                   field_key AS fieldKey, field_code AS fieldCode, label,
                   category_key AS categoryKey, type, unit, decimals,
                   hard_range AS hardRange, soft_range AS softRange,
                   option_set_code AS optionSetCode, dict_code AS dictCode,
                   carry_over AS carryOver, required,
                   visible_when AS visibleWhen, required_when AS requiredWhen,
                   derived_def AS derivedDef, sensitive_flag AS sensitiveFlag,
                   storage_type AS storageType, help_text AS helpText, sort_no AS sortNo
            FROM fe_form_version_field
            WHERE form_code = #{formCode} AND version = #{version}
            ORDER BY category_key, sort_no, field_key
            """)
    List<VersionFieldRow> findVersionFields(
            @Param("formCode") String formCode, @Param("version") int version);

    /** 前移当前发布版本（乐观：current_version 仍是发布前版本才生效，防并发发布）。 */
    @Update(
            """
            UPDATE fe_form SET current_version = #{newVersion}
            WHERE code = #{formCode} AND current_version = #{fromVersion}
            """)
    int bumpCurrentVersion(
            @Param("formCode") String formCode,
            @Param("fromVersion") int fromVersion,
            @Param("newVersion") int newVersion);

    // ---------------------------------------------------------------- fe_record

    /** 单条记录。 */
    @Select(
            """
            SELECT id, form_code AS formCode, template_version AS templateVersion,
                   data, data_meta AS dataMeta, version, status,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM fe_record WHERE id = #{id}
            """)
    FormRecordRow findRecord(@Param("id") long id);

    /** 新建记录（template_version 创建时固化 = 当时 current_version）。 */
    @Insert(
            """
            INSERT INTO fe_record (form_code, template_version, data, data_meta, version, status, created_by)
            VALUES (#{formCode}, #{templateVersion}, #{data}, #{dataMeta}, 0, 'DRAFT', #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRecord(FormRecordInsert record);

    /**
     * 保存（乐观锁）：baseVersion 不匹配时更新 0 行，由服务层区分 40400 与 40900（附 更新人、更新时间与
     * 两边不同字段）。草稿不逐次写审计（技术方案「服务器自动保存」）。
     */
    @Update(
            """
            UPDATE fe_record
            SET data = #{data}, data_meta = #{dataMeta}, version = version + 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion}
            """)
    int updateRecord(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    /**
     * 提交（E15-T02 / A-FU-005）：DRAFT → SUBMITTED 并 version + 1。守卫 version 与 status = 'DRAFT'——并发已提交 /
     * 版本前进时更新 0 行，由服务层区分 40901 与 40900（复用草稿冲突详情口径）。
     */
    @Update(
            """
            UPDATE fe_record
            SET data = #{data}, data_meta = #{dataMeta}, version = version + 1,
                status = 'SUBMITTED', updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion} AND status = 'DRAFT'
            """)
    int submitRecord(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    /**
     * 更新人显示名（E25 草稿 40900 冲突详情「附更新人」，patient.md §4.1 形态为显示名）。 用户已删除等取不到时返回 null，由调用方按「未知」展示，不猜名字。
     */
    @Select("SELECT display_name FROM sys_user WHERE id = #{userId}")
    String findDisplayName(@Param("userId") Long userId);
}
