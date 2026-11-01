package com.crdp.system.org.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** E06-T03 / OQ-52：最小 xlsx 读写（模板生成与导入解析的唯一通道，不引入 POI）。 */
class SimpleXlsxTest {

    @Test
    void writeThenReadRoundTripsHeadersAndRows() {
        byte[] file =
                SimpleXlsx.write(
                        List.of("院区编码", "科室编码", "科室名称"),
                        List.of(
                                List.of("ORG-DEMO", "DEPT-A", "虚构心内科（示例）"),
                                List.of("ORG-DEMO", "DEPT-B", "")));

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(file));
        assertEquals(
                List.of(
                        List.of("院区编码", "科室编码", "科室名称"),
                        List.of("ORG-DEMO", "DEPT-A", "虚构心内科（示例）"),
                        List.of("ORG-DEMO", "DEPT-B", "")),
                grid);
    }

    @Test
    void readFillsBlankCellsByColumnLetters() {
        // 第三行缺 B 列（Excel 常见：跳过空单元格不写 <c>）
        String sheet =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>\
                <row r="1"><c r="A1" t="inlineStr"><is><t>编码</t></is></c><c r="B1" t="inlineStr"><is><t>名称</t></is></c></row>\
                <row r="2"><c r="A2" t="inlineStr"><is><t>D1</t></is></c><c r="C2" t="inlineStr"><is><t>跳了B</t></is></c></row>\
                </sheetData></worksheet>\
                """;
        byte[] file = zipWithSheet(sheet, null);

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(file));
        assertEquals(List.of("编码", "名称"), grid.get(0));
        assertEquals(List.of("D1", "", "跳了B"), grid.get(1));
    }

    @Test
    void readSupportsSharedStringsAndNumericCellsLikeExcelWrites() {
        // Excel 保存的典型形态：文本走 sharedStrings（t="s" 引下标），数字无 t 属性
        String shared =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">\
                <si><t>DEPT-A</t></si>\
                <si><r><t>虚构</t></r><r><t>心内科</t></r></si>\
                </sst>\
                """;
        String sheet =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>\
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1"><v>10</v></c></row>\
                </sheetData></worksheet>\
                """;
        byte[] file = zipWithSheet(sheet, shared);

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(file));
        assertEquals(List.of("DEPT-A", "虚构心内科", "10"), grid.get(0));
    }

    @Test
    void readRejectsNonZipFile() {
        byte[] notZip = "这不是xlsx".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalStateException.class,
                () -> SimpleXlsx.read(new ByteArrayInputStream(notZip)));
    }

    @Test
    void writtenFileIsAzippedPackageWithRequiredParts() {
        byte[] file = SimpleXlsx.write(List.of("表头"), List.of(List.of("值")));
        String text = new String(file, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("[Content_Types].xml"), "压缩包内应有内容类型清单");
        assertTrue(text.contains("xl/worksheets/sheet1.xml"), "压缩包内应有工作表");
    }

    /** 组一个只含 worksheet（与可选 sharedStrings）的最小压缩包，模拟不同来源的 xlsx。 */
    private static byte[] zipWithSheet(String sheetXml, String sharedStringsXml) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (sharedStringsXml != null) {
                    zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
                    zip.write(sharedStringsXml.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
