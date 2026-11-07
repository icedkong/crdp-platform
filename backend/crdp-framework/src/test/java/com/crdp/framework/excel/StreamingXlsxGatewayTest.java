package com.crdp.framework.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E18-T02 流式读取网关测试：多工作表、任意顺序逐表逐行读取、单元格类型（sharedStrings 富文本 / 数值 / 布尔 / 公式结果）、
 * 稀疏单元格按列字母定位、坏文件报错、关闭即清理。 大文件不占满内存的验收在 {@link StreamingXlsxMemoryTest}（受限堆的子进程）。
 */
class StreamingXlsxGatewayTest {

    private final StreamingXlsxGateway gateway = new StreamingXlsxGateway();

    @Test
    void readsSimpleXlsxOutput() throws IOException {
        // SimpleXlsx（E06 科室 / 用户导入、E11 字段字典导入）写的文件流式网关也要能读（E18-T02 测试与大文件生成用）
        byte[] xlsx =
                SimpleXlsx.write(List.of("病案号", "姓名"), List.of(List.of("X0001（示例）", "张三（示例）")));
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            assertEquals(1, workbook.sheetNames().size());
            ExcelSheet sheet = workbook.sheet(workbook.sheetNames().get(0));
            assertEquals(List.of("病案号", "姓名"), sheet.header());
            List<List<String>> rows = new ArrayList<>();
            sheet.dataRows((rowNo, cells) -> rows.add(cells));
            assertEquals(List.of(List.of("X0001（示例）", "张三（示例）")), rows);
        }
    }

    @Test
    void readsSheetsInFileOrderAndRowsAfterHeader() throws IOException {
        byte[] xlsx =
                TestXlsxFiles.workbook(
                        new TestXlsxFiles.Sheet(
                                "患者数据",
                                List.of("病案号", "姓名"),
                                List.of(
                                        List.of("100001（示例）", "张三（示例）"),
                                        List.of("100002（示例）", "李四（示例）"))),
                        new TestXlsxFiles.Sheet("说明", List.of("备注"), List.of(List.of("虚构数据"))));
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            assertEquals(List.of("患者数据", "说明"), workbook.sheetNames());

            ExcelSheet sheet = workbook.sheet("患者数据");
            assertEquals(List.of("病案号", "姓名"), sheet.header());
            List<List<String>> rows = new ArrayList<>();
            sheet.dataRows((rowNo, cells) -> rows.add(cells));
            assertEquals(
                    List.of(List.of("100001（示例）", "张三（示例）"), List.of("100002（示例）", "李四（示例）")),
                    rows);
        }
    }

    @Test
    void readsSheetsInAnyOrderAndRepeatedly() throws IOException {
        byte[] xlsx =
                TestXlsxFiles.workbook(
                        new TestXlsxFiles.Sheet("第一张", List.of("列一"), List.of(List.of("1"))),
                        new TestXlsxFiles.Sheet("第二张", List.of("列二"), List.of(List.of("2"))));
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            // 先读第二张再读第一张（ZipFile 随机访问，每张表独立开流）
            assertEquals("2", onlyRow(workbook.sheet("第二张")));
            assertEquals("1", onlyRow(workbook.sheet("第一张")));
            // 同一张表可以重开（detect 后适配器再取表）
            assertEquals("2", onlyRow(workbook.sheet("第二张")));
        }
    }

    @Test
    void resolvesSharedStringsNumericBooleanAndFormulaCells() throws IOException {
        try (ExcelWorkbook workbook =
                gateway.open(new ByteArrayInputStream(TestXlsxFiles.typedCellWorkbook()))) {
            ExcelSheet sheet = workbook.sheet("数据");
            assertEquals(List.of("病案号", "张三（示例）"), sheet.header());
            List<List<String>> rows = new ArrayList<>();
            sheet.dataRows((rowNo, cells) -> rows.add(cells));
            // sharedStrings 富文本拼接、数值原样文本、布尔 TRUE/FALSE、公式结果字符串
            assertEquals(List.of(List.of("3", "45.5", "TRUE"), List.of("91")), rows);
        }
    }

    @Test
    void positionsSparseCellsByColumnLetters() throws IOException {
        // 行 2 只有 A 与 D 两格（B、C 缺失），按 r 属性的列字母定位补空白
        String sheet =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <sheetData>\
                <row r="1"><c r="A1" t="inlineStr"><is><t>甲</t></is></c><c r="B1" t="inlineStr"><is><t>乙</t></is></c><c r="C1" t="inlineStr"><is><t>丙</t></is></c><c r="D1" t="inlineStr"><is><t>丁</t></is></c></row>\
                <row r="2"><c r="A2" t="inlineStr"><is><t>有</t></is></c><c r="D2" t="inlineStr"><is><t>也有</t></is></c></row>\
                </sheetData></worksheet>\
                """;
        byte[] xlsx = singleSheetWorkbook("稀疏", sheet);
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            List<List<String>> rows = new ArrayList<>();
            workbook.sheet("稀疏").dataRows((rowNo, cells) -> rows.add(cells));
            assertEquals(List.of(List.of("有", "", "", "也有")), rows);
        }
    }

    @Test
    void reportsSheetRowNumbersFromXml() throws IOException {
        // 行号跳号（3 → 5）：按 r 属性回调行号，缺的行不合成（空行由消费方按语义跳过）
        String sheet =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <sheetData>\
                <row r="1"><c r="A1" t="inlineStr"><is><t>列</t></is></c></row>\
                <row r="3"><c r="A3" t="inlineStr"><is><t>三</t></is></c></row>\
                <row r="5"><c r="A5" t="inlineStr"><is><t>五</t></is></c></row>\
                </sheetData></worksheet>\
                """;
        byte[] xlsx = singleSheetWorkbook("跳号", sheet);
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            List<Integer> rowNos = new ArrayList<>();
            workbook.sheet("跳号").dataRows((rowNo, cells) -> rowNos.add(rowNo));
            assertEquals(List.of(3, 5), rowNos);
        }
    }

    @Test
    void unknownSheetNameReturnsNullAndEmptySheetHasNoHeader(@TempDir Path temp)
            throws IOException {
        byte[] xlsx = TestXlsxFiles.workbook(new TestXlsxFiles.Sheet("空表", List.of(), List.of()));
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            assertNull(workbook.sheet("不存在"));
            assertEquals(List.of(), workbook.sheet("空表").header());
            List<List<String>> rows = new ArrayList<>();
            workbook.sheet("空表").dataRows((rowNo, cells) -> rows.add(cells));
            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void rejectsNonZipContent() {
        byte[] garbage = "不是 xlsx".getBytes(StandardCharsets.UTF_8);
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> gateway.open(new ByteArrayInputStream(garbage)));
        assertTrue(failure.getMessage().contains("不是有效的 xlsx 文件"));
    }

    @Test
    void closeDeletesSpooledTempFile(@TempDir Path temp) throws IOException {
        byte[] xlsx = TestXlsxFiles.workbook("表", List.of("列"), List.of(List.of("值")));
        Path file = temp.resolve("in.xlsx");
        Files.write(file, xlsx);
        try (ExcelWorkbook workbook = gateway.open(Files.newInputStream(file))) {
            workbook.sheetNames();
        }
        // 落盘的临时副本随 close 清理（不残留大文件的整份拷贝）
        try (var stream = Files.list(temp)) {
            assertEquals(1, stream.count(), "临时目录应只剩输入文件本身");
        }
    }

    @Test
    void dataRowsCanOnlyBeIteratedOnce() throws IOException {
        byte[] xlsx = TestXlsxFiles.workbook("表", List.of("列"), List.of(List.of("值")));
        try (ExcelWorkbook workbook = gateway.open(new ByteArrayInputStream(xlsx))) {
            ExcelSheet sheet = workbook.sheet("表");
            List<List<String>> first = new ArrayList<>();
            sheet.dataRows((rowNo, cells) -> first.add(cells));
            assertEquals(1, first.size());
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> sheet.dataRows((rowNo, cells) -> first.add(cells)));
            assertTrue(failure.getMessage().contains("只能遍历一次"));
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static String onlyRow(ExcelSheet sheet) throws IOException {
        List<String> first = new ArrayList<>();
        sheet.dataRows((rowNo, cells) -> first.addAll(cells));
        assertEquals(1, first.size());
        return first.get(0);
    }

    /** 单表、自定义 sheet XML（sharedStrings 等特殊用例）。 */
    private static byte[] singleSheetWorkbook(String name, String sheetXml) {
        try {
            var buffer = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                putEntry(
                        zip,
                        "[Content_Types].xml",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                        <Default Extension="xml" ContentType="application/xml"/>\
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                        </Types>\
                        """);
                putEntry(
                        zip,
                        "_rels/.rels",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                        </Relationships>\
                        """);
                putEntry(
                        zip,
                        "xl/workbook.xml",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                        <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>\
                        """
                                .formatted(name));
                putEntry(
                        zip,
                        "xl/_rels/workbook.xml.rels",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                        </Relationships>\
                        """);
                putEntry(zip, "xl/worksheets/sheet1.xml", sheetXml);
            }
            return buffer.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("生成测试 xlsx 失败", failure);
        }
    }

    private static void putEntry(java.util.zip.ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
