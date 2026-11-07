package com.crdp.framework.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * E18-T02 流式读取测试的 xlsx 构造器：可写多张工作表（SimpleXlsx.write 只写单表）。
 *
 * <p>与 SimpleXlsx 相同的极简结构（inlineStr 单元格、无 sharedStrings）；需要 sharedStrings / 数值单元格的用例在测试里
 * 手写条目。全部虚构数据。
 */
final class TestXlsxFiles {

    /** 一张工作表（表头 + 数据行）。 */
    record Sheet(String name, List<String> header, List<List<String>> rows) {}

    /** 多张工作表的 xlsx 字节（inlineStr 全文本单元格）。 */
    static byte[] workbook(Sheet... sheets) {
        StringBuilder types = new StringBuilder();
        StringBuilder book = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        for (int i = 0; i < sheets.length; i++) {
            types.append(
                    """
                    <Override PartName="/xl/worksheets/sheet%d.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                    """
                            .formatted(i + 1));
            book.append(
                    "<sheet name=\"%s\" sheetId=\"%d\" r:id=\"rId%d\"/>"
                            .formatted(escape(sheets[i].name()), i + 1, i + 1));
            rels.append(
                    "<Relationship Id=\"rId%d\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet%d.xml\"/>"
                            .formatted(i + 1, i + 1));
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                put(
                        zip,
                        "[Content_Types].xml",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                        <Default Extension="xml" ContentType="application/xml"/>\
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                        """
                                + types
                                + "</Types>");
                put(zip, "_rels/.rels", rootRelsXml());
                put(
                        zip,
                        "xl/workbook.xml",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                        <sheets>"""
                                + book
                                + "</sheets></workbook>");
                put(
                        zip,
                        "xl/_rels/workbook.xml.rels",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        """
                                + rels
                                + "</Relationships>");
                for (int i = 0; i < sheets.length; i++) {
                    put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets[i]));
                }
            }
            return buffer.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("生成测试 xlsx 失败", failure);
        }
    }

    /** 单表 xlsx（等价 workbook(new Sheet(...)) 的便捷入口）。 */
    static byte[] workbook(String name, List<String> header, List<List<String>> rows) {
        return workbook(new Sheet(name, header, rows));
    }

    /** 覆盖 sharedStrings / 数值 / 布尔 / 公式结果单元格的手写工作表（检验单元格类型解析）。 */
    static byte[] typedCellWorkbook() {
        String shared =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">\
                <si><t>病案号</t></si>\
                <si><r><t>张三</t></r><r><t>（示例）</t></r></si>\
                <si><t>3</t></si>\
                </sst>\
                """;
        String sheet =
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <sheetData>\
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>\
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>45.5</v></c><c r="C2" t="b"><v>1</v></c></row>\
                <row r="3"><c r="A3" t="str"><f>B2*2</f><v>91</v></c></row>\
                </sheetData></worksheet>\
                """;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                put(
                        zip,
                        "[Content_Types].xml",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                        <Default Extension="xml" ContentType="application/xml"/>\
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                        <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>\
                        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                        </Types>\
                        """);
                put(zip, "_rels/.rels", rootRelsXml());
                put(
                        zip,
                        "xl/workbook.xml",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                        <sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets></workbook>\
                        """);
                put(
                        zip,
                        "xl/_rels/workbook.xml.rels",
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                        </Relationships>\
                        """);
                put(zip, "xl/sharedStrings.xml", shared);
                put(zip, "xl/worksheets/sheet1.xml", sheet);
            }
            return buffer.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("生成测试 xlsx 失败", failure);
        }
    }

    private static String sheetXml(Sheet sheet) {
        StringBuilder xml =
                new StringBuilder(
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                        <sheetData>\
                        """);
        int rowNo = 1;
        xml.append(rowXml(rowNo++, sheet.header()));
        for (List<String> row : sheet.rows()) {
            xml.append(rowXml(rowNo++, row));
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static String rowXml(int rowNo, List<String> cells) {
        StringBuilder xml = new StringBuilder("<row r=\"").append(rowNo).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            xml.append("<c r=\"")
                    .append(SimpleXlsx.columnLettersOf(i))
                    .append(rowNo)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(escape(cells.get(i) == null ? "" : cells.get(i)))
                    .append("</t></is></c>");
        }
        return xml.append("</row>").toString();
    }

    private static String rootRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>\
                """;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private TestXlsxFiles() {}
}
