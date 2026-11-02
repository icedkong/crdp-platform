package com.crdp.followup.file.infra.image;

import com.crdp.framework.web.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 图片缩略图生成（E10-T03 / tech-plan §10「派生文件：只生成图片缩略图」；任务验收：上传图片后生成缩略图， 无转码）。
 *
 * <p>只用 JDK 自带 ImageIO，不引第三方图像库（与 E10-T01 只引 tika-core 同一取舍）。要点：
 *
 * <ul>
 *   <li>**无转码**：保持原格式（JPG 出 image/jpeg、PNG 出 image/png），不改编码格式、不改扩展名；
 *   <li>不放大：原图各边都不超过最大边（{@code crdp.file.thumbnail.max-edge}，默认 128，设计稿缩略图 64×64 的 2
 *       倍视网膜余量）时，直接复用原字节（不重编码、无损）；
 *   <li>先读头部尺寸再解码：单边 ≤ {@value #MAX_DIMENSION} px 且总像素 ≤ {@value #MAX_PIXELS}
 *       才进入解码，把「解压炸弹」（头部声明巨型尺寸的 PNG）挡在分配像素缓冲之前；
 *   <li>Tika 认魔数但解码不出像素的内容（截断 / 损坏图片）按 40001 拒绝——发生在写对象存储之前， 不留半成品对象（与 E10-T01 内容校验同一口径）。
 * </ul>
 *
 * <p>缩略图服务口径（按流返回、不缓存、审计）随 E10-T04 预览代理；本类只负责生成字节。
 */
@Component
public class ImageThumbnailGenerator {

    /** 单边尺寸上限（px）：超过即拒绝解码，防解压炸弹耗尽内存。 */
    static final int MAX_DIMENSION = 12_000;

    /** 总像素上限：12 000 × 12 000 以内的安全子集，约 40 MP。 */
    static final long MAX_PIXELS = 40_000_000L;

    private final int maxEdge;

    /** 生产装配：最大边来自配置（默认 128，不放大语义见类注释）。 */
    public ImageThumbnailGenerator(@Value("${crdp.file.thumbnail.max-edge:128}") int maxEdge) {
        if (maxEdge < 16 || maxEdge > 1024) {
            // 配置缺陷直接暴露（不静默夹取），与 StorageKeys 校验同一口径
            throw new IllegalArgumentException("缩略图最大边配置须在 16–1024 之间：" + maxEdge);
        }
        this.maxEdge = maxEdge;
    }

    /** 生成的缩略图：字节、MIME（与源一致）、缩略图宽高。 */
    public record Thumbnail(byte[] body, String contentType, int width, int height) {}

    /**
     * 从图片字节生成缩略图。
     *
     * @param body 图片内容（已过 Tika 真实类型校验的 jpg / png）
     * @param sourceMime 源 MIME（image/jpeg / image/png，缩略图保持不变）
     * @throws ApiException 40001 内容无法解析或尺寸超上限
     */
    public Thumbnail generate(byte[] body, String sourceMime) {
        Dimensions size = headerSize(body);
        if (size.width() > MAX_DIMENSION
                || size.height() > MAX_DIMENSION
                || (long) size.width() * size.height() > MAX_PIXELS) {
            throw bad(
                    "图片尺寸过大（"
                            + size.width()
                            + "×"
                            + size.height()
                            + "），单边不超过 "
                            + MAX_DIMENSION
                            + " 像素，已拒绝上传");
        }

        BufferedImage image = decode(body);
        if (Math.max(image.getWidth(), image.getHeight()) <= maxEdge) {
            // 不放大也不重编码：小图的「缩略图」就是原字节（无损、省 CPU）
            return new Thumbnail(body, sourceMime, image.getWidth(), image.getHeight());
        }

        double scale = (double) maxEdge / Math.max(image.getWidth(), image.getHeight());
        int width = Math.max(1, Math.round(image.getWidth() * (float) scale));
        int height = Math.max(1, Math.round(image.getHeight() * (float) scale));
        // JPEG 无透明通道：PNG 保 ARGB，JPG 一律 RGB（JPEG 编码器不接受 alpha）
        boolean hasAlpha = sourceMime.endsWith("png");
        BufferedImage scaled =
                new BufferedImage(
                        width,
                        height,
                        hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return new Thumbnail(encode(scaled, sourceMime), sourceMime, width, height);
    }

    // ---------------------------------------------------------------- 私有

    /** 只解析头部拿宽高（不解码像素），供尺寸上限在分配缓冲前拦截。 */
    private static Dimensions headerSize(byte[] body) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(body))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw bad("图片内容无法解析（可能已损坏），已拒绝上传");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw bad("图片内容无法解析（可能已损坏），已拒绝上传");
        }
    }

    private static BufferedImage decode(byte[] body) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
            if (image == null) {
                throw bad("图片内容无法解析（可能已损坏），已拒绝上传");
            }
            return image;
        } catch (IOException ex) {
            throw bad("图片内容无法解析（可能已损坏），已拒绝上传");
        }
    }

    /** 按源 MIME 重编码（jpeg → image/jpeg writer，png → png writer），格式不变（无转码）。 */
    private static byte[] encode(BufferedImage image, String sourceMime) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String format = sourceMime.endsWith("png") ? "png" : "jpeg";
            if (!ImageIO.write(image, format, out)) {
                // JDK 自带 jpeg / png writer，理论不可达；真发生时按内容无法解析口径拒绝
                throw bad("图片内容无法解析（可能已损坏），已拒绝上传");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw bad("图片内容无法解析（可能已损坏），已拒绝上传");
        }
    }

    private record Dimensions(int width, int height) {}

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
