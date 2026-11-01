package com.crdp.system.config;

import java.util.List;
import java.util.Optional;

/**
 * 平台字典与系统参数定义的读取端口（E06-T10 / PLT-CFG-001..004）。
 *
 * <p>平台定义存平台库（{@code plt_dict} / {@code plt_dict_item} / {@code plt_param}），租户库无法直接
 * 查询；租户侧的字典合并与参数覆盖计算（{@code crdp-system}）不依赖平台库模块，经本端口取定义。 实现在 crdp-server：读平台库并带 60 秒缓存，平台保存字典 /
 * 参数后立即失效（PLT-CFG-011「保存后刷新缓存即时 生效」）。
 *
 * <p>返回的是定义快照（不可变），只含租户侧需要的字段。
 */
public interface PlatformConfigPort {

    /** 平台字典类型（含全部项，按 sortNo、itemCode 升序）；只返回启用中的类型。 */
    List<PlatformDictDef> dicts();

    /** 按编码取平台字典；不存在返回空。 */
    Optional<PlatformDictDef> dict(String code);

    /** 全部系统参数定义（含平台锁定的）。 */
    List<PlatformParamDef> params();

    /** 按参数键取系统参数定义；不存在返回空。 */
    Optional<PlatformParamDef> param(String paramKey);

    /** 平台字典项：编码创建后不可改；status 为平台侧状态（租户停用标记另行叠加）。 */
    record PlatformDictItem(String itemCode, String label, int sortNo, String status) {}

    /** 平台字典类型定义。 */
    record PlatformDictDef(
            String code,
            String name,
            String description,
            String status,
            List<PlatformDictItem> items) {

        public Optional<PlatformDictItem> item(String itemCode) {
            return items().stream().filter(item -> item.itemCode().equals(itemCode)).findFirst();
        }
    }

    /**
     * 系统参数定义（platform-spec §10「关键字字段 · 系统参数」）：参数键全局唯一不可改； 值类型 TEXT/NUMBER/BOOLEAN/ENUM/JSON；层级
     * tenantOverridable=false 平台锁定 / true 租户可覆盖； 取值范围 valueRange 为 JSON（NUMBER {min,max} / TEXT
     * {maxLength} / ENUM {options[]}，BOOLEAN 与 JSON 无范围）。
     */
    record PlatformParamDef(
            String paramKey,
            String name,
            String description,
            String valueType,
            String platformValue,
            boolean tenantOverridable,
            String valueRange) {}
}
