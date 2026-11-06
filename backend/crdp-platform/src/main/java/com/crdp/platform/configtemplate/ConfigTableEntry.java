package com.crdp.platform.configtemplate;

import java.util.Map;
import java.util.Objects;

/**
 * 配置表注册表条目（A-FLD-014）。表名、模块、复制方式与行排除谓词都是随包代码（与菜单清单 {@code MenuManifest} 同口径），改配置范围 =
 * 改代码发版；未登记的表一律不导出。
 *
 * @param table 租户库表名
 * @param module 所属模块（base / followup），导入按模块迁移存在性对照
 * @param mode 复制方式
 * @param category A-FLD-014 复制清单里的类别名（导出包与预览页展示用）
 * @param description 说明（含「只复制结构」之类的口径备注）
 * @param excludeWhen 行排除谓词（列名 → 值的全等合取；全部命中即排除该行，空 = 不排除）。 只允许出现在 COPY 条目上， 用于同一张表里按
 *     A-FLD-014「只复制结构」剔除部分取值（如术者字典项）。 E12-T03 起为结构化定义——导出读行（参数化 SQL）与敏感信息检查（包行回验）共用同一份规则， 不再各自维护
 *     SQL 字符串
 */
public record ConfigTableEntry(
        String table,
        String module,
        ConfigCopyMode mode,
        String category,
        String description,
        Map<String, String> excludeWhen) {

    public ConfigTableEntry {
        if (table == null || !table.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("配置表名只允许小写字母、数字与下划线: " + table);
        }
        if (excludeWhen == null) {
            excludeWhen = Map.of();
        } else {
            excludeWhen = Map.copyOf(excludeWhen);
        }
        if (!excludeWhen.isEmpty() && mode != ConfigCopyMode.COPY) {
            throw new IllegalArgumentException("STRUCTURE_ONLY 条目不使用行排除谓词: " + table);
        }
    }

    /** 包行是否命中行排除谓词（如术者取值）：命中的行不得出现在导出包里（敏感信息检查用）。 */
    public boolean rowExcluded(Map<String, Object> row) {
        if (excludeWhen.isEmpty() || row == null) {
            return false;
        }
        for (Map.Entry<String, String> condition : excludeWhen.entrySet()) {
            Object value = row.get(condition.getKey());
            if (!Objects.equals(
                    value == null ? null : String.valueOf(value), condition.getValue())) {
                return false;
            }
        }
        return true;
    }
}
