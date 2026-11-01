package com.crdp.system.org.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 最小 xlsx 读写（E06-T03 科室导入专用，OQ-52）：只用 JDK 内置 zip + StAX，不引入 POI—— 宽表（212+ 列、上万行）流式读写仍属 E18 的框架
 * {@code ExcelGateway}，本类只面向小表（≤1000 行、全文本单元格）。
 *
 * <p>写：单工作表、inlineStr 单元格（模板只有表头与示例行，全部虚构数据）。 读：第一个 worksheet part；支持 Excel 常见的
 * sharedStrings（t="s"）、inlineStr（t="inlineStr"）、公式结果字符串（t="str"）与 数值单元格（无 t，v 原样返回），空单元格补 ""，按 r
 * 属性的列字母定位。 日期列不支持（本模板无日期列）。
 */
public final class SimpleXlsx {

    private static final String SHEET_XML_START =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>\
            """;

    private static final String SHEET_XML_END = "</sheetData></worksheet>";

    private SimpleXlsx() {}

    /** 生成单工作表 xlsx（表头 + 数据行，全部按文本写入；行 / 列从 1 计）。 */
    public static byte[] write(List<String> headers, List<List<String>> rows) {
        StringBuilder sheet = new StringBuilder(SHEET_XML_START);
        int rowNo = 1;
        sheet.append(rowXml(rowNo++, headers));
        for (List<String> row : rows) {
            sheet.append(rowXml(rowNo++, row));
        }
        sheet.append(SHEET_XML_END);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                put(zip, "[Content_Types].xml", contentTypesXml());
                put(zip, "_rels/.rels", rootRelsXml());
                put(zip, "xl/workbook.xml", workbookXml());
                put(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml());
                put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 xlsx 失败", ex);
        }
    }

    /** 读取第一个工作表的全部单元格（矩形补齐为最长行；空白为 ""）。 */
    public static List<List<String>> read(InputStream in) {
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            // 条目顺序不可依赖（sharedStrings 不保证在 worksheet 之前）：先整体解压到内存再解析，
            // 科室导入文件上限 1000 行，整体读入可控。
            java.util.Map<String, byte[]> parts = new java.util.LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), zip.readAllBytes());
            }
            if (parts.isEmpty()) {
                throw new IllegalStateException("不是有效的 xlsx 文件（无法读取压缩包）");
            }
            byte[] sharedPart = parts.get("xl/sharedStrings.xml");
            List<String> shared =
                    sharedPart == null
                            ? List.of()
                            : readSharedStrings(new java.io.ByteArrayInputStream(sharedPart));
            byte[] sheetPart = null;
            for (java.util.Map.Entry<String, byte[]> part : parts.entrySet()) {
                if (part.getKey().startsWith("xl/worksheets/") && part.getKey().endsWith(".xml")) {
                    sheetPart = part.getValue();
                    break;
                }
            }
            if (sheetPart == null) {
                throw new IllegalStateException("xlsx 中没有工作表");
            }
            return readSheet(new java.io.ByteArrayInputStream(sheetPart), shared);
        } catch (IOException ex) {
            throw new IllegalStateException("解析 xlsx 失败：不是有效的 xlsx 文件", ex);
        }
    }

    // ---------------------------------------------------------------- 写

    private static String rowXml(int rowNo, List<String> cells) {
        StringBuilder xml = new StringBuilder("<row r=\"").append(rowNo).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            xml.append("<c r=\"")
                    .append(columnLetters(i))
                    .append(rowNo)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(escape(cells.get(i) == null ? "" : cells.get(i)))
                    .append("</t></is></c>");
        }
        return xml.append("</row>").toString();
    }

    private static String contentTypesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                </Types>\
                """;
    }

    private static String rootRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>\
                """;
    }

    private static String workbookXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>\
                """;
    }

    private static String workbookRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                </Relationships>\
                """;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ---------------------------------------------------------------- 读

    /** sharedStrings：每个 &lt;si&gt; 内可能直接是 &lt;t&gt;，也可能是富文本 &lt;r&gt;&lt;t&gt; 拼接。 */
    private static List<String> readSharedStrings(InputStream in) {
        List<String> strings = new ArrayList<>();
        try {
            XMLStreamReader reader =
                    factory().createXMLStreamReader(in, StandardCharsets.UTF_8.name());
            StringBuilder current = null;
            boolean inShared = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("sst".equals(local)) {
                        inShared = true;
                    } else if (inShared && "si".equals(local)) {
                        current = new StringBuilder();
                    } else if (current != null && "t".equals(local)) {
                        current.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("si".equals(reader.getLocalName()) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                }
            }
            reader.close();
            return strings;
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("解析 xlsx sharedStrings 失败", ex);
        }
    }

    private static List<List<String>> readSheet(InputStream in, List<String> shared) {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader reader =
                    factory().createXMLStreamReader(in, StandardCharsets.UTF_8.name());
            List<String> current = null;
            String cellType = null;
            Integer columnIndex = null;
            int sequentialColumn = 0;
            StringBuilder cellText = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    switch (local) {
                        case "row" -> {
                            current = new ArrayList<>();
                            sequentialColumn = 0;
                        }
                        case "c" -> {
                            cellType = attr(reader, "t");
                            String ref = attr(reader, "r");
                            columnIndex = ref == null ? null : columnIndex(ref);
                            cellText = new StringBuilder();
                        }
                        case "v", "t" -> {
                            if (cellText != null) {
                                cellText.append(reader.getElementText());
                            }
                        }
                        default -> {
                            // 忽略公式等其他子元素
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("c".equals(local) && current != null && cellText != null) {
                        int target = columnIndex == null ? sequentialColumn : columnIndex;
                        while (current.size() < target) {
                            current.add("");
                        }
                        current.add(cellValue(cellType, cellText.toString(), shared));
                        sequentialColumn = target + 1;
                        cellText = null;
                        cellType = null;
                        columnIndex = null;
                    } else if ("row".equals(local) && current != null) {
                        rows.add(current);
                        current = null;
                    }
                }
            }
            reader.close();
            return rows;
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("解析 xlsx 工作表失败", ex);
        }
    }

    private static String cellValue(String type, String raw, List<String> shared) {
        if ("s".equals(type)) {
            int index = Integer.parseInt(raw.trim());
            return index >= 0 && index < shared.size() ? shared.get(index) : "";
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        return raw;
    }

    private static String attr(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (reader.getAttributeLocalName(i).equals(name)) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** 列字母 → 0 起始下标（A=0、Z=25、AA=26）。 */
    private static int columnIndex(String cellRef) {
        int index = 0;
        for (char c : cellRef.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                index = index * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return index - 1;
    }

    private static String columnLetters(int index) {
        index++;
        StringBuilder letters = new StringBuilder();
        while (index > 0) {
            index--;
            letters.insert(0, (char) ('A' + index % 26));
            index /= 26;
        }
        return letters.toString();
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // 外部实体解析一律关闭（上传文件是不可信输入）
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
