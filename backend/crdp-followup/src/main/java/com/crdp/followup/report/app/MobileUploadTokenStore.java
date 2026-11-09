package com.crdp.followup.report.app;

import com.crdp.followup.report.app.ReportService.LocationInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 一次性上传令牌的 Redis 存储（E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006）。
 *
 * <p>令牌是 256 位随机值（与登录令牌同规格，不透明），Redis 键 {@code crdp:h5-upload:{token}}、TTL 15 分钟（C-NFR-006）。
 * 载荷绑定**签发会话 + 患者**（C-SCAN-008「绑定当前用户与患者」）：issuerToken 是电脑端当前会话的令牌——H5 请求时用它取签发人 的实时会话（权限 / 数据范围 /
 * 租户上下文全部随会话现场判定，权限被收走或会话结束时手机侧立即失效），不在载荷里复制权限快照。
 *
 * <p>「一次性」口径（OQ-117）：15 分钟窗口内 GET / 上传可多次（连拍多张），**提交即消费**——状态置 SUBMITTED 后不再放行任何 H5 请求；SUBMITTED
 * 行保留剩余 TTL 供电脑端轮询拿结果（reportId），到期由 Redis 过期清除。
 */
@Component
public class MobileUploadTokenStore {

    private static final Logger log = LoggerFactory.getLogger(MobileUploadTokenStore.class);

    private static final String KEY_PREFIX = "crdp:h5-upload:";

    /** 令牌状态：PENDING 可用；SUBMITTED 已提交（一次性已消费，仅电脑端轮询可读）。 */
    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_SUBMITTED = "SUBMITTED";

    /** 令牌载荷（issuerToken 是签发人会话令牌，只存在 Redis，绝不出现在响应里）。 */
    public record Payload(
            String issuerToken,
            long tenantId,
            String tenantCode,
            long patientId,
            LocationInput presetLocation,
            List<String> fileIds,
            String status,
            String reportId) {

        Payload withFiles(List<String> files) {
            return new Payload(
                    issuerToken,
                    tenantId,
                    tenantCode,
                    patientId,
                    presetLocation,
                    files,
                    status,
                    reportId);
        }

        Payload asSubmitted(String reportId) {
            return new Payload(
                    issuerToken,
                    tenantId,
                    tenantCode,
                    patientId,
                    presetLocation,
                    fileIds,
                    STATUS_SUBMITTED,
                    reportId);
        }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final MobileUploadProperties properties;

    public MobileUploadTokenStore(
            StringRedisTemplate redis, ObjectMapper json, MobileUploadProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    /** 签发新令牌（PENDING，TTL 15 分钟）；返回令牌与到期 epoch 秒。 */
    public record Issued(String token, long expiresAtEpochSecond) {}

    public Issued issue(
            String issuerToken,
            long tenantId,
            String tenantCode,
            long patientId,
            LocationInput preset) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long expiresAt = System.currentTimeMillis() / 1000 + properties.effectiveTtlSeconds();
        Payload payload =
                new Payload(
                        issuerToken,
                        tenantId,
                        tenantCode,
                        patientId,
                        preset,
                        new ArrayList<>(),
                        STATUS_PENDING,
                        null);
        write(token, payload);
        return new Issued(token, expiresAt);
    }

    /** 读令牌（不过期判断由 Redis TTL 承担）；不存在 / 载荷损坏返回 null。 */
    public Payload find(String token) {
        return read(token, redis.opsForValue().get(key(token)));
    }

    /** 追加一个已上传文件（保持剩余 TTL，不续期——过期时刻以签发为准）。 */
    public void appendFile(String token, Payload current, String fileId) {
        List<String> files = new ArrayList<>(current.fileIds());
        files.add(fileId);
        writeKeepTtl(token, current.withFiles(List.copyOf(files)));
    }

    /** 标记已提交（一次性消费；保留剩余 TTL 供电脑端轮询）。 */
    public void markSubmitted(String token, Payload current, String reportId) {
        writeKeepTtl(token, current.asSubmitted(reportId));
    }

    /** 令牌剩余秒数（电脑端轮询展示倒计时；不存在返回 0）。 */
    public long remainingSeconds(String token) {
        Long ttl = redis.getExpire(key(token));
        return ttl == null || ttl < 0 ? 0 : ttl;
    }

    // ---------------------------------------------------------------- 私有

    private void write(String token, Payload payload) {
        try {
            redis.opsForValue()
                    .set(
                            key(token),
                            json.writeValueAsString(payload),
                            Duration.ofSeconds(properties.effectiveTtlSeconds()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("H5 上传令牌序列化失败", ex);
        }
    }

    /** 读改写保持剩余 TTL（不重置过期时钟）：先读剩余秒数，读到即过期的令牌按不存在处理 （读与写之间的毫秒级竞态最多让令牌多活一次写间隙，不放大有效期）。 */
    private void writeKeepTtl(String token, Payload payload) {
        long remaining = remainingSeconds(token);
        if (remaining <= 0) {
            log.debug("H5 上传令牌在读改写间隙过期 tokenLength={}", token == null ? 0 : token.length());
            return;
        }
        try {
            redis.opsForValue()
                    .set(
                            key(token),
                            json.writeValueAsString(payload),
                            Duration.ofSeconds(remaining));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("H5 上传令牌序列化失败", ex);
        }
    }

    private Payload read(String token, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            Payload payload = json.readValue(raw, Payload.class);
            // 兼容旧载荷缺 fileIds / status 的极端情况：按空 / PENDING 处理
            if (payload.fileIds() == null || payload.status() == null) {
                return new Payload(
                        payload.issuerToken(),
                        payload.tenantId(),
                        payload.tenantCode(),
                        payload.patientId(),
                        payload.presetLocation(),
                        payload.fileIds() == null ? List.of() : payload.fileIds(),
                        payload.status() == null ? STATUS_PENDING : payload.status(),
                        payload.reportId());
            }
            return payload;
        } catch (JsonProcessingException ex) {
            log.warn("H5 上传令牌载荷损坏（按不存在处理）tokenLength={}", token == null ? 0 : token.length());
            return null;
        }
    }

    private static String key(String token) {
        return KEY_PREFIX + token;
    }
}
