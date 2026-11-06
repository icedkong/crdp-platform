package com.crdp.followup.report.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告单映射写入的值转换与冲突判定（E16-T07 / A-ATT-013、C-SCAN-005）。
 *
 * <p>指标结果与附带写入值都是文本（rpt_indicator_value.result / rpt_mapping*.extra_writes.value），目标字段按
 * fe_field.type 存类型值 （FormDataValidator 同一套）：number 存 JSON 数值、date 存 YYYY-MM-DD 文本、单选 /
 * 下拉存选项编码整数（结果可填 选项名或编码）、pair_number 存 {length,width}（室壁瘤大小「30×20」，FormDataValidator.PairNumber
 * 口径）。 转换失败不抛异常，返回带中文原因的 {@link Converted}（预览逐行提示、确认 40001 拦截），用户改报告单指标后重试。
 *
 * <p>冲突判定与 fe_field_history 的值口径一致（OQ-65）：空值 = null / 空串 / 空数组，空对空不算冲突； 数值按值比较（55 与 55.0
 * 同值）；其余严格同型。
 */
public final class MappingValues {

    private MappingValues() {}

    /** 选项（编码 + 显示名；fe_field 选项集的精简投影）。 */
    public record Option(int code, String label) {}

    /** 转换结果：ok 时 value 为目标类型值；失败时 value 为空、error 为提示文案。 */
    public record Converted(Object value, String error) {

        static Converted ok(Object value) {
            return new Converted(value, null);
        }

        static Converted fail(String error) {
            return new Converted(null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** 「长×宽」二元数值（×、x、X、* 分隔；如室壁瘤大小 30×20）。 */
    private static final Pattern PAIR =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*[×xX\\*]\\s*(\\d+(?:\\.\\d+)?)\\s*$");

    /**
     * 文本结果 → 目标字段类型的值。
     *
     * @param fieldLabel 目标字段显示名（错误提示用）
     * @param fieldType fe_field.type 存储编码
     * @param options 选项集（单选 / 下拉必填；是否类可空按 0/1）
     * @param raw 指标结果或附带写入取值文本（REPORT_DATE 行由调用方先转成报告时间的文本形式）
     */
    public static Converted convert(
            String fieldLabel, String fieldType, List<Option> options, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Converted.fail("「" + fieldLabel + "」结果为空，不能写入");
        }
        return switch (fieldType == null ? "" : fieldType) {
            case "number" -> convertNumber(fieldLabel, text);
            case "pair_number" -> convertPair(fieldLabel, text);
            case "date" -> convertDate(fieldLabel, text);
            case "select", "radio" -> convertOption(fieldLabel, options, text, false);
            case "boolean" -> convertOption(fieldLabel, options, text, true);
            case "text", "textarea" -> Converted.ok(text);
            case "datetime" -> Converted.fail("「" + fieldLabel + "」暂不支持日期时间字段写入");
            case "multiselect" -> Converted.fail("「" + fieldLabel + "」暂不支持多选字段写入");
            case "derived" -> Converted.fail("「" + fieldLabel + "」是派生字段，由系统计算，不能写入");
            default -> Converted.fail("「" + fieldLabel + "」字段类型不支持写入：" + fieldType);
        };
    }

    private static Converted convertNumber(String fieldLabel, String text) {
        try {
            return Converted.ok(numberOf(text));
        } catch (NumberFormatException | ArithmeticException e) {
            return Converted.fail("「" + fieldLabel + "」要求数值，结果「" + text + "」不能写入");
        }
    }

    private static Converted convertPair(String fieldLabel, String text) {
        Matcher pair = PAIR.matcher(text);
        if (!pair.matches()) {
            return Converted.fail("「" + fieldLabel + "」要求「长×宽」（如 30×20），结果「" + text + "」不能写入");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("length", numberOf(pair.group(1)));
        value.put("width", numberOf(pair.group(2)));
        return Converted.ok(value);
    }

    /** 文本 → JSON 数值（整数走 int/long、小数走 double，避免 55 序列化成 55.0）。 */
    private static Object numberOf(String text) {
        BigDecimal decimal = new BigDecimal(text).stripTrailingZeros();
        if (decimal.scale() > 0) {
            return decimal.doubleValue();
        }
        long value = decimal.longValueExact();
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? (Object) (int) value
                : (Object) value;
    }

    private static Converted convertDate(String fieldLabel, String text) {
        try {
            return Converted.ok(LocalDate.parse(text).toString());
        } catch (DateTimeParseException e) {
            return Converted.fail("「" + fieldLabel + "」要求日期（YYYY-MM-DD），结果「" + text + "」不能写入");
        }
    }

    private static Converted convertOption(
            String fieldLabel, List<Option> options, String text, boolean booleanLike) {
        if (options == null || options.isEmpty()) {
            options = booleanLike ? List.of(new Option(0, "否"), new Option(1, "是")) : List.of();
        }
        if (options.isEmpty()) {
            return Converted.fail("「" + fieldLabel + "」未配置选项集，不能写入");
        }
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                int code = Integer.parseInt(text);
                for (Option option : options) {
                    if (option.code() == code) {
                        return Converted.ok(code);
                    }
                }
            } catch (NumberFormatException ignored) {
                // 落到按选项名匹配
            }
        }
        for (Option option : options) {
            if (option.label().equals(text)) {
                return Converted.ok(option.code());
            }
        }
        String labels =
                options.stream().map(Option::label).reduce((a, b) -> a + " / " + b).orElse("");
        return Converted.fail("「" + fieldLabel + "」结果「" + text + "」不在选项（" + labels + "）内");
    }

    /**
     * 原值与新值是否冲突（原值非空且与新值不同 → true；A-ATT-013「原值不同的须逐项确认覆盖」）。 空值口径与数值比较同 {@link
     * com.crdp.form.history.app.FieldHistoryService}（OQ-65 ①②）。
     */
    public static boolean conflict(Object oldValue, Object newValue) {
        if (isEmpty(oldValue)) {
            return false;
        }
        return !valuesEqual(oldValue, newValue);
    }

    private static boolean valuesEqual(Object oldValue, Object newValue) {
        if (isEmpty(oldValue) && isEmpty(newValue)) {
            return true;
        }
        if (oldValue instanceof Number oldNum
                && newValue instanceof Number newNum
                && !(oldValue instanceof Boolean)
                && !(newValue instanceof Boolean)) {
            return oldNum.doubleValue() == newNum.doubleValue();
        }
        if (oldValue instanceof List<?> oldList && newValue instanceof List<?> newList) {
            if (oldList.size() != newList.size()) {
                return false;
            }
            for (int i = 0; i < oldList.size(); i++) {
                if (!valuesEqual(oldList.get(i), newList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (oldValue instanceof Map<?, ?> oldMap && newValue instanceof Map<?, ?> newMap) {
            if (!oldMap.keySet().equals(newMap.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : oldMap.entrySet()) {
                if (!valuesEqual(entry.getValue(), newMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(oldValue, newValue);
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isEmpty();
        }
        return value instanceof List<?> list && list.isEmpty();
    }
}
