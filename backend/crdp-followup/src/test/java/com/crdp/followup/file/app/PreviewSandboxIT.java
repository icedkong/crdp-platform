package com.crdp.followup.file.app;

import static org.assertj.core.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真正调用无网络隔离启动器；不向外部转换服务发送文件，所有源文件均为本机生成的示例。 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_PREVIEW_LAUNCHER", matches = ".+")
class PreviewSandboxIT {
    private PreviewRenderer renderer() {
        var p = new FilePreviewProperties();
        p.setRendererLauncher(System.getenv("CRDP_IT_PREVIEW_LAUNCHER"));
        return new PreviewRenderer(p);
    }

    private static final String MARK = "医生（示例）（doctor.example） · 医院（示例） · 2026-11-19 15:00";

    @Test
    void actualDocxIsConvertedToBurnedPdfInsideSandbox() throws Exception {
        var rendered = renderer().render(docx(), "docx", MARK);
        assertThat(rendered.mime()).isEqualTo("application/pdf");
        try (var pdf = Loader.loadPDF(rendered.body())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(pdf).trim()).isEmpty();
            assertThat(pdf.getPage(0).getResources().getXObjectNames()).isNotEmpty();
        }
        // 只输出示例 QA 文件到测试构建目录，生产渲染器不落持久副本。
        Path qa = Path.of("target/preview-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("docx-watermarked-example.pdf"), rendered.body());
    }

    @Test
    void imageAndPdfAlsoUseTheSameIsolatedPipeline() throws Exception {
        var image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 700);
        g.dispose();
        var source = new ByteArrayOutputStream();
        ImageIO.write(image, "png", source);
        var rendered = renderer().render(source.toByteArray(), "png", MARK);
        var result = ImageIO.read(new ByteArrayInputStream(rendered.body()));
        long nonwhite = 0;
        for (int y = 0; y < result.getHeight(); y++)
            for (int x = 0; x < result.getWidth(); x++)
                if ((result.getRGB(x, y) & 0xffffff) != 0xffffff) nonwhite++;
        assertThat(nonwhite).isGreaterThan(1000);
        Path qa = Path.of("target/preview-qa");
        Files.createDirectories(qa);
        Files.write(qa.resolve("image-watermarked-example.png"), rendered.body());
        var input = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(input);
        }
        var copy = renderer().render(input.toByteArray(), "pdf", MARK);
        try (var pdf = Loader.loadPDF(copy.body())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void malformedOfficeFailsWithoutDeliveringSource() {
        assertThatThrownBy(() -> renderer().render("not-docx".getBytes(), "docx", MARK))
                .isInstanceOf(com.crdp.framework.web.ApiException.class);
    }

    static byte[] docx() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entry(
                    zip,
                    "[Content_Types].xml",
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(
                    zip,
                    "_rels/.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(
                    zip,
                    "word/document.xml",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>随访报告（示例）</w:t></w:r></w:p><w:p><w:r><w:t>仅用于水印转换验证，无真实患者信息。</w:t></w:r></w:p><w:sectPr/></w:body></w:document>");
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
