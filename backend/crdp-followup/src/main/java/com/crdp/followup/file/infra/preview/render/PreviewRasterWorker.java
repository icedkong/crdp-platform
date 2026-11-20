package com.crdp.followup.file.infra.preview.render;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.*;

/** 只能由隔离启动器调用的渲染进程；新建纯图片 PDF，原始文本、附件、脚本和元数据不进入副本。 */
public final class PreviewRasterWorker {
    private static final long MAX_PIXELS = 12_000_000L, MAX_TOTAL_PIXELS = 100_000_000L;
    private static final int MAX_PAGES = 50;

    private PreviewRasterWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IOException("渲染参数不合法");
        Path work = Path.of(args[0]);
        String format = args[1];
        if (!Set.of("jpg", "png", "pdf", "docx").contains(format)) throw new IOException("不支持预览类型");
        String mark = Files.readString(work.resolve("watermark.txt"));
        if (mark.isBlank() || mark.length() > 1024) throw new IOException("水印身份不完整");
        Path input = work.resolve("input.bin");
        if (Files.size(input) > 64L * 1024 * 1024) throw new IOException("超过输入预算");
        Files.createDirectories(work.resolve("output"));
        if (Set.of("jpg", "png").contains(format)) {
            BufferedImage image = readImage(input);
            paint(image, mark);
            ImageIO.write(image, "png", work.resolve("output/result.png").toFile());
            image.flush();
        } else {
            if ("docx".equals(format)) input = convertDocx(input, work);
            pdf(input, work.resolve("output/result.pdf"), mark);
        }
    }

    private static BufferedImage readImage(Path input) throws IOException {
        try (var stream = ImageIO.createImageInputStream(input.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("图片不可解析");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
                var source = reader.read(0);
                var result = canvas(source);
                var graphics = result.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
                    drawSource(graphics, source, result);
                } finally {
                    graphics.dispose();
                    source.flush();
                }
                return result;
            } finally {
                reader.dispose();
            }
        }
    }

    private static void pdf(Path input, Path output, String mark) throws IOException {
        try (PDDocument source = Loader.loadPDF(input.toFile());
                PDDocument result = new PDDocument()) {
            if (source.getNumberOfPages() > MAX_PAGES || source.getNumberOfPages() < 1)
                throw new IOException("超过页数预算");
            var renderer = new PDFRenderer(source);
            renderer.setSubsamplingAllowed(true);
            long total = 0;
            for (int i = 0; i < source.getNumberOfPages(); i++) {
                var page = source.getPage(i);
                var box = page.getCropBox();
                if (!Float.isFinite(box.getWidth()) || !Float.isFinite(box.getHeight()))
                    throw new IOException("页面尺寸异常");
                checkPixels(
                        (long) Math.ceil(box.getWidth() * 2),
                        (long) Math.ceil(box.getHeight() * 2));
                var raster = renderer.renderImageWithDPI(i, 144, ImageType.RGB);
                var image = canvas(raster);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    drawSource(graphics, raster, image);
                } finally {
                    graphics.dispose();
                    raster.flush();
                }
                checkPixels(image.getWidth(), image.getHeight());
                total += (long) image.getWidth() * image.getHeight();
                if (total > MAX_TOTAL_PIXELS) throw new IOException("超过总像素预算");
                try {
                    paint(image, mark);
                    var rendered =
                            new PDPage(
                                    new PDRectangle(image.getWidth() / 2f, image.getHeight() / 2f));
                    result.addPage(rendered);
                    try (var canvas = new PDPageContentStream(result, rendered)) {
                        canvas.drawImage(
                                LosslessFactory.createFromImage(result, image),
                                0,
                                0,
                                rendered.getMediaBox().getWidth(),
                                rendered.getMediaBox().getHeight());
                    }
                } finally {
                    image.flush();
                }
            }
            result.save(output.toFile());
        }
    }

    private static BufferedImage canvas(BufferedImage source) throws IOException {
        int width = Math.max(800, source.getWidth()), height = Math.max(600, source.getHeight());
        checkPixels(width, height);
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static void drawSource(
            Graphics2D graphics, BufferedImage source, BufferedImage target) {
        double scale =
                Math.min(
                        (double) target.getWidth() / source.getWidth(),
                        (double) target.getHeight() / source.getHeight());
        int width = (int) Math.round(source.getWidth() * scale),
                height = (int) Math.round(source.getHeight() * scale);
        graphics.drawImage(
                source,
                (target.getWidth() - width) / 2,
                (target.getHeight() - height) / 2,
                width,
                height,
                null);
    }

    private static void checkPixels(long width, long height) throws IOException {
        if (width <= 0
                || height <= 0
                || width > MAX_PIXELS
                || height > MAX_PIXELS
                || width * height > MAX_PIXELS) throw new IOException("超过图片像素预算");
    }

    private static void paint(BufferedImage image, String mark) throws IOException {
        var graphics = image.createGraphics();
        try {
            var font = new Font(Font.SANS_SERIF, Font.PLAIN, 22);
            if (font.canDisplayUpTo(mark) != -1) throw new IOException("缺少水印字体");
            graphics.setFont(font);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var metrics = graphics.getFontMetrics();
            var lines = new ArrayList<String>();
            var line = new StringBuilder();
            for (int cp : mark.codePoints().toArray()) {
                String next = new String(Character.toChars(cp));
                if (metrics.stringWidth(line + next) > 440 && !line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
            }
            if (!line.isEmpty()) lines.add(line.toString());
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f));
            graphics.setColor(Color.DARK_GRAY);
            graphics.rotate(Math.toRadians(-20), image.getWidth() / 2.0, image.getHeight() / 2.0);
            int step = Math.max(160, lines.size() * 30 + 70);
            for (int y = -image.getWidth(); y < image.getHeight() + image.getWidth(); y += step)
                for (int x = -image.getHeight(); x < image.getWidth() + image.getHeight(); x += 480)
                    for (int index = 0; index < lines.size(); index++)
                        graphics.drawString(lines.get(index), x, y + index * 30);
        } finally {
            graphics.dispose();
        }
    }

    private static Path convertDocx(Path input, Path work) throws Exception {
        // 不展开源 ZIP；先限制解压总量和条目数，宏 Office 不进入转换器。
        try (var zip = new ZipInputStream(Files.newInputStream(input))) {
            long size = 0;
            int count = 0;
            byte[] buffer = new byte[8192];
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 2000
                        || entry.getName().contains("..")
                        || entry.getName().startsWith("/")
                        || entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject"))
                    throw new IOException("不安全的 DOCX 结构");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read;
                    if (size > 128L * 1024 * 1024) throw new IOException("DOCX 超过展开预算");
                }
            }
            if (count == 0) throw new IOException("DOCX 不可解析");
        }
        Path docx = work.resolve("input.docx");
        Files.copy(input, docx);
        Path profile = Files.createTempDirectory("office-profile-");
        Path user = Files.createDirectories(profile.resolve("user"));
        Files.writeString(
                user.resolve("registrymodifications.xcu"),
                """
            <?xml version="1.0" encoding="UTF-8"?>
            <oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item></oor:items>
            """);
        var command =
                new ProcessBuilder(
                        "/usr/bin/soffice",
                        "-env:UserInstallation=" + profile.toUri(),
                        "--headless",
                        "--norestore",
                        "--nodefault",
                        "--nolockcheck",
                        "--convert-to",
                        "pdf:writer_pdf_Export",
                        "--outdir",
                        work.toString(),
                        docx.toString());
        command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = command.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("DOCX 转换失败");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        Path pdf = work.resolve("input.pdf");
        if (!Files.isRegularFile(pdf) || Files.size(pdf) > 64L * 1024 * 1024)
            throw new IOException("DOCX 转换结果异常");
        return pdf;
    }
}
