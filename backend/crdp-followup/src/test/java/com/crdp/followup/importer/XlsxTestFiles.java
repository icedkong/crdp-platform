package com.crdp.followup.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** E18-T02 测试共用：生成多工作表 xlsx 字节（inlineStr 全文本单元格；框架 SimpleXlsx.write 只写单表）。 全部虚构数据（规则 5）。 */
public final class XlsxTestFiles {

    /** 一张工作表（rows 第 1 项 = 表头行）。 */
    public record Sheet(String name, List<List<String>> rows) {}

    /** 多张工作表的 xlsx 字节。 */
    public static byte[] workbook(Sheet... sheets) {
        StringBuilder types = new StringBuilder();
        StringBuilder book = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sheets.length; i++) {
            types.append(
                    "<Override PartName=\"/xl/worksheets/sheet%d.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                            .formatted(i + 1));
            book.append(
                    "<sheet name=\"%s\" sheetId=\"%d\" r:id=\"rId%d\"/>"
                            .formatted(escape(sheets[i].name()), i + 1, i + 1));
            rels.append(
                    "<Relationship Id=\"rId%d\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet%d.xml\"/>"
                            .formatted(i + 1, i + 1));
            parts.add(sheetXml(sheets[i].rows()));
        }
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
            put(
                    zip,
                    "_rels/.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>\
                    """);
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
            for (int i = 0; i < parts.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", parts.get(i));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("生成测试 xlsx 失败", failure);
        }
        return buffer.toByteArray();
    }

    private static String sheetXml(List<List<String>> rows) {
        StringBuilder xml =
                new StringBuilder(
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                        <sheetData>\
                        """);
        for (int r = 0; r < rows.size(); r++) {
            xml.append("<row r=\"").append(r + 1).append("\">");
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                xml.append("<c r=\"")
                        .append(com.crdp.framework.excel.SimpleXlsx.columnLettersOf(c))
                        .append(r + 1)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(row.get(c)))
                        .append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
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

    private XlsxTestFiles() {}
}
