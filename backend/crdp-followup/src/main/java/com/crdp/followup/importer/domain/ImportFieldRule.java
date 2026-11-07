package com.crdp.followup.importer.domain;

import com.crdp.form.field.domain.FieldType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E18-T03：一个导入字段的校验规则（字段字典 fe_field / fe_option 的校验投影，tech-plan §12④ 「按字段字典做类型、范围、选项、必填与跨字段校验」）。
 *
 * <p>由 crdp-followup importer app 的规则加载器从 {@code FieldDictionaryService} 构造（显示名 / 原表列名锚点与 {@link
 * ImportFieldCatalog} 同口径，OQ-72）； 单元格一律按文本读入，类型转换与校验都发生在校验阶段（{@link ImportCellValidator}），不在解析阶段。
 *
 * @param fieldKey 稳定键
 * @param label 显示名（问题清单的表头列）
 * @param originalHeader 原表列名锚点（宽表列）
 * @param category 分类锚点键（fe_field.category_key；E18-T05 提交按它把基线 JSON 字段分组写 pt_baseline_section）
 * @param storage 存储方式（column 主档实体列 / json 表单 JSON / encrypted 敏感加密存储——E18-T05 提交按它分流：column 写
 *     pt_patient 实体列、json 写分节 / 记录 data、encrypted 不进明文 JSON，由各自信列承载）
 * @param enabled 是否启用（停用字段仍是宽表列，校验照做——历史数据按导入时的字典口径）
 * @param type 字段类型
 * @param unit 单位（数值 / 范围提示里带上，如 mmHg）
 * @param decimals 小数位（0 = 整数字段）
 * @param hardMin / hardMax 硬范围（空端不限制；越界报错）
 * @param softMin / softMax 提示范围（越界警告，可导入）
 * @param required 是否必填（无条件必填；条件必填随录入界面触发，历史导入不检查）
 * @param sensitive 是否敏感（敏感字段的问题不回显原值，OQ-101③）
 * @param options 选项编码 → 显示名（含停用选项——历史数据照常接受，C-FLD-009）
 * @param derivedDefJson 派生定义原文（fe_field.derived_def 的 JSON 文本；非派生字段为 null）——派生重算比对透传给
 *     FormDerivedEngine
 */
public record ImportFieldRule(
        String fieldKey,
        String label,
        String originalHeader,
        String category,
        String storage,
        boolean enabled,
        FieldType type,
        String unit,
        Integer decimals,
        Double hardMin,
        Double hardMax,
        Double softMin,
        Double softMax,
        boolean required,
        boolean sensitive,
        Map<Integer, String> options,
        String derivedDefJson) {

    public ImportFieldRule {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** 表头可匹配名（与 {@link ImportFieldCatalog.CatalogField#matchNames()} 同口径）。 */
    public String headerName() {
        return originalHeader != null ? originalHeader : label;
    }

    /** 是否「是否类」选项集（编码 {0,1} × 显示名 {是,否}，Q36 的适用面）。 */
    public boolean isYesNo() {
        if (options.size() != 2 || !options.containsKey(0) || !options.containsKey(1)) {
            return false;
        }
        return "是".equals(options.get(1)) && "否".equals(options.get(0));
    }

    /** 选项显示名清单（错误提示列举合法取值，最多 {@code limit} 个）。 */
    public String optionSummary(int limit) {
        StringBuilder text = new StringBuilder();
        new LinkedHashMap<>(options)
                .entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .limit(limit)
                        .forEach(
                                entry -> {
                                    if (text.length() > 0) {
                                        text.append("、");
                                    }
                                    text.append(entry.getValue());
                                });
        return text.toString();
    }
}
