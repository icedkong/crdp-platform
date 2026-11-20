package com.crdp.followup.file.infra.preview.render;

import static org.assertj.core.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 实际像素烧录；原始 PDF 文本 / 元数据 / 动作不进入副本，超预算与坏文件失败。 */
class PreviewRasterWorkerTest {
    @TempDir Path work;
    private static final String MARK = "医生（示例）（doctor.example） · 医院（示例） · 2026-11-19 15:00";

    private void setup(byte[] input) throws IOException {
        Files.write(work.resolve("input.bin"), input);
        Files.writeString(work.resolve("watermark.txt"), MARK);
    }

    @Test
    void pngBytesContainVisibleWatermarkEvenOnTinyInput() throws Exception {
        var source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 20, 20);
        g.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        setup(bytes.toByteArray());
        PreviewRasterWorker.main(new String[] {work.toString(), "png"});
        var result = ImageIO.read(work.resolve("output/result.png").toFile());
        assertThat(result.getWidth()).isGreaterThanOrEqualTo(800);
        assertThat(result.getHeight()).isGreaterThanOrEqualTo(600);
        long marked = 0;
        for (int y = 0; y < result.getHeight(); y++)
            for (int x = 0; x < result.getWidth(); x++)
                if ((result.getRGB(x, y) & 0xffffff) != 0xffffff) marked++;
        assertThat(marked).isGreaterThan(1000);
        assertThat(Files.readAllBytes(work.resolve("output/result.png")))
                .isNotEqualTo(bytes.toByteArray());
    }

    @Test
    void pdfIsRebuiltFromBurnedPageImagesWithNoOriginalTextOrActions() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            pdf.getDocumentInformation().setTitle("不得带入副本的原始元数据（示例）");
            pdf.getDocumentCatalog()
                    .setOpenAction(new PDActionJavaScript("console.println('example')"));
            for (int i = 0; i < 2; i++) {
                var page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                try (var text = new PDPageContentStream(pdf, page)) {
                    text.beginText();
                    text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    text.newLineAtOffset(40, 700);
                    text.showText("Clinical record (example)");
                    text.endText();
                }
            }
            pdf.save(bytes);
        }
        setup(bytes.toByteArray());
        PreviewRasterWorker.main(new String[] {work.toString(), "pdf"});
        try (var pdf = Loader.loadPDF(work.resolve("output/result.pdf").toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(pdf).trim()).isEmpty();
            assertThat(pdf.getDocumentInformation().getTitle()).isNull();
            assertThat(pdf.getDocumentCatalog().getOpenAction()).isNull();
            for (var page : pdf.getPages()) {
                assertThat(page.getResources().getXObjectNames()).isNotEmpty();
                assertThat(page.getAnnotations()).isEmpty();
            }
        }
    }

    @Test
    void oversizedPageIsRejectedBeforeAllocatingRaster() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(100000, 100000)));
            pdf.save(bytes);
        }
        setup(bytes.toByteArray());
        assertThatThrownBy(() -> PreviewRasterWorker.main(new String[] {work.toString(), "pdf"}))
                .isInstanceOf(IOException.class);
        assertThat(work.resolve("output/result.pdf")).doesNotExist();
    }

    @Test
    void encryptedPdfDoesNotFallBackToOriginal() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(
                    new StandardProtectionPolicy(
                            "owner-example", "reader-example", new AccessPermission()));
            pdf.save(bytes);
        }
        setup(bytes.toByteArray());
        assertThatThrownBy(() -> PreviewRasterWorker.main(new String[] {work.toString(), "pdf"}))
                .isInstanceOf(IOException.class);
        assertThat(work.resolve("output/result.pdf")).doesNotExist();
    }

    @Test
    void badImageAndUnsupportedFormatAreRejected() throws Exception {
        setup("not-an-image".getBytes());
        for (String format : new String[] {"png", "xlsx"})
            assertThatThrownBy(
                            () -> PreviewRasterWorker.main(new String[] {work.toString(), format}))
                    .isInstanceOf(IOException.class);
    }

    @Test
    void malformedOrMacroDocxNeverReachesOffice() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/vbaProject.bin"));
            zip.write("macro-example".getBytes());
            zip.closeEntry();
        }
        setup(bytes.toByteArray());
        assertThatThrownBy(() -> PreviewRasterWorker.main(new String[] {work.toString(), "docx"}))
                .isInstanceOf(IOException.class);
        assertThat(work.resolve("output/result.pdf")).doesNotExist();
    }
}
