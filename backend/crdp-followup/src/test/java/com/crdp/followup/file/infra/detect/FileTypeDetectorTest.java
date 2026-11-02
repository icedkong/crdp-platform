package com.crdp.followup.file.infra.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.file.domain.FileFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * E10-T01 / A-ATT-008、C-SCAN-018：真实类型检测（Tika 按内容魔数，不传文件名）。
 *
 * <p>锁定：白名单 9 种格式的合成样例全部识别为预期 MIME；可执行文件（PE / ELF）、脚本、压缩包 识别结果不落在白名单（交由 FileUploadService
 * 拒绝）。全部合成字节，不含真实文件。
 */
public class FileTypeDetectorTest {

    private final FileTypeDetector detector = new FileTypeDetector();

    @Test
    void detectsDocuments() throws IOException {
        assertEquals(Optional.of(FileFormat.PDF), FileFormat.ofMime(detector.detect(pdf())));
        assertEquals(Optional.of(FileFormat.JPG), FileFormat.ofMime(detector.detect(jpeg())));
        assertEquals(Optional.of(FileFormat.PNG), FileFormat.ofMime(detector.detect(png())));
        assertEquals(Optional.of(FileFormat.DOCX), FileFormat.ofMime(detector.detect(docx())));
        assertEquals(Optional.of(FileFormat.XLSX), FileFormat.ofMime(detector.detect(xlsx())));
    }

    @Test
    void detectsAudio() {
        assertEquals(Optional.of(FileFormat.MP3), FileFormat.ofMime(detector.detect(mp3(64))));
        assertEquals(Optional.of(FileFormat.WAV), FileFormat.ofMime(detector.detect(wav())));
        assertEquals(Optional.of(FileFormat.AMR), FileFormat.ofMime(detector.detect(amr())));
        // m4a 是否被 tika-core 识别取决于内置 mime 注册表；识别不出时按「不支持格式」拒绝，
        // 这里锁定现状（识别为 audio/mp4），升级 Tika 版本时此断言提醒复核。
        org.junit.jupiter.api.Assertions.assertEquals(
                "audio/mp4", detector.detect(m4a()), "m4a 合成样例应识别为 audio/mp4");
    }

    @Test
    void executablesAndScriptsAreOutsideWhitelist() throws IOException {
        // PE（MZ 头）与 ELF：改扩展名也不进白名单
        assertEquals(Optional.empty(), FileFormat.ofMime(detector.detect(peExe())));
        assertEquals(Optional.empty(), FileFormat.ofMime(detector.detect(elf())));
        // 脚本与压缩包同样不支持（NFR 恶意代码防范：拒绝脚本、宏 Office 与压缩包）
        assertEquals(Optional.empty(), FileFormat.ofMime(detector.detect(script())));
        assertEquals(Optional.empty(), FileFormat.ofMime(detector.detect(zip())));
        // 伪装检查：PE 内容无论叫什么名字，识别结果都不是 PDF
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "application/pdf", detector.detect(peExe()));
    }

    // ---------------------------------------------------------------- 合成样例（最小魔数）

    public static byte[] pdf() {
        return ("%PDF-1.7\n%"
                        + "\u00e2\u00e3\u00cf\u00d3\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                        + "trailer\n<< /Root 1 0 R >>\n%%EOF")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] jpeg() {
        return new byte[] {
            (byte) 0xFF,
            (byte) 0xD8,
            (byte) 0xFF,
            (byte) 0xE0,
            0x00,
            0x10,
            'J',
            'F',
            'I',
            'F',
            0x00,
            0x01,
            0x01,
            0x00,
            0x00,
            0x01,
            0x00,
            0x01,
            0x00,
            0x00,
            (byte) 0xFF,
            (byte) 0xD9
        };
    }

    public static byte[] png() {
        return new byte[] {
            (byte) 0x89,
            'P',
            'N',
            'G',
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
            0x00,
            0x00,
            0x0D,
            'I',
            'H',
            'D',
            'R',
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            8,
            2,
            0,
            0,
            0,
            (byte) 0x90,
            0x77,
            0x53,
            (byte) 0xDE
        };
    }

    public static byte[] wav() {
        byte[] body =
                ("RIFFFFFFWAVEfmt " + "aaaaaaaaaaaaaaaaaaaadataaaaaaaaa")
                        .getBytes(StandardCharsets.US_ASCII);
        return body;
    }

    public static byte[] mp3(int padding) {
        byte[] header = new byte[] {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] payload = new byte[header.length + padding];
        System.arraycopy(header, 0, payload, 0, header.length);
        for (int i = header.length; i < payload.length; i++) {
            payload[i] = 0x55; // 帧填充字节
        }
        return payload;
    }

    public static byte[] m4a() {
        return new byte[] {
            0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 'M', '4', 'A', ' ',
            0x00, 0x00, 0x00, 0x00
        };
    }

    public static byte[] amr() {
        return "#!AMR\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** PE 可执行文件最小合成样例（MZ 头 + DOS stub + PE 签名占位），不可执行、不含真实代码。 */
    public static byte[] peExe() {
        byte[] exe = new byte[256];
        exe[0] = 'M';
        exe[1] = 'Z';
        for (int i = 2; i < 0x40; i++) {
            exe[i] = (byte) 0x90; // DOS stub 填充
        }
        exe[0x3C] = 0x40; // e_lfanew
        exe[0x40] = 'P';
        exe[0x41] = 'E';
        exe[0x42] = 0;
        exe[0x43] = 0;
        return exe;
    }

    public static byte[] elf() {
        byte[] elf = new byte[64];
        elf[0] = 0x7F;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        elf[4] = 2;
        return elf;
    }

    public static byte[] script() {
        return "#!/bin/sh\necho 示例（虚构）\n".getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("demo.txt"));
            zip.write("示例（虚构）".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** 最小 OOXML 包（[Content_Types].xml + _rels/.rels + 主部件）。 */
    private static byte[] ooxml(String mainPart, String mainContentType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                    + "<Override PartName=\""
                                    + mainPart
                                    + "\" ContentType=\""
                                    + mainContentType
                                    + "\"/></Types>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\""
                                    + mainPart.substring(1)
                                    + "\"/></Relationships>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(mainPart));
            zip.write("<?xml version=\"1.0\"?><root/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    public static byte[] docx() throws IOException {
        return ooxml(
                "/word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
    }

    public static byte[] xlsx() throws IOException {
        return ooxml(
                "/xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
    }
}
