package com.crdp.followup.file.infra.download;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下载签名链接（E10-T05 / tech-plan §10「下载」：生成 5 分钟有效的签名链接）。
 *
 * <p>与预览的随机不透明令牌（OQ-62 ③，Redis 登记）不同，下载按技术方案用 **HMAC-SHA256 无状态签名**： 负载 {@code
 * v1|租户ID|租户编码|文件ID|到期Unix秒} base64url 编码后跟 {@code .} 与对负载的 HMAC， 形如 {@code
 * {payload}.{sig}}。到期时间进签名——改一位负载验签即失败，链接短时有效不可顺延； 取流口凭签名恢复「租户 + 文件」与到期时间，不依赖 Redis（审计在签发时已记，见
 * {@code FileDownloadService}）。
 *
 * <p>密钥来自 {@code crdp.file.download.signing-key}（Base64，≥ 32 字节）：开发默认值只在 application.yml，
 * 生产经环境变量注入，缺失 / 过短启动即失败（不放行弱签名）。验签一律常量时间比较； 任何格式、结构、签名、过期不通过都返回空（fail closed：调用方按 40400
 * 处理，不暴露存在性）。装配见 {@link FileDownloadConfiguration}。
 */
public class FileDownloadSigner {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadSigner.class);

    /** 负载格式版本（防将来字段演进时旧链接被误读）。 */
    private static final String VERSION = "v1";

    private static final int MIN_KEY_BYTES = 32;

    /** 链接字符集（base64url 无填充；取流口先做格式校验再解码）。 */
    private static final String SEGMENT_PATTERN = "[A-Za-z0-9_-]+";

    /** 已初始化的 HMAC 模板：Mac 非线程安全，每次签名 / 验签 clone 一份用（JCE 标准支持）。 */
    private final Mac macTemplate;

    /**
     * @param signingKeyBase64 Base64 编码的 HMAC 密钥（解码后至少 32 字节）
     */
    public FileDownloadSigner(String signingKeyBase64) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(signingKeyBase64 == null ? "" : signingKeyBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "crdp.file.download.signing-key 不是合法的 Base64，无法初始化下载签名", ex);
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "crdp.file.download.signing-key 解码后不足 "
                            + MIN_KEY_BYTES
                            + " 字节，拒绝以弱密钥签发下载链接（生产经 CRDP_FILE_DOWNLOAD_SIGNING_KEY 注入）");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            this.macTemplate = mac;
        } catch (Exception ex) {
            // JVM 规范要求提供 HmacSHA256，理论不可达
            throw new IllegalStateException("HmacSHA256 不可用", ex);
        }
    }

    /** 给「租户 + 文件 + 到期时间」签一个链接（到期由调用方按 ≤ 5 分钟的口径计算）。 */
    public String sign(long tenantId, String tenantCode, long fileId, Instant expiresAt) {
        String payload =
                VERSION
                        + "|"
                        + tenantId
                        + "|"
                        + tenantCode
                        + "|"
                        + fileId
                        + "|"
                        + expiresAt.getEpochSecond();
        byte[] sig = mac().doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    /**
     * 验签并解析链接；格式不对、签名不符、负载结构不合法、已过期一律空（调用方按 40400 处理，不暴露存在性）。
     *
     * @param token 签发口生成的 {@code {payload}.{sig}}
     * @param now 判过期用的当前时间（测试可注入）
     */
    public Optional<Grant> verify(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            return Optional.empty();
        }
        String payloadSegment = token.substring(0, dot);
        String sigSegment = token.substring(dot + 1);
        if (!payloadSegment.matches(SEGMENT_PATTERN) || !sigSegment.matches(SEGMENT_PATTERN)) {
            return Optional.empty();
        }
        byte[] payloadBytes;
        byte[] sigBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(payloadSegment);
            sigBytes = Base64.getUrlDecoder().decode(sigSegment);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        byte[] expected = mac().doFinal(payloadBytes);
        if (!MessageDigest.isEqual(expected, sigBytes)) {
            return Optional.empty();
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0]) || parts[2].isBlank()) {
            log.warn("下载链接负载结构不合法，按无效处理 payloadLength={}", payload.length());
            return Optional.empty();
        }
        long tenantId;
        long fileId;
        long expiresAt;
        try {
            tenantId = Long.parseLong(parts[1]);
            fileId = Long.parseLong(parts[3]);
            expiresAt = Long.parseLong(parts[4]);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (now.getEpochSecond() >= expiresAt) {
            return Optional.empty();
        }
        return Optional.of(new Grant(tenantId, parts[2], fileId, expiresAt));
    }

    /** 每次签名 / 验签用独立 Mac（Mac 非线程安全，clone 模板）。 */
    private Mac mac() {
        try {
            return (Mac) macTemplate.clone();
        } catch (CloneNotSupportedException ex) {
            // JCE 的 Mac 标准实现 Cloneable，理论不可达
            throw new IllegalStateException("HmacSHA256 实现不支持 clone", ex);
        }
    }

    /** 链接携带的定位信息：取流口凭它恢复租户上下文并定位文件（与预览令牌同一口径）。 */
    public record Grant(long tenantId, String tenantCode, long fileId, long expiresAtEpochSecond) {}
}
