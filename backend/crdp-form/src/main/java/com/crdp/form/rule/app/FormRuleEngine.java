package com.crdp.form.rule.app;

import com.crdp.form.field.domain.FieldType;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.infra.mapper.RuleRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * E11-T04 / A-FLD-003、A-FLD-006、C-FLD-007：类型值之上的<b>范围与跨字段校验</b>（类型闸门是 {@link
 * com.crdp.form.record.app.FormDataValidator}，先于本引擎执行）；E11-T03 / A-FLD-004、C-FLD-005 追加<b>条件必填</b>与
 * <b>隐藏字段掩蔽</b>。
 *
 * <p>与前端 {@code packages/rules} 的 {@code validateFormData} 同语义（前后端校验一致），共用用例锁定： {@code
 * backend/crdp-form/src/test/resources/shared-cases/form-validation-cases.json}（components.md
 * §10.5）。
 *
 * <ul>
 *   <li>范围：number 与 pair_number（length / width 各查同一范围）按 fe_field 的 hard_range / soft_range
 *       检查——超硬范围不可保存（A-FLD-003「超出硬范围不可保存」）、超软范围提示可保存；硬范围已命中时不叠加软提示。 派生字段不查（E11-T05 按定义重算），空值（null /
 *       缺键 / 分量缺失）跳过。
 *   <li>跨字段：fe_rule 的 COMPARE 规则，语义「leftField op rightField 须成立」（op = lt / lte），值按 tech-plan §6
 *       值编码口径比较——数值比大小、日期 YYYY-MM-DD 与日期时间 YYYY-MM-DDTHH:mm 比先后（日期按当日 00:00 归一，分钟 /
 *       秒两种写法等价）；一侧为空或不可比（数值 × 日期、普通文本）不触发。HARD 违反即拦截（form.md §2 失败 42200），SOFT 只提示（C-FLD-007
 *       建议范围语义）。
 *   <li>条件必填（E11-T03）：有效必填 = 字段显示（visibleWhen 求值）&&（required || requiredWhen 求值）；命中而值为空 → errors 带
 *       {@code rule=required}（文案「必填」，与渲染器 §10.3 同词）。只查 storage=json 且非派生的字段（引擎只校验记录 JSON；实体 /
 *       加密列必填随业务接口 E13，派生随 E11-T05 重算）。
 *   <li>隐藏字段掩蔽（E11-T03）：visibleWhen 不成立字段的值不参与范围与跨字段（tech-plan §6「提交时清空」——值残留按已清空口径校验）； 清空动作本身由
 *       {@code FormService.stripHiddenFields} 在提交流程执行（写字段历史随 E11-T06）。
 * </ul>
 *
 * <p>提示文案与前端逐字一致：范围两端都有用「取值应在 min–max 之间」，只有一端用「取值不能小于 min」/「取值不能大于 max」；跨字段文案直接用
 * fe_rule.message（前后端同源）。
 */
@Component
public class FormRuleEngine {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATETIME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?");

    private final ObjectMapper json = new ObjectMapper();
    private final FormConditionEngine conditionEngine;

    public FormRuleEngine(FormConditionEngine conditionEngine) {
        this.conditionEngine = conditionEngine;
    }

    /**
     * 提交 / 保存修改前的完整规则执行（form.md §2「后端再执行一次」）：errors 拦截、warnings 提示。无上下文（context = 空）：ctx
     * 叶子全部不成立（与前端缺 context 同口径）。
     */
    public RuleResult validate(
            Map<String, Object> data, List<VersionFieldRow> fields, List<RuleRow> rules) {
        return validate(data, fields, rules, Map.of());
    }

    /**
     * 同 {@link #validate(Map, List, List)}，带条件上下文（C 的 device.type 等，由业务侧传入，E21-T03）。 检查顺序：范围 → 条件必填
     * → 跨字段（errors 顺序前后端一致，共享用例锁定）。
     */
    public RuleResult validate(
            Map<String, Object> data,
            List<VersionFieldRow> fields,
            List<RuleRow> rules,
            Map<String, Object> context) {
        Map<String, Object> values = data == null ? Map.of() : data;
        List<FieldIssue> errors = new ArrayList<>();
        List<FieldIssue> warnings = new ArrayList<>();
        List<String> hidden = conditionEngine.hiddenFieldKeys(fields, values, context);
        Map<String, Object> effective = maskHidden(values, hidden);
        checkRanges(effective, fields, errors, warnings);
        checkRequired(values, fields, context, hidden, errors);
        checkCrossFields(effective, rules, errors, warnings);
        return new RuleResult(List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * 草稿写入闸门的硬范围检查（A-FLD-003「超出硬范围不可保存」；tech-plan §6「写入只能经 FormService，按元数据做
     * 类型、范围与选项校验」）。草稿语义：不含软提示与跨字段（form.md §2），软范围与跨字段在提交时再查。
     */
    public List<FieldIssue> rangeErrors(Map<String, Object> data, List<VersionFieldRow> fields) {
        List<FieldIssue> errors = new ArrayList<>();
        checkRanges(data == null ? Map.of() : data, fields, errors, new ArrayList<>());
        return List.copyOf(errors);
    }

    // ---------------------------------------------------------------- 条件必填（E11-T03 / A-FLD-004）

    /**
     * 有效必填缺失 → errors（{@code rule=required}，文案「必填」与渲染器 §10.3 同词，前后端逐字一致）。 只查 storage=json
     * 且非派生的显示中字段；隐藏字段永不必填（值提交时清空、不计完整度，A-PAT-019）。
     */
    private void checkRequired(
            Map<String, Object> data,
            List<VersionFieldRow> fields,
            Map<String, Object> context,
            List<String> hidden,
            List<FieldIssue> errors) {
        for (VersionFieldRow field : fields) {
            if (!"json".equals(field.storageType())
                    || FieldType.DERIVED.code().equals(field.type())) {
                continue; // 实体 / 加密列必填随业务接口（E13）；派生字段由 E11-T05 重算
            }
            if (hidden.contains(field.fieldKey())) {
                continue;
            }
            if (!conditionEngine.requiredNow(field, data, context)) {
                continue;
            }
            if (FormConditionEngine.isEmptyValue(data.get(field.fieldKey()))) {
                errors.add(new FieldIssue(field.fieldKey(), field.categoryKey(), "required", "必填"));
            }
        }
    }

    /** 隐藏字段的值从校验视角移除（残留值按已清空口径：不查范围、不参与跨字段比较）。 */
    private static Map<String, Object> maskHidden(Map<String, Object> data, List<String> hidden) {
        if (hidden.isEmpty()) {
            return data;
        }
        Map<String, Object> effective = new java.util.LinkedHashMap<>(data);
        for (String key : hidden) {
            effective.remove(key);
        }
        return effective;
    }

    // ---------------------------------------------------------------- 范围（硬 / 软）

    private void checkRanges(
            Map<String, Object> data,
            List<VersionFieldRow> fields,
            List<FieldIssue> errors,
            List<FieldIssue> warnings) {
        for (VersionFieldRow field : fields) {
            FieldType type = FieldType.ofCode(field.type()).orElse(null);
            if (type != FieldType.NUMBER && type != FieldType.PAIR_NUMBER) {
                continue; // 文本 / 日期 / 选项 / 派生不走范围（派生由 E11-T05 重算）
            }
            Range hard = parseRange(field.hardRange());
            Range soft = parseRange(field.softRange());
            if (hard == null && soft == null) {
                continue;
            }
            for (Double value : numericValues(type, data.get(field.fieldKey()))) {
                if (hard != null && !hard.contains(value)) {
                    errors.add(
                            new FieldIssue(
                                    field.fieldKey(),
                                    field.categoryKey(),
                                    "hardRange",
                                    hard.message()));
                } else if (soft != null && !soft.contains(value)) {
                    warnings.add(
                            new FieldIssue(
                                    field.fieldKey(),
                                    field.categoryKey(),
                                    "softRange",
                                    soft.message()));
                }
            }
        }
    }

    /** number 取自身；pair_number 取 length / width 中已填的分量（形状错误交给类型闸门，这里防御跳过）。 */
    private static List<Double> numericValues(FieldType type, Object value) {
        List<Double> values = new ArrayList<>();
        if (type == FieldType.NUMBER) {
            addFinite(value, values);
            return values;
        }
        if (value instanceof Map<?, ?> pair) {
            addFinite(pair.get("length"), values);
            addFinite(pair.get("width"), values);
        }
        return values;
    }

    private static void addFinite(Object value, List<Double> out) {
        if (value instanceof Number number && !(value instanceof Boolean)) {
            double raw = number.doubleValue();
            if (Double.isFinite(raw)) {
                out.add(raw);
            }
        }
    }

    private Range parseRange(String raw) {
        JsonNode node = readTree(raw);
        if (node == null || !node.isObject()) {
            return null;
        }
        Double min = node.hasNonNull("min") ? node.get("min").asDouble() : null;
        Double max = node.hasNonNull("max") ? node.get("max").asDouble() : null;
        if (min == null && max == null) {
            return null;
        }
        return new Range(min, max);
    }

    /** 数值范围：闭区间；文案与前端 validateFormData 逐字一致（含整数不带小数点的数字格式化）。 */
    private record Range(Double min, Double max) {

        boolean contains(double value) {
            return (min == null || value >= min) && (max == null || value <= max);
        }

        String message() {
            if (min != null && max != null) {
                return "取值应在 " + format(min) + "–" + format(max) + " 之间";
            }
            if (min != null) {
                return "取值不能小于 " + format(min);
            }
            return "取值不能大于 " + format(max);
        }

        /** 整数不带小数点（50.0 → 50），小数按原样（2.5 → 2.5）——与 JS Number.prototype.toString 一致。 */
        private static String format(double value) {
            return value == Math.floor(value) && !Double.isInfinite(value)
                    ? String.valueOf((long) value)
                    : String.valueOf(value);
        }
    }

    // ---------------------------------------------------------------- 跨字段（fe_rule COMPARE）

    private void checkCrossFields(
            Map<String, Object> data,
            List<RuleRow> rules,
            List<FieldIssue> errors,
            List<FieldIssue> warnings) {
        for (RuleRow rule : rules) {
            if (!"COMPARE".equals(rule.ruleType())) {
                continue; // 后续规则类型（E11-T05 派生、E17 对比）不在此处理
            }
            CompareParams params = parseParams(rule.params());
            if (params == null) {
                continue;
            }
            Integer comparison =
                    compare(data.get(params.leftField()), data.get(params.rightField()));
            if (comparison == null) {
                continue; // 一侧为空或不可比：不触发
            }
            boolean holds = "lte".equals(params.op()) ? comparison <= 0 : comparison < 0;
            if (holds) {
                continue;
            }
            FieldIssue issue =
                    new FieldIssue(
                            rule.targetField(), rule.categoryKey(), rule.ruleId(), rule.message());
            if ("SOFT".equals(rule.severity())) {
                warnings.add(issue);
            } else {
                errors.add(issue);
            }
        }
    }

    /** {@code {leftField, op(lt|lte), rightField}}；残缺或 op 不认识时返回 null（防御跳过，不炸保存）。 */
    private CompareParams parseParams(String raw) {
        JsonNode node = readTree(raw);
        if (node == null) {
            return null;
        }
        String leftField = text(node, "leftField");
        String rightField = text(node, "rightField");
        String op = text(node, "op");
        if (leftField == null || rightField == null || !("lt".equals(op) || "lte".equals(op))) {
            return null;
        }
        return new CompareParams(leftField, op, rightField);
    }

    private record CompareParams(String leftField, String op, String rightField) {}

    /** 比较两个值（返回负 / 零 / 正）：数值比大小；日期 / 日期时间按时间先后（日期归一为当日 00:00，秒可省略）； 数值 × 日期、普通文本等不可比返回 null。 */
    static Integer compare(Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        boolean leftNumber = left instanceof Number && !(left instanceof Boolean);
        boolean rightNumber = right instanceof Number && !(right instanceof Boolean);
        if (leftNumber && rightNumber) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        if (leftNumber != rightNumber) {
            return null; // 数值与日期不可比
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            String leftKey = temporalKey(leftText);
            String rightKey = temporalKey(rightText);
            if (leftKey == null || rightKey == null) {
                return null; // 非日期 / 日期时间写法不比较（格式由类型闸门拦截）
            }
            return leftKey.compareTo(rightKey);
        }
        return null;
    }

    /** 日期 / 日期时间 → 可字典序比较的归一键 {@code yyyy-MM-dd'T'HH:mm:ss}（日期 = 当日 00:00，分钟写法补秒）。 */
    private static String temporalKey(String raw) {
        if (DATE.matcher(raw).matches()) {
            try {
                return LocalDate.parse(raw) + "T00:00:00";
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (DATETIME.matcher(raw).matches()) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(raw.length() == 16 ? raw + ":00" : raw);
                return parsed.format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw);
            return node.isNull() ? null : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("表单规则 JSON 解析失败", e);
        }
    }
}
