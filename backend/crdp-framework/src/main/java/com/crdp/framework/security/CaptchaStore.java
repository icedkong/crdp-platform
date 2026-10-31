package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * E04-T05 / PLT-AUTH-001、007：登录图形验证码（连续失败 3 次后必须携带）。
 *
 * <p>Redis 只存 4 位验证码的 SHA-256 摘要（不落明文），有效期 {@code captchaTtlSeconds}（默认 5 分钟）； 验证忽略大小写、一次一密
 * ——无论对错，验证后立即作废，前端在 40102 后重新取图。错误 / 过期 / 缺参统一 40102，不区分存在性。 取图接口公开，按 IP 每小时限流（AGENTS.md 第 6 节）。
 */
@Component
public class CaptchaStore {

    /** 去掉易混字符（0/O、1/I）的字母数字表。 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int WIDTH = 132;
    private static final int HEIGHT = 44;
    private static final int CODE_LENGTH = 4;

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;
    private final SecureRandom random = new SecureRandom();

    public CaptchaStore(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 签发验证码。clientIp 用于按 IP 限流，为 null 表示内部调用不限流。返回 PNG 的 data URL。 */
    public CaptchaImage issue(String clientIp) {
        if (clientIp != null) {
            Long issued = redis.opsForValue().increment(ipKey(clientIp));
            if (issued != null && issued == 1L) {
                redis.expire(ipKey(clientIp), Duration.ofHours(1));
            }
            if (issued != null && issued > properties.getCaptchaIpHourlyLimit()) {
                throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
            }
        }
        String captchaId = UUID.randomUUID().toString();
        String code = randomCode();
        put(captchaId, code);
        return new CaptchaImage(captchaId, dataUrl(render(code)));
    }

    /** 校验并消费验证码。缺失、未知、过期、错误统一 40102；一次一密。 */
    public void verify(String captchaId, String code) {
        if (captchaId == null || captchaId.isBlank() || code == null || code.isBlank()) {
            throw new ApiException(401, 40102, AuthMessages.BAD_SMS_CODE);
        }
        String expected = redis.opsForValue().get(codeKey(captchaId));
        redis.delete(codeKey(captchaId));
        if (expected == null || !expected.equals(digest(code.toUpperCase(java.util.Locale.ROOT)))) {
            throw new ApiException(401, 40102, AuthMessages.BAD_SMS_CODE);
        }
    }

    /** 写入验证码摘要。包内可见，供同包测试构造已知验证码。 */
    void put(String captchaId, String code) {
        redis.opsForValue()
                .set(
                        codeKey(captchaId),
                        digest(code.toUpperCase(java.util.Locale.ROOT)),
                        Duration.ofSeconds(properties.getCaptchaTtlSeconds()));
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private BufferedImage render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            // 干扰线：细、浅色，不遮盖字符
            for (int i = 0; i < 6; i++) {
                graphics.setColor(
                        new Color(170 + random.nextInt(60), 170 + random.nextInt(60), 200));
                graphics.drawLine(
                        random.nextInt(WIDTH),
                        random.nextInt(HEIGHT),
                        random.nextInt(WIDTH),
                        random.nextInt(HEIGHT));
            }
            Font font = new Font("SansSerif", Font.BOLD, 26);
            for (int i = 0; i < code.length(); i++) {
                graphics.setFont(font);
                graphics.setColor(
                        new Color(
                                40 + random.nextInt(80),
                                40 + random.nextInt(80),
                                120 + random.nextInt(100)));
                int x = 12 + i * (WIDTH - 30) / CODE_LENGTH + random.nextInt(6);
                int y = HEIGHT / 2 + 10 + random.nextInt(6) - 3;
                double theta = (random.nextInt(50) - 25) * Math.PI / 180.0;
                graphics.rotate(theta, x, y);
                graphics.drawString(String.valueOf(code.charAt(i)), x, y);
                graphics.rotate(-theta, x, y);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static String dataUrl(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private static String digest(String code) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            sha256.digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private static String codeKey(String captchaId) {
        return "crdp:captcha:code:" + captchaId;
    }

    private static String ipKey(String clientIp) {
        return "crdp:captcha:ip:" + clientIp;
    }

    /** 取图结果：captchaId 与 PNG 的 data URL（前端 <img :src> 直用）。 */
    public record CaptchaImage(String captchaId, String image) {}
}
