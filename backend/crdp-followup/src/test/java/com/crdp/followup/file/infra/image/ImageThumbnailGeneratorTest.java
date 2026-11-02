package com.crdp.followup.file.infra.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.file.infra.detect.FileTypeDetectorTest;
import com.crdp.framework.web.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * E10-T03 缩略图生成（任务验收：上传图片后生成缩略图，无转码；tech-plan §10「派生文件：只生成图片缩略图」）。
 *
 * <p>锁定：①大图按最大边等比缩小、**保持原格式**（JPG 出 JPEG、PNG 出 PNG，不改编码格式 = 无转码）； ②小图（不超过最大边）直接复用原字节、不放大不重编码；③Tika
 * 认、JDK 解码不了的内容（损坏图）→ 40001；④先读头部尺寸再解码， 巨型头（解压炸弹）在分配像素前拒绝。全部合成样例，不含真实文件。
 */
public class ImageThumbnailGeneratorTest {

    private final ImageThumbnailGenerator generator = new ImageThumbnailGenerator(128);

    /** 大图 JPEG：等比缩到最大边 128（300×200 → 128×85），仍是 JPEG 魔数，可再次解码。 */
    @Test
    void largeJpegScaledDownKeepingFormat() throws IOException {
        ImageThumbnailGenerator.Thumbnail thumb = generator.generate(jpeg(300, 200), "image/jpeg");

        assertEquals("image/jpeg", thumb.contentType());
        assertEquals(128, thumb.width());
        assertEquals(85, thumb.height());
        assertEquals((byte) 0xFF, thumb.body()[0], "JPEG SOI 高字节");
        assertEquals((byte) 0xD8, thumb.body()[1], "JPEG SOI 低字节");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(thumb.body()));
        assertEquals(128, decoded.getWidth());
        assertEquals(85, decoded.getHeight());
    }

    /** 大图 PNG：等比缩小（300×200 → 128×85）且仍是 PNG（不转 JPEG）。 */
    @Test
    void largePngScaledDownKeepingPng() throws IOException {
        ImageThumbnailGenerator.Thumbnail thumb = generator.generate(png(300, 200), "image/png");

        assertEquals("image/png", thumb.contentType());
        assertEquals(128, thumb.width());
        assertEquals(85, thumb.height());
        assertEquals((byte) 0x89, thumb.body()[0], "PNG 魔数首字节");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(thumb.body()));
        assertEquals(128, decoded.getWidth());
        assertEquals(85, decoded.getHeight());
    }

    /** 竖图：按高为最大边缩放（200×300 → 85×128）。 */
    @Test
    void portraitScaledByHeight() {
        ImageThumbnailGenerator.Thumbnail thumb = generator.generate(jpeg(200, 300), "image/jpeg");
        assertEquals(85, thumb.width());
        assertEquals(128, thumb.height());
    }

    /** 小图（各边均 ≤ 最大边）：缩略图就是原字节（不放大、不重编码，无损）。 */
    @Test
    void smallImageReusesOriginalBytes() {
        byte[] body = png(50, 40);

        ImageThumbnailGenerator.Thumbnail thumb = generator.generate(body, "image/png");

        assertEquals("image/png", thumb.contentType());
        assertEquals(50, thumb.width());
        assertEquals(40, thumb.height());
        assertArrayEquals(body, thumb.body(), "小图不重编码，直接复用原字节");
    }

    /** 任务口径（无转码的锁死）：格式不改变，PNG 不会被存成 JPEG、JPEG 不会变 PNG。 */
    @Test
    void formatNeverChanges() {
        assertEquals("image/png", generator.generate(png(300, 200), "image/png").contentType());
        assertEquals("image/jpeg", generator.generate(jpeg(300, 200), "image/jpeg").contentType());
    }

    /** Tika 认魔数但解码不出像素（损坏 / 截断的图片）→ 40001 拒绝。 */
    @Test
    void undecodableImageRejected() {
        ApiException corrupt =
                assertThrows(
                        ApiException.class,
                        () -> generator.generate(FileTypeDetectorTest.jpeg(), "image/jpeg"));
        assertEquals(40001, corrupt.code());
        assertTrue(corrupt.getMessage().contains("无法解析"), corrupt.getMessage());

        ApiException headerOnlyPng =
                assertThrows(
                        ApiException.class,
                        () -> generator.generate(FileTypeDetectorTest.png(), "image/png"));
        assertEquals(40001, headerOnlyPng.code());
    }

    /** 解压炸弹：头部声明巨型尺寸，在分配像素缓冲前按尺寸上限拒绝（40001）。 */
    @Test
    void oversizedDimensionsRejectedBeforeDecoding() {
        ApiException bomb =
                assertThrows(
                        ApiException.class,
                        () -> generator.generate(headerOnlyPng(30_000, 30_000), "image/png"));
        assertEquals(40001, bomb.code());
        assertTrue(bomb.getMessage().contains("尺寸"), bomb.getMessage());

        ApiException tall =
                assertThrows(
                        ApiException.class,
                        () -> generator.generate(headerOnlyPng(100, 50_000), "image/png"));
        assertEquals(40001, tall.code());
    }

    // ---------------------------------------------------------------- 合成样例（全部程序生成）

    /** 合成 JPEG（w×h，画渐变色块保证有内容可压缩）。 */
    public static byte[] jpeg(int width, int height) {
        return encode(image(width, height, false), "jpeg");
    }

    /** 合成 PNG（w×h）。 */
    public static byte[] png(int width, int height) {
        return encode(image(width, height, true), "png");
    }

    private static BufferedImage image(int width, int height, boolean alpha) {
        BufferedImage img =
                new BufferedImage(
                        width,
                        height,
                        alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            for (int x = 0; x < width; x += 7) {
                g.setColor(new Color((x * 7) % 255, 80, 160));
                g.fillRect(x, 0, 7, height);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) {
                throw new IllegalStateException("无 " + format + " 编码器（JDK 自带，理论不可达）");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * 只含 IHDR / IEND 的 PNG（头部声明 w×h，无像素数据）：Tika 按魔数识别为 image/png， ImageIO
     * 能读出尺寸但读不出像素——用于验证尺寸上限在解码前拦截。
     */
    static byte[] headerOnlyPng(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            ByteBuffer ihdr =
                    ByteBuffer.allocate(13)
                            .putInt(width)
                            .putInt(height)
                            .put((byte) 8) // 位深
                            .put((byte) 2) // 颜色类型 RGB
                            .put((byte) 0)
                            .put((byte) 0)
                            .put((byte) 0);
            chunk(out, "IHDR", ihdr.array());
            chunk(out, "IEND", new byte[0]);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(type.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
