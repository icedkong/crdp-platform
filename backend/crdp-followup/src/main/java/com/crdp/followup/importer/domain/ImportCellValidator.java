package com.crdp.followup.importer.domain;

import com.crdp.followup.importer.domain.ImportIssue.Level;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * E18-T03：单个导入单元格的字段级校验（tech-plan §12④ + A 规格 §4「校验报告中的常见问题」）。
 *
 * <p>输入是<strong>原始文本</strong>单元格（tech-plan §12「单元格一律按文本读入再转换」），按字段字典的 {@link ImportFieldRule}
 * 检查类型、硬 / 软范围、选项、必填，产出行 / 列无关的 {@link Finding}（服务层补坐标后成 {@link ImportIssue}）； 能转换的顺带产出类型化值（派生重算要用的
 * Number / ISO 日期 / 选项编码）。
 *
 * <p>口径（A 规格 §4「校验报告常见问题」表 + Q36 / Q37 已确认）：
 *
 * <ul>
 *   <li>数值：超出硬范围报错、超出提示范围（且未超硬范围）警告；小数位 0 的整数字段填小数报错；
 *   <li>日期 yyyymmdd / YYYY-MM-DD、日期时间 yyyymmddHHMM / YYYY-MM-DD HH:mm（或 T），非法日期报错
 *       （20251345、2025-7-18 都报错）；
 *   <li>选项：编码或显示名精确匹配合法；「编码 + 文字」（如 6颈动脉内剥）取编码导入并列警告（Q37）； 编码不在选项内报错；
 *   <li>是否类（编码 {0,1} × {是,否}）单元格填「0/1」：按空值导入并列警告供人工复核（Q36）；
 *   <li>必填字段为空：历史导入只报警告，完整度按未填计算（A 规格 §4「必填字段为空」行）；
 *   <li>数值 × 2：单元格写「长×宽」（× / x / X / * 分隔），两端各按范围检查；
 *   <li>派生字段不在单元格层校验（有原值时与重算结果比对属行级校验，以重算结果为准）。
 * </ul>
 *
 * <p>提示文案不回显敏感字段的取值（OQ-101③：问题列表只写非敏感文本）。
 */
public final class ImportCellValidator {

    /** 数值文本（十进制，可带符号与小数点；科学计数 / NaN / Infinity 不认）。 */
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    /** 数值 × 2：长 × 宽（× / x / X / * 分隔，OQ-102 暂行口径）。 */
    private static final Pattern PAIR =
            Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)\\s*[×xX*]\\s*([+-]?\\d+(?:\\.\\d+)?)");

    private static final Pattern COMPACT_DATE = Pattern.compile("\\d{8}");

    private static final Pattern COMPACT_DATETIME = Pattern.compile("\\d{12}");

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern ISO_DATETIME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}");

    /** 选项错误提示列举的合法取值上限（避免 761 列场景下错误信息过长）。 */
    private static final int OPTION_SUMMARY_LIMIT = 8;

    /** 多选分隔符（、 ， , ； ;）。 */
    private static final Pattern MULTI_SPLIT = Pattern.compile("[、，,；;]");

    private ImportCellValidator() {}

    /** 一个单元格的检查结果：findings 为空即通过；value 是能转换出的类型化值（转换失败为 null—— findings 已有报错），派生重算用它。 */
    public record Checked(List<Finding> findings, Object value) {

        static Checked of(List<Finding> findings, Object value) {
            return new Checked(List.copyOf(findings), value);
        }
    }

    /** 行 / 列无关的一条发现（服务层补 sheet / row / col / header 成 {@link ImportIssue}）。 */
    public record Finding(String code, Level level, String message) {}

    /** 检查一个单元格（cell 为空 = 该字段无单元格或空值：只可能产出必填警告）。 */
    public static Checked check(ImportFieldRule rule, ImportCell cell) {
        String text = cell == null ? null : cell.normalized();
        if (text == null) {
            if (rule.required()) {
                return Checked.of(
                        List.of(
                                new Finding(
                                        ImportIssue.CODE_REQUIRED_EMPTY,
                                        Level.WARN,
                                        "必填字段为空（历史导入仅提示，完整度按未填计算）")),
                        null);
            }
            return new Checked(List.of(), null);
        }
        List<Finding> findings = new ArrayList<>();
        Object value =
                switch (rule.type()) {
                    case NUMBER -> checkNumber(rule, text, findings, null);
                    case PAIR_NUMBER -> checkPair(rule, text, findings);
                    case DATE -> checkDate(rule, text, findings);
                    case DATETIME -> checkDatetime(rule, text, findings);
                    case SELECT, RADIO -> checkSingleOption(rule, text, findings);
                    case MULTI_SELECT -> checkMultiOption(rule, text, findings);
                    case BOOLEAN -> checkBoolean(rule, text, findings);
                    case TEXT, TEXTAREA -> text;
                    case DERIVED -> NUMBER.matcher(text).matches() ? Double.valueOf(text) : text;
                };
        return Checked.of(findings, value);
    }

    // ---------------------------------------------------------------- 数值

    private static Object checkNumber(
            ImportFieldRule rule, String text, List<Finding> findings, String part) {
        if (!NUMBER.matcher(text).matches()) {
            findings.add(typeFinding(rule, part, "数值" + unitText(rule)));
            return null;
        }
        double number = Double.parseDouble(text);
        if (rule.decimals() != null && rule.decimals() == 0 && number != Math.floor(number)) {
            findings.add(typeFinding(rule, part, "整数" + unitText(rule)));
        }
        checkRange(rule, text, number, findings, part);
        return number;
    }

    private static Object checkPair(ImportFieldRule rule, String text, List<Finding> findings) {
        java.util.regex.Matcher matcher = PAIR.matcher(text);
        if (!matcher.matches()) {
            findings.add(typeFinding(rule, null, "「长×宽」两个数值（如 24×31）"));
            return null;
        }
        Map<String, Object> pair = new LinkedHashMap<>();
        double length = Double.parseDouble(matcher.group(1));
        double width = Double.parseDouble(matcher.group(2));
        findings.addAll(rangeFindings(rule, "长", matcher.group(1), length));
        findings.addAll(rangeFindings(rule, "宽", matcher.group(2), width));
        pair.put("length", length);
        pair.put("width", width);
        return pair;
    }

    private static void checkRange(
            ImportFieldRule rule, String text, double number, List<Finding> findings, String part) {
        findings.addAll(rangeFindings(rule, part, text, number));
    }

    /** 硬 / 软范围（A 规格 §4：超出硬范围报错、超出合理范围警告；已报硬范围不再叠软范围警告）。 */
    private static List<Finding> rangeFindings(
            ImportFieldRule rule, String part, String text, double number) {
        List<Finding> findings = new ArrayList<>();
        if (out(number, rule.hardMin(), rule.hardMax())) {
            findings.add(
                    new Finding(
                            ImportIssue.CODE_HARD_RANGE,
                            Level.ERROR,
                            quote(rule, text)
                                    + "超出硬范围"
                                    + rangeText(rule.hardMin(), rule.hardMax())
                                    + unitText(rule)
                                    + "，不能导入"));
            return findings;
        }
        if (out(number, rule.softMin(), rule.softMax())) {
            findings.add(
                    new Finding(
                            ImportIssue.CODE_SOFT_RANGE,
                            Level.WARN,
                            quote(rule, text)
                                    + "超出提示范围"
                                    + rangeText(rule.softMin(), rule.softMax())
                                    + unitText(rule)
                                    + "，请人工复核"));
        }
        return findings;
    }

    private static boolean out(double number, Double min, Double max) {
        return (min != null && number < min) || (max != null && number > max);
    }

    private static String rangeText(Double min, Double max) {
        return " "
                + (min == null ? "−∞" : trim(min))
                + "–"
                + (max == null ? "+∞" : trim(max))
                + " ";
    }

    private static String trim(double number) {
        return number == Math.floor(number) && !Double.isInfinite(number)
                ? String.valueOf((long) number)
                : String.valueOf(number);
    }

    // ---------------------------------------------------------------- 日期 / 日期时间

    private static Object checkDate(ImportFieldRule rule, String text, List<Finding> findings) {
        LocalDate date = null;
        if (ISO_DATE.matcher(text).matches()) {
            date = parseDate(text);
        } else if (COMPACT_DATE.matcher(text).matches()) {
            date =
                    parseDate(
                            text.substring(0, 4)
                                    + "-"
                                    + text.substring(4, 6)
                                    + "-"
                                    + text.substring(6, 8));
        }
        if (date == null) {
            findings.add(typeFinding(rule, null, "日期（yyyymmdd 或 YYYY-MM-DD，如 20260315）"));
            return null;
        }
        return date.toString();
    }

    private static Object checkDatetime(ImportFieldRule rule, String text, List<Finding> findings) {
        LocalDateTime datetime = null;
        String normalized = null;
        if (ISO_DATETIME.matcher(text).matches()) {
            normalized = text.replace(' ', 'T');
        } else if (COMPACT_DATETIME.matcher(text).matches()) {
            normalized =
                    text.substring(0, 4)
                            + "-"
                            + text.substring(4, 6)
                            + "-"
                            + text.substring(6, 8)
                            + "T"
                            + text.substring(8, 10)
                            + ":"
                            + text.substring(10, 12);
        }
        if (normalized != null) {
            try {
                datetime =
                        LocalDateTime.parse(
                                normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            } catch (DateTimeParseException ex) {
                datetime = null;
            }
        }
        if (datetime == null) {
            findings.add(
                    typeFinding(
                            rule, null, "日期时间（yyyymmddHHMM 或 YYYY-MM-DD HH:mm，如 202603150830）"));
            return null;
        }
        return normalized;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 选项

    private static Object checkSingleOption(
            ImportFieldRule rule, String text, List<Finding> findings) {
        if (rule.options().isEmpty()) {
            findings.add(new Finding(ImportIssue.CODE_OPTION_MISS, Level.ERROR, "字段没有配置选项，不能导入取值"));
            return null;
        }
        for (Map.Entry<Integer, String> option : rule.options().entrySet()) {
            if (text.equals(option.getValue())) {
                return option.getKey(); // 显示名精确匹配（页面显示显示名，A-FLD-002）
            }
        }
        for (Map.Entry<Integer, String> option : rule.options().entrySet()) {
            if (text.equals(String.valueOf(option.getKey()))) {
                if (rule.isYesNo() && (option.getKey() == 0 || option.getKey() == 1)) {
                    // Q36：是否类单元格填「0/1」→ 按空值导入，列入报告供人工复核
                    findings.add(
                            new Finding(
                                    ImportIssue.CODE_BOOLEAN_01,
                                    Level.WARN,
                                    "是否类单元格填了「" + text + "」，按空值导入，请人工复核后补录（Q36）"));
                    return null;
                }
                return option.getKey();
            }
        }
        java.util.regex.Matcher codeText = Pattern.compile("^(\\d+)(\\D.*)$").matcher(text);
        if (codeText.matches()) {
            int code = Integer.parseInt(codeText.group(1));
            if (rule.options().containsKey(code)) {
                // Q37：编码 + 文字（如 6颈动脉内剥）→ 取编码导入、文字存备注，警告供人工复核
                findings.add(
                        new Finding(
                                ImportIssue.CODE_OPTION_CODE_TEXT,
                                Level.WARN,
                                "按编码 "
                                        + code
                                        + " 导入，文字「"
                                        + maskText(rule, codeText.group(2).strip())
                                        + "」将存入该字段备注，请人工复核（Q37）"));
                return code;
            }
        }
        findings.add(optionMiss(rule, text));
        return null;
    }

    private static Object checkMultiOption(
            ImportFieldRule rule, String text, List<Finding> findings) {
        List<Integer> codes = new ArrayList<>();
        for (String item : MULTI_SPLIT.split(text)) {
            String stripped = item.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            Object code = checkSingleOption(rule, stripped, findings);
            if (code instanceof Integer codeValue) {
                if (codes.contains(codeValue)) {
                    findings.add(typeFinding(rule, null, "选项编码数组（编码 " + codeValue + " 重复）"));
                } else {
                    codes.add(codeValue);
                }
            }
        }
        return codes.isEmpty() ? null : codes;
    }

    private static Finding optionMiss(ImportFieldRule rule, String text) {
        return new Finding(
                ImportIssue.CODE_OPTION_MISS,
                Level.ERROR,
                "取值"
                        + quote(rule, text)
                        + "不在字典选项内（可为："
                        + rule.optionSummary(OPTION_SUMMARY_LIMIT)
                        + (rule.options().size() > OPTION_SUMMARY_LIMIT ? " 等" : "")
                        + "）");
    }

    // ---------------------------------------------------------------- 是否类（无选项集兜底 0=否 / 1=是）

    private static Object checkBoolean(ImportFieldRule rule, String text, List<Finding> findings) {
        if ("是".equals(text)) {
            return Boolean.TRUE;
        }
        if ("否".equals(text)) {
            return Boolean.FALSE;
        }
        if ("0".equals(text) || "1".equals(text)) {
            findings.add(
                    new Finding(
                            ImportIssue.CODE_BOOLEAN_01,
                            Level.WARN,
                            "是否类单元格填了「" + text + "」，按空值导入，请人工复核后补录（Q36）"));
            return null;
        }
        findings.add(typeFinding(rule, null, "是否值（是 / 否）"));
        return null;
    }

    // ---------------------------------------------------------------- 文案

    private static Finding typeFinding(ImportFieldRule rule, String part, String expected) {
        String field = part == null ? "" : part + "应为";
        return new Finding(ImportIssue.CODE_TYPE, Level.ERROR, field + expected);
    }

    /** 引用取值：敏感字段不回显原文（OQ-101③），只说「该取值」。 */
    private static String quote(ImportFieldRule rule, String text) {
        return rule.sensitive() ? "该取值" : "「" + text + "」";
    }

    private static String maskText(ImportFieldRule rule, String text) {
        return rule.sensitive() ? "（略）" : text;
    }

    private static String unitText(ImportFieldRule rule) {
        return rule.unit() == null || rule.unit().isBlank() ? "" : "（单位 " + rule.unit() + "）";
    }
}
