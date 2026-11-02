package com.crdp.form.history.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 字段级历史表 fe_field_history（E11-T06 / A-PAT-005、C-PAT-011，租户库 followup V32）。
 *
 * <p>调用前必须已在租户上下文（租户库路由）。只追加（INSERT 与 SELECT），没有修改与删除接口（审计 同款不可变口径；历史随记录长期在线，归档策略随 E22 运维定）。
 */
@Mapper
public interface FieldHistoryMapper {

    /** 追加一行字段变更（同一事务内由调用方的保存 / 提交流程连带写入）。 */
    @Insert(
            """
            INSERT INTO fe_field_history
              (record_type, record_id, form_code, template_version, field_key,
               old_value, new_value, source, trigger_fields, reason, operator_id, operator_name)
            VALUES (#{recordType}, #{recordId}, #{formCode}, #{templateVersion}, #{fieldKey},
                    #{oldValue}, #{newValue}, #{source}, #{triggerFields}, #{reason},
                    #{operatorId}, #{operatorName})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(FieldHistoryInsert row);

    /** 按记录查历史（新到旧 = id 倒序；变更记录页「该记录的全部字段改动」）。 */
    @Select(
            """
            SELECT id, record_type AS recordType, record_id AS recordId,
                   form_code AS formCode, template_version AS templateVersion,
                   field_key AS fieldKey, old_value AS oldValue, new_value AS newValue,
                   source, trigger_fields AS triggerFields, reason,
                   operator_id AS operatorId, operator_name AS operatorName,
                   created_at AS createdAt
            FROM fe_field_history
            WHERE record_type = #{recordType} AND record_id = #{recordId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<FieldHistoryRow> findByRecord(
            @Param("recordType") String recordType,
            @Param("recordId") long recordId,
            @Param("limit") int limit);

    /** 按记录 + 字段查历史（单字段追溯，新到旧）。 */
    @Select(
            """
            SELECT id, record_type AS recordType, record_id AS recordId,
                   form_code AS formCode, template_version AS templateVersion,
                   field_key AS fieldKey, old_value AS oldValue, new_value AS newValue,
                   source, trigger_fields AS triggerFields, reason,
                   operator_id AS operatorId, operator_name AS operatorName,
                   created_at AS createdAt
            FROM fe_field_history
            WHERE record_type = #{recordType} AND record_id = #{recordId}
              AND field_key = #{fieldKey}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<FieldHistoryRow> findByField(
            @Param("recordType") String recordType,
            @Param("recordId") long recordId,
            @Param("fieldKey") String fieldKey,
            @Param("limit") int limit);
}
