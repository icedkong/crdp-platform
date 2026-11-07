package com.crdp.followup.importer.app;

import com.crdp.followup.importer.domain.ImportCell;
import java.util.List;
import java.util.Map;

/**
 * imp_row.payload_cipher 的明文 JSON 形态（E18-T02 写入、E18-T03 校验与批注报告读取，单一事实源）。
 *
 * <p>单元格载荷 {@link CellValue} 带<strong>行列坐标</strong>（v = 原文、r = 行号 1 起、c = 列号 0 起）——
 * 批注版校验报告（E18-T03，A-PAT-018 「校验报告逐个列出问题单元格（行、列、原值、原因）」）按它把问题钉回原单元格； 病案号 / 随访年度身份列同样带坐标（mrnCell /
 * yearCell，空单元格也保留坐标，供「病案号为空」「随访年度解析不了」定位）。 整行 JSON 含姓名 /
 * 病案号等敏感明文，落库前整体加密（purpose=imp-row-payload，OQ-101③），明文不落任何列。
 */
public final class ImportRowPayloads {

    private ImportRowPayloads() {}

    /** 单元格载荷：原文 + 行列坐标（批注定位用）。 */
    public record CellValue(String v, int r, int c) {

        public static CellValue of(ImportCell cell) {
            return cell == null ? null : new CellValue(cell.raw(), cell.rowNo(), cell.colNo());
        }

        /** 归一化文本（空白 / N/A → null），与 {@link ImportCell#normalized()} 同口径。 */
        public String normalized() {
            ImportCell cell = toCell();
            return cell.normalized();
        }

        /** 还原为领域单元格（校验共用同一归一口径）。 */
        public ImportCell toCell() {
            return new ImportCell(v, r, c);
        }
    }

    /** 宽表行（一行 = 一位患者：前置列 + 基线 + 各年度随访组；group = 第几组即第几年）。 */
    public record WideRow(
            String mrn,
            CellValue mrnCell,
            String legacyNo,
            Map<String, CellValue> front,
            Map<String, CellValue> baseline,
            List<FollowupGroup> followups) {}

    /** 宽表随访组载荷。 */
    public record FollowupGroup(
            Integer group, Integer year, String yearRaw, Map<String, CellValue> values) {}

    /** 长表「基线」行载荷。 */
    public record BaselineRow(
            String mrn, CellValue mrnCell, String legacyNo, Map<String, CellValue> baseline) {}

    /** 长表「随访记录」行载荷（yearCell 保留原文与坐标；year 解析不了为 null）。 */
    public record FollowupRow(
            String mrn,
            CellValue mrnCell,
            Integer year,
            String yearRaw,
            CellValue yearCell,
            Map<String, CellValue> values) {}
}
