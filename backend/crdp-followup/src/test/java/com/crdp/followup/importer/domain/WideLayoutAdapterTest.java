package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T01 / A-PAT-018 宽表版面适配器测试：识别 9 前置列 + 基线锚点列 + N 组 × 54 随访列的布局、 表头按字典顺序与锚点匹配、组位置定年度、全空组跳过、N/A
 * 与空白归一为空、前置列保留供冲突比对（序号 / 数据完整度丢弃）。 列数或表头对不上整体报错、不做猜测（A 规格 §4 宽表流程第 2 步）。全部虚构数据。
 */
class WideLayoutAdapterTest {

    private final WideLayoutAdapter adapter = new WideLayoutAdapter();

    /** 基线列 = 带锚点字段、按字典顺序匹配（含显示名 ≠ 原表列名的手术年龄 →「年龄」）。 */
    @Test
    void parsesFrontBaselineAndFollowupGroupsByAnchor() {
        // 列：9 前置 + 5 基线 + 2 组 × 4 随访
        List<String> header = LayoutFixture.wideHeader(2);
        List<String> data =
                LayoutFixture.row(
                        header.size(),
                        "1",
                        "L0001",
                        "X0001",
                        "张三（示例）",
                        "男",
                        "65",
                        "20260110",
                        "冠心病",
                        "90%",
                        "20260115", // f001 手术日期
                        "X0001", // f002 病案号（基线列为准）
                        "张三（示例）", // f003 姓名
                        "1", // f004 性别
                        "65", // f005 手术年龄（表头「年龄」）
                        "20260318", // 第 1 组 随访日期
                        "0", // 死亡
                        "N/A", // 死亡随访时间 → 空值
                        "45", // EF
                        "20270325", // 第 2 组 随访日期
                        "0",
                        "",
                        "47");
        ImportParseResult result =
                adapter.parse(
                        LayoutFixture.wideWorkbook(header, List.of(data)), LayoutFixture.catalog());

        assertEquals(ImportLayout.WIDE, result.layout());
        assertEquals(1, result.patients().size());
        ImportPatientRecord patient = result.patients().get(0);
        assertEquals("X0001", patient.mrn());
        assertEquals("L0001", patient.legacyNo());
        assertEquals("20260115", patient.baseline().get("f001").normalized());
        assertEquals("20260115", patient.baseline().get("f001").raw());
        assertEquals("X0001", patient.baseline().get("f002").normalized());
        assertEquals("65", patient.baseline().get("f005").normalized());
        assertNull(patient.baseline().get("f267"), "出生日期无锚点，不是宽表列");
        // 前置列保留（供 T03 比对前置列与基线列冲突）；序号、数据完整度丢弃
        assertEquals("张三（示例）", patient.frontColumns().get("姓名").normalized());
        assertEquals("20260110", patient.frontColumns().get("入院日期").normalized());
        assertNull(patient.frontColumns().get("序号"));
        assertNull(patient.frontColumns().get("数据完整度"));
        // 溯源：单元格坐标（第 2 行、0 起列号）与 Excel 列字母
        assertEquals(2, patient.baseline().get("f001").rowNo());
        assertEquals(9, patient.baseline().get("f001").colNo());
        assertEquals("J", patient.baseline().get("f001").columnLetters());
        // 第 1 组即第 1 年、第 2 组即第 2 年；N/A 的死亡随访时间不入值
        assertEquals(2, patient.followups().size());
        assertEquals(1, patient.followups().get(0).year());
        assertEquals("20260318", patient.followups().get(0).values().get("f214").normalized());
        assertNull(patient.followups().get(0).values().get("f217"));
        assertEquals(2, patient.followups().get(1).year());
        assertEquals("47", patient.followups().get(1).values().get("f257").normalized());
        assertTrue(result.unmatchedFollowups().isEmpty());
    }

    /** 全空的随访组跳过（不生成该年度）；整行空白也跳过。 */
    @Test
    void skipsAllBlankFollowupGroupsAndBlankRows() {
        List<String> header = LayoutFixture.wideHeader(3);
        List<String> onlyFirstYear =
                LayoutFixture.row(
                        header.size(),
                        "1",
                        "L0001",
                        "X0001",
                        "张三（示例）",
                        "男",
                        "65",
                        "20260110",
                        "冠心病",
                        "90%",
                        "20260115",
                        "X0001",
                        "张三（示例）",
                        "1",
                        "65",
                        "20260318",
                        "0",
                        "N/A",
                        "45",
                        "", // 第 2 组全空
                        "",
                        "",
                        "",
                        "", // 第 3 组全空
                        "",
                        "",
                        "");
        List<String> blank =
                LayoutFixture.row(
                        header.size(),
                        "3",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");
        ImportParseResult result =
                adapter.parse(
                        LayoutFixture.wideWorkbook(
                                header, List.of(onlyFirstYear, blank, onlyFirstYear)),
                        LayoutFixture.catalog());

        assertEquals(2, result.patients().size(), "整行空白跳过、有数据的行保留");
        assertEquals(1, result.patients().get(0).followups().size());
        assertEquals(1, result.patients().get(0).followups().get(0).year());
    }

    /** 病案号以基线列为准，基线列空白时回落前置列（两者不一致的警告随 E18-T03 校验报告）。 */
    @Test
    void resolvesMrnFromBaselineColumnWithFrontFallback() {
        List<String> header = LayoutFixture.wideHeader(1);
        List<String> baselineBlank =
                LayoutFixture.row(
                        header.size(),
                        "1",
                        "L0001",
                        "X0001", // 前置列病案号
                        "张三（示例）",
                        "男",
                        "65",
                        "20260110",
                        "冠心病",
                        "90%",
                        "20260115",
                        "", // 基线病案号空白 → 回落前置列
                        "张三（示例）",
                        "1",
                        "65",
                        "20260318",
                        "0",
                        "",
                        "45");
        ImportParseResult result =
                adapter.parse(
                        LayoutFixture.wideWorkbook(header, List.of(baselineBlank)),
                        LayoutFixture.catalog());

        assertEquals("X0001", result.patients().get(0).mrn());
    }

    /** 前置列表头错误：整体报错，指出列字母与应为的名字。 */
    @Test
    void rejectsWrongFrontHeader() {
        List<String> header = new ArrayList<>(LayoutFixture.wideHeader(1));
        header.set(0, "编号");
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.wideWorkbook(header, List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream()
                        .anyMatch(p -> p.contains("A") && p.contains("编号") && p.contains("序号")),
                "问题应指出第 1 列（A）实际「编号」应为「序号」：" + ex.problems());
    }

    /** 基线列表头对不上（显示名与锚点都不匹配）：整体报错，不做猜测。 */
    @Test
    void rejectsMismatchedBaselineHeaderByAnchorOrLabel() {
        List<String> header = new ArrayList<>(LayoutFixture.wideHeader(1));
        header.set(10, "出院日期"); // 第 11 列（K）应为「病案号」或其显示名
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.wideWorkbook(header, List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("K") && p.contains("出院日期")),
                "问题应指出第 11 列（K）表头「出院日期」：" + ex.problems());
    }

    /** 随访列组列数不是每组列数的整数倍 / 没有随访组：整体报错。 */
    @Test
    void rejectsWrongFollowupGroupColumnCount() {
        List<String> oneExtra = new ArrayList<>(LayoutFixture.wideHeader(2));
        oneExtra.add("多余列");
        ImportLayoutException extra =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.wideWorkbook(oneExtra, List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                extra.problems().stream().anyMatch(p -> p.contains("随访列") && p.contains("4")),
                "应报随访列组列数不匹配（每组 4 列）：" + extra.problems());

        List<String> noGroups = new ArrayList<>(LayoutFixture.wideHeader(0));
        ImportLayoutException none =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                adapter.parse(
                                        LayoutFixture.wideWorkbook(noGroups, List.of()),
                                        LayoutFixture.catalog()));
        assertTrue(
                none.problems().stream().anyMatch(p -> p.contains("随访列")),
                "没有随访列组也应整体报错：" + none.problems());
    }

    /** 多张工作表不是宽表（长表两张工作表在 ImportWorkbookParser 里分流，这里防御）。 */
    @Test
    void rejectsWorkbookWithMultipleSheets() {
        ImportWorkbookData twoSheets =
                new InMemoryImportWorkbook(
                        List.of(
                                new InMemoryImportSheet(
                                        "患者数据", List.of(LayoutFixture.wideHeader(1))),
                                new InMemoryImportSheet("说明", List.of(List.of("备注")))));
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () -> adapter.parse(twoSheets, LayoutFixture.catalog()));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("一张工作表")),
                ex.problems().toString());
    }
}
