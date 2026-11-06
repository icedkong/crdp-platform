package com.crdp.platform.configtemplate;

/**
 * 配置表注册表条目（A-FLD-014）。表名、模块、复制方式与行排除谓词都是随包代码（与菜单清单 {@code MenuManifest} 同口径），改配置范围 =
 * 改代码发版；未登记的表一律不导出。
 *
 * @param table 租户库表名
 * @param module 所属模块（base / followup），导入按模块迁移存在性对照
 * @param mode 复制方式
 * @param category A-FLD-014 复制清单里的类别名（导出包与预览页展示用）
 * @param description 说明（含「只复制结构」之类的口径备注）
 * @param excludeRows 行排除谓词（SQL 布尔表达式，引用该表列；满足谓词的行不入包）。 只允许出现在 COPY 条目上， 用于同一张表里按
 *     A-FLD-014「只复制结构」剔除部分取值（如术者字典项）；null = 不排除
 */
public record ConfigTableEntry(
        String table,
        String module,
        ConfigCopyMode mode,
        String category,
        String description,
        String excludeRows) {

    public ConfigTableEntry {
        if (table == null || !table.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("配置表名只允许小写字母、数字与下划线: " + table);
        }
        if (excludeRows != null && mode != ConfigCopyMode.COPY) {
            throw new IllegalArgumentException("STRUCTURE_ONLY 条目不使用行排除谓词: " + table);
        }
    }
}
