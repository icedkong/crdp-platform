package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T01 版面识别（ImportWorkbookParser）：「基线」+「随访记录」两张工作表 → 长表； 单张工作表 → 宽表；
 * 其余（空工作簿、三张工作表、只有一张长表工作表）整体报错不做猜测。
 */
class ImportWorkbookParserTest {

    @Test
    void detectsLongLayoutBySheetNames() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(LayoutFixture.LONG_BASELINE_HEADERS),
                        List.of(LayoutFixture.LONG_FOLLOWUP_HEADERS));
        assertEquals(ImportLayout.LONG, ImportWorkbookParser.detect(workbook));
    }

    @Test
    void detectsWideLayoutForSingleSheetWorkbook() {
        ImportWorkbookData workbook =
                LayoutFixture.wideWorkbook(LayoutFixture.wideHeader(1), List.of());
        assertEquals(ImportLayout.WIDE, ImportWorkbookParser.detect(workbook));
    }

    @Test
    void rejectsEmptyWorkbook() {
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                ImportWorkbookParser.parse(
                                        new InMemoryImportWorkbook(List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("工作表")), ex.problems().toString());
    }

    @Test
    void rejectsWorkbookWithOnlyOneLongSheet() {
        ImportWorkbookData onlyFollowup =
                new InMemoryImportWorkbook(
                        List.of(
                                new InMemoryImportSheet(
                                        "随访记录", List.of(LayoutFixture.LONG_FOLLOWUP_HEADERS))));
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () -> ImportWorkbookParser.parse(onlyFollowup, LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("基线")),
                "应指出缺少「基线」工作表：" + ex.problems());
    }

    @Test
    void rejectsThreeSheetWorkbookWithoutLongSheets() {
        ImportWorkbookData threeSheets =
                new InMemoryImportWorkbook(
                        List.of(
                                new InMemoryImportSheet("Sheet1", List.of(List.of("a"))),
                                new InMemoryImportSheet("Sheet2", List.of(List.of("b"))),
                                new InMemoryImportSheet("Sheet3", List.of(List.of("c")))));
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () -> ImportWorkbookParser.parse(threeSheets, LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("宽表") && p.contains("长表")),
                "应说明既不是宽表也不是长表：" + ex.problems());
    }
}
