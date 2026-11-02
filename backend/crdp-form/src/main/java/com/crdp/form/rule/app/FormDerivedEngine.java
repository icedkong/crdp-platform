package com.crdp.form.rule.app;

import com.crdp.form.field.domain.FieldType;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * E11-T05 / A-FLD-005、C-FLD-006：<b>内置派生函数库</b>（tech-plan §6「派生字段」，首期只用内置函数加参数，不开放任意脚本）—— 9 个函数在
 * Java 与 TypeScript 各一份实现，共用标准用例锁定一致（验收「BMI、年龄等派生字段两端一致」）：
 *
 * <ul>
 *   <li>{@code DAYS_INCLUSIVE(起, 止)}：止 − 起 + 1（含当天；止 ≥ 起才计算）——A 术后住院天数。
 *   <li>{@code MINUTES_BETWEEN(起, 止)}：分钟差向下取整——A 手术时长 min。
 *   <li>{@code HOURS_BETWEEN_1DP(起, 止)}：小时差保留 1 位小数（160 小时 54 分 → 160.9）——A ICU 时间 h。
 *   <li>{@code BMI(体重 kg, 身高 cm)}：kg ÷ (cm ÷ 100)²，1 位小数——A BMI（身高 175 体重 70 → 22.9）。
 *   <li>{@code AGE_YEARS(出生, 参考日?)}：周岁；参考日缺省按今天（C-FLD-006 / Q13「年龄按今天计周岁」）——A 手术年龄、C 年龄。
 *   <li>{@code SUM(...)}：数值求和（整数来源保持整数）——A 桥血管数量（动脉桥 + 静脉桥）。
 *   <li>{@code YEARS_SINCE(起, 止?)}：整周年数（差一天不满即少一年，不设上限）——A 随访年限（止 = 死亡日期或今天）。
 *   <li>{@code DURATION_YM(起, 止?)}：{@code X 年 Y 个月}（完整年 + 完整月，日未到减月）——C 植入时长（按当前器械）。
 *   <li>{@code CHANGE_PCT(本次, 上次)}：（本次 − 上次）÷ 上次 × 100，1 位小数——C 参数变化率（上次为 0 无效）。
 * </ul>
 *
 * <p>与前端 {@code packages/rules} 的 {@code computeDerivedValues} 同语义，共用用例锁定： {@code
 * shared-cases/form-derived-cases.json}（components.md §10.5）。口径：
 *
 * <ul>
 *   <li><b>留空与提示</b>（A-FLD-005「输入缺失或结果无效时留空并提示」）：来源缺失（null / 缺键 / 空串，与条件 DSL 空值同口径 OQ-65
 *       ①；不可解析按缺失防御）→ status=missing、message「缺少{显示名}」（多个用「、」连接，按参数顺序）；来源无效（止早于起、身高 ≤ 0、上次为 0）→
 *       status=invalid、message「{止}早于{起}」等（显示名取来源字段 label，缺省止日提示「今天」）——文案前后端逐字一致。
 *   <li><b>依赖拓扑</b>（tech-plan §6「依赖图由派生定义自动生成；按拓扑顺序重算」）：派生可以依赖派生（如变化率依赖桥血管数量），
 *       求值按依赖图拓扑顺序；被依赖方来源缺失时取其实际生效值（手工值），与存储口径一致。
 *   <li><b>防御</b>（与条件 / 规则引擎坏配置同口径，OQ-68）：未知函数、参数个数不符、坏 JSON、依赖成环的字段<b>不出结果</b>（不炸保存、不产出误导值）。
 *   <li><b>写入闸门</b>（{@link #applyToWrite}，A-FLD-005）：来源齐全 → 计算值覆盖（含旧手工值，「来源齐全后以计算值为准」）； 来源缺失 →
 *       保留手工值（「只有来源字段缺失时允许手工录入」）；结果无效 → 不允许手工录入（errors 带 {@code rule=derived}， 提示与留空原因同源，应先修正来源字段）。
 * </ul>
 */
@Component
public class FormDerivedEngine {

    /** 业务日期按北京时间计算（AGENTS.md §6）。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATETIME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?");

    private final ObjectMapper json = new ObjectMapper();

    /** 求值状态：ok 已算出 / missing 来源缺失（留空并提示）/ invalid 来源无效（留空并提示）。 */
    public enum DerivedStatus {
        OK("ok"),
        MISSING("missing"),
        INVALID("invalid");

        private final String code;

        DerivedStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 一个派生字段的求值结果：value 为 null 即留空（missing / invalid），message 为留空原因（ok 为 null，文案前后端逐字一致）。 */
    public record DerivedOutcome(Object value, DerivedStatus status, String message) {

        static DerivedOutcome ok(Object value) {
            return new DerivedOutcome(value, DerivedStatus.OK, null);
        }

        static DerivedOutcome empty(DerivedStatus status, String message) {
            return new DerivedOutcome(null, status, message);
        }
    }

    /** 写入闸门结果：合并后的 data（原 map 不动）与无效手工值的 errors（42200 fieldErrors 口径）。 */
    public record DerivedWrite(Map<String, Object> data, List<FieldIssue> errors) {}

    /** 解析后的派生定义（fe_field.derived_def：{fn, args}，args 为字段稳定键）。 */
    private record DerivedDef(String fn, List<String> args) {}

    // ---------------------------------------------------------------- 重算（拓扑顺序）

    /** 重算全部派生字段（today 缺省 = 北京时间当天）；结果按字段列表顺序，只含正常配置的派生字段。 */
    public Map<String, DerivedOutcome> recalculate(
            List<VersionFieldRow> fields, Map<String, Object> data) {
        return recalculate(fields, data, LocalDate.now(ZONE));
    }

    /**
     * 同 {@link #recalculate(List, Map)}，带固定参考日（today）——AGE_YEARS / YEARS_SINCE / DURATION_YM 的止日缺省
     * （或止字段为空）时按它计算（测试与导入重算传固定日期保证确定性；生产为北京时间当天）。
     */
    public Map<String, DerivedOutcome> recalculate(
            List<VersionFieldRow> fields, Map<String, Object> data, LocalDate today) {
        Evaluation evaluation = new Evaluation(fields, data);
        for (String key : evaluation.topologicalOrder()) {
            DerivedOutcome outcome = evaluate(evaluation.defs.get(key), evaluation, today);
            if (outcome != null) {
                evaluation.computed.put(key, outcome); // 未知函数 → null：不出结果（防御，OQ-68）
            }
        }
        Map<String, DerivedOutcome> outcomes = new LinkedHashMap<>();
        for (VersionFieldRow field : fields) {
            DerivedOutcome outcome = evaluation.computed.get(field.fieldKey());
            if (outcome != null) {
                outcomes.put(field.fieldKey(), outcome); // 成环 / 坏定义跳过 → 不产出
            }
        }
        return outcomes;
    }

    // ---------------------------------------------------------------- 写入闸门（A-FLD-005）

    /**
     * 写入闸门口径（FormService.createRecord / saveRecord 调用，tech-plan §6「后端保存时再算一遍并以后端结果为准」）： ok →
     * 计算值覆盖（「来源齐全后以计算值为准」）；missing → 保留手工值（「只有来源字段缺失时允许手工录入」）； invalid → 手工值存在即 errors（{@code
     * rule=derived}，message 与留空原因同源；「结果无效不允许手工录入，应先修正来源字段」）。 防御跳过（未知函数 / 坏定义 / 成环）的字段不覆盖，历史数据原样保留。
     */
    public DerivedWrite applyToWrite(List<VersionFieldRow> fields, Map<String, Object> data) {
        Map<String, DerivedOutcome> outcomes = recalculate(fields, data);
        Map<String, Object> merged = new LinkedHashMap<>(data == null ? Map.of() : data);
        List<FieldIssue> errors = new ArrayList<>();
        for (VersionFieldRow field : fields) {
            DerivedOutcome outcome = outcomes.get(field.fieldKey());
            if (outcome == null) {
                continue; // 非派生字段或防御跳过的坏定义
            }
            switch (outcome.status()) {
                case OK -> merged.put(field.fieldKey(), outcome.value());
                case MISSING -> {
                    // 保留手工值（data_meta.manual 由录入侧标记，E25 / E15）
                }
                case INVALID -> {
                    if (!FormConditionEngine.isEmptyValue(merged.get(field.fieldKey()))) {
                        errors.add(
                                new FieldIssue(
                                        field.fieldKey(),
                                        field.categoryKey(),
                                        "derived",
                                        outcome.message()));
                    }
                }
            }
        }
        return new DerivedWrite(merged, List.copyOf(errors));
    }

    // ---------------------------------------------------------------- 求值

    private DerivedOutcome evaluate(DerivedDef def, Evaluation evaluation, LocalDate today) {
        return switch (def.fn()) {
            case "DAYS_INCLUSIVE" -> daysInclusive(def, evaluation);
            case "MINUTES_BETWEEN" -> minutesBetween(def, evaluation, false);
            case "HOURS_BETWEEN_1DP" -> minutesBetween(def, evaluation, true);
            case "BMI" -> bmi(def, evaluation);
            case "AGE_YEARS" -> ageYears(def, evaluation, today);
            case "SUM" -> sum(def, evaluation);
            case "YEARS_SINCE" -> yearsSince(def, evaluation, today);
            case "DURATION_YM" -> durationYm(def, evaluation, today);
            case "CHANGE_PCT" -> changePct(def, evaluation);
            default -> null; // 未知函数：不出结果（topologicalOrder 已过滤，防御兜底）
        };
    }

    /** DAYS_INCLUSIVE(起, 止)：止 − 起 + 1（含当天）；止早于起无效。 */
    private DerivedOutcome daysInclusive(DerivedDef def, Evaluation evaluation) {
        LocalDate start = evaluation.dateArg(def.args().get(0));
        LocalDate end = evaluation.dateArg(def.args().get(1));
        List<String> missing = evaluation.missingLabels(def.args(), start, end);
        if (!missing.isEmpty()) {
            return DerivedOutcome.empty(DerivedStatus.MISSING, missingMessage(missing));
        }
        if (end.isBefore(start)) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID,
                    evaluation.label(def.args().get(1))
                            + "早于"
                            + evaluation.label(def.args().get(0)));
        }
        return DerivedOutcome.ok(ChronoUnit.DAYS.between(start, end) + 1);
    }

    /** MINUTES_BETWEEN(起, 止)（分钟向下取整）/ HOURS_BETWEEN_1DP(起, 止)（小时 1 位小数）；止早于起无效。 */
    private DerivedOutcome minutesBetween(DerivedDef def, Evaluation evaluation, boolean hours) {
        LocalDateTime start = evaluation.dateTimeArg(def.args().get(0));
        LocalDateTime end = evaluation.dateTimeArg(def.args().get(1));
        List<String> missing = evaluation.missingLabels(def.args(), start, end);
        if (!missing.isEmpty()) {
            return DerivedOutcome.empty(DerivedStatus.MISSING, missingMessage(missing));
        }
        if (end.isBefore(start)) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID,
                    evaluation.label(def.args().get(1))
                            + "早于"
                            + evaluation.label(def.args().get(0)));
        }
        long minutes = Duration.between(start, end).toMinutes(); // 向下取整（秒截断）
        return DerivedOutcome.ok(hours ? round1(minutes / 60.0) : minutes);
    }

    /** BMI(体重 kg, 身高 cm)：kg ÷ (cm ÷ 100)²，1 位小数；身高 ≤ 0 无效。 */
    private DerivedOutcome bmi(DerivedDef def, Evaluation evaluation) {
        Double weight = evaluation.numberArg(def.args().get(0));
        Double height = evaluation.numberArg(def.args().get(1));
        List<String> missing = evaluation.missingLabels(def.args(), weight, height);
        if (!missing.isEmpty()) {
            return DerivedOutcome.empty(DerivedStatus.MISSING, missingMessage(missing));
        }
        if (height <= 0) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID, evaluation.label(def.args().get(1)) + "必须大于 0");
        }
        return DerivedOutcome.ok(round1(weight / Math.pow(height / 100.0, 2)));
    }

    /** AGE_YEARS(出生, 参考日?)：周岁（生日未到减一）；参考日缺省或为空按今天。 */
    private DerivedOutcome ageYears(DerivedDef def, Evaluation evaluation, LocalDate today) {
        LocalDate birth = evaluation.dateArg(def.args().get(0));
        if (birth == null) {
            return DerivedOutcome.empty(
                    DerivedStatus.MISSING,
                    missingMessage(List.of(evaluation.label(def.args().get(0)))));
        }
        Reference end = evaluation.referenceArg(def, today);
        if (end.date().isBefore(birth)) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID,
                    end.label() + "早于" + evaluation.label(def.args().get(0)));
        }
        return DerivedOutcome.ok((long) span(birth, end.date())[0]);
    }

    /** SUM(...)：数值求和；任一来源缺失即留空并提示；全部为整数时保持整数。 */
    private DerivedOutcome sum(DerivedDef def, Evaluation evaluation) {
        List<Double> numbers = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean allIntegral = true;
        for (String arg : def.args()) {
            Double number = evaluation.numberArg(arg);
            if (number == null) {
                missing.add(evaluation.label(arg));
            } else {
                numbers.add(number);
                allIntegral &= number == Math.floor(number);
            }
        }
        if (!missing.isEmpty()) {
            return DerivedOutcome.empty(DerivedStatus.MISSING, missingMessage(missing));
        }
        double total = numbers.stream().mapToDouble(Double::doubleValue).sum();
        return DerivedOutcome.ok(allIntegral ? (long) total : total);
    }

    /** YEARS_SINCE(起, 止?)：整周年数（差一天不满即少一年）；止缺省或为空按今天（A 随访年限 = 止日 − 出院日期）。 */
    private DerivedOutcome yearsSince(DerivedDef def, Evaluation evaluation, LocalDate today) {
        LocalDate start = evaluation.dateArg(def.args().get(0));
        if (start == null) {
            return DerivedOutcome.empty(
                    DerivedStatus.MISSING,
                    missingMessage(List.of(evaluation.label(def.args().get(0)))));
        }
        Reference end = evaluation.referenceArg(def, today);
        if (end.date().isBefore(start)) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID,
                    end.label() + "早于" + evaluation.label(def.args().get(0)));
        }
        return DerivedOutcome.ok((long) span(start, end.date())[0]);
    }

    /** DURATION_YM(起, 止?)：{@code X 年 Y 个月}（完整年 + 完整月，日未到减月）；止缺省或为空按今天。 */
    private DerivedOutcome durationYm(DerivedDef def, Evaluation evaluation, LocalDate today) {
        LocalDate start = evaluation.dateArg(def.args().get(0));
        if (start == null) {
            return DerivedOutcome.empty(
                    DerivedStatus.MISSING,
                    missingMessage(List.of(evaluation.label(def.args().get(0)))));
        }
        Reference end = evaluation.referenceArg(def, today);
        if (end.date().isBefore(start)) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID,
                    end.label() + "早于" + evaluation.label(def.args().get(0)));
        }
        int[] span = span(start, end.date());
        return DerivedOutcome.ok(span[0] + " 年 " + span[1] + " 个月");
    }

    /** CHANGE_PCT(本次, 上次)：（本次 − 上次）÷ 上次 × 100，1 位小数；上次为 0 无效。 */
    private DerivedOutcome changePct(DerivedDef def, Evaluation evaluation) {
        Double current = evaluation.numberArg(def.args().get(0));
        Double previous = evaluation.numberArg(def.args().get(1));
        List<String> missing = evaluation.missingLabels(def.args(), current, previous);
        if (!missing.isEmpty()) {
            return DerivedOutcome.empty(DerivedStatus.MISSING, missingMessage(missing));
        }
        if (previous == 0) {
            return DerivedOutcome.empty(
                    DerivedStatus.INVALID, evaluation.label(def.args().get(1)) + "为 0，不能计算变化率");
        }
        return DerivedOutcome.ok(round1((current - previous) / previous * 100));
    }

    // ---------------------------------------------------------------- 共用口径

    /**
     * 保留 1 位小数：{@code Math.round(v × 10) ÷ 10}——Java 与 JS 的 Math.round 同为「半值向 +∞」，双精度运算 IEEE-754
     * 确定性一致（两端逐位相同，共享用例锁定；不用 BigDecimal 的十进制 HALF_UP，避免两端舍入口径分叉）。
     */
    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 完整年 + 完整月（日未到减月，跨年借位）：[years, months]。 */
    static int[] span(LocalDate start, LocalDate end) {
        int years = end.getYear() - start.getYear();
        int months = end.getMonthValue() - start.getMonthValue();
        if (end.getDayOfMonth() < start.getDayOfMonth()) {
            months--;
        }
        if (months < 0) {
            months += 12;
            years--;
        }
        return new int[] {years, months};
    }

    private static String missingMessage(List<String> labels) {
        return "缺少" + String.join("、", labels);
    }

    /** 止日参数（AGE_YEARS 的参考日 / YEARS_SINCE / DURATION_YM 的止日）：字段值优先，缺省或为空按今天（label =「今天」）。 */
    private record Reference(LocalDate date, String label) {}

    /** 一次重算的求值上下文：字段索引、数据、派生定义与已算结果。 */
    private static final class Evaluation {

        private final Map<String, VersionFieldRow> byKey = new LinkedHashMap<>();
        private final Map<String, Object> data;
        private final Map<String, DerivedDef> defs = new LinkedHashMap<>();
        private final Map<String, DerivedOutcome> computed = new LinkedHashMap<>();

        Evaluation(List<VersionFieldRow> fields, Map<String, Object> data) {
            this.data = data == null ? Map.of() : data;
            for (VersionFieldRow field : fields) {
                byKey.put(field.fieldKey(), field);
                if (FieldType.DERIVED.code().equals(field.type())) {
                    DerivedDef def = parseDef(field);
                    if (def != null) {
                        defs.put(field.fieldKey(), def);
                    }
                }
            }
        }

        /** 依赖图拓扑顺序（派生依赖派生先算；成环节点排除——防御跳过）；字段列表顺序保证稳定。 */
        List<String> topologicalOrder() {
            Map<String, Integer> indegree = new LinkedHashMap<>();
            Map<String, List<String>> dependents = new LinkedHashMap<>();
            for (Map.Entry<String, DerivedDef> entry : defs.entrySet()) {
                indegree.putIfAbsent(entry.getKey(), 0);
                for (String arg : entry.getValue().args()) {
                    if (defs.containsKey(arg) && !arg.equals(entry.getKey())) {
                        indegree.merge(entry.getKey(), 1, Integer::sum);
                        dependents
                                .computeIfAbsent(arg, key -> new ArrayList<>())
                                .add(entry.getKey());
                    }
                }
            }
            List<String> order = new ArrayList<>();
            List<String> ready =
                    indegree.entrySet().stream()
                            .filter(entry -> entry.getValue() == 0)
                            .map(Map.Entry::getKey)
                            .toList();
            for (String key : ready) {
                visit(key, indegree, dependents, order);
            }
            return order; // 未访问到的即成环或依赖成环 → 跳过
        }

        private void visit(
                String key,
                Map<String, Integer> indegree,
                Map<String, List<String>> dependents,
                List<String> order) {
            order.add(key);
            for (String dependent : dependents.getOrDefault(key, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    visit(dependent, indegree, dependents, order);
                }
            }
        }

        /**
         * 参数的实际生效值：普通字段取数据；派生依赖取已算结果（ok → 计算值；missing → 手工值生效；invalid → 空， 写入闸门本就拒绝其手工值；成环跳过 → 空）。
         */
        Object effective(String key) {
            if (!defs.containsKey(key)) {
                return data.get(key);
            }
            DerivedOutcome outcome = computed.get(key);
            if (outcome == null) {
                return null;
            }
            return switch (outcome.status()) {
                case OK -> outcome.value();
                case MISSING -> data.get(key);
                case INVALID -> null;
            };
        }

        LocalDate dateArg(String key) {
            Object value = effective(key);
            if (!(value instanceof String text) || !DATE.matcher(text).matches()) {
                return null;
            }
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                return null; // 不可解析按缺失防御（类型闸门上游已拦）
            }
        }

        /** 日期时间（YYYY-MM-DDTHH:mm[:ss]）；日期写法按当日 00:00 归一（与跨字段比较同口径）。 */
        LocalDateTime dateTimeArg(String key) {
            Object value = effective(key);
            if (!(value instanceof String text)) {
                return null;
            }
            try {
                if (DATE.matcher(text).matches()) {
                    return LocalDate.parse(text).atStartOfDay();
                }
                if (DATETIME.matcher(text).matches()) {
                    return LocalDateTime.parse(text.length() == 16 ? text + ":00" : text);
                }
            } catch (DateTimeParseException e) {
                return null;
            }
            return null;
        }

        Double numberArg(String key) {
            Object value = effective(key);
            if (value instanceof Number number && !(value instanceof Boolean)) {
                double raw = number.doubleValue();
                return Double.isFinite(raw) ? raw : null;
            }
            return null;
        }

        /** 止日参数：args 第 2 位（可省）；字段值可解析用字段值，否则按今天（label 用字段显示名或「今天」）。 */
        Reference referenceArg(DerivedDef def, LocalDate today) {
            if (def.args().size() > 1) {
                LocalDate fieldDate = dateArg(def.args().get(1));
                if (fieldDate != null) {
                    return new Reference(fieldDate, label(def.args().get(1)));
                }
            }
            return new Reference(today, "今天");
        }

        String label(String key) {
            VersionFieldRow field = byKey.get(key);
            return field == null ? key : field.label();
        }

        /** 需求参数里取不到值（缺失 / 不可解析）的显示名清单（按参数顺序）。 */
        @SafeVarargs
        final List<String> missingLabels(List<String> args, Object... resolved) {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < resolved.length && i < args.size(); i++) {
                if (resolved[i] == null) {
                    missing.add(label(args.get(i)));
                }
            }
            return missing;
        }

        /** {fn, args} 解析与参数个数校验（个数不符 / 坏 JSON → null：不出结果，OQ-68 防御）。 */
        private static DerivedDef parseDef(VersionFieldRow field) {
            String raw = field.derivedDef();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            JsonNode node;
            try {
                node = new ObjectMapper().readTree(raw);
            } catch (JsonProcessingException e) {
                return null;
            }
            if (!node.isObject()) {
                return null;
            }
            String fn = node.path("fn").asText(null);
            JsonNode argsNode = node.path("args");
            if (fn == null || fn.isBlank() || !argsNode.isArray() || argsNode.isEmpty()) {
                return null;
            }
            List<String> args = new ArrayList<>();
            for (JsonNode arg : argsNode) {
                String key = arg.asText(null);
                if (key == null || key.isBlank()) {
                    return null;
                }
                args.add(key);
            }
            int size = args.size();
            boolean arity =
                    switch (fn) {
                        case "DAYS_INCLUSIVE",
                                "MINUTES_BETWEEN",
                                "HOURS_BETWEEN_1DP",
                                "BMI",
                                "CHANGE_PCT" ->
                                size == 2;
                        case "AGE_YEARS", "YEARS_SINCE", "DURATION_YM" -> size == 1 || size == 2;
                        case "SUM" -> true; // ≥ 1（argsNode.isEmpty 已保证）
                        default -> true; // 未知函数放行到求值处统一不出结果
                    };
            return arity ? new DerivedDef(fn, List.copyOf(args)) : null;
        }
    }
}
