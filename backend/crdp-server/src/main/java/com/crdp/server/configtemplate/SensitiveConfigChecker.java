package com.crdp.server.configtemplate;

import com.crdp.platform.configtemplate.ConfigCopyMode;
import com.crdp.platform.configtemplate.ConfigTableEntry;
import com.crdp.platform.configtemplate.ConfigTableRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置包敏感信息检查（E12-T03 / A-FLD-014，验收「含敏感信息的包被拦截」；tech-plan §6「导出前做敏感信息检查」）。
 *
 * <p>同一套规则用于两侧：
 *
 * <ul>
 *   <li><b>导出侧自检</b>（{@code ConfigTemplateExportService}）：建包后、落库前跑一遍——正常导出永远干净（白名单 + 行排除 + 用户引用置空），
 *       拦到的只可能是缺陷（如误登记表、排除谓词失效），缺陷要当场暴露而不是把敏感数据存进平台库；
 *   <li><b>导入侧拦截</b>（{@code ConfigTemplateImportService}）：应用前对包体跑一遍——包可能来自旧版本代码或被篡改，
 *       含敏感信息的包直接拒绝（40901），不进导入事务。
 * </ul>
 *
 * <p>五条规则（{@link Violation#rule()}）：{@value #RULE_FORBIDDEN_TABLE}（患者 / 业务数据 / 显式排除表——凭据、账号、审计等）；
 * {@value #RULE_UNREGISTERED_TABLE}（未在注册表标注的表，A-FLD-014「未标注的表一律不导出」）； {@value
 * #RULE_STRUCTURE_ROWS}（STRUCTURE_ONLY 表携带取值行）；{@value #RULE_EXCLUDED_ROW}（被行排除谓词剔除的行，如术者取值）；
 * {@value #RULE_SENSITIVE_VALUE}（疑似患者敏感值：大陆手机号、身份证号——身份证按 GB 11643 校验位验证，降低假阳性）。
 *
 * <p>用户引用列（created_by / updated_by / published_by）不在此拦截：tech-plan 的口径是「一律置空」——
 * 导出侧置空、导入侧同样置空（E12-T02 {@code prepareRow}），数字用户 ID 本身不构成患者数据。
 */
public final class SensitiveConfigChecker {

    /** 患者与业务数据表 / 显式排除表（含凭据、账号、审计）出现在包内。 */
    public static final String RULE_FORBIDDEN_TABLE = "FORBIDDEN_TABLE";

    /** 未在注册表标注的表（包与代码版本不一致或被篡改）。 */
    public static final String RULE_UNREGISTERED_TABLE = "UNREGISTERED_TABLE";

    /** 「只复制结构」表（术者、病区 / 中心）携带取值行。 */
    public static final String RULE_STRUCTURE_ROWS = "STRUCTURE_ROWS";

    /** 被行排除谓词命中的行（如术者字典取值）出现在包内。 */
    public static final String RULE_EXCLUDED_ROW = "EXCLUDED_ROW";

    /** 配置值里含疑似患者敏感值（手机号 / 身份证号）。 */
    public static final String RULE_SENSITIVE_VALUE = "SENSITIVE_VALUE";

    /** 大陆手机号：独立 11 位数字（前后不再紧跟数字，避免长编号 / 正则样例误报）。 */
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 身份证号 18 位（校验位单独验证）；15 位旧证不做（假阳性高、现网已淘汰）。 */
    private static final Pattern ID_CARD_18 =
            Pattern.compile("(?<![0-9Xx])\\d{17}[0-9Xx](?![0-9Xx])");

    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CHECK_CODES = {
        '1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'
    };

    /** 单条违规：表、规则、命中行数与详情（不含敏感明文，只报列名 / 行数）。 */
    public record Violation(String table, String rule, long hits, String detail) {}

    private SensitiveConfigChecker() {}

    /** 检查配置包；返回违规清单（空 = 干净）。 */
    public static List<Violation> check(ConfigPackage pkg) {
        List<Violation> violations = new ArrayList<>();
        if (pkg == null || pkg.tables() == null) {
            return violations;
        }
        for (ConfigPackage.TableSection section : pkg.tables()) {
            checkSection(section, violations);
        }
        return violations;
    }

    /** 汇总成错误消息（进 ApiException 与审计 reason，只含表名 / 规则 / 计数，不含数据明文）。 */
    public static String summarize(List<Violation> violations) {
        StringBuilder text = new StringBuilder();
        for (Violation violation : violations) {
            if (!text.isEmpty()) {
                text.append("；");
            }
            text.append(violationSummary(violation))
                    .append("[")
                    .append(violation.rule())
                    .append("]");
        }
        return text.toString();
    }

    private static String violationSummary(Violation v) {
        return switch (v.rule()) {
            case RULE_FORBIDDEN_TABLE ->
                    "表 %s 属患者 / 业务数据或显式排除表（不复制清单），不得出现在配置包内".formatted(v.table());
            case RULE_UNREGISTERED_TABLE -> "表 %s 未在配置表注册表标注（未标注的表一律不导不进）".formatted(v.table());
            case RULE_STRUCTURE_ROWS ->
                    "表 %s 只复制结构（术者、病区 / 中心），包内不得携带取值（%d 行）".formatted(v.table(), v.hits());
            case RULE_EXCLUDED_ROW ->
                    "表 %s 携带被排除规则剔除的行（如术者取值）：%s（%d 行）".formatted(v.table(), v.detail(), v.hits());
            case RULE_SENSITIVE_VALUE ->
                    "表 %s 配置值含疑似患者敏感值（手机号 / 证件号）：%s".formatted(v.table(), v.detail());
            default -> "表 %s 违反规则 %s".formatted(v.table(), v.rule());
        };
    }

    private static void checkSection(
            ConfigPackage.TableSection section, List<Violation> violations) {
        String table = section.table();
        if (ConfigTableRegistry.NEVER_EXPORT_TABLES.contains(table)) {
            violations.add(new Violation(table, RULE_FORBIDDEN_TABLE, 1, "不复制清单"));
            return;
        }
        ConfigTableEntry entry = ConfigTableRegistry.entry(table);
        if (entry == null) {
            violations.add(new Violation(table, RULE_UNREGISTERED_TABLE, 1, "未登记"));
            return;
        }
        List<Map<String, Object>> rows = section.rows() == null ? List.of() : section.rows();
        long carried = Math.max(section.rowCount(), rows.size());
        if (entry.mode() == ConfigCopyMode.STRUCTURE_ONLY) {
            if (carried > 0) {
                violations.add(
                        new Violation(table, RULE_STRUCTURE_ROWS, carried, "STRUCTURE_ONLY"));
            }
            return;
        }
        long excludedHits = 0;
        List<String> sensitiveColumns = new ArrayList<>();
        long sensitiveHits = 0;
        for (Map<String, Object> row : rows) {
            if (entry.rowExcluded(row)) {
                excludedHits++;
            }
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                if (cell.getValue() != null && looksSensitive(String.valueOf(cell.getValue()))) {
                    if (!sensitiveColumns.contains(cell.getKey())) {
                        sensitiveColumns.add(cell.getKey());
                    }
                    sensitiveHits++;
                }
            }
        }
        if (excludedHits > 0) {
            violations.add(
                    new Violation(
                            table,
                            RULE_EXCLUDED_ROW,
                            excludedHits,
                            String.join("、", entry.excludeWhen().keySet())));
        }
        if (sensitiveHits > 0) {
            violations.add(
                    new Violation(
                            table,
                            RULE_SENSITIVE_VALUE,
                            sensitiveHits,
                            "列 " + String.join("、", sensitiveColumns)));
        }
    }

    /** 值是否形似患者敏感值：独立手机号或校验位正确的身份证号。 */
    static boolean looksSensitive(String value) {
        if (value == null || value.length() < 11) {
            return false;
        }
        Matcher mobile = MOBILE.matcher(value);
        if (mobile.find()) {
            return true;
        }
        Matcher idCard = ID_CARD_18.matcher(value);
        while (idCard.find()) {
            if (idCardChecksumOk(idCard.group())) {
                return true;
            }
        }
        return false;
    }

    /** GB 11643 校验位：Σ(前 17 位 × 权重) mod 11 映射校验码。 */
    private static boolean idCardChecksumOk(String id) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int digit = id.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return false;
            }
            sum += digit * ID_WEIGHTS[i];
        }
        char expected = ID_CHECK_CODES[sum % 11];
        return Character.toUpperCase(id.charAt(17)) == expected;
    }
}
