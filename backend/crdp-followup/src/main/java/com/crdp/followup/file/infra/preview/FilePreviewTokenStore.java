package com.crdp.followup.file.infra.preview;

import com.crdp.followup.file.app.FilePreviewProperties;
import com.crdp.framework.security.TokenGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 短时预览授权：随机令牌仅存摘要，授权绑定会话、文件版本和水印；旧无绑定授权失败关闭。 */
@Component
public class FilePreviewTokenStore {
    static final String KEY_PREFIX = "crdp:file-preview:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StringRedisTemplate redis;
    private final TokenGenerator generator;
    private final FilePreviewProperties properties;

    public FilePreviewTokenStore(
            StringRedisTemplate redis, TokenGenerator generator, FilePreviewProperties properties) {
        this.redis = redis;
        this.generator = generator;
        this.properties = properties;
    }

    public record Grant(
            int version,
            long tenantId,
            String tenantCode,
            long fileId,
            long userId,
            String sessionId,
            String objectKey,
            String sha256,
            long sizeBytes,
            String format,
            String watermark,
            long expiresAt) {}

    public String issue(Grant grant) {
        try {
            String token = generator.generate();
            redis.opsForValue()
                    .set(
                            key(token),
                            JSON.writeValueAsString(grant),
                            Duration.ofSeconds(properties.effectiveTtlSeconds()));
            return token;
        } catch (Exception unavailable) {
            throw new com.crdp.framework.web.ApiException(503, 50301, "预览授权暂不可用，请稍后重试");
        }
    }

    public Optional<Grant> resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,64}")) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key(token));
            if (value == null) return Optional.empty();
            Grant grant = JSON.readValue(value, Grant.class);
            if (grant.version() != 2
                    || grant.tenantId() <= 0
                    || grant.fileId() <= 0
                    || grant.userId() <= 0
                    || grant.sessionId() == null
                    || grant.sessionId().isBlank()
                    || grant.watermark() == null
                    || grant.objectKey() == null
                    || grant.tenantCode() == null
                    || grant.format() == null
                    || grant.sha256() == null
                    || grant.sizeBytes() < 0
                    || grant.expiresAt() <= Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(grant);
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    private static String key(String token) {
        try {
            return KEY_PREFIX
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
