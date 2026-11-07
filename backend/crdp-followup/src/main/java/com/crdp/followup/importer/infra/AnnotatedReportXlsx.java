package com.crdp.followup.importer.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * E18-T03：批注版校验报告 xlsx 写入器（tech-plan §12⑤「生成在原文件上标色与批注的 Excel 供下载」）。
 *
 * <p>沿用仓库无 POI 的直写方案（ADR 0007「写入端沿用 SimpleXlsx 的直写方案」）：JDK zip + 拼 OOXML——
 *
 * <ul>
 *   <li>单元格 inlineStr 全文本（与 SimpleXlsx / ExcelGateway 读取口径一致）；
 *   <li>标色：xl/styles.xml 预置两格填充（错误 FFFFC7CE 浅红、警告 FFFFEB9C 浅黄—— 前端状态色 #FF4D4F / #FA8C16
 *       是深前景色，直接做填充会淹没黑字，报告用同色系浅底保证可读），单元格 s= 引用；
 *   <li>批注：legacy comment（xl/commentsN.xml + xl/drawings/vmlDrawingN.vml + 工作表关系与 legacyDrawing
 *       引用）——Excel / WPS 打开即见红角批注，悬停显示原因（同一单元格的多条问题合并成一条批注多行）；
 *   <li>无批注的工作表不生成 comments / vml 部件（保持包最小）。
 * </ul>
 *
 * <p>输入是<strong>中性的报告模型</strong>（工作表 = 表头 + 稀疏单元格 + 标注），不依赖导入领域类型—— 由 app 层从暂存行（imp_row
 * 密文还原）与校验问题（imp_issue）组装；本类只负责 xlsx 形态。写完的字节由上传 / 下载端点（E18-T06）按租户加密存储并写审计。
 */
public final class AnnotatedReportXlsx {

    private static final String MAIN_NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private static final String REL_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";

    private static final String OFFICE_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** 批注作者（Excel 审阅栏显示）。 */
    private static final String AUTHOR = "导入校验";

    private AnnotatedReportXlsx() {}

    /** 标注级别（决定填充色；NONE = 不标）。 */
    public enum Mark {
        NONE(0),
        ERROR(1),
        WARN(2);

        private final int styleIndex;

        Mark(int styleIndex) {
            this.styleIndex = styleIndex;
        }

        int styleIndex() {
            return styleIndex;
        }
    }

    /** 单元格坐标：rowNo 1 起（表头 = 1）、colNo 0 起——与 {@code cells} 同口径。 */
    public record CellRef(int rowNo, int colNo) {}

    /** 一个单元格的标注：填充级别 + 批注行（多行合并为一条批注）。 */
    public record Annotation(Mark mark, List<String> commentLines) {

        public Annotation {
            commentLines = commentLines == null ? List.of() : List.copyOf(commentLines);
        }
    }

    /**
     * 一张报告工作表。
     *
     * @param name 工作表名（与源文件同名；问题清单表自命名）
     * @param header 表头行（第 1 行）
     * @param cells 稀疏单元格：行号（1 起）→ 列号（0 起）→ 文本
     * @param annotations 标注：坐标 → 填充与批注（数据行）
     */
    public record SheetSpec(
            String name,
            List<String> header,
            Map<Integer, Map<Integer, String>> cells,
            Map<CellRef, Annotation> annotations) {}

    /** 生成报告 xlsx 字节（工作表按给定顺序；第 1 行都是表头）。 */
    public static byte[] write(List<SheetSpec> sheets) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                put(zip, "[Content_Types].xml", contentTypes(sheets));
                put(zip, "_rels/.rels", rootRels());
                put(zip, "xl/workbook.xml", workbook(sheets));
                put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets));
                put(zip, "xl/styles.xml", styles());
                for (int i = 0; i < sheets.size(); i++) {
                    SheetSpec sheet = sheets.get(i);
                    put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheet));
                    if (!sheet.annotations().isEmpty()) {
                        put(zip, "xl/comments" + (i + 1) + ".xml", comments(sheet));
                        put(zip, "xl/drawings/vmlDrawing" + (i + 1) + ".vml", vmlDrawing(sheet));
                        put(
                                zip,
                                "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels",
                                sheetRels(i + 1));
                    }
                }
            }
            return buffer.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("生成批注版校验报告失败", failure);
        }
    }

    // ---------------------------------------------------------------- 包部件

    private static String contentTypes(List<SheetSpec> sheets) {
        StringBuilder overrides =
                new StringBuilder(
                        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            overrides.append(
                    "<Override PartName=\"/xl/worksheets/sheet"
                            + (i + 1)
                            + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            if (!sheets.get(i).annotations().isEmpty()) {
                overrides.append(
                        "<Override PartName=\"/xl/comments"
                                + (i + 1)
                                + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml\"/>");
            }
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Default Extension="vml" ContentType="application/vnd.openxmlformats-officedocument.vmlDrawing"/>\
                """
                + overrides
                + "</Types>";
    }

    private static String rootRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="%s">\
                <Relationship Id="rId1" Type="%s/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>\
                """
                .formatted(REL_NS, OFFICE_REL);
    }

    private static String workbook(List<SheetSpec> sheets) {
        StringBuilder list = new StringBuilder("<sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            list.append(
                    "<sheet name=\"%s\" sheetId=\"%d\" r:id=\"rId%d\"/>"
                            .formatted(escape(sheets.get(i).name()), i + 1, i + 1));
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="%s" xmlns:r="%s">\
                """
                        .formatted(MAIN_NS, OFFICE_REL)
                + list
                + "</sheets></workbook>";
    }

    private static String workbookRels(List<SheetSpec> sheets) {
        StringBuilder rels = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            rels.append(
                    "<Relationship Id=\"rId%d\" Type=\"%s/worksheet\" Target=\"worksheets/sheet%d.xml\"/>"
                            .formatted(i + 1, OFFICE_REL, i + 1));
        }
        rels.append(
                "<Relationship Id=\"rId%d\" Type=\"%s/styles\" Target=\"styles.xml\"/>"
                        .formatted(sheets.size() + 1, OFFICE_REL));
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="%s">\
                """
                        .formatted(REL_NS)
                + rels
                + "</Relationships>";
    }

    /** 带批注的工作表关系：comments（rId1）与 vmlDrawing（rId2，legacyDrawing 引用它）。 */
    private static String sheetRels(int sheetIndex) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="%s">\
                <Relationship Id="rId1" Type="%s/comments" Target="../comments%d.xml"/>\
                <Relationship Id="rId2" Type="%s/vmlDrawing" Target="../drawings/vmlDrawing%d.vml"/>\
                </Relationships>\
                """
                .formatted(REL_NS, OFFICE_REL, sheetIndex, OFFICE_REL, sheetIndex);
    }

    // ---------------------------------------------------------------- 工作表

    private static String sheetXml(SheetSpec sheet) {
        // 合并出现的行：表头(1) + 数据行 + 只有标注没有值的行（空单元格也要标色）
        TreeMap<Integer, TreeMap<Integer, Annotation>> marks = new TreeMap<>();
        sheet.annotations()
                .forEach(
                        (ref, annotation) ->
                                marks.computeIfAbsent(ref.rowNo(), row -> new TreeMap<>())
                                        .put(ref.colNo(), annotation));
        TreeMap<Integer, TreeMap<Integer, String>> values = new TreeMap<>();
        sheet.cells().forEach((rowNo, row) -> values.put(rowNo, new TreeMap<>(row)));

        StringBuilder xml =
                new StringBuilder(
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <worksheet xmlns="%s" xmlns:r="%s">\
                        <sheetData>\
                        """
                                .formatted(MAIN_NS, OFFICE_REL));
        xml.append(rowXml(1, headerCells(sheet), Map.of()));
        for (Map.Entry<Integer, TreeMap<Integer, String>> row : values.entrySet()) {
            if (row.getKey() == 1) {
                continue; // 表头已写
            }
            xml.append(
                    rowXml(
                            row.getKey(),
                            row.getValue(),
                            marks.getOrDefault(row.getKey(), new TreeMap<>())));
        }
        for (Map.Entry<Integer, TreeMap<Integer, Annotation>> row : marks.entrySet()) {
            if (row.getKey() == 1 || values.containsKey(row.getKey())) {
                continue;
            }
            xml.append(rowXml(row.getKey(), Map.of(), row.getValue()));
        }
        xml.append("</sheetData>");
        if (!sheet.annotations().isEmpty()) {
            xml.append("<legacyDrawing r:id=\"rId2\"/>");
        }
        return xml.append("</worksheet>").toString();
    }

    private static Map<Integer, String> headerCells(SheetSpec sheet) {
        Map<Integer, String> cells = new TreeMap<>();
        for (int i = 0; i < sheet.header().size(); i++) {
            cells.put(i, sheet.header().get(i));
        }
        return cells;
    }

    /** 一行 XML：值单元格与标注单元格合并（有标注没值的写空单元格只带样式）。 */
    private static String rowXml(
            int rowNo, Map<Integer, String> values, Map<Integer, Annotation> marks) {
        StringBuilder xml = new StringBuilder("<row r=\"").append(rowNo).append("\">");
        TreeMap<Integer, Boolean> columns = new TreeMap<>();
        values.keySet().forEach(column -> columns.put(column, true));
        marks.keySet().forEach(column -> columns.putIfAbsent(column, true));
        for (int column : columns.keySet()) {
            String value = values.get(column);
            Annotation annotation = marks.get(column);
            String style =
                    annotation == null || annotation.mark() == Mark.NONE
                            ? ""
                            : " s=\"" + annotation.mark().styleIndex() + "\"";
            String ref = cellRef(column, rowNo);
            if (value == null) {
                xml.append("<c r=\"").append(ref).append("\"").append(style).append("/>");
            } else {
                xml.append("<c r=\"")
                        .append(ref)
                        .append("\"")
                        .append(style)
                        .append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(value))
                        .append("</t></is></c>");
            }
        }
        return xml.append("</row>").toString();
    }

    // ---------------------------------------------------------------- 批注

    private static String comments(SheetSpec sheet) {
        StringBuilder xml =
                new StringBuilder(
                        """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Comments xmlns="%s"><authors><author>%s</author></authors>\
                        """
                                .formatted(MAIN_NS, escape(AUTHOR)));
        List<CellRef> refs = new ArrayList<>(sheet.annotations().keySet());
        refs.sort(
                (a, b) ->
                        a.rowNo() != b.rowNo()
                                ? Integer.compare(a.rowNo(), b.rowNo())
                                : Integer.compare(a.colNo(), b.colNo()));
        for (CellRef ref : refs) {
            Annotation annotation = sheet.annotations().get(ref);
            if (annotation.commentLines().isEmpty()) {
                continue;
            }
            xml.append("<comment ref=\"")
                    .append(cellRef(ref.colNo(), ref.rowNo()))
                    .append("\" authorId=\"0\"><text>");
            for (String line : annotation.commentLines()) {
                xml.append("<r><t xml:space=\"preserve\">").append(escape(line)).append("</t></r>");
            }
            xml.append("</text></comment>");
        }
        return xml.append("</Comments>").toString();
    }

    /** legacy 批注的 VML 形状（每个被批注单元格一个 Note 形状；锚点跟随单元格）。 */
    private static String vmlDrawing(SheetSpec sheet) {
        StringBuilder xml =
                new StringBuilder(
                        """
                        <xml xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office"\
                         xmlns:x="urn:schemas-microsoft-com:office:excel">\
                        <o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1"/></o:shapelayout>\
                        <v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe">\
                        <v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/>\
                        </v:shapetype>\
                        """);
        List<CellRef> refs = new ArrayList<>(sheet.annotations().keySet());
        refs.sort(
                (a, b) ->
                        a.rowNo() != b.rowNo()
                                ? Integer.compare(a.rowNo(), b.rowNo())
                                : Integer.compare(a.colNo(), b.colNo()));
        int shape = 1025;
        for (CellRef ref : refs) {
            if (sheet.annotations().get(ref).commentLines().isEmpty()) {
                continue;
            }
            int column = ref.colNo();
            int row = ref.rowNo() - 1;
            xml.append(
                    """
                    <v:shape id="_x0000_s%d" type="#_x0000_t202" style='position:absolute;margin-left:%dpt;\
                    margin-top:%gpt;width:150pt;height:%dpt;z-index:%d;visibility:hidden' fillcolor="#ffffe1" o:insetmode="auto">\
                    <v:fill color2="#ffffe1"/><v:shadow on="t" color="black" obscured="t"/><v:path o:connecttype="none"/>\
                    <v:textbox style='mso-direction-alt:auto'><div style='text-align:left'/></v:textbox>\
                    <x:ClientData ObjectType="Note"><x:MoveWithCells/><x:SizeWithCells/>\
                    <x:Anchor>%d,15,%d,2,%d,15,%d,8</x:Anchor>\
                    <x:AutoFill>False</x:AutoFill><x:Row>%d</x:Row><x:Column>%d</x:Column>\
                    </x:ClientData></v:shape>\
                    """
                            .formatted(
                                    ++shape,
                                    column * 48,
                                    row * 13.5,
                                    Math.max(
                                            45,
                                            sheet.annotations().get(ref).commentLines().size()
                                                    * 15),
                                    shape - 1024,
                                    column,
                                    row,
                                    column + 3,
                                    row + 1 + sheet.annotations().get(ref).commentLines().size(),
                                    row,
                                    column));
        }
        return xml.append("</xml>").toString();
    }

    // ---------------------------------------------------------------- 样式与共用

    /** xf 0 = 默认、1 = 错误浅红填充、2 = 警告浅黄填充。 */
    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="%s">\
                <fonts count="1"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font></fonts>\
                <fills count="4"><fill><patternFill patternType="none"/></fill>\
                <fill><patternFill patternType="gray125"/></fill>\
                <fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/><bgColor indexed="64"/></patternFill></fill>\
                <fill><patternFill patternType="solid"><fgColor rgb="FFFFEB9C"/><bgColor indexed="64"/></patternFill></fill></fills>\
                <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                <cellXfs count="3">\
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                <xf numFmtId="0" fontId="0" fillId="2" borderId="0" xfId="0" applyFill="1"/>\
                <xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1"/>\
                </cellXfs>\
                <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
                </styleSheet>\
                """
                .formatted(MAIN_NS);
    }

    /** 列号（0 起）+ 行号（1 起）→ 单元格引用（如 B3）。 */
    private static String cellRef(int colNo, int rowNo) {
        int index = colNo + 1;
        StringBuilder letters = new StringBuilder();
        while (index > 0) {
            index--;
            letters.insert(0, (char) ('A' + index % 26));
            index /= 26;
        }
        return letters.append(rowNo).toString();
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
}
