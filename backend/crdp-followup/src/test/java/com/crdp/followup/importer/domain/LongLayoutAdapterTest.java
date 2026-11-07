package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T01 / A-PAT-008 长表版面适配器测试：「基线」（一个患者一行）与「随访记录」（一次随访一行， 病案号 +
 * 随访年度定位）两张工作表，按表头名匹配启用字段的显示名（停用字段不进模板）， 随访行并入对应患者、基线表中查不到病案号的随访行单列（报错随 E18-T03 校验报告）。全部虚构数据。
 */
class LongLayoutAdapterTest {

    private final LongLayoutAdapter adapter = new LongLayoutAdapter();

    /** 基线行 → 患者；随访行按病案号并入；原系统编号为可选固定列。 */
    @Test
    void parsesBaselineSheetAndAttachesFollowupRowsByMrn() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "20260115",
                                        "X0001",
                                        "张三（示例）",
                                        "1",
                                        "",
                                        "65",
                                        "L0001")),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X0001",
                                        "1",
                                        "20260318",
                                        "0",
                                        "45"),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X0001",
                                        "2",
                                        "20270325",
                                        "0",
                                        "47")));
        ImportParseResult result = adapter.parse(workbook, LayoutFixture.catalog());

        assertEquals(ImportLayout.LONG, result.layout());
        assertEquals(1, result.patients().size());
        ImportPatientRecord patient = result.patients().get(0);
        assertEquals("X0001", patient.mrn());
        assertEquals("L0001", patient.legacyNo());
        assertEquals("20260115", patient.baseline().get("f001").normalized());
        assertEquals("65", patient.baseline().get("f005").normalized());
        assertNull(patient.baseline().get("f267"), "出生日期列为空 → 不入值");
        assertTrue(patient.frontColumns().isEmpty(), "长表没有前置列");
        assertEquals(2, patient.followups().size());
        assertEquals(1, patient.followups().get(0).year());
        assertEquals("20260318", patient.followups().get(0).values().get("f214").normalized());
        assertEquals(2, patient.followups().get(1).year());
        assertEquals(2, patient.followups().get(0).rowNo());
        assertTrue(result.unmatchedFollowups().isEmpty());
    }

    /** 基线表中查不到病案号的随访行单列（校验报告报错随 E18-T03）；随访年度解析不了的行保留原文待报。 */
    @Test
    void collectsUnmatchedFollowupsAndUnparsableYear() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "20260115",
                                        "X0001",
                                        "张三（示例）",
                                        "1",
                                        "",
                                        "65",
                                        "")),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X9999",
                                        "1",
                                        "20260318",
                                        "0",
                                        "45"),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X0001",
                                        "第一年",
                                        "20270325",
                                        "0",
                                        "47"),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "",
                                        "2",
                                        "20280325",
                                        "0",
                                        "49")));
        ImportParseResult result = adapter.parse(workbook, LayoutFixture.catalog());

        assertEquals(1, result.patients().size());
        ImportPatientRecord patient = result.patients().get(0);
        assertEquals(1, patient.followups().size(), "随访年度解析不了的行并入患者（年度为空），报错随 T03");
        assertNull(patient.followups().get(0).year());
        assertEquals("第一年", patient.followups().get(0).yearCell().normalized());
        assertEquals(2, result.unmatchedFollowups().size(), "基线表查不到的病案号与病案号为空的行都单列");
        assertEquals("X9999", result.unmatchedFollowups().get(0).mrnCell().normalized());
        assertEquals(1, result.unmatchedFollowups().get(0).year());
        assertTrue(
                result.unmatchedFollowups().stream().anyMatch(f -> f.mrnCell().isBlank()),
                "病案号为空的随访行也无法归入患者");
    }

    /** 基线表同一病案号出现两行：两行都保留（重复报错随 T03），随访行并入第一行。 */
    @Test
    void keepsDuplicateBaselineRowsAndAttachesToFirst() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "20260115",
                                        "X0001",
                                        "张三（示例）",
                                        "1",
                                        "",
                                        "65",
                                        ""),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "20260120",
                                        "X0001",
                                        "李四（示例）",
                                        "2",
                                        "",
                                        "70",
                                        "")),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X0001",
                                        "1",
                                        "20260318",
                                        "0",
                                        "45")));
        ImportParseResult result = adapter.parse(workbook, LayoutFixture.catalog());

        assertEquals(2, result.patients().size());
        assertEquals(1, result.patients().get(0).followups().size());
        assertTrue(result.patients().get(1).followups().isEmpty());
    }

    /** 同一病案号 + 随访年度两行：都并入（校验报告标重复随 T03）。 */
    @Test
    void keepsDuplicateYearRows() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "20260115",
                                        "X0001",
                                        "张三（示例）",
                                        "1",
                                        "",
                                        "65",
                                        "")),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X0001",
                                        "1",
                                        "20260318",
                                        "0",
                                        "45"),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "X0001",
                                        "1",
                                        "20260320",
                                        "0",
                                        "46")));
        ImportParseResult result = adapter.parse(workbook, LayoutFixture.catalog());

        assertEquals(2, result.patients().get(0).followups().size());
        assertEquals(
                "20260320",
                result.patients().get(0).followups().get(1).values().get("f214").normalized());
    }

    /** 未知表头 / 缺字段表头 / 重复表头：整体报错，不做猜测。 */
    @Test
    void rejectsUnknownMissingOrDuplicatedHeaders() {
        List<String> unknown = new ArrayList<>(LayoutFixture.LONG_BASELINE_HEADERS);
        unknown.set(2, "曾用名");
        ImportLayoutException unknownEx =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.longWorkbook(List.of(unknown), List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                unknownEx.problems().stream().anyMatch(p -> p.contains("曾用名")),
                "未知表头应报错：" + unknownEx.problems());

        List<String> missing = new ArrayList<>(LayoutFixture.LONG_BASELINE_HEADERS);
        missing.remove("手术日期");
        ImportLayoutException missingEx =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.longWorkbook(List.of(missing), List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                missingEx.problems().stream().anyMatch(p -> p.contains("手术日期")),
                "缺字段表头应报错：" + missingEx.problems());

        List<String> duplicated = new ArrayList<>(LayoutFixture.LONG_BASELINE_HEADERS);
        duplicated.set(1, "姓名"); // 与第 3 列重复
        ImportLayoutException duplicateEx =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.longWorkbook(List.of(duplicated), List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                duplicateEx.problems().stream().anyMatch(p -> p.contains("重复")),
                "重复表头应报错：" + duplicateEx.problems());
    }

    /** 缺「基线」或「随访记录」工作表：整体报错。 */
    @Test
    void rejectsMissingSheets() {
        ImportWorkbookData onlyBaseline =
                new InMemoryImportWorkbook(
                        List.of(
                                new InMemoryImportSheet(
                                        "基线", List.of(LayoutFixture.LONG_BASELINE_HEADERS))));
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () -> adapter.parse(onlyBaseline, LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("随访记录")), ex.problems().toString());
    }
}
