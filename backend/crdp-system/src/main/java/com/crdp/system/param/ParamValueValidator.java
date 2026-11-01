package com.crdp.system.param;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;

/**
 * 参数取值与取值范围校验（E06-T10 / PLT-CFG-003、004）。
 *
 * <p>值类型与范围口径（platform-spec §10「关键字字段 · 系统参数」，范围 JSON 存 {@code plt_param.value_range}）：
 *
 * <ul>
 *   <li>NUMBER：十进制数；范围 {@code {"min":5,"max":50}}（闭区间，可只给一端）；
 *   <li>TEXT：非空白文本；范围 {@code {"maxLength":255}}；
 *   <li>BOOLEAN：{@code true} / {@code false}，无范围（取值集合即类型本身）；
 *   <li>ENUM：范围 {@code {"options":["A","B"]}}，取值必须命中选项；
 *   <li>JSON：合法 JSON 文本，无范围。
 * </ul>
 *
 * <p>平台保存参数时校验范围本身（{@link #rangeError}：可覆盖时必填——BOOLEAN / JSON 除外，ENUM 必须给 选项）；平台默认值与租户覆盖值都按 {@link
 * #valueError} 校验。纯函数，无状态。
 */
public final class ParamValueValidator {

    public static final String TEXT = "TEXT";
    public static final String NUMBER = "NUMBER";
    public static final String BOOLEAN = "BOOLEAN";
    public static final String ENUM = "ENUM";
    public static final String JSON = "JSON";

    public static final Set<String> VALUE_TYPES = Set.of(TEXT, NUMBER, BOOLEAN, ENUM, JSON);

    /** 文案顺序固定（Set 迭代序不稳定）。 */
    private static final String VALUE_TYPE_TEXT =
            String.join(" / ", TEXT, NUMBER, BOOLEAN, ENUM, JSON);

    /** 与 plt_param.value_range / cfg_param.param_value 的列宽一致。 */
    public static final int VALUE_MAX = 512;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParamValueValidator() {}

    // --------------------------------------------------------------- 取值范围

    /**
     * 校验取值范围 JSON 本身。返回 null 表示合法，否则为错误消息（平台保存参数与租户覆盖前共用）。
     *
     * @param overridable 是否租户可覆盖（可覆盖时范围必填，BOOLEAN / JSON 例外）
     */
    public static String rangeError(String valueType, String valueRange, boolean overridable) {
        String typeError = typeError(valueType);
        if (typeError != null) {
            return typeError;
        }
        if (isBlank(valueRange)) {
            if (overridable && !BOOLEAN.equals(valueType) && !JSON.equals(valueType)) {
                return "租户可覆盖的 " + valueType + " 参数必须配置取值范围";
            }
            return null;
        }
        if (valueRange.length() > VALUE_MAX) {
            return "取值范围不能超过 " + VALUE_MAX + " 个字符";
        }
        JsonNode node = parse(valueRange);
        if (node == null || !node.isObject()) {
            return "取值范围必须是 JSON 对象";
        }
        switch (valueType) {
            case NUMBER -> {
                if (!has(node, "min") && !has(node, "max")) {
                    return "数值参数的取值范围必须包含 min 或 max 至少一端";
                }
                if (decimalOf(node.get("min")) == null || decimalOf(node.get("max")) == null) {
                    return "数值参数的取值范围 min / max 必须是数字";
                }
            }
            case TEXT -> {
                JsonNode max = node.get("maxLength");
                if (max == null
                        || !max.canConvertToInt()
                        || max.asInt() < 1
                        || max.asInt() > VALUE_MAX) {
                    return "文本参数的取值范围必须是 1–" + VALUE_MAX + " 的 maxLength";
                }
            }
            case ENUM -> {
                JsonNode options = node.get("options");
                if (options == null || !options.isArray() || options.isEmpty()) {
                    return "枚举参数的取值范围必须是非空 options 数组";
                }
                for (JsonNode option : options) {
                    if (!option.isTextual() || isBlank(option.asText())) {
                        return "枚举参数的 options 必须是非空字符串";
                    }
                }
            }
            case BOOLEAN, JSON -> {
                return BOOLEAN.equals(valueType)
                        ? "布尔参数没有取值范围（取值只能是 true / false）"
                        : "JSON 参数没有取值范围";
            }
            default -> {
                return "不支持的值类型：" + valueType;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- 取值

    /** 校验取值。返回 null 表示通过，否则为错误消息（不含字段名，由调用方拼接）。 */
    public static String valueError(String valueType, String valueRange, String value) {
        String typeError = typeError(valueType);
        if (typeError != null) {
            return typeError;
        }
        if (value == null) {
            return "取值不能为空";
        }
        if (value.length() > VALUE_MAX) {
            return "取值不能超过 " + VALUE_MAX + " 个字符";
        }
        switch (valueType) {
            case TEXT -> {
                if (isBlank(value)) {
                    return "文本参数取值不能为空白";
                }
                int maxLength = maxLengthOf(valueRange);
                if (value.length() > maxLength) {
                    return "文本长度不能超过 " + maxLength;
                }
            }
            case NUMBER -> {
                BigDecimal number;
                try {
                    number = new BigDecimal(value.trim());
                } catch (NumberFormatException exception) {
                    return "数值参数取值必须是数字";
                }
                JsonNode node = parse(valueRange);
                if (node != null) {
                    BigDecimal min = decimalOf(node.get("min"));
                    BigDecimal max = decimalOf(node.get("max"));
                    if (min != null && number.compareTo(min) < 0) {
                        return "取值不得小于 " + min.toPlainString();
                    }
                    if (max != null && number.compareTo(max) > 0) {
                        return "取值不得大于 " + max.toPlainString();
                    }
                }
            }
            case BOOLEAN -> {
                if (!"true".equals(value.trim()) && !"false".equals(value.trim())) {
                    return "布尔参数取值只能是 true 或 false";
                }
            }
            case ENUM -> {
                JsonNode node = parse(valueRange);
                if (node == null || node.get("options") == null) {
                    return "枚举参数缺少取值范围";
                }
                for (JsonNode option : node.get("options")) {
                    if (option.isTextual() && option.asText().equals(value.trim())) {
                        return null;
                    }
                }
                return "取值必须是枚举选项之一：" + optionsText(node.get("options"));
            }
            case JSON -> {
                if (isBlank(value)) {
                    return "JSON 参数取值不能是空白";
                }
                if (parse(value.trim()) == null) {
                    return "JSON 参数取值必须是合法 JSON";
                }
            }
            default -> {
                return "不支持的值类型：" + valueType;
            }
        }
        return null;
    }

    /** 取值范围内的枚举选项文案（错误消息用）；无范围返回空串。 */
    public static String optionsText(JsonNode options) {
        if (options == null || !options.isArray()) {
            return "";
        }
        java.util.List<String> texts = new ArrayList<>();
        for (JsonNode option : options) {
            texts.add(option.asText());
        }
        return String.join(" / ", texts);
    }

    // --------------------------------------------------------------- 私有

    private static String typeError(String valueType) {
        return valueType == null || !VALUE_TYPES.contains(valueType)
                ? "值类型必须是 " + VALUE_TYPE_TEXT
                : null;
    }

    private static int maxLengthOf(String valueRange) {
        JsonNode node = parse(valueRange);
        if (node != null
                && node.get("maxLength") != null
                && node.get("maxLength").canConvertToInt()) {
            int maxLength = node.get("maxLength").asInt();
            return maxLength >= 1 && maxLength <= VALUE_MAX ? maxLength : VALUE_MAX;
        }
        return VALUE_MAX;
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean has(JsonNode node, String field) {
        return node != null && node.get(field) != null && !node.get(field).isNull();
    }

    private static BigDecimal decimalOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isNumber() ? new BigDecimal(node.asText()) : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
