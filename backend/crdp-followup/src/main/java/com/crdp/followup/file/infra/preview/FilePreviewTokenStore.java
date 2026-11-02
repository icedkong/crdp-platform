package com.crdp.followup.file.infra.preview;

import com.crdp.followup.file.app.FilePreviewProperties;
import com.crdp.framework.security.TokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 预览令牌（E10-T04 / tech-plan §10「预览」：短时链接 ≤ 5 分钟）。
 *
 * <p>pdf.js、docx-preview 与原生 {@code <audio>} 都无法携带登录令牌（浏览器子资源请求不带 Authorization
 * 头），预览内容走**应用代理的公开短链接**：登录态调 {@code preview-url} 换一个 256 位随机不透明令牌（Redis 登记， TTL ≤ 5
 * 分钟），取流口凭令牌定位「租户 + 文件」。
 *
 * <p>与 {@code ReauthStore} 同一存储模式：Redis 键只存令牌的 SHA-256 摘要（转储不外泄可用令牌）， 值为 {@code
 * 租户ID|租户编码|文件ID}——取流口没有登录上下文，租户只能来自令牌（等价 H5 一次性令牌的口径， report.md §5）。窗口内可多次取流（pdf.js 分页、audio
 * 拉流会发多个请求），过期自然失效。 Redis 不可用时解析一律失败（fail closed：宁可不能预览，不可绕开审计与租户隔离）。
 */
@Component
public class FilePreviewTokenStore {

    private static final Logger log = LoggerFactory.getLogger(FilePreviewTokenStore.class);

    /** 令牌摘要键前缀（值 = 租户ID|租户编码|文件ID）；与取流口 IP 限流键 {@code …:ip:} 不冲突（摘要恒为 64 位十六进制）。 */
    static final String KEY_PREFIX = "crdp:file-preview:";

    /** 令牌字符集（{@link TokenGenerator} 的 base64url）；取流口先做格式校验再进 Redis。 */
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final StringRedisTemplate redis;
    private final TokenGenerator generator;
    private final FilePreviewProperties properties;

    public FilePreviewTokenStore(
            StringRedisTemplate redis, TokenGenerator generator, FilePreviewProperties properties) {
        this.redis = redis;
        this.generator = generator;
        this.properties = properties;
    }

    /** 签发一个预览令牌（绑定租户与文件，TTL = 有效期上限内的配置值）。 */
    public String issue(long tenantId, String tenantCode, long fileId) {
        String token = generator.generate();
        redis.opsForValue()
                .set(
                        key(token),
                        tenantId + "|" + tenantCode + "|" + fileId,
                        Duration.ofSeconds(properties.effectiveTtlSeconds()));
        return token;
    }

    /** 解析取流口带来的令牌；无效、过期、格式不对、Redis 不可用一律空（调用方按 40400 处理，不暴露存在性）。 */
    public Optional<Grant> resolve(String token) {
        if (token == null || !token.matches(TOKEN_PATTERN)) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().get(key(token));
        } catch (RuntimeException failure) {
            log.warn("预览令牌解析失败（fail closed 拒绝）reason={}", failure.getMessage());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3 || parts[1].isBlank()) {
            log.warn("预览令牌登记值不合法，按无效处理 valueLength={}", value.length());
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new Grant(Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2])));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String key(String token) {
        return KEY_PREFIX + sha256Hex(token);
    }

    private static String sha256Hex(String token) {
        try {
            byte[] hashed =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JVM 规范要求提供 SHA-256，理论不可达
            throw new IllegalStateException(ex);
        }
    }

    /** 令牌登记内容：取流口凭它恢复租户上下文并定位文件。 */
    public record Grant(long tenantId, String tenantCode, long fileId) {}
}
