package com.crdp.followup.patient.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 基线分节表 pt_baseline_section（E13-T02 / A-PAT-003「可分次补录」、A-PAT-005 字段级留痕；表 followup V60）。
 *
 * <p>查询全部为「当前用户数据范围内」口径（{@code @DataScope}：拦截器按 pt_patient 锚点 EXISTS 改写， 越范围行不可见，AGENTS
 * §5.2）；INSERT / UPDATE 不走查询拦截（E07 只拦 SELECT），写入前的可见性校验由应用层经 网关的 {@code @DataScope} 查询先行完成。写入闸门（键 /
 * 类型 / 派生 / 硬范围）与版本口径经 {@link com.crdp.form.record.app.FormService}，本 mapper 只做持久化（与
 * FollowupRecordMapper 同口径）。
 */
@Mapper
public interface BaselineSectionMapper {

    /** 分节行（data / data_meta 为 JSON 文本，应用层解析）。 */
    record SectionRowRaw(
            long id,
            long patientId,
            String formCode,
            String categoryKey,
            int templateVersion,
            String data,
            String dataMeta,
            int version,
            boolean hasDraft,
            Long createdBy,
            LocalDateTime createdAt,
            Long updatedBy,
            LocalDateTime updatedAt) {}

    /** 新建分节（首次暂存 / 首次保存时建行；template_version 创建时固化 = 当时发布版）。 */
    @Insert(
            """
            INSERT INTO pt_baseline_section (patient_id, form_code, category_key, template_version,
                                             data, data_meta, version, has_draft, created_by, updated_by)
            VALUES (#{patientId}, #{formCode}, #{categoryKey}, #{templateVersion},
                    #{data}, #{dataMeta}, #{version}, #{hasDraft}, #{createdBy}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertSection(BaselineSectionInsert row);

    /** 某分类分节（越范围 / 不存在 null——40400 由服务层抛，不暴露存在）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, form_code AS formCode,
                   category_key AS categoryKey, template_version AS templateVersion,
                   data, data_meta AS dataMeta, version, has_draft AS hasDraft,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM pt_baseline_section
            WHERE patient_id = #{patientId} AND category_key = #{categoryKey}
            """)
    SectionRowRaw findSection(
            @Param("patientId") long patientId, @Param("categoryKey") String categoryKey);

    /** 患者全部分节（GET /patients/{id}/baseline 数据源；按分类键排序，行序与模板分类声明对齐由应用层完成）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, form_code AS formCode,
                   category_key AS categoryKey, template_version AS templateVersion,
                   data, data_meta AS dataMeta, version, has_draft AS hasDraft,
                   created_by AS createdBy, created_at AS createdAt,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM pt_baseline_section
            WHERE patient_id = #{patientId}
            ORDER BY category_key ASC
            """)
    List<SectionRowRaw> findSections(@Param("patientId") long patientId);

    /** 自动保存草稿（乐观锁：baseVersion 不匹配 0 行，服务层区分 40400 / 40900；hasDraft 置 1）。 */
    @Update(
            """
            UPDATE pt_baseline_section
            SET data = #{data}, version = version + 1, has_draft = 1, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion}
            """)
    int updateDraft(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("updatedBy") long updatedBy);

    /** 正式保存（乐观锁同上；has_draft 清 0——草稿随本次保存定稿，tech-plan §6「草稿语义」）。 */
    @Update(
            """
            UPDATE pt_baseline_section
            SET data = #{data}, data_meta = #{dataMeta}, version = version + 1,
                has_draft = 0, updated_by = #{updatedBy}
            WHERE id = #{id} AND version = #{baseVersion}
            """)
    int updateSection(
            @Param("id") long id,
            @Param("baseVersion") int baseVersion,
            @Param("data") String data,
            @Param("dataMeta") String dataMeta,
            @Param("updatedBy") long updatedBy);

    /** 更新人显示名（40900 冲突详情「附更新人」，与 FollowupRecordMapper 同口径；取不到 null）。 */
    @Select("SELECT display_name FROM sys_user WHERE id = #{userId}")
    String findDisplayName(@Param("userId") Long userId);
}
