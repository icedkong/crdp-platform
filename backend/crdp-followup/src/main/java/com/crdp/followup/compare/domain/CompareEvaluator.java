package com.crdp.followup.compare.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 录入时对比标红求值器（E17-T03 / A-FU-027、C-FU-011；tech-plan §11、docs/api/followup.md §4）。
 *
 * <p>纯函数：七类规则 ×（本次 data + 上一次值）→ 命中列表。与前端 {@code packages/rules/src/form-compare.ts} 是同一份实现的两侧，
 * 由共享用例 {@code backend/crdp-followup/src/test/resources/shared-cases/compare-cases.json} 锁定逐条一致
 * （components.md §10.5）。 只在表单内提示：不发通知、不生成待办、不影响提交。
 *
 * <p>求值口径（hospital-a-spec 第 5 章「录入时对比提示规则」与迁移 V67 注释同源）：
 *
 * <ul>
 *   <li><b>上一次取值</b>：FOLLOWUP 取 {@code values[targetField]}；BASELINE（第 1 年或此前无 随访）取规则的回落基线列 {@code
 *       values[baselineField]}；previous 为 null 时对比类规则 全部跳过；
 *   <li><b>上一次该字段为空跳过</b>：DIFF / CHANGE_PCT / ORDINAL_RISE 需要上一次有值；FLIP_TO_YES
 *       上一次为空时仅「无对比对象（无基线回落列）」的规则生效（如本次「是否死亡」= 是）；
 *   <li><b>空值口径</b>：null / 缺键 / 空串为空（0 与 false 是有效值）；本次为空一律不命中；
 *   <li><b>边界符</b>：DIFF / ORDINAL_RISE ≥、CHANGE_PCT 严格 >、OUT_OF_RANGE / VALUE_HIT 按 op；
 *   <li><b>变化量文案</b>（前后端逐字一致，验收「标红字段旁显示对比值与变化量」）：数值两位小数内 原样、去尾零；单位 % 显示「个百分点」、其余单位前加空格；
 *   <li>命中顺序 = 规则入参顺序（落库按 sort_no，本类不排序）。
 * </ul>
 *
 * <p>结果持久化（fu_record.highlight）与规则变更重算随 E17-T04；C 的「同一器械 / 电极」取上次随 E21。
 */
public final class CompareEvaluator {

    private CompareEvaluator() {}

    /** 求值规则（CompareRuleRow 的求值子集：启用规则由调用方过滤；params 已解析）。 */
    public record EvalRule(
            String ruleCode,
            String categoryKey,
            String targetField,
            String ruleType,
            Map<String, Object> params,
            String baselineField,
            String unit,
            String message) {

        /** params 阈值（数值键；缺省 0——预置迁移已按 CompareRuleType 校验必填）。 */
        double paramNumber(String key) {
            if (params.get(key) instanceof Number number) {
                return number.doubleValue();
            }
            return 0d;
        }

        String paramString(String key) {
            return params.get(key) instanceof String text ? text : null;
        }

        List<String> paramStrings(String key) {
            if (params.get(key) instanceof List<?> list) {
                return list.stream().map(item -> String.valueOf(item)).toList();
            }
            return List.of();
        }
    }

    /** 上一次（E17-T02 previous 的求值子集；null = 无上一次且基线回落全空）。 */
    public record EvalPrevious(String kind, String date, Map<String, Object> values) {

        public static EvalPrevious none() {
            return new EvalPrevious(null, null, Map.of());
        }

        boolean isBaseline() {
            return "BASELINE".equals(kind);
        }
    }

    /**
     * 一条命中（FormRenderer highlight 事件的载荷；前后端同形）。
     *
     * @param previousValue 命中规则用到的上一次值（原样；固定阈值规则为 null）
     * @param change 有符号变化量（本次 − 上一次；序数规则为级数差；无变化量概念为 null）
     * @param changeText 变化量文案（「下降 11 个百分点」「新发」…；固定阈值规则为 null）
     */
    public record Hit(
            String ruleId,
            String key,
            String categoryKey,
            String message,
            Object previousValue,
            Double change,
            String changeText) {}

    /** 求值：命中列表（规则顺序；无规则 / 全不命中为空）。ordinals = 字段键 → 选项编码 → 序数。 */
    public static List<Hit> evaluate(
            List<EvalRule> rules,
            Map<String, Object> current,
            EvalPrevious previous,
            Map<String, Map<Integer, Integer>> ordinals) {
        List<Hit> hits = new ArrayList<>();
        if (rules == null) {
            return hits;
        }
        EvalPrevious prev = previous == null ? EvalPrevious.none() : previous;
        for (EvalRule rule : rules) {
            CompareRuleType type = CompareRuleType.fromCode(rule.ruleType()).orElse(null);
            if (type == null) {
                continue; // 未知类型：不参与求值（预置迁移约束，防御）
            }
            Hit hit =
                    switch (type) {
                        case DIFF -> diff(rule, current, prev);
                        case CHANGE_PCT -> changePct(rule, current, prev);
                        case ORDINAL_RISE -> ordinalRise(rule, current, prev, ordinals);
                        case NEW_EVENT -> newEvent(rule, current, prev);
                        case FLIP_TO_YES -> flipToYes(rule, current, prev);
                        case OUT_OF_RANGE -> outOfRange(rule, current);
                        case VALUE_HIT -> valueHit(rule, current);
                    };
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    // ---------------------------------------------------------------- 各类型

    /** 差值下降 / 上升：|本次 − 上一次| ≥ threshold（DROP 看 prev − cur，RISE 看 cur − prev）。 */
    private static Hit diff(EvalRule rule, Map<String, Object> current, EvalPrevious prev) {
        Double cur = numberOf(current.get(rule.targetField()));
        Double old = numberOf(previousValue(rule, prev));
        if (cur == null || old == null) {
            return null;
        }
        double delta = cur - old;
        String direction = rule.paramString("direction");
        boolean hit =
                "DROP".equals(direction)
                        ? -delta >= rule.paramNumber("threshold")
                        : "RISE".equals(direction) ? delta >= rule.paramNumber("threshold") : false;
        if (!hit) {
            return null;
        }
        String word = "DROP".equals(direction) ? "下降" : "增加";
        double rounded = round2(delta);
        return new Hit(
                rule.ruleCode(),
                rule.targetField(),
                rule.categoryKey(),
                rule.message(),
                previousValue(rule, prev),
                rounded,
                word + " " + formatNumber(Math.abs(rounded)) + unitSuffix(rule.unit()));
    }

    /**
     * 变化率：（本次 − 上一次）÷ 上一次 × 100，严格 > percent；上一次为 0 无效跳过。
     *
     * <p>参考注（E21-T20 / C-FU-010，可选键 noteField / noteText）：命中且 noteField 本次与上一次均为数值且不同时，
     * 提示文案追加「；noteText」（起搏阈值「脉宽不同，仅供参考」）；任一侧为空无法判断不同，不追加。
     */
    private static Hit changePct(EvalRule rule, Map<String, Object> current, EvalPrevious prev) {
        Double cur = numberOf(current.get(rule.targetField()));
        Double old = numberOf(previousValue(rule, prev));
        if (cur == null || old == null || old == 0d) {
            return null;
        }
        double pct = (cur - old) / old * 100d;
        String direction = rule.paramString("direction");
        boolean hit =
                switch (direction == null ? "" : direction) {
                    case "DROP" -> -pct > rule.paramNumber("percent");
                    case "RISE" -> pct > rule.paramNumber("percent");
                    case "ANY" -> Math.abs(pct) > rule.paramNumber("percent");
                    default -> false;
                };
        if (!hit) {
            return null;
        }
        String word =
                "RISE".equals(direction) || ("ANY".equals(direction) && pct > 0) ? "升高" : "下降";
        double rounded = round2(pct);
        return new Hit(
                rule.ruleCode(),
                rule.targetField(),
                rule.categoryKey(),
                messageWithNote(rule, current, prev),
                previousValue(rule, prev),
                rounded,
                word + " " + formatNumber(Math.abs(rounded)) + "%");
    }

    /** CHANGE_PCT 参考注：noteField 本次与上一次均为数值且不同 → 提示追加「；noteText」。 */
    private static String messageWithNote(
            EvalRule rule, Map<String, Object> current, EvalPrevious prev) {
        String noteField = rule.paramString("noteField");
        String noteText = rule.paramString("noteText");
        if (noteField == null || noteText == null) {
            return rule.message();
        }
        Double curNote = numberOf(current.get(noteField));
        Double prevNote = prev.values() == null ? null : numberOf(prev.values().get(noteField));
        if (curNote == null
                || prevNote == null
                || curNote.doubleValue() == prevNote.doubleValue()) {
            return rule.message();
        }
        return rule.message() + "；" + noteText;
    }

    /** 序数升级：序数差 ≥ grades，或本次取值 ∈ reachCodes（达到即标红）；任一侧序数未知跳过。 */
    private static Hit ordinalRise(
            EvalRule rule,
            Map<String, Object> current,
            EvalPrevious prev,
            Map<String, Map<Integer, Integer>> ordinals) {
        Integer curCode = optionCodeOf(current.get(rule.targetField()));
        Object prevRaw = previousValue(rule, prev);
        Integer prevCode = optionCodeOf(prevRaw);
        Map<Integer, Integer> ordinalMap =
                ordinals == null ? null : ordinals.get(rule.targetField());
        if (curCode == null || prevCode == null || ordinalMap == null) {
            return null;
        }
        Integer curOrdinal = ordinalMap.get(curCode);
        Integer prevOrdinal = ordinalMap.get(prevCode);
        if (curOrdinal == null || prevOrdinal == null) {
            return null;
        }
        int delta = curOrdinal - prevOrdinal;
        int grades = (int) rule.paramNumber("grades");
        boolean reach = rule.paramStrings("reachCodes").contains(String.valueOf(curCode));
        boolean hit = delta >= grades || reach;
        if (!hit) {
            return null;
        }
        return new Hit(
                rule.ruleCode(),
                rule.targetField(),
                rule.categoryKey(),
                rule.message(),
                prevRaw,
                (double) delta,
                delta >= grades ? "升高 " + delta + " 级" : "达到预设档位");
    }

    /** 新发事件：本次命中（triggerCodes；缺省 = 非空且编码 ≠ 0「无」），且上一次该事件为空 / 为无， 或事件日期晚于上一次随访日期（V67 注释口径）。 */
    private static Hit newEvent(EvalRule rule, Map<String, Object> current, EvalPrevious prev) {
        Object curRaw = current.get(rule.targetField());
        List<String> triggerCodes = rule.paramStrings("triggerCodes");
        boolean currentHit =
                !triggerCodes.isEmpty()
                        ? triggerCodes.contains(String.valueOf(optionCodeOf(curRaw)))
                        : isFilled(curRaw) && !isZero(curRaw);
        if (!currentHit) {
            return null;
        }
        Object prevRaw = previousValue(rule, prev);
        boolean previousEmpty = !isFilled(prevRaw) || isZero(prevRaw);
        boolean isNew;
        if (previousEmpty) {
            isNew = true; // 上一次未填 / 为「无」→ 本次即新发（Q41「或上一次未填 / 为无」）
        } else {
            String dateField = rule.paramString("dateField");
            String curDate = textOf(current.get(dateField));
            isNew =
                    dateField != null
                            && curDate != null
                            && prev.date() != null
                            && curDate.compareTo(prev.date()) > 0;
        }
        if (!isNew) {
            return null;
        }
        return new Hit(
                rule.ruleCode(),
                rule.targetField(),
                rule.categoryKey(),
                rule.message(),
                isFilled(prevRaw) ? prevRaw : null,
                null,
                "新发");
    }

    /** 由否变是：上一次为否且本次为是；上一次为空时仅无回落列的规则（本次 = 是即新发，如死亡）。 */
    private static Hit flipToYes(EvalRule rule, Map<String, Object> current, EvalPrevious prev) {
        if (!isYes(current.get(rule.targetField()))) {
            return null;
        }
        Object prevRaw = previousValue(rule, prev);
        if (!isFilled(prevRaw)) {
            // 有回落列却缺值：上一次为空跳过（不臆断新发）；无对比对象（如「是否死亡」）：本次 = 是即标红
            return rule.baselineField() == null ? hitOf(rule, null, "由否变是") : null;
        }
        return isNo(prevRaw) ? hitOf(rule, prevRaw, "由否变是") : null;
    }

    /** 超出提示范围：本次数值与 {op, value} 比较（与字段字典 hardRange 分开配置，不拦提交）。 */
    private static Hit outOfRange(EvalRule rule, Map<String, Object> current) {
        Double cur = numberOf(current.get(rule.targetField()));
        if (cur == null) {
            return null;
        }
        return compareOp(rule.paramString("op"), cur, rule.paramNumber("value"))
                ? hitOf(rule, null)
                : null;
    }

    /** 取值命中：codes（选项编码）或 {op, value} 二选一。 */
    private static Hit valueHit(EvalRule rule, Map<String, Object> current) {
        Object raw = current.get(rule.targetField());
        List<String> codes = rule.paramStrings("codes");
        if (!codes.isEmpty()) {
            Integer code = optionCodeOf(raw);
            return code != null && codes.contains(String.valueOf(code)) ? hitOf(rule, null) : null;
        }
        Double cur = numberOf(raw);
        return cur != null && compareOp(rule.paramString("op"), cur, rule.paramNumber("value"))
                ? hitOf(rule, null)
                : null;
    }

    private static Hit hitOf(EvalRule rule, Object previousValue) {
        return new Hit(
                rule.ruleCode(),
                rule.targetField(),
                rule.categoryKey(),
                rule.message(),
                previousValue,
                null,
                null);
    }

    private static Hit hitOf(EvalRule rule, Object previousValue, String changeText) {
        return new Hit(
                rule.ruleCode(),
                rule.targetField(),
                rule.categoryKey(),
                rule.message(),
                previousValue,
                null,
                changeText);
    }

    // ---------------------------------------------------------------- 取值与口径

    /** 上一次该规则的取值：FOLLOWUP 取 targetField；BASELINE 取回落基线列。 */
    private static Object previousValue(EvalRule rule, EvalPrevious prev) {
        if (prev.values() == null || prev.values().isEmpty()) {
            return null;
        }
        String key = prev.isBaseline() ? rule.baselineField() : rule.targetField();
        return key == null ? null : prev.values().get(key);
    }

    private static boolean compareOp(String op, double cur, double bound) {
        return switch (op == null ? "" : op) {
            case "LT" -> cur < bound;
            case "LTE" -> cur <= bound;
            case "GT" -> cur > bound;
            case "GTE" -> cur >= bound;
            default -> false;
        };
    }

    private static Double numberOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** 选项编码（单选 / 下拉存整数编码；整数 Number 直接取，小数取整）。 */
    private static Integer optionCodeOf(Object value) {
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        return null;
    }

    private static String textOf(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** 空值口径：null / 缺键 / 空串 / 空数组为空（0 与 false 有效）。 */
    private static boolean isFilled(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static boolean isZero(Object value) {
        return value instanceof Number number && number.doubleValue() == 0d;
    }

    /** 「是」口径：yesno 编码 1 / true。 */
    private static boolean isYes(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof Number number && number.doubleValue() == 1d;
    }

    /** 「否」口径：yesno 编码 0 / false。 */
    private static boolean isNo(Object value) {
        if (value instanceof Boolean flag) {
            return !flag;
        }
        return value instanceof Number number && number.doubleValue() == 0d;
    }

    /** 数值文案（前后端逐字一致）：四舍五入到两位小数去尾零 —— 整数显示整数、11.3 显示 11.3。 */
    static String formatNumber(double value) {
        double rounded = round2(value);
        if (rounded == Math.rint(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }

    /** 变化量按两位小数收敛（IEEE 尘埃 11.299…→ 11.3，与文案同口径；两端运算一致）。 */
    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /** 单位后缀：% 显示「个百分点」（EF 下降 10 个百分点），其余单位前加空格（mm / Ω…）。 */
    private static String unitSuffix(String unit) {
        if (unit == null || unit.isBlank()) {
            return "";
        }
        return "%".equals(unit) ? " 个百分点" : " " + unit;
    }
}
