package com.crdp.followup.file.app;

import com.crdp.followup.file.domain.FileFormat;
import com.crdp.followup.file.infra.detect.FileTypeDetector;
import com.crdp.followup.file.infra.image.ImageThumbnailGenerator;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.scan.VirusScanner;
import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.web.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件上传（E10-T01 / A-ATT-008、C-SCAN-018、PLT-CFG-010；docs/api/report.md §1）。
 *
 * <p>校验顺序（全部 40001 + 中文原因）：文件名与扩展名 → 本院白名单 → 大小上限 → 内容真实类型 （Tika 魔数识别，不传文件名）→ 扩展名与真实类型一致 →
 * 病毒扫描（E22-T15 / D-01）。改扩展名的可执行文件（如 report.pdf 实为 PE/exe）在真实类型一步被拒——识别结果是
 * application/x-msdownload，不在白名单（任务验收）。 宏 Office（docm/xlsm）、脚本、压缩包等目录外格式同理拒绝（NFR「恶意代码防范」首期口径）。
 *
 * <p>病毒扫描（E22-T15 / 等保自查 D-01 整改，tech-plan §10 方案）：真实类型校验通过、写任何对象之前， 扫描启用（{@code
 * crdp.file.virus-scan.mode=clamd}）时把文件体经 INSTREAM 同步扫描——检出病毒 40001 拒绝并写 FILE_VIRUS_REJECTED
 * 审计（等保「恶意代码防范」证据）；扫描服务不可用 50300「稍后重试」，**不放行**（fail closed）； 禁用态（本机 / CI / 未部署 clamd）不扫描，台账 {@code
 * virus_scan_status=UNSCANNED}，由补扫作业（{@code VirusScanRetroService}）在启用后按 SHA-256 补扫存量。
 *
 * <p>通过后先写对象存储、再落 sys_file 台账（状态 PENDING，未关联报告单；关联与每份数量上限的落点校验随 E16 报告单）。 E10-T02 起文件体只在对象存储（OBS
 * SSE-KMS 服务端加密；本机为文件系统替身），对象键由存储端口加租户前缀 {@code t/{租户}/files/…} 实现按院隔离（C-NFR-003/005，ADR
 * 0006）；台账行失败会留下无引用的孤儿对象，清理策略随 E10-T05 / E16。 之后写 FILE_UPLOAD 审计（A-ATT-006 口径：谁、何时、传了什么）。上传接口经
 * {@code @RequiresPermission("followup:scan:upload")} 控制（OQ-12），模块未开通 40301。
 *
 * <p>E10-T03（图片缩略图）：图片（jpg / png）通过校验后同步生成缩略图（保持原格式、不放大，见 {@link ImageThumbnailGenerator}），落 {@code
 * thumbnails/{年月}/…} 前缀对象并记 {@code sys_file.thumbnail_key} 与 {@code
 * pages=1}；非图片不生成任何派生对象（录音不转码、不另存副本，tech-plan §10「派生文件」）。损坏 / 超尺寸图片在写对象之前 40001 拒绝。缩略图的查看链路（代理、审计）随
 * E10-T04。
 */
@Service
public class FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    /** 原文件名长度上限（sys_file.original_name 列宽）。 */
    private static final int NAME_MAX = 255;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final FilePolicyService policy;
    private final FileTypeDetector detector;
    private final ImageThumbnailGenerator thumbnails;
    private final SysFileMapper files;
    private final ObjectStorage storage;
    private final AuditLogger audit;

    /** 存储配额（E05-T09 / PLT-TEN-014）：达到上限禁止上传；null=不限不校验。 */
    private final TenantStorageQuota quota;

    /** 病毒扫描（E22-T15 / D-01）：禁用态替身不扫描；clamd 启用时同步扫描 fail closed。 */
    private final VirusScanner virusScanner;

    public FileUploadService(
            FilePolicyService policy,
            FileTypeDetector detector,
            ImageThumbnailGenerator thumbnails,
            SysFileMapper files,
            ObjectStorage storage,
            AuditLogger audit,
            TenantStorageQuota quota,
            VirusScanner virusScanner) {
        this.policy = policy;
        this.detector = detector;
        this.thumbnails = thumbnails;
        this.files = files;
        this.storage = storage;
        this.audit = audit;
        this.quota = quota;
        this.virusScanner = virusScanner;
    }

    /** 上传结果（report.md §1：fileId、sha256、size、mime、format、originalName、pages）。 */
    public record FileUploadView(
            String fileId,
            String sha256,
            long size,
            String mime,
            String format,
            String originalName,
            Integer pages) {}

    /** 校验并保存一个上传文件；任何校验失败抛 40001（前端按 message 提示）。 */
    @Transactional
    public FileUploadView upload(String filename, byte[] body) {
        String name = sanitizeName(filename);
        String extension = extensionOf(name);
        FilePolicyService.UploadPolicy current = policy.policy();
        FilePolicyService.FormatPolicy declared =
                current.byExtension(extension)
                        .orElseThrow(
                                () ->
                                        bad(
                                                "不支持的文件类型：."
                                                        + extension
                                                        + "（本院开放："
                                                        + openExtensions(current)
                                                        + "）"));

        if (body == null || body.length == 0) {
            throw bad("文件内容为空，无法上传");
        }
        if (body.length > declared.maxBytes()) {
            throw bad(
                    "文件超过大小限制："
                            + declared.label()
                            + "单个最大 "
                            + declared.maxMb()
                            + " MB，当前 "
                            + String.format(Locale.ROOT, "%.1f", body.length / 1024.0 / 1024.0)
                            + " MB");
        }

        // 真实类型：按内容识别（不传文件名），白名单 + 与扩展名一致（A-ATT-008 验收）
        String mime = detector.detect(body);
        FileFormat real = FileFormat.ofMime(mime).orElse(null);
        if (real == null) {
            throw bad("文件真实类型不受支持（识别为 " + (mime.isBlank() ? "未知类型" : mime) + "），已拒绝上传");
        }
        if (!real.code().equals(declared.format())) {
            throw bad("文件扩展名 ." + extension + " 与真实类型（" + real.label() + "）不符，已拒绝上传");
        }

        // 病毒扫描（E22-T15 / D-01 整改）：写任何对象之前同步扫描——检出 40001 + FILE_VIRUS_REJECTED 审计
        // （等保「恶意代码防范」证据）；不可用 50300 不放行（fail closed，tech-plan §10「稍后重试」）；
        // 禁用态跳过（台账落 UNSCANNED，补扫作业启用后补）
        boolean scanned = virusScanner.enabled();
        if (scanned) {
            VirusScanner.ScanResult verdict = virusScanner.scan(body);
            if (verdict.infected()) {
                long deniedUploader = CurrentSession.requireHospital().userId();
                audit.record(
                        new AuditEvent(
                                "FILE_VIRUS_REJECTED",
                                deniedUploader,
                                "CREATE",
                                "sys_file:-",
                                "上传文件检出病毒已拒绝："
                                        + name
                                        + "（检出 "
                                        + verdict.virusName()
                                        + "，真实类型 "
                                        + real.code()
                                        + "，"
                                        + body.length
                                        + " 字节）"));
                log.warn(
                        "上传文件检出病毒已拒绝 userId={} format={} virus={} bytes={}",
                        deniedUploader,
                        real.code(),
                        verdict.virusName(),
                        body.length);
                throw bad("文件未通过病毒检测（检出 " + verdict.virusName() + "），已拒绝上传");
            }
        }

        long uploader = CurrentSession.requireHospital().userId();
        String sha256 = sha256Hex(body);
        // E10-T03 缩略图（图片才有派生；录音不转码、不另存副本）：先在内存里生成——损坏 / 超尺寸图片
        // 在写任何对象之前 40001 拒绝，不留半成品；小图直接复用原字节（无转码、不放大见生成器注释）
        ImageThumbnailGenerator.Thumbnail thumbnail = null;
        Integer pages = null;
        if (real.isImage()) {
            thumbnail = thumbnails.generate(body, mime);
            pages = 1;
        }
        // 存储配额（E05-T09 / PLT-TEN-014）：设置了上限且存量 + 本次将超出 → 拒绝（写任何对象之前）；
        // 不限（null）不校验。用量口径 = 未删除台账行的 size_bytes 合计；80% 预警由平台配额视图展示
        Long storageLimit = quota.storageLimitBytes();
        if (storageLimit != null) {
            long used = files.sumActiveBytes();
            if (used + body.length > storageLimit) {
                throw bad(
                        "存储空间已达上限（"
                                + formatBytes(storageLimit)
                                + "，已用 "
                                + formatBytes(used)
                                + "），无法上传，请联系平台调整配额");
            }
        }
        // 先 PUT 对象再落台账（ADR 0006 §4）：台账失败只留下无引用的孤儿对象（键含随机 UUID），
        // 反过来会留下取不到内容的 PENDING 台账行；租户前缀由存储端口强制（验收：按租户前缀隔离）
        String objectKey = storage.put(relativeKey("files", real), body, mime);
        String thumbnailKey =
                thumbnail == null
                        ? null
                        : storage.put(
                                relativeKey("thumbnails", real),
                                thumbnail.body(),
                                thumbnail.contentType());
        SysFileRow row = new SysFileRow();
        row.setObjectKey(objectKey);
        row.setThumbnailKey(thumbnailKey);
        row.setOriginalName(name);
        row.setFormatCode(real.code());
        row.setMimeType(mime);
        row.setSizeBytes(body.length);
        row.setSha256(sha256);
        row.setPages(pages);
        row.setStatus("PENDING");
        row.setVirusScanStatus(scanned ? "CLEAN" : "UNSCANNED");
        row.setVirusScannedAt(scanned ? LocalDateTime.now(ZONE) : null);
        row.setUploaderId(uploader);
        files.insert(row);

        audit.record(
                new AuditEvent(
                        "FILE_UPLOAD",
                        uploader,
                        "CREATE",
                        "sys_file:" + row.getId(),
                        "上传文件：" + name + "（真实类型 " + real.code() + "，" + body.length + " 字节）"));
        log.info(
                "文件上传成功 id={} format={} size={} sha256={} thumbnail={}",
                row.getId(),
                real.code(),
                body.length,
                sha256,
                thumbnailKey != null);
        return new FileUploadView(
                String.valueOf(row.getId()), sha256, body.length, mime, real.code(), name, pages);
    }

    // ---------------------------------------------------------------- 私有

    /** 字节数的展示文案（配额提示用）：MB / GB 一位小数，小于 1 MB 按字节。 */
    static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 去掉路径部分、限制长度；无文件名 / 超长 / 含路径分隔符直接 40001。 */
    private static String sanitizeName(String filename) {
        if (filename == null || filename.isBlank()) {
            throw bad("文件名不能为空");
        }
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank() || ".".equals(name)) {
            throw bad("文件名不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw bad("文件名不能超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    /** 小写扩展名；无扩展名 40001（无法对照白名单）。 */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw bad("文件缺少扩展名，无法识别类型");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String openExtensions(FilePolicyService.UploadPolicy current) {
        return current.formats().stream()
                .flatMap(format -> format.extensions().stream())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /** 相对键：{前缀}/{年月}/{uuid}.{规范扩展名}（files/ 原件、thumbnails/ 缩略图）；存储端口在其前拼 t/{租户编码}/ 前缀（E10-T02）。 */
    private static String relativeKey(String prefix, FileFormat format) {
        String month = LocalDate.now(ZONE).toString().substring(0, 7).replace("-", "");
        return prefix + "/" + month + "/" + UUID.randomUUID() + "." + format.code();
    }

    /** SHA-256 十六进制（上传审计 / C-SCAN-017 重复提示 / E22-T15 补扫完整性核对共用；包内可见）。 */
    static String sha256Hex(byte[] body) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JVM 规范要求提供 SHA-256，理论不可达
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
