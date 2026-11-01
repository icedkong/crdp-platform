package com.crdp.platform.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * E06-T10 / PLT-CFG-001、003：平台字典与系统参数（平台库 plt_dict / plt_dict_item / plt_param）。
 * 调用前必须已在平台上下文（TenantContext.callAsPlatform）。
 */
@Mapper
public interface PlatformConfigMapper {

    // --------------------------------------------------------------- 字典类型

    @Select(
            """
            SELECT id, code, name, description, status, updated_by AS updatedBy,
                   updated_at AS updatedAt
            FROM plt_dict
            ORDER BY code
            """)
    List<DictRow> listDicts();

    @Select(
            """
            SELECT id, code, name, description, status, updated_by AS updatedBy,
                   updated_at AS updatedAt
            FROM plt_dict WHERE code = #{code}
            """)
    DictRow findDict(@Param("code") String code);

    @Insert(
            """
            INSERT INTO plt_dict (code, name, description, status, updated_by)
            VALUES (#{code}, #{name}, #{description}, #{status}, #{updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDict(DictRow row);

    @Update(
            """
            UPDATE plt_dict
            SET name = #{name}, description = #{description}, status = #{status},
                updated_by = #{updatedBy}
            WHERE code = #{code}
            """)
    int updateDict(DictRow row);

    // --------------------------------------------------------------- 字典项

    @Select(
            """
            SELECT id, dict_id AS dictId, item_code AS itemCode, label, sort_no AS sortNo,
                   status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM plt_dict_item
            WHERE dict_id = #{dictId}
            ORDER BY sort_no, item_code
            """)
    List<ItemRow> listItems(@Param("dictId") long dictId);

    @Select(
            """
            SELECT id, dict_id AS dictId, item_code AS itemCode, label, sort_no AS sortNo,
                   status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM plt_dict_item
            WHERE dict_id = #{dictId} AND item_code = #{itemCode}
            """)
    ItemRow findItem(@Param("dictId") long dictId, @Param("itemCode") String itemCode);

    @Insert(
            """
            INSERT INTO plt_dict_item (dict_id, item_code, label, sort_no, status, updated_by)
            VALUES (#{dictId}, #{itemCode}, #{label}, #{sortNo}, #{status}, #{updatedBy})
            """)
    int insertItem(ItemRow row);

    @Update(
            """
            UPDATE plt_dict_item
            SET label = #{label}, sort_no = #{sortNo}, status = #{status},
                updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int updateItem(ItemRow row);

    /** 全部字典项（缓存装配用；字典类型量级小，一次取全后按 dict_id 分组）。 */
    @Select(
            """
            SELECT id, dict_id AS dictId, item_code AS itemCode, label, sort_no AS sortNo,
                   status, updated_by AS updatedBy, updated_at AS updatedAt
            FROM plt_dict_item
            ORDER BY dict_id, sort_no, item_code
            """)
    List<ItemRow> listAllItems();

    // --------------------------------------------------------------- 系统参数

    @Select(
            """
            SELECT id, param_key AS paramKey, name, description, value_type AS valueType,
                   platform_value AS platformValue,
                   tenant_overridable AS tenantOverridable, value_range AS valueRange,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM plt_param
            ORDER BY param_key
            """)
    List<ParamRow> listParams();

    @Select(
            """
            SELECT id, param_key AS paramKey, name, description, value_type AS valueType,
                   platform_value AS platformValue,
                   tenant_overridable AS tenantOverridable, value_range AS valueRange,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM plt_param WHERE param_key = #{paramKey}
            """)
    ParamRow findParam(@Param("paramKey") String paramKey);

    @Insert(
            """
            INSERT INTO plt_param
              (param_key, name, description, value_type, platform_value,
               tenant_overridable, value_range, updated_by)
            VALUES (#{paramKey}, #{name}, #{description}, #{valueType}, #{platformValue},
                    #{tenantOverridable}, #{valueRange}, #{updatedBy})
            """)
    int insertParam(ParamRow row);

    @Update(
            """
            UPDATE plt_param
            SET name = #{name}, description = #{description}, value_type = #{valueType},
                platform_value = #{platformValue}, tenant_overridable = #{tenantOverridable},
                value_range = #{valueRange}, updated_by = #{updatedBy}
            WHERE param_key = #{paramKey}
            """)
    int updateParam(ParamRow row);

    /** plt_dict 行。 */
    class DictRow {
        private Long id;
        private String code;
        private String name;
        private String description;
        private String status;
        private Long updatedBy;
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

    /** plt_dict_item 行。 */
    class ItemRow {
        private Long id;
        private Long dictId;
        private String itemCode;
        private String label;
        private int sortNo;
        private String status;
        private Long updatedBy;
        private LocalDateTime updatedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getDictId() {
            return dictId;
        }

        public void setDictId(Long dictId) {
            this.dictId = dictId;
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

        public int getSortNo() {
            return sortNo;
        }

        public void setSortNo(int sortNo) {
            this.sortNo = sortNo;
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

    /** plt_param 行。 */
    class ParamRow {
        private Long id;
        private String paramKey;
        private String name;
        private String description;
        private String valueType;
        private String platformValue;
        private boolean tenantOverridable;
        private String valueRange;
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

        public String getValueType() {
            return valueType;
        }

        public void setValueType(String valueType) {
            this.valueType = valueType;
        }

        public String getPlatformValue() {
            return platformValue;
        }

        public void setPlatformValue(String platformValue) {
            this.platformValue = platformValue;
        }

        public boolean isTenantOverridable() {
            return tenantOverridable;
        }

        public void setTenantOverridable(boolean tenantOverridable) {
            this.tenantOverridable = tenantOverridable;
        }

        public String getValueRange() {
            return valueRange;
        }

        public void setValueRange(String valueRange) {
            this.valueRange = valueRange;
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
}
