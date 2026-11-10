package com.crdp.followup.file.app;

import com.crdp.followup.file.infra.download.FileDownloadSigner;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
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
 * 签名下载（E10-T05 / tech-plan §10「下载」；docs/api/report.md §1）。
 *
 * <p>下载是**敏感权限**（followup:scan:download，V18 登记在 plt_sensitive_perm）：登录医院会话调 {@code POST
 * /files/{fileId}/download-url} 换 **5 分钟有效的 HMAC 签名链接**（{@link FileDownloadSigner}，OQ-62 ③ 口径：
 * 预览用随机令牌、下载按技术方案用签名链接），公开取流口 {@code GET /files/download/{token}} 凭签名定位租户与文件， 附件返回原文件。**每次签发写
 * DOWNLOAD 数据访问审计**（PLT-AUD-003：谁、何时、哪个文件 + 文件 SHA-256，A-ATT-006；报告单关联随 E16 rpt_report_file
 * 补进描述，OQ-62 ① 同口径——sys_file 尚未挂 rpt_report_file，见 report.md §1）。
 *
 * <p>跨租户由两层挡住：台账查询按签名租户路由租户库；对象键前缀与签名租户不符直接 40400（E10-T02 前缀隔离）。 签发后文件被软删除的，窗口内链接同样拒绝（台账状态优先于签名）。
 */
@Service
public class FileDownloadService {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadService.class);

    /** 公开取流口路径前缀（与 FileDownloadController 的映射一致；前端拼基地址后直接用）。 */
    static final String URL_PREFIX = "/api/hospital/files/download/";

    private static final String IP_LIMIT_PREFIX = "crdp:file-download:ip:";

    private final SysFileMapper files;
    private final ObjectStorage storage;
    private final FileDownloadSigner signer;
    private final AuditLogger audit;
    private final FileDownloadProperties properties;
    private final StringRedisTemplate redis;
    private final FilePreviewService.ContactAudioScopePort contactScope;
    private final ReportFileScopePort reportScope;

    public FileDownloadService(
            SysFileMapper files,
            ObjectStorage storage,
            FileDownloadSigner signer,
            AuditLogger audit,
            FileDownloadProperties properties,
            StringRedisTemplate redis,
            FilePreviewService.ContactAudioScopePort contactScope,
            ReportFileScopePort reportScope) {
        this.files = files;
        this.storage = storage;
        this.signer = signer;
        this.audit = audit;
        this.properties = properties;
        this.redis = redis;
        this.contactScope = contactScope;
        this.reportScope = reportScope;
    }

    /** 签发结果：短时签名链接 + 展示所需字段（前端不缓存，用完即弃）。 */
    public record DownloadGrant(
            String url,
            long expiresAtEpochSecond,
            String format,
            String mime,
            String originalName,
            long sizeBytes) {}

    /** 取流结果：原始字节 + MIME + 文件名（附件返回，不缓存）。 */
    public record DownloadContent(byte[] body, String mime, String originalName) {}

    /**
     * 签发下载短时链接（登录医院会话 + followup:scan:download，敏感权限在控制器注解校验）； 文件不存在 / 已删除 → 40400（不暴露存在性）。
     * E16-T09：挂在报告单上的文件按患者数据范围校验（OQ-62① 的报告单部分），审计描述带「哪份报告单的哪个文件」 （A-ATT-006：谁、何时、哪份报告单的哪个文件）。
     */
    public DownloadGrant downloadUrl(long fileId) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        SysFileRow row = files.find(fileId);
        if (row == null || "DELETED".equals(row.getStatus())) {
            throw notFound();
        }
        // E15-T04：挂在沟通记录上的录音按患者数据范围校验（A-FU-025 下载走敏感权限 + 患者范围双闸）
        boolean audioLinked = contactScope.requireAudioPlayable(fileId);
        // E16-T09：挂在报告单上的文件按患者数据范围校验（越范围 40400），并取报告单上下文进审计描述
        ReportFileScopePort.ReportFileView report = reportScope.requireReportFileReadable(fileId);
        // S1（M3 审查）：未挂任何业务对象的文件只允许上传者本人取下载链接（与预览同口径）
        FilePreviewService.requireUploaderIfUnlinked(audioLinked, report != null, row, principal);
        relativeKeyOf(row.getObjectKey(), principal.tenantCode());
        Instant expiresAt = Instant.now().plusSeconds(properties.effectiveTtlSeconds());
        String token = signer.sign(principal.tenantId(), principal.tenantCode(), fileId, expiresAt);
        audit.append(
                AuditEntry.dataAccess("file", "DOWNLOAD")
                        .eventType("FILE_DOWNLOAD")
                        .objectType("sys_file")
                        .objectId(String.valueOf(fileId))
                        .fileHash(row.getSha256())
                        .description(
                                "下载文件："
                                        + row.getOriginalName()
                                        + "（真实类型 "
                                        + row.getFormatCode()
                                        + "）"
                                        + reportSuffix(report))
                        .success()
                        .build());
        log.info(
                "下载链接签发 fileId={} format={} userId={} ttl={}s",
                fileId,
                row.getFormatCode(),
                principal.userId(),
                properties.effectiveTtlSeconds());
        return new DownloadGrant(
                URL_PREFIX + token,
                expiresAt.getEpochSecond(),
                row.getFormatCode(),
                row.getMimeType(),
                row.getOriginalName(),
                row.getSizeBytes());
    }

    /**
     * 公开取流口：先按 IP 限流（AGENTS.md 第 6 节），再验签并在**签名租户**上下文里读台账与对象。
     *
     * @param token download-url 签发的签名链接段
     * @param clientIp 客户端 IP（反代后取 X-Forwarded-For 首段；空则跳过限流，签名本身仍是硬闸）
     */
    public DownloadContent content(String token, String clientIp) {
        rateLimit(clientIp);
        FileDownloadSigner.Grant grant =
                signer.verify(token, Instant.now()).orElseThrow(FileDownloadService::notFound);
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
                                "下载对象缺失 fileId={} tenant={} objectKeyLength={}",
                                grant.fileId(),
                                grant.tenantCode(),
                                row.getObjectKey().length());
                        throw notFound();
                    }
                    return new DownloadContent(body, row.getMimeType(), row.getOriginalName());
                });
    }

    // ---------------------------------------------------------------- 私有

    /** 报告单上下文后缀（A-ATT-006 审计「哪份报告单的哪个文件」）：未挂报告单（沟通录音等）为空串。 */
    private static String reportSuffix(ReportFileScopePort.ReportFileView report) {
        if (report == null) {
            return "";
        }
        String label =
                report.nameSnapshot() == null
                        ? report.typeName()
                        : report.typeName() + " · " + report.nameSnapshot();
        return "，报告单：" + label + "（#" + report.reportId() + "）";
    }

    /**
     * 校验台账对象键确在签名租户前缀下并剥出相对键（与 FilePreviewService 同一隔离规则）： 前缀对不上说明签名与台账不是同一租户 → 40400（E10-T02
     * 按租户前缀隔离）。
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
