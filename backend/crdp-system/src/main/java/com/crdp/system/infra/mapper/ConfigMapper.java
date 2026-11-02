package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * E06-T10 / PLT-CFG-002、004：租户字典覆盖与参数覆盖（租户库 cfg_dict / cfg_dict_item / cfg_param /
 * cfg_param_log）。调用前必须已在租户上下文。
 *
 * <p>字典是增量模型：cfg_dict 只存租户自有字典；cfg_dict_item 的 PLATFORM 行是平台项在本院的停用 标记（status=DISABLED），TENANT
 * 行是租户自有项。平台定义经 {@code PlatformConfigPort} 读取后合并。
 */
@Mapper
public interface ConfigMapper {

    // --------------------------------------------------------------- 租户字典

    @Select(
            """
            SELECT id, code, name, description, status, updated_at AS updatedAt
            FROM cfg_dict
            ORDER BY code
            """)
    List<DictRow> listOwnDicts();

    @Select(
            """
            SELECT id, code, name, description, status, updated_at AS updatedAt
            FROM cfg_dict WHERE code = #{code}
            """)
    DictRow findOwnDict(@Param("code") String code);

    @Insert(
            """
            INSERT INTO cfg_dict (code, name, description, status)
            VALUES (#{code}, #{name}, #{description}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOwnDict(DictRow row);

    @Update(
            """
            UPDATE cfg_dict
            SET name = #{name}, description = #{description}, status = #{status}
            WHERE code = #{code}
            """)
    int updateOwnDict(DictRow row);

    // --------------------------------------------------------------- 字典项（覆盖标记与自有项）

    @Select(
            """
            SELECT id, dict_code AS dictCode, item_code AS itemCode, label, aliases, sort_no AS sortNo,
                   source, status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM cfg_dict_item
            WHERE dict_code = #{dictCode}
            ORDER BY sort_no, item_code
            """)
    List<ItemRow> listItems(@Param("dictCode") String dictCode);

    @Select(
            """
            SELECT id, dict_code AS dictCode, item_code AS itemCode, label, aliases, sort_no AS sortNo,
                   source, status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM cfg_dict_item
            WHERE dict_code = #{dictCode} AND item_code = #{itemCode}
            """)
    ItemRow findItem(@Param("dictCode") String dictCode, @Param("itemCode") String itemCode);

    @Insert(
            """
            INSERT INTO cfg_dict_item
              (dict_code, item_code, label, aliases, sort_no, source, status, updated_by)
            VALUES (#{dictCode}, #{itemCode}, #{label}, #{aliases}, #{sortNo}, #{source}, #{status}, #{updatedBy})
            """)
    int insertItem(ItemRow row);

    @Update(
            """
            UPDATE cfg_dict_item
            SET label = #{label}, aliases = #{aliases}, sort_no = #{sortNo}, status = #{status},
                updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateItem(ItemRow row);

    // --------------------------------------------------------------- 字典变更记录（A-FLD-009、C-FLD-009）

    @Insert(
            """
            INSERT INTO cfg_dict_log
              (dict_code, item_code, action, before_json, after_json, operator_id)
            VALUES (#{dictCode}, #{itemCode}, #{action}, #{beforeJson}, #{afterJson}, #{operatorId})
            """)
    int insertDictLog(DictLogRow row);

    @Select(
            """
            SELECT l.id, l.dict_code AS dictCode, l.item_code AS itemCode, l.action,
                   l.before_json AS beforeJson, l.after_json AS afterJson,
                   l.operator_id AS operatorId, u.display_name AS operatorName,
                   l.created_at AS createdAt
            FROM cfg_dict_log l
            LEFT JOIN sys_user u ON u.id = l.operator_id
            WHERE l.dict_code = #{dictCode}
            ORDER BY l.id DESC
            LIMIT #{limit}
            """)
    List<DictLogRow> listDictLogs(@Param("dictCode") String dictCode, @Param("limit") int limit);

    // --------------------------------------------------------------- 租户参数覆盖

    @Select(
            """
            SELECT id, param_key AS paramKey, param_value AS paramValue, remark,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM cfg_param
            """)
    List<ParamRow> listParamOverrides();

    @Select(
            """
            SELECT id, param_key AS paramKey, param_value AS paramValue, remark,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM cfg_param WHERE param_key = #{paramKey}
            """)
    ParamRow findParam(@Param("paramKey") String paramKey);

    @Insert(
            """
            INSERT INTO cfg_param (param_key, param_value, remark, updated_by)
            VALUES (#{paramKey}, #{paramValue}, #{remark}, #{updatedBy})
            """)
    int insertParam(ParamRow row);

    @Update(
            """
            UPDATE cfg_param
            SET param_value = #{paramValue}, remark = #{remark}, updated_by = #{updatedBy}
            WHERE param_key = #{paramKey}
            """)
    int updateParam(ParamRow row);

    /** 恢复平台默认：删除本院覆盖行（PLT-CFG-004）。 */
    @Delete("DELETE FROM cfg_param WHERE param_key = #{paramKey}")
    int deleteParam(@Param("paramKey") String paramKey);

    // --------------------------------------------------------------- 覆盖变更记录（PLT-CFG-011「记录变更」）

    @Insert(
            """
            INSERT INTO cfg_param_log
              (param_key, action, old_value, new_value, reason, operator_id)
            VALUES (#{paramKey}, #{action}, #{oldValue}, #{newValue}, #{reason}, #{operatorId})
            """)
    int insertParamLog(ParamLogRow row);

    @Select(
            """
            SELECT id, param_key AS paramKey, action, old_value AS oldValue,
                   new_value AS newValue, reason, operator_id AS operatorId,
                   created_at AS createdAt
            FROM cfg_param_log
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<ParamLogRow> listParamLogs(@Param("limit") int limit);

    /** cfg_dict 租户自有字典行。 */
    class DictRow {
        private Long id;
        private String code;
        private String name;
        private String description;
        private String status;
        private LocalDateTime updatedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    /** cfg_dict_item 行：PLATFORM＝平台项停用标记（label 为空），TENANT＝租户自有项。 */
    class ItemRow {
        private Long id;
        private String dictCode;
        private String itemCode;
        private String label;
        private String aliases;
        private int sortNo;
        private String source;
        private String status;
        private Long updatedBy;
        private LocalDateTime updatedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getDictCode() {
            return dictCode;
        }

        public void setDictCode(String dictCode) {
            this.dictCode = dictCode;
        }

        public String getItemCode() {
            return itemCode;
        }

        public void setItemCode(String itemCode) {
            this.itemCode = itemCode;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getAliases() {
            return aliases;
        }

        public void setAliases(String aliases) {
            this.aliases = aliases;
        }

        public int getSortNo() {
            return sortNo;
        }

        public void setSortNo(int sortNo) {
            this.sortNo = sortNo;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(Long updatedBy) {
            this.updatedBy = updatedBy;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    /** cfg_dict_log 变更记录行（A-FLD-009、C-FLD-009；只追加）。operatorName 联查 sys_user。 */
    class DictLogRow {
        private Long id;
        private String dictCode;
        private String itemCode;
        private String action;
        private String beforeJson;
        private String afterJson;
        private Long operatorId;
        private String operatorName;
        private LocalDateTime createdAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getDictCode() {
            return dictCode;
        }

        public void setDictCode(String dictCode) {
            this.dictCode = dictCode;
        }

        public String getItemCode() {
            return itemCode;
        }

        public void setItemCode(String itemCode) {
            this.itemCode = itemCode;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getBeforeJson() {
            return beforeJson;
        }

        public void setBeforeJson(String beforeJson) {
            this.beforeJson = beforeJson;
        }

        public String getAfterJson() {
            return afterJson;
        }

        public void setAfterJson(String afterJson) {
            this.afterJson = afterJson;
        }

        public Long getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(Long operatorId) {
            this.operatorId = operatorId;
        }

        public String getOperatorName() {
            return operatorName;
        }

        public void setOperatorName(String operatorName) {
            this.operatorName = operatorName;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }

    /** cfg_param 覆盖行（param_value 是本院覆盖值）。 */
    class ParamRow {
        private Long id;
        private String paramKey;
        private String paramValue;
        private String remark;
        private Long updatedBy;
        private LocalDateTime updatedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getParamKey() {
            return paramKey;
        }

        public void setParamKey(String paramKey) {
            this.paramKey = paramKey;
        }

        public String getParamValue() {
            return paramValue;
        }

        public void setParamValue(String paramValue) {
            this.paramValue = paramValue;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public Long getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(Long updatedBy) {
            this.updatedBy = updatedBy;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    /** cfg_param_log 变更记录行。 */
    class ParamLogRow {
        private Long id;
        private String paramKey;
        private String action;
        private String oldValue;
        private String newValue;
        private String reason;
        private Long operatorId;
        private LocalDateTime createdAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getParamKey() {
            return paramKey;
        }

        public void setParamKey(String paramKey) {
            this.paramKey = paramKey;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getOldValue() {
            return oldValue;
        }

        public void setOldValue(String oldValue) {
            this.oldValue = oldValue;
        }

        public String getNewValue() {
            return newValue;
        }

        public void setNewValue(String newValue) {
            this.newValue = newValue;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Long getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(Long operatorId) {
            this.operatorId = operatorId;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }
}
