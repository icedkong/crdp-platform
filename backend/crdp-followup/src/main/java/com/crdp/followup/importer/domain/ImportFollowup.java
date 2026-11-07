package com.crdp.followup.importer.domain;

import java.util.Map;

/**
 * E18-T01：解析后的一次随访（统一模型的患者子项，两种版面同形）。
 *
 * <p>宽表：随访年度按组的位置确定（第 N 组即第 N 年），没有「随访年度」单元格； 长表：「随访年度」列的值即年度，解析不了的保留原文（{@code year} 为 null，报错随
 * E18-T03 校验报告）。 值只收非空单元格（空白 / N/A 归一为空值，见 {@link ImportCell#normalized()}）。
 *
 * @param year 随访年度（第几年）；解析不了时为 null
 * @param yearCell 「随访年度」单元格（宽表为 null）
 * @param mrnCell 病案号单元格（宽表 = 患者病案号单元格；长表 = 该行「病案号」列）
 * @param rowNo 来源行号（工作表行号，批注 / 报告引用）
 * @param values 字段稳定键 → 非空单元格（LinkedHashMap，保持列顺序）
 */
public record ImportFollowup(
        Integer year,
        ImportCell yearCell,
        ImportCell mrnCell,
        int rowNo,
        Map<String, ImportCell> values) {}
