package com.crdp.form.rule.app;

import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * E11-T03 / A-FLD-004、C-FLD-005：条件 DSL 求值——<b>条件显示与条件必填</b>共用同一棵条件树（tech-plan §6「条件显示与
 * 条件必填」，与高级检索同构）。
 *
 * <p>与前端 {@code packages/rules} 的 {@code evaluateCondition} / {@code isFieldVisible}
 * 同语义（验收「前后端同一套用例 结果一致」），共用用例锁定： {@code shared-cases/form-condition-cases.json}（components.md
 * §10.5）。
 *
 * <ul>
 *   <li>条件树：节点 {@code {all: [...]}}（全部成立，空数组恒真）/ {@code {any: [...]}}（任一成立，空数组恒假），可嵌套； 叶子 {@code
 *       {field | ctx, op, value}}——field 取本表单字段值（稳定键），ctx 取上下文变量（如 C 的 {@code
 *       device.type}，按点路径在嵌套对象中取值）。
 *   <li>操作符：{@code eq | ne | in | nin | gt | gte | lt | lte | empty | notEmpty}。比较按 tech-plan §6
 *       值编码口径： 数值与数值、布尔与布尔、字符串与字符串严格同型（1 与 "1" 不等）；gt / gte / lt / lte 只比数值与日期 / 日期时间 （日期归一当日
 *       00:00，与跨字段 COMPARE 同口径），普通文本不可比 → 不成立。
 *   <li>操作数缺失（未答 / null）：除 {@code empty} / {@code notEmpty} 外一律<b>不成立</b>——「是否死亡」未答 → 死亡日期
 *       不显示、不加必填（未答 ≠ 否）。
 *   <li>空值口径：null / 空串 / 空数组 / 分量全缺的 pair_number 对象为空；{@code 0} 与 {@code false} 是已答，不算空。
 *   <li>防御：条件为空 / 解析失败 / 残缺（无 op、field 与 ctx 并存或全无）恒真——配置错误不隐藏字段、不加必填、不炸保存 （与 {@link
 *       FormRuleEngine} 坏规则防御跳过同口径，OQ-65）。
 * </ul>
 */
@Component
public class FormConditionEngine {

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 条件树求值。visibleWhen / requiredWhen 共用：visibleWhen 成立才显示，requiredWhen 成立在 required 之外追加必填 （有效必填
     * = 显示 &&（required || requiredWhen 成立），见 {@link FormRuleEngine}）。
     */
    public boolean matches(
            String conditionJson, Map<String, Object> data, Map<String, Object> context) {
        JsonNode tree = readTree(conditionJson);
        return tree == null || evaluate(tree, values(data), context == null ? Map.of() : context);
    }

    /** 字段是否显示：visibleWhen 为空恒显示（无条件字段）。 */
    public boolean visible(
            VersionFieldRow field, Map<String, Object> data, Map<String, Object> context) {
        return matches(field.visibleWhen(), data, context);
    }

    /** 显示中的字段里被隐藏的稳定键（提交时清空这些字段的值，tech-plan §6）。 */
    public List<String> hiddenFieldKeys(
            List<VersionFieldRow> fields, Map<String, Object> data, Map<String, Object> context) {
        List<String> hidden = new ArrayList<>();
        for (VersionFieldRow field : fields) {
            if (!visible(field, data, context)) {
                hidden.add(field.fieldKey());
            }
        }
        return List.copyOf(hidden);
    }

    /**
     * 有效必填：字段显示 &&（required || requiredWhen 成立）。requiredWhen 为空不追加必填（无条件必填就是 required
     * 列）；隐藏字段永不必填（值提交时清空、不计完整度，A-PAT-019 / tech-plan §6）；是否参与提交校验 （storage=json、非派生）由 {@link
     * FormRuleEngine} 把关。
     */
    public boolean requiredNow(
            VersionFieldRow field, Map<String, Object> data, Map<String, Object> context) {
        if (!visible(field, data, context)) {
            return false;
        }
        if (field.required()) {
            return true;
        }
        String when = field.requiredWhen();
        return when != null && !when.isBlank() && matches(when, data, context);
    }

    // ---------------------------------------------------------------- 树 / 叶子

    private boolean evaluate(JsonNode node, Map<String, Object> data, Map<String, Object> context) {
        if (!node.isObject()) {
            return true; // 残缺防御：不隐藏、不加必填
        }
        boolean hasAll = node.has("all") && node.get("all").isArray();
        boolean hasAny = node.has("any") && node.get("any").isArray();
        if (hasAll) {
            for (JsonNode child : node.get("all")) {
                if (!evaluate(child, data, context)) {
                    return false; // 全部成立（空数组恒真）
                }
            }
        }
        if (hasAny) {
            boolean any = false;
            for (JsonNode child : node.get("any")) {
                if (evaluate(child, data, context)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false; // 任一成立（空数组恒假）
            }
        }
        if (hasAll || hasAny) {
            return true;
        }
        return evaluateLeaf(node, data, context);
    }

    /** 叶子：{@code {field | ctx, op, value}}；残缺（无 op、field 与 ctx全无或并存）恒真（防御）。 */
    private boolean evaluateLeaf(
            JsonNode leaf, Map<String, Object> data, Map<String, Object> context) {
        String op = leaf.path("op").asText(null);
        boolean hasField = leaf.hasNonNull("field");
        boolean hasCtx = leaf.hasNonNull("ctx");
        if (op == null || hasField == hasCtx) {
            return true; // 坏配置不隐藏、不加必填（OQ-65）
        }
        Object operand =
                hasField
                        ? data.get(leaf.get("field").asText())
                        : resolvePath(context, leaf.get("ctx").asText());
        JsonNode expected = leaf.get("value");
        switch (op) {
            case "empty" -> {
                return isEmptyValue(operand);
            }
            case "notEmpty" -> {
                return !isEmptyValue(operand);
            }
            case "eq" -> {
                return operand != null && eq(operand, expected);
            }
            case "ne" -> {
                return operand != null && !eq(operand, expected);
            }
            case "in" -> {
                return operand != null && contains(expected, operand);
            }
            case "nin" -> {
                return operand != null && !contains(expected, operand);
            }
            case "gt", "gte", "lt", "lte" -> {
                return order(op, operand, expected);
            }
            default -> {
                return true; // 不认识的操作符：防御恒真
            }
        }
    }

    private boolean eq(Object operand, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            return false; // 与 null 比较不是相等（空值语义用 empty / notEmpty）
        }
        if (expected.isNumber()
                && operand instanceof Number number
                && !(operand instanceof Boolean)) {
            return expected.asDouble() == number.doubleValue();
        }
        if (expected.isBoolean() && operand instanceof Boolean bool) {
            return expected.asBoolean() == bool;
        }
        if (expected.isTextual() && operand instanceof String text) {
            return expected.asText().equals(text);
        }
        return false; // 类型不匹配（1 与 "1"）不成立：配置值按值编码写
    }

    private boolean contains(JsonNode expected, Object operand) {
        if (expected == null || !expected.isArray()) {
            return false; // value 须为数组
        }
        for (JsonNode item : expected) {
            if (eq(operand, item)) {
                return true;
            }
        }
        return false;
    }

    /** gt / gte / lt / lte：三态比较（{@link FormRuleEngine#compare} 值编码口径），不可比不成立。 */
    private boolean order(String op, Object operand, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            return false;
        }
        Object right =
                expected.isNumber()
                        ? (Object) expected.asDouble()
                        : expected.isTextual() ? expected.asText() : null;
        if (right == null) {
            return false; // 布尔 / 数组等不参与大小比较
        }
        Integer comparison = FormRuleEngine.compare(operand, right);
        if (comparison == null) {
            return false; // 不可比（数值 × 日期、普通文本）：不成立
        }
        return switch (op) {
            case "gt" -> comparison > 0;
            case "gte" -> comparison >= 0;
            case "lt" -> comparison < 0;
            default -> comparison <= 0;
        };
    }

    // ---------------------------------------------------------------- 取值与空值口径

    /** ctx 点路径取值（{@code device.type} → context.device.type），路径断链返回 null（缺失 → 不成立）。 */
    private static Object resolvePath(Map<String, Object> context, String path) {
        Object node = context;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(part);
        }
        return node;
    }

    /**
     * 空值口径（与 empty / notEmpty 及必填缺失同一口径）：null / 空串 / 空数组 / 值全为空的 pair_number 对象为空； 0 与 false
     * 是已答，不算空。
     */
    static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().allMatch(FormConditionEngine::isEmptyValue);
        }
        return false;
    }

    private static Map<String, Object> values(Map<String, Object> data) {
        return data == null ? Map.of() : data;
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw);
            return node.isNull() ? null : node;
        } catch (JsonProcessingException e) {
            return null; // 坏 JSON 防御：恒真（不隐藏、不加必填）
        }
    }
}
