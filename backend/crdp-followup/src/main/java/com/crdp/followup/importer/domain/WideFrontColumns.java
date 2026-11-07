package com.crdp.followup.importer.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * E18-T03：宽表前置列与基线列的一致性比对（A 规格 §4「医院现有宽表的列结构」前置列行、 「校验报告常见问题 · 前置列与基线列不一致」： 以基线列为准，不一致报警告）。
 *
 * <p>前置列里保留的 6 个身份 / 事实列按显示名对到基线字段（{@link WideLayoutAdapter#FRONT_COLUMNS} 的列名 → 基线字段显示名）；
 * 「诊断分类」对应基线「诊断信息」（A 规格 §4 原文），其余同名。序号忽略、数据完整度不导入（E18-T01 口径），不参与比对。
 *
 * <p>比对语义：文本精确相等，或两侧都能按<strong>该字段的类型</strong>转换后相等（数值 60 = 60.0、日期 20200110 = 2020-01-10）——
 * 同一事实的两种写法不算冲突。
 */
public final class WideFrontColumns {

    /** 前置列名 → 基线字段显示名（诊断分类 → 诊断信息为 A 规格 §4 明文对应，其余同名）。 */
    public static final Map<String, String> FRONT_TO_BASELINE_LABEL =
            Map.of(
                    "病案号", "病案号",
                    "姓名", "姓名",
                    "性别", "性别",
                    "手术年龄", "手术年龄",
                    "入院日期", "入院日期",
                    "诊断分类", "诊断信息");

    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    private WideFrontColumns() {}

    /** 前置列单元格与基线列单元格是否一致（任一侧为空 = 没得比，视为一致）。 */
    public static boolean consistent(ImportCell front, ImportCell baseline) {
        if (front == null || baseline == null || front.isBlank() || baseline.isBlank()) {
            return true;
        }
        String left = front.normalized();
        String right = baseline.normalized();
        if (left.equals(right)) {
            return true;
        }
        LocalDate leftDate = date(left);
        LocalDate rightDate = date(right);
        if (leftDate != null && rightDate != null) {
            return leftDate.equals(rightDate);
        }
        if (NUMBER.matcher(left).matches() && NUMBER.matcher(right).matches()) {
            return Double.parseDouble(left) == Double.parseDouble(right);
        }
        return false;
    }

    /** 两种合法日期写法（yyyymmdd / YYYY-MM-DD，与 {@link ImportCellValidator} 同口径）。 */
    private static LocalDate date(String text) {
        String iso =
                text.matches("\\d{8}")
                        ? text.substring(0, 4)
                                + "-"
                                + text.substring(4, 6)
                                + "-"
                                + text.substring(6, 8)
                        : text;
        if (!iso.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
