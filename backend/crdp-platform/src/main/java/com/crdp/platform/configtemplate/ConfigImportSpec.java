package com.crdp.platform.configtemplate;

import java.util.List;
import java.util.Map;

/**
 * 单张配置表的导入规格（E12-T02 / A-FLD-014）：自然键、数值引用与导入方式。 与导出侧 {@link ConfigTableEntry}（表清单）互补—— 导入按本规格做 ID
 * 重映射与合并判定，规格全集见 {@link ConfigImportSpecs}。
 *
 * @param table 表名（必须是 ConfigTableRegistry 登记的 COPY 条目）
 * @param mode 导入方式
 * @param keyColumns 自然键列（存在性判定与合并的依据；REPLACE_ROWS / DROP_ROWS 为空）
 * @param references 数值引用列 → 被引用表（导入时按各表「源 ID → 目标 ID」映射改写； 引用不到目标行的行整行丢弃）
 * @param menuRangeFilter true = 关联行里的 menu_id 须落在目标租户可分配范围内（角色权限与新租户可分配范围取交集， tech-plan §6）
 */
public record ConfigImportSpec(
        String table,
        ConfigImportMode mode,
        List<String> keyColumns,
        Map<String, String> references,
        boolean menuRangeFilter) {

    public ConfigImportSpec {
        if (table == null || !table.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("配置表名只允许小写字母、数字与下划线: " + table);
        }
        keyColumns = keyColumns == null ? List.of() : List.copyOf(keyColumns);
        references = references == null ? Map.of() : Map.copyOf(references);
    }

    /** 常规 COPY_ROWS 规格（无引用）。 */
    static ConfigImportSpec copy(String table, List<String> keyColumns) {
        return new ConfigImportSpec(table, ConfigImportMode.COPY_ROWS, keyColumns, Map.of(), false);
    }

    /** 常规 COPY_ROWS 规格（带数值引用）。 */
    static ConfigImportSpec copy(
            String table, List<String> keyColumns, Map<String, String> references) {
        return new ConfigImportSpec(
                table, ConfigImportMode.COPY_ROWS, keyColumns, references, false);
    }
}
