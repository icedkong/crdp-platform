package com.crdp.followup.importer.domain;

/**
 * E18-T01：导入单元格——原始文本 + 工作表坐标（校验报告「行、列、原值、原因」与批注 Excel 要用，A-PAT-018）。
 *
 * <p>所有单元格一律按文本读入（tech-plan §12「流式解析与转换」）；类型 / 范围 / 选项转换与校验属 E18-T03， 这里只做版面无关的归一：{@link
 * #normalized()} 把空白与「N/A」归为 null（tech-plan §12 单元格转换表「N/A、空白 → 空值」）。
 *
 * @param raw 原始文本（空单元格补 ""，不传 null）
 * @param rowNo 工作表行号（1 起：表头 = 1、数据从 2 起）
 * @param colNo 列号（0 起的数字下标，与表头位置对应）
 */
public record ImportCell(String raw, int rowNo, int colNo) {

    public ImportCell {
        raw = raw == null ? "" : raw;
    }

    /** 归一化文本：去掉首尾空白后，空串与「N/A」为 null（空值），其余为去空白文本。 「N/A」按规格原文精确匹配，不做大小写变体猜测。 */
    public String normalized() {
        String text = raw.strip();
        return text.isEmpty() || "N/A".equals(text) ? null : text;
    }

    /** 是否为空值（空白或 N/A）。 */
    public boolean isBlank() {
        return normalized() == null;
    }

    /** Excel 列字母（0 起下标：0=A、25=Z、26=AA……），校验报告按「行、列字母」引用单元格。 */
    public String columnLetters() {
        return columnLetters(colNo);
    }

    /** 列下标 → Excel 列字母（0 起：0=A、25=Z、26=AA）。 */
    public static String columnLetters(int colNo) {
        int index = colNo + 1;
        StringBuilder letters = new StringBuilder();
        while (index > 0) {
            index--;
            letters.insert(0, (char) ('A' + index % 26));
            index /= 26;
        }
        return letters.toString();
    }
}
