package com.crdp.followup.patient.domain;

import com.crdp.followup.patient.domain.AdvancedSearchCatalog.OptionValue;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.SearchFieldSpec;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.ValueType;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 高级检索条件树编译器（E13-T06 / A-PAT-014、A-NFR-009、C-PAT-013）。
 *
 * <p>输入为前端条件构造器输出的结构化 JSON（components.md §11.2 {@code ConditionGroup}：{@code {logic: AND|OR, items:
 * [行 | 组]}}，行 = {@code {field, op, value?}}）；输出为**参数化** SQL 片段：
 *
 * <ul>
 *   <li>列名只来自目录白名单（字典生成的 {@link SearchFieldSpec#column()}），未知字段 40001；
 *   <li>取值一律绑定参数位（{@code #{c.advancedBinds[i]}}，MyBatis 预编译占位），请求原文不进 SQL 文本；
 *   <li>运算符为固定枚举（等于 / 不等于 / 大于 / 小于 / 介于 / 包含 / 为空 / 不为空），白名单外 40001；
 *   <li>运算符与字段类型匹配（日期 / 数值支持比较与介于，文本支持等值与包含，选项只支持等值）；
 *   <li>选项字段取值必须在字段选项内；包含（CONTAINS）转义 LIKE 通配符；
 *   <li>嵌套分组 ≤ 3 层、条件行 ≤ 30 行，防止构造超长 SQL；
 *   <li>病区条件**不在**条件树里——数据范围拦截器（E07-T02）对语句自动追加病区 / 科室 / 本人改写 （A-NFR-009「自动追加病区条件」），编译产物与其 AND 叠加。
 * </ul>
 */
public final class AdvancedConditionCompiler {

    /** 条件行 / 嵌套层数上限（防超长 SQL；建议值）。 */
    private static final int MAX_ROWS = 30;

    private static final int MAX_DEPTH = 3;

    private static final int TEXT_MAX_LENGTH = 64;

    /** 运算符白名单（A-PAT-014：等于、不等于、大于、小于、介于、包含、为空、不为空）。 */
    public static final Set<String> OPS =
            Set.of("EQ", "NE", "GT", "LT", "BETWEEN", "CONTAINS", "IS_NULL", "NOT_NULL");

    private AdvancedConditionCompiler() {}

    /** 编译结果：where 为完整括号化的 SQL 片段（无条件时 null）；binds 按占位符顺序排列。 */
    public record Compiled(String where, List<Object> binds, int rows) {}

    /**
     * 编译条件树；{@code condition} 为 null / 空对象 / 空 items 时返回 where=null（不追加条件）。
     *
     * @throws ApiException 40001 条件引用目录外字段、运算符或类型不匹配、取值非法、结构错误、超限
     */
    public static Compiled compile(JsonNode condition, Map<String, SearchFieldSpec> fields) {
        if (condition == null || condition.isNull()) {
            return new Compiled(null, List.of(), 0);
        }
        if (!condition.isObject()) {
            throw invalid("condition（须为条件组对象）");
        }
        JsonNode items = condition.get("items");
        if (items == null || (items.isArray() && items.isEmpty())) {
            return new Compiled(null, List.of(), 0);
        }
        Ctx ctx = new Ctx();
        String where = group(condition, fields, ctx, 1);
        return new Compiled(where, List.copyOf(ctx.binds), ctx.rows);
    }

    // ---------------------------------------------------------------- 分组

    private static String group(
            JsonNode node, Map<String, SearchFieldSpec> fields, Ctx ctx, int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("condition（条件组最多嵌套 " + MAX_DEPTH + " 层）");
        }
        String logic = textOrDefault(node, "logic", "AND");
        if (!"AND".equals(logic) && !"OR".equals(logic)) {
            throw invalid("condition.logic（AND / OR）");
        }
        JsonNode items = node.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw invalid("condition.items（条件组不能为空）");
        }
        List<String> fragments = new ArrayList<>();
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) {
                throw invalid("condition.items（条件行 / 条件组须为对象）");
            }
            fragments.add(
                    item.has("items")
                            ? group(item, fields, ctx, depth + 1)
                            : row(item, fields, ctx));
        }
        return "(" + String.join(" " + logic + " ", fragments) + ")";
    }

    // ---------------------------------------------------------------- 条件行

    private static String row(JsonNode node, Map<String, SearchFieldSpec> fields, Ctx ctx) {
        JsonNode fieldNode = node.get("field");
        if (fieldNode == null || !fieldNode.isTextual() || fieldNode.asText().isBlank()) {
            throw invalid("condition.field（必填）");
        }
        SearchFieldSpec spec = fields.get(fieldNode.asText().trim());
        if (spec == null) {
            throw invalid("condition.field（未知字段：" + clip(fieldNode.asText()) + "，只能引用字段字典中的字段）");
        }
        JsonNode opNode = node.get("op");
        if (opNode == null || !opNode.isTextual() || !OPS.contains(opNode.asText())) {
            throw invalid(
                    "condition.op（" + String.join(" / ", OPS.stream().sorted().toList()) + "）");
        }
        String op = opNode.asText();
        requireType(spec, op);

        if (ctx.rows >= MAX_ROWS) {
            throw invalid("condition（条件最多 " + MAX_ROWS + " 行）");
        }
        ctx.rows++;
        JsonNode value = node.get("value");

        return switch (op) {
            case "EQ" -> spec.column() + " = " + placeholder(ctx, valueOf(spec, value));
            case "NE" -> spec.column() + " <> " + placeholder(ctx, valueOf(spec, value));
            case "GT" -> spec.column() + " > " + placeholder(ctx, valueOf(spec, value));
            case "LT" -> spec.column() + " < " + placeholder(ctx, valueOf(spec, value));
            case "BETWEEN" -> between(spec, ctx, value);
            case "CONTAINS" -> contains(spec, ctx, value);
            case "IS_NULL" -> isNull(spec, true);
            case "NOT_NULL" -> isNull(spec, false);
            default -> throw invalid("condition.op");
        };
    }

    /** 介于：取值为二元数组（闭区间），开始不能晚于结束。 */
    private static String between(SearchFieldSpec spec, Ctx ctx, JsonNode value) {
        if (value == null || !value.isArray() || value.size() != 2) {
            throw invalid("condition.value（介于须为 [开始, 结束] 两个取值）");
        }
        Object from = valueOf(spec, value.get(0));
        Object to = valueOf(spec, value.get(1));
        if (from instanceof LocalDate d1 && to instanceof LocalDate d2 && d1.isAfter(d2)) {
            throw invalid("condition.value（开始日期不能晚于结束日期）");
        }
        if (from instanceof Number n1
                && to instanceof Number n2
                && Double.compare(n1.doubleValue(), n2.doubleValue()) > 0) {
            throw invalid("condition.value（开始值不能大于结束值）");
        }
        return spec.column()
                + " BETWEEN "
                + placeholder(ctx, from)
                + " AND "
                + placeholder(ctx, to);
    }

    /** 包含：仅文本字段；LIKE 转义（\\、%、_）后两侧加 %。 */
    private static String contains(SearchFieldSpec spec, Ctx ctx, JsonNode value) {
        String text = textValue(value);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return spec.column() + " LIKE " + placeholder(ctx, "%" + escaped + "%");
    }

    /** 为空 / 不为空：文本列同时把空串算空（部分链路写 '' 而非 NULL）。 */
    private static String isNull(SearchFieldSpec spec, boolean nullOp) {
        if (spec.type() == ValueType.TEXT) {
            return nullOp
                    ? "(" + spec.column() + " IS NULL OR " + spec.column() + " = '')"
                    : "(" + spec.column() + " IS NOT NULL AND " + spec.column() + " <> '')";
        }
        return spec.column() + (nullOp ? " IS NULL" : " IS NOT NULL");
    }

    /** 类型与运算符匹配校验（日期 / 数值：比较类；文本：等值与包含；选项：等值与空判断）。 */
    private static void requireType(SearchFieldSpec spec, String op) {
        boolean allowed =
                switch (spec.type()) {
                    case DATE, NUMBER -> !"CONTAINS".equals(op);
                    case TEXT ->
                            "EQ".equals(op)
                                    || "NE".equals(op)
                                    || "CONTAINS".equals(op)
                                    || "IS_NULL".equals(op)
                                    || "NOT_NULL".equals(op);
                    case OPTION ->
                            "EQ".equals(op)
                                    || "NE".equals(op)
                                    || "IS_NULL".equals(op)
                                    || "NOT_NULL".equals(op);
                };
        if (!allowed) {
            throw invalid("condition.op（字段「" + spec.label() + "」不支持 " + op + "）");
        }
    }

    // ---------------------------------------------------------------- 取值解析

    private static Object valueOf(SearchFieldSpec spec, JsonNode value) {
        if (value == null || value.isNull()) {
            throw invalid("condition.value（必填）");
        }
        return switch (spec.type()) {
            case DATE -> dateValue(value);
            case NUMBER -> numberValue(value);
            case TEXT -> textValue(value);
            case OPTION -> optionValue(spec, value);
        };
    }

    private static LocalDate dateValue(JsonNode value) {
        if (!value.isTextual()) {
            throw invalid("condition.value（日期须为 YYYY-MM-DD 字符串）");
        }
        try {
            return LocalDate.parse(value.asText().trim());
        } catch (DateTimeParseException e) {
            throw invalid("condition.value（日期格式 YYYY-MM-DD）");
        }
    }

    private static Number numberValue(JsonNode value) {
        if (!value.isNumber()) {
            throw invalid("condition.value（须为数字）");
        }
        return value.decimalValue();
    }

    private static String textValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid("condition.value（须为文本）");
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            throw invalid("condition.value（不能为空白）");
        }
        if (text.length() > TEXT_MAX_LENGTH) {
            throw invalid("condition.value（最多 " + TEXT_MAX_LENGTH + " 个字符）");
        }
        return text;
    }

    /** 选项：取值必须等于字段选项编码之一（类型也须一致：数字编码给数字、字符编码给字符串）。 */
    private static Object optionValue(SearchFieldSpec spec, JsonNode value) {
        for (OptionValue option : spec.options()) {
            if (option.value() instanceof Number n) {
                if (value.isNumber() && value.decimalValue().doubleValue() == n.doubleValue()) {
                    return n.longValue();
                }
            } else if (value.isTextual() && option.value().equals(value.asText().trim())) {
                return option.value();
            }
        }
        throw invalid("condition.value（字段「" + spec.label() + "」的取值须为选项之一）");
    }

    private static String placeholder(Ctx ctx, Object bindValue) {
        ctx.binds.add(bindValue);
        return "#{c.advancedBinds[" + (ctx.binds.size() - 1) + "]}";
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || !v.isTextual() || v.asText().isBlank() ? fallback : v.asText().trim();
    }

    private static String clip(String text) {
        return text.length() > 32 ? text.substring(0, 32) + "…" : text;
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不正确：" + field);
    }

    /** 编译上下文：参数位顺序与行数。 */
    private static final class Ctx {
        private final List<Object> binds = new ArrayList<>();
        private int rows;
    }
}
