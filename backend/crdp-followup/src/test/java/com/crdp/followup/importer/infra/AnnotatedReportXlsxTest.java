package com.crdp.followup.importer.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.infra.AnnotatedReportXlsx.Annotation;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.CellRef;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.Mark;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.SheetSpec;
import com.crdp.framework.excel.StreamingXlsxGateway;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * E18-T03 批注版校验报告的 xlsx 形态测试（tasks.csv 验收「错误单元格带批注返回」）： 错误 / 警告单元格标色 + legacy 批注（comments 部件 + VML
 * 形状 + 工作表关系与 legacyDrawing 引用）， 包结构完整（Content_Types / rels），且框架流式网关能读回同一张表（与上传文件同一读取口径）。
 */
class AnnotatedReportXlsxTest {

    @Test
    void writesErrorCellWithCommentAndFill() throws IOException {
        byte[] xlsx =
                AnnotatedReportXlsx.write(
                        List.of(
                                new SheetSpec(
                                        "患者数据",
                                        List.of("病案号", "EF", "随访日期"),
                                        Map.of(
                                                2, cellRow(0, "X0001（示例）", 1, "150", 2, "20251345"),
                                                3, cellRow(0, "X0002（示例）", 1, "73")),
                                        Map.of(
                                                new CellRef(2, 1),
                                                new Annotation(
                                                        Mark.ERROR,
                                                        List.of("【错误】「150」超出硬范围 0–75，不能导入")),
                                                new CellRef(3, 1),
                                                new Annotation(
                                                        Mark.WARN,
                                                        List.of(
                                                                "【警告】「73」超出提示范围 20–70，请人工复核",
                                                                "【警告】与第 2 行重复（文件内）"))))));

        Map<String, String> parts = unzip(xlsx);
        // 包结构：工作表 + 样式 + 批注 + VML + 关系齐全
        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.get("[Content_Types].xml").contains("vmlDrawing"));
        assertTrue(
                parts.get("[Content_Types].xml").contains("/xl/comments1.xml"),
                "批注部件要在 Content_Types 登记");
        assertTrue(parts.containsKey("xl/styles.xml"));
        assertTrue(parts.containsKey("xl/comments1.xml"));
        assertTrue(parts.containsKey("xl/drawings/vmlDrawing1.vml"));
        assertTrue(parts.containsKey("xl/worksheets/_rels/sheet1.xml.rels"));

        // 工作表：错误单元格 s=1（浅红）、警告 s=2（浅黄）、值单元格 inlineStr、legacyDrawing 引用
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(
                sheet.contains(
                        "<c r=\"B2\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">150</t>"),
                "错误单元格标色并保留原值");
        assertTrue(sheet.contains("<c r=\"B3\" s=\"2\" t=\"inlineStr\">"), "警告单元格标色");
        assertTrue(
                sheet.contains(
                        "<c r=\"A2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">X0001（示例）</t>"));
        assertTrue(sheet.contains("</sheetData><legacyDrawing r:id=\"rId2\"/>"));

        // 批注：错误单元格 B2 一条（作者 = 导入校验）；B3 两条问题合并为一条批注的两个文本行
        String comments = parts.get("xl/comments1.xml");
        assertTrue(comments.contains("<author>导入校验</author>"));
        assertTrue(comments.contains("<comment ref=\"B2\" authorId=\"0\">"));
        assertTrue(comments.contains("【错误】「150」超出硬范围 0–75，不能导入"));
        assertTrue(comments.contains("<comment ref=\"B3\" authorId=\"0\">"));
        assertTrue(comments.contains("【警告】「73」超出提示范围 20–70，请人工复核"));
        assertTrue(comments.contains("与第 2 行重复（文件内）"));

        // VML：两个 Note 形状锚在 (row 1, col 1) 与 (row 2, col 1)（0 起）
        String vml = parts.get("xl/drawings/vmlDrawing1.vml");
        assertTrue(vml.contains("<x:ClientData ObjectType=\"Note\">"));
        assertTrue(vml.contains("<x:Row>1</x:Row><x:Column>1</x:Column>"), "B2 批注锚在源单元格");
        assertTrue(vml.contains("<x:Row>2</x:Row><x:Column>1</x:Column>"));
        assertTrue(vml.contains("o:idmap"), "形状 id 映射（Excel 兼容）");

        // 样式：两格填充（错误浅红 / 警告浅黄）
        String styles = parts.get("xl/styles.xml");
        assertTrue(styles.contains("FFFFC7CE"));
        assertTrue(styles.contains("FFFFEB9C"));

        // 工作表关系：comments（rId1）+ vmlDrawing（rId2，legacyDrawing 指向它）
        String rels = parts.get("xl/worksheets/_rels/sheet1.xml.rels");
        assertTrue(rels.contains("Target=\"../comments1.xml\""));
        assertTrue(rels.contains("Target=\"../drawings/vmlDrawing1.vml\""));
    }

    @Test
    void annotatesBlankCellWithoutValueAndSkipsPartsWhenNoAnnotations() throws IOException {
        byte[] xlsx =
                AnnotatedReportXlsx.write(
                        List.of(
                                new SheetSpec(
                                        "基线",
                                        List.of("病案号", "姓名"),
                                        Map.of(2, cellRow(0, "X0001（示例）")),
                                        Map.of(
                                                new CellRef(2, 1),
                                                new Annotation(Mark.ERROR, List.of("【错误】必填字段为空")))),
                                new SheetSpec(
                                        "随访记录",
                                        List.of("病案号", "随访年度"),
                                        Map.of(2, cellRow(0, "X0001（示例）", 1, "1")),
                                        Map.of())));
        Map<String, String> parts = unzip(xlsx);
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"B2\" s=\"1\"/>"), "没有值的错误单元格也要标色（空单元格带样式）");
        assertTrue(parts.containsKey("xl/comments1.xml"));
        assertFalse(parts.containsKey("xl/comments2.xml"), "无批注的表不生成批注部件");
        assertFalse(parts.containsKey("xl/worksheets/_rels/sheet2.xml.rels"));
        assertFalse(parts.get("xl/worksheets/sheet2.xml").contains("legacyDrawing"));
        assertTrue(parts.get("xl/workbook.xml").contains("基线"));
        assertTrue(parts.get("xl/workbook.xml").contains("随访记录"));
    }

    @Test
    void reportReadableBackByStreamingGateway() throws IOException {
        // 与上传文件同一读取口径读回：值与坐标不丢（E18-T06 下载前的自检口径）
        byte[] xlsx =
                AnnotatedReportXlsx.write(
                        List.of(
                                new SheetSpec(
                                        "患者数据",
                                        List.of("病案号", "EF"),
                                        Map.of(2, cellRow(0, "X0001（示例）", 1, "150")),
                                        Map.of(
                                                new CellRef(2, 1),
                                                new Annotation(
                                                        Mark.ERROR, List.of("【错误】超出硬范围"))))));
        try (var workbook = new StreamingXlsxGateway().open(new ByteArrayInputStream(xlsx))) {
            var sheet = workbook.sheet("患者数据");
            List<String> header = sheet.header();
            assertEquals(List.of("病案号", "EF"), header);
            Map<Integer, List<String>> rows = new TreeMap<>();
            sheet.dataRows(
                    (rowNo, cells) -> {
                        rows.put(rowNo, cells);
                    });
            assertEquals(1, rows.size());
            assertEquals("X0001（示例）", rows.get(2).get(0));
            assertEquals("150", rows.get(2).get(1));
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static Map<Integer, String> cellRow(Object... colToValue) {
        Map<Integer, String> cells = new TreeMap<>();
        for (int i = 0; i < colToValue.length; i += 2) {
            cells.put((Integer) colToValue[i], (String) colToValue[i + 1]);
        }
        return cells;
    }

    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip =
                new ZipInputStream(new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertNotNull(parts.get("xl/workbook.xml"));
        return parts;
    }
}
