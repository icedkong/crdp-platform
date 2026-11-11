package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** E05-T08 纯函数锁定：证明 PDF 的换行与结构、CSV 转义、保留项字段校验、对象相对键、附件 / 文本工具。 */
class TenantCancelFlowTest {

    // ---------------------------------------------------------------- CancelCertificatePdf

    @Test
    void pdfHasValidStructureAndXrefOffset() {
        byte[] pdf =
                CancelCertificatePdf.build(
                        "数据销毁证明", List.of("证明编号：DEST-000001", "医院：虚构示例医院癸（租户编码 txyz123）"));
        String head = new String(pdf, 0, 8, StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF-1.4"), "PDF 头");
        String all = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(all.contains("/Type /Catalog"));
        assertTrue(all.contains("/BaseFont /STSong-Light"), "中文用非嵌入 CID 字体");
        assertTrue(all.contains("/Encoding /UniGB-UCS2-H"));
        assertTrue(all.contains("/DescendantFonts [6 0 R]"));
        assertTrue(all.endsWith("%%EOF\n"));
        // startxref 指向 xref 表（自描述校验）
        int start = all.lastIndexOf("startxref\n");
        int offset = Integer.parseInt(all.substring(start + 10, all.indexOf("\n", start + 10)));
        assertEquals("xref\n0 7\n", all.substring(offset, offset + 9), "startxref 指向 xref 表");
        // 中文以 UTF-16BE 十六进制写入内容流（“数据销毁证明” = 6570 636E 9500 6BC1 8BC1 660E）
        assertTrue(all.contains("<6570636E95006BC18BC1660E>"), "标题以 UTF-16BE 十六进制渲染");
    }

    @Test
    void wrapCountsCjkAsDoubleWidth() {
        // 12 个汉字、maxHalfWidth=10 → 每行 5 个汉字
        assertEquals(
                List.of("数据销毁证", "明数据销毁", "证明"), CancelCertificatePdf.wrap("数据销毁证明数据销毁证明", 10));
        // ASCII 1 半宽
        assertEquals(List.of("abcdefghij", "klm"), CancelCertificatePdf.wrap("abcdefghijklm", 10));
        // 混排：2 汉字（4）+ 6 字母 = 10
        assertEquals(List.of("数据abcdef", "gh"), CancelCertificatePdf.wrap("数据abcdefgh", 10));
        assertTrue(CancelCertificatePdf.wrap(null, 10).isEmpty());
        assertTrue(CancelCertificatePdf.wrap("", 10).isEmpty());
        // 单个超宽字符不丢字
        assertEquals(List.of("数", "据"), CancelCertificatePdf.wrap("数据", 1));
    }

    // ---------------------------------------------------------------- CSV 转义（RFC 4180）

    @Test
    void csvRowEscapesQuotesCommasAndNewlines() {
        assertEquals("a,b", TenantPackageBuilder.csvRow(List.of("a", "b")));
        assertEquals("\"a,b\"", TenantPackageBuilder.csvRow(List.of("a,b")));
        assertEquals(
                "\"he said \"\"hi\"\"\"", TenantPackageBuilder.csvRow(List.of("he said \"hi\"")));
        assertEquals("\"line1\nline2\"", TenantPackageBuilder.csvRow(List.of("line1\nline2")));
        assertEquals(",,", TenantPackageBuilder.csvRow(java.util.Arrays.asList(null, "", "")));
    }

    // ---------------------------------------------------------------- 保留项校验（PLT-TEN-021）

    @Test
    void retentionValidationRequiresScopeBasisAndFutureDate() {
        TenantRetentionService.validateInput("RCT 原始资料", "《药物临床试验质量管理规范》", "2099-12-31");
        // 缺范围 / 依据 / 期满日
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantRetentionService.validateInput(" ", "依据", "2099-12-31"))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantRetentionService.validateInput("范围", "", "2099-12-31"))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantRetentionService.validateInput("范围", "依据", null))
                        .code());
        // 过去的期满日与非法格式
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantRetentionService.validateInput(
                                                "范围", "依据", "2020-01-01"))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantRetentionService.validateInput(
                                                "范围", "依据", "2099/12/31"))
                        .code());
        // 超长
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        TenantRetentionService.validateInput(
                                                "范".repeat(201), "依据", "2099-12-31"))
                        .code());
    }

    // ---------------------------------------------------------------- 工具

    @Test
    void textRequiredAndBounded() {
        assertEquals("示例原因", TenantCancelService.text(" 示例原因 ", "注销原因", 500));
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> TenantCancelService.text(null, "原因", 500))
                        .code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> TenantCancelService.text("  ", "原因", 500))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantCancelService.text("长".repeat(501), "原因", 500))
                        .code());
    }

    @Test
    void sha256HexIsLowercaseDigest() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                TenantCancelService.sha256Hex(new byte[0]),
                "空字节的 SHA-256");
        String hex = TenantCancelService.sha256Hex("crdp".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]{64}"));
    }

    @Test
    void relativeOfStripsPlatformPrefix() {
        assertEquals(
                "cancel/1/export/EXP-1-x.crdpa",
                TenantExportService.relativeOf("platform/cancel/1/export/EXP-1-x.crdpa"));
        assertEquals("a/b", TenantExportService.relativeOf("a/b"));
        assertNotNull(TenantPackageBuilder.safeName("报告 单/2026#(1).pdf"));
        assertEquals("_", TenantPackageBuilder.safeName("???"), "非法字符替换为下划线");
        assertEquals("file", TenantPackageBuilder.safeName(""), "空名兜底 file");
    }
}
