package com.crdp.server.platform;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据销毁证明 PDF（E05-T08 / PLT-TEN-020「文件：PDF，平台签章」）。
 *
 * <p>零依赖的极简 PDF 生成：中文用非嵌入 CID 字体 STSong-Light + UniGB-UCS2-H 编码（Adobe CJK 标准字体， macOS 预览 /
 * Adobe（含亚洲字体包）直接渲染；不含字体包的阅读器可能空白——机读事实源是数据库行与 JSON 字段， PDF 是交付格式）。 Latin 文本同字体渲染（STSong 含 ASCII）。
 */
final class CancelCertificatePdf {

    private static final float PAGE_WIDTH = 595;
    private static final float PAGE_HEIGHT = 842;
    private static final float MARGIN = 50;
    private static final float LINE_HEIGHT = 20;

    private CancelCertificatePdf() {}

    /** 生成证明 PDF：标题 + 标签行（已换行）+ 平台签章脚注。 */
    static byte[] build(String title, List<String> lines) {
        StringBuilder content = new StringBuilder();
        float y = PAGE_HEIGHT - MARGIN - 10;
        // 标题居中（16pt）
        content.append(draw(title, centered(title, 16), y, 16));
        y -= LINE_HEIGHT * 1.4f;
        for (String line : lines) {
            content.append(draw(line, MARGIN, y, 11));
            y -= LINE_HEIGHT;
        }
        // 平台签章脚注
        String stamp = "临床科研数据一体化平台 · 数据销毁证明（电子签章）";
        content.append(draw(stamp, centered(stamp, 9), MARGIN + 10, 9));
        String stream = content.toString();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        int offset1 = out.size();
        write(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int offset2 = out.size();
        write(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        int offset3 = out.size();
        write(
                out,
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                        + (int) PAGE_WIDTH
                        + " "
                        + (int) PAGE_HEIGHT
                        + "] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n");
        int offset4 = out.size();
        write(
                out,
                "4 0 obj\n<< /Length "
                        + stream.getBytes(StandardCharsets.US_ASCII).length
                        + " >>\nstream\n"
                        + stream
                        + "endstream\nendobj\n");
        int offset5 = out.size();
        write(
                out,
                "5 0 obj\n<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding"
                        + " /UniGB-UCS2-H /DescendantFonts [6 0 R] >>\nendobj\n");
        int offset6 = out.size();
        write(
                out,
                "6 0 obj\n<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light"
                        + " /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >>"
                        + " /DW 1000 >>\nendobj\n");
        int xref = out.size();
        write(out, "xref\n0 7\n0000000000 65535 f \n");
        for (int offset : new int[] {offset1, offset2, offset3, offset4, offset5, offset6}) {
            write(out, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        write(out, "trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String draw(String text, float x, float y, float size) {
        return "BT /F1 "
                + (int) size
                + " Tf "
                + (int) x
                + " "
                + (int) y
                + " Td <"
                + hex(text)
                + "> Tj ET\n";
    }

    private static float centered(String text, float size) {
        return Math.max(MARGIN, (PAGE_WIDTH - widthOf(text, size)) / 2);
    }

    /** 换行（纯函数，单测锁定）：中日韩全角按 2 个半宽、ASCII 半宽 1 计，maxHalfWidth 为每行半宽上限。 */
    static List<String> wrap(String text, int maxHalfWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            int w = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ? 2 : 1;
            if (width + w > maxHalfWidth && current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
                width = 0;
            }
            current.append(ch);
            width += w;
            i += ch.length();
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** 显示宽度（pt 近似）：全角 = size，半角 = size/2。 */
    private static float widthOf(String text, float size) {
        int half = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            half += Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return half * size / 2;
    }

    /** UTF-16BE 十六进制（UniGB-UCS2-H 的字符串形态；BMP 内字符即 UCS-2 编码值）。 */
    private static String hex(String text) {
        StringBuilder builder = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isBmpCodePoint(codePoint)) {
                builder.append(String.format(Locale.ROOT, "%04X", codePoint));
            } else {
                builder.append(
                        String.format(
                                Locale.ROOT,
                                "%04X%04X",
                                Character.highSurrogate(codePoint),
                                Character.lowSurrogate(codePoint)));
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
