package com.crdp.followup.patient.domain;

import java.util.List;

/**
 * 高级检索字段目录（E13-T06 / A-PAT-014、A-NFR-009）：条件构造器可引用的字段清单与取值形态。
 *
 * <p>目录由字段字典（fe_field，经 crdp-form {@code FieldDictionaryService}）生成：只有
 * 「字典中启用、非敏感、实体列存储且已登记列绑定」的字段才会出现——这是「只能引用字典字段」的 服务端闸门（字典外的字段码在编译期 40001，不会到 SQL）。JSON 存储（基线分节数据随
 * E13-T02）与 敏感加密字段（姓名 / 病案号 / 电话走 E13-T04 keyword 盲索引精确检索）暂不进目录，存储落地后扩列绑定即可。
 *
 * <p>列绑定（{@link SearchFieldSpec#column()}）只来自代码内白名单，请求里的任何文本都不可能成为 SQL 片段（A-NFR-009「不允许输入原始 SQL」）。
 */
public final class AdvancedSearchCatalog {

    private AdvancedSearchCatalog() {}

    /** 条件值形态（决定可用运算符与取值解析）。 */
    public enum ValueType {
        /** 日期（YYYY-MM-DD）。 */
        DATE,
        /** 数值（目前无实体列字段，为 E13-T02 基线 JSON / 数值列预置）。 */
        NUMBER,
        /** 单行文本（包含 = LIKE 转义匹配）。 */
        TEXT,
        /** 选项（取值必须是字段选项编码之一）。 */
        OPTION
    }

    /** 选项值（value 为数据库存储编码：性别 M / F、病区 id、出院状态 1–3）。 */
    public record OptionValue(Object value, String label) {}

    /** 可检索字段：{@code field} 为字段字典的 field_code（对外稳定名），{@code column} 为白名单列。 */
    public record SearchFieldSpec(
            String field, String label, ValueType type, String column, List<OptionValue> options) {

        public SearchFieldSpec {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
