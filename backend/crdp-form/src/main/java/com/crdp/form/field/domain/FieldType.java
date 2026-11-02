package com.crdp.form.field.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 字段类型（A-FLD-001「类型」；tech-plan §6 fe_field.type）。
 *
 * <p>编码与 components.md §10.1 {@code FormField['type']} 同一套（渲染控件、校验、导入导出都由它决定）； 派生字段 {@code derived}
 * 前端只读、由系统按派生定义计算（A-FLD-005，引擎随 E11-T05）。
 */
public enum FieldType {
    /** 数值（单位 / 小数位 / 范围见元数据）。 */
    NUMBER("number"),
    /** 单行文本。 */
    TEXT("text"),
    /** 多行文本。 */
    TEXTAREA("textarea"),
    /** 日期（YYYY-MM-DD）。 */
    DATE("date"),
    /** 日期时间（YYYY-MM-DDTHH:mm）。 */
    DATETIME("datetime"),
    /** 单选下拉（存选项编码）。 */
    SELECT("select"),
    /** 单选按钮组（存选项编码，如吸烟史 0=否 / 1=是）。 */
    RADIO("radio"),
    /** 多选（存选项编码数组）。 */
    MULTI_SELECT("multiselect"),
    /** 是否类（无选项集时按 0=否 / 1=是，A 规格「原表未写编码的是否类字段按 0=否、1=是」）。 */
    BOOLEAN("boolean"),
    /** 数值 × 2（如室壁瘤大小长 × 宽，存 {length,width} 对象）。 */
    PAIR_NUMBER("pair_number"),
    /** 派生字段（只读，公式见派生定义）。 */
    DERIVED("derived");

    private final String code;

    FieldType(String code) {
        this.code = code;
    }

    /** 存储编码（fe_field.type、接口与 FormSchema 同口径）。 */
    public String code() {
        return code;
    }

    /** 按存储编码解析；未知编码为空（调用方按脏数据报 40001）。 */
    public static Optional<FieldType> ofCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }

    /** 是否带选项集（单选 / 下拉 / 多选必须有选项；是否类可无选项集，按 0/1 兜底）。 */
    public boolean hasOptionSet() {
        return this == SELECT || this == RADIO || this == MULTI_SELECT;
    }
}
