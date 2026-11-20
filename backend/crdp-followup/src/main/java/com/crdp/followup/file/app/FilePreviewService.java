package com.crdp.followup.file.app;

import com.crdp.followup.file.domain.FileFormat;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.preview.FilePreviewTokenStore;
import com.crdp.framework.audit.*;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** OQR-07：会话绑定的鉴权预览；可视文件只返回沙箱生成的水印副本，音频鉴权原流。 */
@Service
public class FilePreviewService {
    static final String URL_PREFIX = "/api/hospital/files/preview/";
    private static final Set<String> VISUAL = Set.of("pdf", "docx", "jpg", "png");
    private static final Set<String> AUDIO = Set.of("mp3", "m4a", "wav");
    private final SysFileMapper files;
    private final ObjectStorage storage;
    private final FilePreviewTokenStore tokens;
    private final AuditLogger audit;
    private final FilePreviewProperties properties;
    private final StringRedisTemplate redis;
    private final ContactAudioScopePort contactScope;
    private final ReportFileScopePort reportScope;
    private final PreviewRenderer renderer;
    private final PreviewAccessService access;

    public FilePreviewService(
            SysFileMapper files,
            ObjectStorage storage,
            FilePreviewTokenStore tokens,
            AuditLogger audit,
            FilePreviewProperties properties,
            StringRedisTemplate redis,
            ContactAudioScopePort contactScope,
            ReportFileScopePort reportScope,
            PreviewRenderer renderer,
            PreviewAccessService access) {
        this.files = files;
        this.storage = storage;
        this.tokens = tokens;
        this.audit = audit;
        this.properties = properties;
        this.redis = redis;
        this.contactScope = contactScope;
        this.reportScope = reportScope;
        this.renderer = renderer;
        this.access = access;
    }

    public interface ContactAudioScopePort {
        boolean requireAudioPlayable(long fileId);
    }

    public record PreviewGrant(
            String url,
            long expiresAtEpochSecond,
            String format,
            String mime,
            String originalName,
            boolean inlinePlayable) {}

    public record PreviewContent(byte[] body, String mime, String originalName) {}

    public PreviewGrant previewUrl(long fileId) {
        try {
            var actor = access.current();
            var row = authorize(fileId, actor);
            long expires = Instant.now().getEpochSecond() + properties.effectiveTtlSeconds();
            var grant =
                    new FilePreviewTokenStore.Grant(
                            2,
                            actor.tenantId(),
                            actor.tenantCode(),
                            fileId,
                            actor.userId(),
                            actor.sessionId(),
                            row.getObjectKey(),
                            row.getSha256(),
                            row.getSizeBytes(),
                            row.getFormatCode(),
                            watermark(actor),
                            expires);
            String token = tokens.issue(grant);
            audit("FILE_PREVIEW", fileId, AuditResult.SUCCESS, "签发会话与文件版本绑定的预览授权");
            var type = previewType(row.getFormatCode());
            return new PreviewGrant(
                    URL_PREFIX + token,
                    expires,
                    type[0],
                    type[1],
                    previewName(row.getOriginalName(), row.getFormatCode()),
                    VISUAL.contains(row.getFormatCode()) || AUDIO.contains(row.getFormatCode()));
        } catch (ApiException rejected) {
            audit("FILE_PREVIEW_DENIED", fileId, AuditResult.DENIED, "预览授权被拒绝");
            throw rejected;
        }
    }

    public PreviewContent content(String token, String clientIp) {
        rateLimit(clientIp);
        var grant = tokens.resolve(token).orElseThrow(FilePreviewService::notFound);
        try {
            SysFileRow row = authorizedGrant(grant);
            if (!VISUAL.contains(row.getFormatCode()) && !AUDIO.contains(row.getFormatCode()))
                throw new ApiException(400, 40001, "该格式不支持在线预览，原件下载需单独权限");
            if (row.getSizeBytes() > PreviewRenderer.MAX_BYTES) throw PreviewRenderer.unavailable();
            byte[] source = storage.get(relativeKeyOf(row.getObjectKey(), grant.tenantCode()));
            if (source == null || source.length != grant.sizeBytes()) throw notFound();
            PreviewContent result;
            if (VISUAL.contains(row.getFormatCode())) {
                var rendered = renderer.render(source, row.getFormatCode(), grant.watermark());
                result =
                        new PreviewContent(
                                rendered.body(),
                                rendered.mime(),
                                previewName(row.getOriginalName(), row.getFormatCode()));
            } else
                result =
                        new PreviewContent(
                                source, previewType(row.getFormatCode())[1], row.getOriginalName());
            // 转换期间可能撤权、登出、删除或改版；输出前再次按当前会话与范围核验。
            if (tokens.resolve(token).filter(grant::equals).isEmpty()) throw notFound();
            authorizedGrant(grant);
            audit(
                    "FILE_PREVIEW_CONTENT",
                    grant.fileId(),
                    AuditResult.SUCCESS,
                    "鉴权返回预览内容（可视文件为水印副本）");
            return result;
        } catch (ApiException rejected) {
            audit(
                    "FILE_PREVIEW_CONTENT_DENIED",
                    grant.fileId(),
                    rejected.httpStatus() >= 500 ? AuditResult.FAIL : AuditResult.DENIED,
                    "预览内容未返回");
            throw rejected;
        }
    }

    private SysFileRow authorizedGrant(FilePreviewTokenStore.Grant grant) {
        var actor = access.current();
        if (!Objects.equals(grant.sessionId(), actor.sessionId())
                || grant.userId() != actor.userId()
                || grant.tenantId() != actor.tenantId()
                || !Objects.equals(grant.tenantCode(), actor.tenantCode())) throw notFound();
        var row = authorize(grant.fileId(), actor);
        if (!Objects.equals(row.getObjectKey(), grant.objectKey())
                || !Objects.equals(row.getSha256(), grant.sha256())
                || !Objects.equals(row.getFormatCode(), grant.format())
                || row.getSizeBytes() != grant.sizeBytes()) throw notFound();
        return row;
    }

    private SysFileRow authorize(long id, SessionPrincipal actor) {
        var tenant = TenantContext.require();
        if (!Objects.equals(tenant.tenantId(), actor.tenantId())
                || !Objects.equals(tenant.tenantCode(), actor.tenantCode())) throw notFound();
        var row = files.find(id);
        if (row == null
                || "DELETED".equals(row.getStatus())
                || Set.of("INFECTED", "HASH_MISMATCH")
                        .contains(Objects.toString(row.getVirusScanStatus(), ""))) throw notFound();
        boolean audio = contactScope.requireAudioPlayable(id);
        boolean report = reportScope.requireReportFileReadable(id) != null;
        requireUploaderIfUnlinked(audio, report, row, actor);
        String permission = audio || report ? "followup:patient:view" : "followup:scan:upload";
        if (!actor.permissionCodes().contains(permission))
            throw new ApiException(403, 40300, "无权预览该文件");
        relativeKeyOf(row.getObjectKey(), actor.tenantCode());
        return row;
    }

    static void requireUploaderIfUnlinked(
            boolean audio, boolean report, SysFileRow row, SessionPrincipal actor) {
        if (!audio && !report && row.getUploaderId() != actor.userId()) throw notFound();
    }

    private static String relativeKeyOf(String key, String tenant) {
        String prefix = "t/" + tenant + "/";
        if (key == null || !key.startsWith(prefix)) throw notFound();
        return key.substring(prefix.length());
    }

    private static String watermark(SessionPrincipal actor) {
        String identity =
                actor.tenantView()
                        ? "平台支持人员（" + actor.sessionId() + "）"
                        : actor.displayName() + "（" + actor.username() + "）";
        if (actor.tenantName() == null
                || actor.tenantName().isBlank()
                || actor.displayName() == null
                || actor.username() == null) throw PreviewRenderer.unavailable();
        return identity
                + " · "
                + actor.tenantName()
                + " · "
                + LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static String[] previewType(String format) {
        if (VISUAL.contains(format))
            return Set.of("png", "jpg").contains(format)
                    ? new String[] {"png", "image/png"}
                    : new String[] {"pdf", "application/pdf"};
        return new String[] {
            format,
            FileFormat.ofExtension(format)
                    .map(f -> f.mimes().getFirst())
                    .orElse("application/octet-stream")
        };
    }

    private static String previewName(String name, String format) {
        if (!VISUAL.contains(format)) return name;
        String base = name == null ? "文件" : name.replaceFirst("\\.[^.]*$", "");
        return base + "（水印预览）." + (Set.of("png", "jpg").contains(format) ? "png" : "pdf");
    }

    private void rateLimit(String ip) {
        if (ip == null || ip.isBlank()) return;
        String key = "crdp:file-preview:ip:" + ip;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) redis.expire(key, Duration.ofHours(1));
        if (count != null && count > properties.getIpHourlyLimit())
            throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
    }

    private void audit(String action, long id, AuditResult result, String description) {
        audit.append(
                (action.equals("FILE_PREVIEW") || action.equals("FILE_PREVIEW_DENIED")
                                ? AuditEntry.operation("file", action)
                                : AuditEntry.dataAccess("file", action))
                        .eventType(action)
                        .objectType("sys_file")
                        .objectId(String.valueOf(id))
                        .description(description)
                        .result(result)
                        .build());
    }

    private static ApiException notFound() {
        return new ApiException(404, 40400, "文件不存在或无权访问");
    }
}
