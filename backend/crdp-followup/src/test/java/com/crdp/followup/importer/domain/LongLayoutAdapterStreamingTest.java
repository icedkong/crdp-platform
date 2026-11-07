package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T02 长表流式解析测试：基线行与随访行分开逐行回调（暂存按源表行入库，跨行「病案号 + 随访年度」的关联交给 校验阶段按盲索引做，不整本入内存）—— 基线行产出与 {@link
 * LongLayoutAdapter#parse}（E18-T01）的基线骨架一致、随访行与 parse 汇总的随访
 * 一致（含并入与未并入的行）、年度解析不了保留原文、表头问题在任何行回调之前整体报错。
 */
class LongLayoutAdapterStreamingTest {

    private final LongLayoutAdapter adapter = new LongLayoutAdapter();

    @Test
    void streamBaselineMatchesParseSkeletons() {
        // 随访表只有表头：parse 的患者没有可并入的随访行，基线骨架与流式产出可直接对比
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "2020-01-10",
                                        "MRN001（示例）",
                                        "张三（示例）",
                                        "男",
                                        "1970-01-01",
                                        "50",
                                        "P001（示例）"),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "",
                                        "",
                                        "",
                                        "",
                                        "",
                                        "",
                                        "")),
                        List.of(LayoutFixture.LONG_FOLLOWUP_HEADERS));
        List<ImportPatientRecord> baselines = new ArrayList<>();
        adapter.streamBaseline(workbook, LayoutFixture.catalog(), baselines::add);

        List<ImportPatientRecord> parsed =
                adapter.parse(workbook, LayoutFixture.catalog()).patients();
        assertEquals(1, baselines.size());
        assertEquals(parsed, baselines);
        assertEquals(2, baselines.get(0).rowNo());
        assertEquals("P001（示例）", baselines.get(0).legacyNo());
    }

    @Test
    void streamFollowupsMatchesAllFollowupRowsAttachedAndUnmatched() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "2020-01-10",
                                        "MRN001（示例）",
                                        "张三（示例）",
                                        "男",
                                        "1970-01-01",
                                        "50",
                                        "")),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "MRN001（示例）",
                                        "1",
                                        "2021-02-11",
                                        "0",
                                        "55"),
                                // 病案号在基线表不存在：仍按行产出（数据级问题留给校验报告）
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "MRN404（示例）",
                                        "2",
                                        "2022-03-12",
                                        "0",
                                        "56"),
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "",
                                        "",
                                        "",
                                        "",
                                        "")));
        List<ImportFollowup> followups = new ArrayList<>();
        adapter.streamFollowups(workbook, LayoutFixture.catalog(), followups::add);

        ImportParseResult parsed = adapter.parse(workbook, LayoutFixture.catalog());
        List<ImportFollowup> all = new ArrayList<>(parsed.patients().get(0).followups());
        all.addAll(parsed.unmatchedFollowups());
        assertEquals(2, followups.size());
        assertEquals(all, followups);
        assertEquals("MRN404（示例）", followups.get(1).mrnCell().normalized());
        assertEquals(3, followups.get(1).rowNo());
    }

    @Test
    void streamFollowupsKeepsUnparseableYearText() {
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "",
                                        "MRN001（示例）")),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "MRN001（示例）",
                                        "第 3 年",
                                        "2023-04-15",
                                        "0",
                                        "57")));
        List<ImportFollowup> followups = new ArrayList<>();
        adapter.streamFollowups(workbook, LayoutFixture.catalog(), followups::add);
        assertEquals(1, followups.size());
        assertNull(followups.get(0).year());
        assertEquals("第 3 年", followups.get(0).yearCell().normalized());
    }

    @Test
    void streamBaselineRejectsBadHeaderBeforeAnyRow() {
        List<String> broken = new ArrayList<>(LayoutFixture.LONG_BASELINE_HEADERS);
        broken.set(0, "手术日");
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(broken),
                        List.of(
                                LayoutFixture.LONG_FOLLOWUP_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_FOLLOWUP_HEADERS.size(),
                                        "MRN001（示例）",
                                        "1")));
        List<ImportPatientRecord> baselines = new ArrayList<>();
        ImportLayoutException failure =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.streamBaseline(
                                        workbook, LayoutFixture.catalog(), baselines::add));
        assertTrue(baselines.isEmpty());
        assertTrue(failure.getMessage().contains("基线"));
    }

    @Test
    void streamFollowupsRejectsMissingIdentityColumn() {
        // 「随访记录」缺固定列「随访年度」：整体报错，不做猜测
        ImportWorkbookData workbook =
                LayoutFixture.longWorkbook(
                        List.of(
                                LayoutFixture.LONG_BASELINE_HEADERS,
                                LayoutFixture.row(
                                        LayoutFixture.LONG_BASELINE_HEADERS.size(),
                                        "",
                                        "MRN001（示例）")),
                        List.of(
                                List.of("病案号", "随访日期", "是否死亡", "EF"),
                                LayoutFixture.row(4, "MRN001（示例）", "2021-02-11", "0", "55")));
        List<ImportFollowup> followups = new ArrayList<>();
        ImportLayoutException failure =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.streamFollowups(
                                        workbook, LayoutFixture.catalog(), followups::add));
        assertTrue(followups.isEmpty());
        assertTrue(failure.getMessage().contains("随访记录"));
    }
}
