package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T02 宽表流式解析测试：{@link WideLayoutAdapter#stream} 逐行回调（不整本入内存，暂存管道的接缝）—— 逐行产出与 {@link
 * WideLayoutAdapter#parse}（E18-T01 一次性模型）完全一致、整行空白跳过、结构性表头问题在任何行回调之前整体报错。
 */
class WideLayoutAdapterStreamingTest {

    private final WideLayoutAdapter adapter = new WideLayoutAdapter();

    @Test
    void streamEmitsSameRecordsAsParse() {
        ImportWorkbookData workbook =
                LayoutFixture.wideWorkbook(
                        LayoutFixture.wideHeader(2),
                        List.of(
                                LayoutFixture.row(
                                        LayoutFixture.wideHeader(2).size(),
                                        "1",
                                        "P001（示例）",
                                        "MRN001（示例）",
                                        "张三（示例）",
                                        "男",
                                        "60",
                                        "2020-01-10",
                                        "冠心病",
                                        "90%",
                                        "2020-01-10",
                                        "MRN001（示例）",
                                        "张三（示例）",
                                        "男",
                                        "60",
                                        "2021-02-11",
                                        "0",
                                        "",
                                        "55"
                                        // 第二组随访全空（row 补齐）→ 该组跳过
                                        ),
                                LayoutFixture.row(LayoutFixture.wideHeader(2).size())));
        List<ImportPatientRecord> streamed = new ArrayList<>();
        adapter.stream(workbook, LayoutFixture.catalog(), streamed::add);

        assertEquals(1, streamed.size());
        assertEquals(adapter.parse(workbook, LayoutFixture.catalog()).patients(), streamed);
    }

    @Test
    void streamKeepsRowNumbersAndCoordinates() {
        ImportWorkbookData workbook =
                LayoutFixture.wideWorkbook(
                        LayoutFixture.wideHeader(1),
                        List.of(
                                LayoutFixture.row(
                                        LayoutFixture.wideHeader(1).size(),
                                        "1",
                                        "P001（示例）",
                                        "MRN001（示例）",
                                        "张三（示例）"),
                                LayoutFixture.row(
                                        LayoutFixture.wideHeader(1).size(),
                                        "2",
                                        "",
                                        "MRN002（示例）",
                                        "李四（示例）")));
        List<ImportPatientRecord> streamed = new ArrayList<>();
        adapter.stream(workbook, LayoutFixture.catalog(), streamed::add);
        assertEquals(2, streamed.size());
        // 数据从工作表第 2 行起；单元格坐标保留（E18-T03 批注版报告用）
        assertEquals(2, streamed.get(0).rowNo());
        assertEquals(2, streamed.get(0).mrnCell().rowNo());
        // 基线列空白时回落前置列「病案号」（第 3 列，0 起下标 2）
        assertEquals(2, streamed.get(0).mrnCell().colNo());
        assertEquals(3, streamed.get(1).rowNo());
    }

    @Test
    void streamRejectsBadHeaderBeforeAnyRow() {
        List<String> brokenHeader = new ArrayList<>(LayoutFixture.wideHeader(1));
        brokenHeader.set(9, "对不上的表头");
        ImportWorkbookData workbook =
                LayoutFixture.wideWorkbook(
                        brokenHeader,
                        List.of(LayoutFixture.row(brokenHeader.size(), "1", "P001（示例）")));
        List<ImportPatientRecord> streamed = new ArrayList<>();
        ImportLayoutException failure =
                assertThrows(
                        ImportLayoutException.class,
                        () -> adapter.stream(workbook, LayoutFixture.catalog(), streamed::add));
        assertEquals(List.of(), streamed);
        assertEquals("宽表表头不匹配（1 处，整体报错不做猜测）", failure.getMessage());
    }
}
