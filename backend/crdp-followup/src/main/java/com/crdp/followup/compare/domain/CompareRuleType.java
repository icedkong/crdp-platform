package com.crdp.followup.compare.domain;

import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对比标红规则类型与 params 校验（E17-T01 / A-FU-027、C-FU-011；tech-plan §11 规则类型表）。
 *
 * <p>七个类型共用一张表（followup V67 {@code fu_compare_rule}）：对比字段恒为 {@code target_field}、 基线回落字段独立成列；{@code
 * params} 只放阈值类键（租户管理员可调，PUT 全量覆盖并按键集校验），字段引用与规则结构由预置迁移落库、 不开放改动（docs/api/followup.md §4：只做开关与调阈值）。
 *
 * <p>键集校验口径：必填键缺失、未知键、枚举值不合法、数值越界 → 40001（与前端 {@code packages/api} 的 compare-rule 同口径，后端为准）。
 */
public enum CompareRuleType {
    /** 差值下降 / 上升：{direction: DROP|RISE, threshold: 差值阈值}。A：EF 下降 ≥ 10 个百分点、LVDd 增加 ≥ 5 mm。 */
    DIFF(List.of("direction", "threshold"), Set.of()),

    /**
     * 变化率：{direction: DROP|RISE|ANY, percent: 百分比阈值, noteField?, noteText?}。C：阈值升高 > 50%、 阻抗升高或降低 >
     * 50%（ANY）；noteField / noteText 成对可选（E21-T20 / C-FU-010）：命中且该字段本次与上一次均为数值且不同时，
     * 提示追加「；noteText」（C 起搏阈值「脉宽不同，仅供参考」，components.md §10.4）。
     */
    CHANGE_PCT(List.of("direction", "percent"), Set.of("noteField", "noteText")),

    /** 序数升级：{grades: 升级级数, reachCodes?: 达到即标红的选项编码}。A：返流加重 ≥ 2 级或达到中量 / 大量。 */
    ORDINAL_RISE(List.of("grades"), Set.of("reachCodes")),

    /** 新发事件：{dateField?, triggerCodes?}（本次命中且日期晚于上一次随访；无阈值键，只可开关）。 */
    NEW_EVENT(List.of(), Set.of("dateField", "triggerCodes")),

    /** 由否变是：{}（无阈值键，只可开关）。A：新发心室血栓、本次「是否死亡」= 是。 */
    FLIP_TO_YES(List.of(), Set.of()),

    /** 超出提示范围：{op: LT|LTE|GT|GTE, value: 边界值}。A 默认 EF < 40、LVDd > 60、LA > 45。 */
    OUT_OF_RANGE(List.of("op", "value"), Set.of()),

    /** 取值命中：{codes} 或 {op, value} 二选一。C：ERI 状态非正常；预计电池寿命 ≤ 1 年。 */
    VALUE_HIT(List.of(), Set.of("codes", "op", "value"));

    /** 数值阈值公共口径（迁移 V67 注释同源）。 */
    private static final double POSITIVE_MAX = 9999d;

    private static final double VALUE_MAX = 10000d;

    private static final int MAX_DECIMALS = 2;

    private final List<String> requiredKeys;
    private final Set<String> optionalKeys;

    CompareRuleType(List<String> requiredKeys, Set<String> optionalKeys) {
        this.requiredKeys = requiredKeys;
        this.optionalKeys = optionalKeys;
    }

    /** 配置页展示名。 */
    public String label() {
        switch (this) {
            case DIFF:
                return "差值下降 / 上升";
            case CHANGE_PCT:
                return "变化率";
            case ORDINAL_RISE:
                return "序数升级";
            case NEW_EVENT:
                return "新发事件";
            case FLIP_TO_YES:
                return "由否变是";
            case OUT_OF_RANGE:
                return "超出提示范围";
            case VALUE_HIT:
                return "取值命中";
            default:
                return name();
        }
    }

    /** 按编码解析；未知编码返回空（调用方转 40001）。 */
    public static java.util.Optional<CompareRuleType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(valueOf(code.trim()));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }

    /**
     * PUT 阈值参数校验：键集 = 必填 ∪ 该类型可选（未知键即 40001，防止借 params 改规则结构）； 数值 / 枚举逐键校验。
     * 通过时原样返回（保持插入序），供落库与审计前后值使用。
     */
    public Map<String, Object> validateParams(Map<String, Object> params) {
        if (params == null) {
            throw invalid("params（阈值参数不能为空）");
        }
        for (String key : params.keySet()) {
            if (!requiredKeys.contains(key) && !optionalKeys.contains(key)) {
                throw invalid("params." + key + "（该规则类型不支持此键）");
            }
        }
        for (String key : requiredKeys) {
            if (!params.containsKey(key)) {
                throw invalid("params." + key + "（必填阈值键缺失）");
            }
        }
        switch (this) {
            case DIFF, CHANGE_PCT -> {
                requireDirection(params, this == CHANGE_PCT);
                if (this == DIFF) {
                    requirePositive(params.get("threshold"), "params.threshold");
                } else {
                    requirePositive(params.get("percent"), "params.percent");
                    requireNote(params);
                }
            }
            case ORDINAL_RISE -> {
                Integer grades = requireInteger(params.get("grades"), "params.grades");
                if (grades < 1 || grades > 5) {
                    throw invalid("params.grades（升级级数须为 1–5 的整数）");
                }
                requireStringList(params.get("reachCodes"), "params.reachCodes");
            }
            case NEW_EVENT -> {
                requireFieldKey(params.get("dateField"), "params.dateField");
                requireStringList(params.get("triggerCodes"), "params.triggerCodes");
            }
            case FLIP_TO_YES -> {
                // 无阈值键：只可开关
            }
            case OUT_OF_RANGE -> {
                requireOp(params);
                requireBounded(params.get("value"), "params.value");
            }
            case VALUE_HIT -> {
                boolean hasCodes = params.containsKey("codes");
                boolean hasBound = params.containsKey("op") || params.containsKey("value");
                if (hasCodes == hasBound) {
                    throw invalid("params（取值命中须二选一：codes 或 op + value）");
                }
                if (hasCodes) {
                    requireStringList(params.get("codes"), "params.codes");
                } else {
                    requireOp(params);
                    requireBounded(params.get("value"), "params.value");
                }
            }
        }
        return params;
    }

    private void requireDirection(Map<String, Object> params, boolean allowAny) {
        String direction = String.valueOf(params.get("direction"));
        boolean ok =
                "DROP".equals(direction)
                        || "RISE".equals(direction)
                        || (allowAny && "ANY".equals(direction));
        if (!ok) {
            throw invalid(
                    "params.direction（须为 " + (allowAny ? "DROP|RISE|ANY" : "DROP|RISE") + "）");
        }
    }

    private void requireOp(Map<String, Object> params) {
        String op = String.valueOf(params.get("op"));
        if (!List.of("LT", "LTE", "GT", "GTE").contains(op)) {
            throw invalid("params.op（须为 LT|LTE|GT|GTE）");
        }
    }

    /** CHANGE_PCT 参考注键（E21-T20 / C-FU-010）：noteField 与 noteText 成对出现或都省略；文案 ≤ 50 字。 */
    private static void requireNote(Map<String, Object> params) {
        boolean hasField = params.containsKey("noteField");
        boolean hasText = params.containsKey("noteText");
        if (hasField == hasText) {
            if (!hasField) {
                return; // 都省略：无参考注
            }
        } else {
            throw invalid("params.noteField / params.noteText（参考注键须成对出现）");
        }
        requireFieldKey(params.get("noteField"), "params.noteField");
        Object text = params.get("noteText");
        if (!(text instanceof String note) || note.isBlank()) {
            throw invalid("params.noteText（须为非空字符串）");
        }
        if (note.length() > 50) {
            throw invalid("params.noteText（最多 50 字）");
        }
    }

    private static void requirePositive(Object value, String field) {
        Double number = requireNumber(value, field);
        if (number <= 0 || number > POSITIVE_MAX) {
            throw invalid(field + "（须为大于 0 且不超过 " + (int) POSITIVE_MAX + " 的数值）");
        }
    }

    private static void requireBounded(Object value, String field) {
        Double number = requireNumber(value, field);
        if (Math.abs(number) > VALUE_MAX) {
            throw invalid(field + "（超出 ±" + (int) VALUE_MAX + " 范围）");
        }
    }

    private static Double requireNumber(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw invalid(field + "（须为数值）");
        }
        double raw = number.doubleValue();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            throw invalid(field + "（须为有限数值）");
        }
        double scaled = Math.round(raw * Math.pow(10, MAX_DECIMALS)) / Math.pow(10, MAX_DECIMALS);
        if (Math.abs(raw - scaled) > 1e-9) {
            throw invalid(field + "（最多 " + MAX_DECIMALS + " 位小数）");
        }
        return raw;
    }

    private static Integer requireInteger(Object value, String field) {
        if (!(value instanceof Integer number)) {
            throw invalid(field + "（须为整数）");
        }
        return number;
    }

    private static void requireFieldKey(Object value, String field) {
        if (value == null) {
            return;
        }
        if (!(value instanceof String key) || key.isBlank()) {
            throw invalid(field + "（须为非空字段键）");
        }
    }

    private static void requireStringList(Object value, String field) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw invalid(field + "（须为非空字符串数组；无此条件请省略该键）");
        }
        for (Object item : list) {
            if (!(item instanceof String code) || code.isBlank()) {
                throw invalid(field + "（数组元素须为非空字符串）");
            }
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
