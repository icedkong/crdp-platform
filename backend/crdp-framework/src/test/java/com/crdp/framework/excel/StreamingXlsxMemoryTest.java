package com.crdp.framework.excel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * E18-T02 验收（tasks.csv「大文件不占满内存」）：在受限堆（-Xmx64m）的子进程里流式读完一张 远大于堆的 xlsx——内容全量驻留约需 130MB+（2 万行 × 64
 * 列的字符串与容器开销），整表读入内存会 OOM，逐行流式只需每行数 KB。 生成端与 {@link BigSheetProbe} 共用同一套内容与校验和口径。
 */
class StreamingXlsxMemoryTest {

    /** 2 万行 × 64 列；全量驻留约 130MB+ > 64m 堆。 */
    private static final int ROWS = 20_000;

    private static final int COLUMNS = 64;

    /** 子进程堆上限：明显小于全量内容，又给流式读取留足余量。 */
    private static final String HEAP = "-Xmx64m";

    @Test
    @Timeout(300)
    void streamsSheetBiggerThanHeapInSmallHeapJvm(@TempDir Path temp) throws Exception {
        Path xlsx = temp.resolve("big.xlsx");
        int checksum = writeBigWorkbook(xlsx);

        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                HEAP,
                                "-cp",
                                System.getProperty("java.class.path"),
                                BigSheetProbe.class.getName(),
                                xlsx.toString(),
                                String.valueOf(ROWS),
                                String.valueOf(checksum))
                        .redirectErrorStream(true)
                        .start();
        StringBuilder output = new StringBuilder();
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
        String report = output.toString();
        assertTrue(finished, "子进程未在时限内结束：" + report);
        assertTrue(
                process.exitValue() == 0 && report.startsWith("OK " + ROWS + " "),
                "受限堆（"
                        + HEAP
                        + "）流式读取失败（退出码 "
                        + process.exitValue()
                        + "，内容驻留约需 130MB+，整表读入内存会 OOM）：\n"
                        + report);
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 流式写出大表（不压缩，生成快、磁盘上就是百兆级），边写边按探针口径算校验和。
     *
     * @return 期望校验和（与 BigSheetProbe 折叠口径一致）
     */
    private static int writeBigWorkbook(Path file) throws IOException {
        int hasher = 0;
        try (ZipOutputStream zip =
                new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(file)),
                        StandardCharsets.UTF_8)) {
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            put(zip, "[Content_Types].xml", contentTypesXml());
            put(zip, "_rels/.rels", rootRelsXml());
            put(zip, "xl/workbook.xml", workbookXml());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml());
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            writeUtf8(
                    zip,
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                    <sheetData>\
                    """);
            // 表头行（第 1 行）；校验和只折数据行（探针从 dataRows 起算，不含表头）
            writeUtf8(
                    zip,
                    "<row r=\"1\">"
                            + inlineCell(1, 0, "病案号")
                            + inlineCell(1, 1, "姓名")
                            + inlineCell(1, 2, "年度")
                            + "</row>");
            StringBuilder row = new StringBuilder(8 * 1024);
            for (int r = 0; r < ROWS; r++) {
                int rowNo = r + 2;
                row.setLength(0);
                row.append("<row r=\"").append(rowNo).append("\">");
                for (int c = 0; c < COLUMNS; c++) {
                    String value = cell(r, c);
                    row.append("<c r=\"")
                            .append(columnLetters(c))
                            .append(rowNo)
                            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(value)
                            .append("</t></is></c>");
                    hasher = hasher * 31 + value.hashCode();
                }
                row.append("</row>");
                writeUtf8(zip, row.toString());
                hasher = hasher * 31 + rowNo;
            }
            writeUtf8(zip, "</sheetData></worksheet>");
            zip.closeEntry();
        }
        return hasher;
    }

    /** 单元格内容：足够长（约 22–26 字符），保证全量字符串驻留超过堆上限。 */
    private static String cell(int row, int col) {
        return "第" + row + "行第" + col + "列-虚构示例值-padding";
    }

    private static String inlineCell(int rowNo, int col, String value) {
        return "<c r=\""
                + columnLetters(col)
                + rowNo
                + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + value
                + "</t></is></c>";
    }

    private static void writeUtf8(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        writeUtf8(zip, content);
        zip.closeEntry();
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
                <sheets><sheet name="患者数据" sheetId="1" r:id="rId1"/></sheets></workbook>\
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

    /** 列字母（与 SimpleXlsx / ImportCell 同口径）。 */
    private static String columnLetters(int colNo) {
        int index = colNo + 1;
        List<Character> letters = new ArrayList<>();
        while (index > 0) {
            index--;
            letters.add(0, (char) ('A' + index % 26));
            index /= 26;
        }
        StringBuilder text = new StringBuilder();
        for (char letter : letters) {
            text.append(letter);
        }
        return text.toString();
    }
}
