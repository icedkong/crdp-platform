package com.crdp.followup.file.app;

import com.crdp.followup.file.domain.FileFormat;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.preview.FilePreviewTokenStore;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 预览代理（E10-T04 / A-FU-025、tech-plan §10「预览」；docs/api/report.md §1）。
 *
 * <p>预览**经应用代理按流返回、不缓存**（不把对象存储直链暴露给浏览器）：登录态调 {@code GET /files/{fileId}/preview-url} 换短时链接（≤ 5
 * 分钟，{@link FilePreviewTokenStore}）， 公开取流口凭令牌定位租户与文件后从对象存储读回流。**每次预览（签发链接时）写 FILE_PREVIEW
 * 审计**（用户、租户、文件、时间、IP 由审计服务自动补全）——水印是威慑、审计是追溯（tech-plan §10 残余风险口径）。
 *
 * <p>权限：登录医院会话 + followup 模块开通（控制器注解）；「能看到所属报告单」的数据范围 校验随 E16 报告单子域落地（sys_file 尚未挂
 * rpt_report_file，见 report.md §1）。跨租户由两层挡住： 台账查询按令牌租户路由租户库；对象键前缀与令牌租户不符直接 40400（E10-T02 前缀隔离）。
 *
 * <p>浏览器可播性（A-FU-025）：MP3 / M4A / WAV 原生播放、图片 / PDF / DOCX 页内预览； AMR 与 XLSX 不在线预览（{@code
 * inlinePlayable=false}，前端只给下载），录音不转码（E10-T03 口径）。
 */
@Service
public class FilePreviewService {

    private static final Logger log = LoggerFactory.getLogger(FilePreviewService.class);

    /** 公开取流口路径前缀（与 FilePreviewController 的映射一致；前端拼基地址后直接用）。 */
    static final String URL_PREFIX = "/api/hospital/files/preview/";

    private static final String IP_LIMIT_PREFIX = "crdp:file-preview:ip:";

    private final SysFileMapper files;
    private final ObjectStorage storage;
    private final FilePreviewTokenStore tokens;
    private final AuditLogger audit;
    private final FilePreviewProperties properties;
    private final StringRedisTemplate redis;

    public FilePreviewService(
            SysFileMapper files,
            ObjectStorage storage,
            FilePreviewTokenStore tokens,
            AuditLogger audit,
            FilePreviewProperties properties,
            StringRedisTemplate redis) {
        this.files = files;
        this.storage = storage;
        this.tokens = tokens;
        this.audit = audit;
        this.properties = properties;
        this.redis = redis;
    }

    /** 签发结果：短时链接 + 展示与播放判定所需字段（前端不缓存，用完即弃）。 */
    public record PreviewGrant(
            String url,
            long expiresAtEpochSecond,
            String format,
            String mime,
            String originalName,
            boolean inlinePlayable) {}

    /** 取流结果：原始字节 + MIME + 文件名（代理响应不缓存，inline 展示）。 */
    public record PreviewContent(byte[] body, String mime, String originalName) {}

    /** 签发预览短时链接（登录医院会话；文件不存在 / 已删除 → 40400，不暴露存在性）。 */
    public PreviewGrant previewUrl(long fileId) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        SysFileRow row = files.find(fileId);
        if (row == null || "DELETED".equals(row.getStatus())) {
            throw notFound();
        }
        relativeKeyOf(row.getObjectKey(), principal.tenantCode());
        String token = tokens.issue(principal.tenantId(), principal.tenantCode(), fileId);
        audit.append(
                AuditEntry.operation("file", "FILE_PREVIEW")
                        .eventType("FILE_PREVIEW")
                        .objectType("sys_file")
                        .objectId(String.valueOf(fileId))
                        .description(
                                "预览文件："
                                        + row.getOriginalName()
                                        + "（真实类型 "
                                        + row.getFormatCode()
                                        + "）")
                        .build());
        log.info(
                "预览链接签发 fileId={} format={} userId={} ttl={}s",
                fileId,
                row.getFormatCode(),
                principal.userId(),
                properties.effectiveTtlSeconds());
        return new PreviewGrant(
                URL_PREFIX + token,
                Instant.now().getEpochSecond() + properties.effectiveTtlSeconds(),
                row.getFormatCode(),
                row.getMimeType(),
                row.getOriginalName(),
                inlinePlayable(row.getFormatCode()));
    }

    /**
     * 公开取流口：先按 IP 限流（AGENTS.md 第 6 节），再凭令牌在**令牌租户**上下文里读台账与对象。
     *
     * @param token 预览令牌（preview-url 签发）
     * @param clientIp 客户端 IP（反代后取 X-Forwarded-For 首段；空则跳过限流，令牌本身仍是硬闸）
     */
    public PreviewContent content(String token, String clientIp) {
        rateLimit(clientIp);
        FilePreviewTokenStore.Grant grant =
                tokens.resolve(token).orElseThrow(FilePreviewService::notFound);
        return TenantContext.callAsTenant(
                grant.tenantId(),
                grant.tenantCode(),
                () -> {
                    SysFileRow row = files.find(grant.fileId());
                    if (row == null || "DELETED".equals(row.getStatus())) {
                        throw notFound();
                    }
                    byte[] body =
                            storage.get(relativeKeyOf(row.getObjectKey(), grant.tenantCode()));
                    if (body == null) {
                        log.warn(
                                "预览对象缺失 fileId={} tenant={} objectKeyLength={}",
                                grant.fileId(),
                                grant.tenantCode(),
                                row.getObjectKey().length());
                        throw notFound();
                    }
                    return new PreviewContent(body, row.getMimeType(), row.getOriginalName());
                });
    }

    // ---------------------------------------------------------------- 私有

    /**
     * 校验台账对象键确在令牌租户前缀下并剥出相对键（存储端口会再按当前上下文拼回前缀， 双保险）。前缀对不上说明令牌与台账不是同一租户 → 40400（E10-T02 按租户前缀隔离）。
     */
    private static String relativeKeyOf(String objectKey, String tenantCode) {
        String prefix = "t/" + tenantCode + "/";
        if (objectKey == null || !objectKey.startsWith(prefix)) {
            log.warn(
                    "对象键与租户前缀不符（跨租户访问或数据异常）tenant={} keyLength={}",
                    tenantCode,
                    objectKey == null ? 0 : objectKey.length());
            throw notFound();
        }
        return objectKey.substring(prefix.length());
    }

    /** 浏览器原生可播 / 可预览：AMR（浏览器不能直接播放）与 XLSX（需下载查看，A-ATT-005）之外全部可。 */
    private static boolean inlinePlayable(String formatCode) {
        return FileFormat.ofExtension(formatCode)
                .filter(format -> format != FileFormat.AMR && format != FileFormat.XLSX)
                .isPresent();
    }

    private void rateLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        String key = IP_LIMIT_PREFIX + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > properties.getIpHourlyLimit()) {
            throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, 40400, "文件不存在或无权访问");
    }
}
