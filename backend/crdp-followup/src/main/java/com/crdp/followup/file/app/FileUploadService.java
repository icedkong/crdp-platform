package com.crdp.followup.file.app;

import com.crdp.followup.file.domain.FileFormat;
import com.crdp.followup.file.infra.detect.FileTypeDetector;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
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
 * <p>校验顺序（全部 40001 + 中文原因）：文件名与扩展名 → 本院白名单 → 大小上限 → 内容真实类型 （Tika 魔数识别，不传文件名）→
 * 扩展名与真实类型一致。改扩展名的可执行文件（如 report.pdf 实为 PE/exe）在真实类型一步被拒——识别结果是
 * application/x-msdownload，不在白名单（任务验收）。 宏 Office（docm/xlsm）、脚本、压缩包等目录外格式同理拒绝（NFR「恶意代码防范」首期口径）。
 *
 * <p>通过后写 sys_file（状态 PENDING，未关联报告单；关联与每份数量上限的落点校验随 E16 报告单）、 文件体暂存表内 LONGBLOB（OBS 适配在
 * E10-T02，届时迁桶并清理 body 列，接口与哈希口径不变）， 并写 FILE_UPLOAD 审计（A-ATT-006 口径：谁、何时、传了什么）。上传接口经
 * {@code @RequiresPermission("followup:scan:upload")} 控制（OQ-12），模块未开通 40301。
 */
@Service
public class FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    /** 原文件名长度上限（sys_file.original_name 列宽）。 */
    private static final int NAME_MAX = 255;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final FilePolicyService policy;
    private final FileTypeDetector detector;
    private final SysFileMapper files;
    private final AuditLogger audit;

    public FileUploadService(
            FilePolicyService policy,
            FileTypeDetector detector,
            SysFileMapper files,
            AuditLogger audit) {
        this.policy = policy;
        this.detector = detector;
        this.files = files;
        this.audit = audit;
    }

    /** 上传结果（report.md §1：fileId、sha256、size、mime；pages 随 E10-T03 回填）。 */
    public record FileUploadView(
            String fileId,
            String sha256,
            long size,
            String mime,
            String format,
            String originalName) {}

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

        long uploader = CurrentSession.requireHospital().userId();
        String sha256 = sha256Hex(body);
        SysFileRow row = new SysFileRow();
        row.setObjectKey(objectKey(real));
        row.setOriginalName(name);
        row.setFormatCode(real.code());
        row.setMimeType(mime);
        row.setSizeBytes(body.length);
        row.setSha256(sha256);
        row.setStatus("PENDING");
        row.setBody(body);
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
                "文件上传成功 id={} format={} size={} sha256={}",
                row.getId(),
                real.code(),
                body.length,
                sha256);
        return new FileUploadView(
                String.valueOf(row.getId()), sha256, body.length, mime, real.code(), name);
    }

    // ---------------------------------------------------------------- 私有

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

    /** 对象键：files/{年月}/{uuid}.{规范扩展名}；租户前缀与桶内隔离随 E10-T02 接入。 */
    private static String objectKey(FileFormat format) {
        String month = LocalDate.now(ZONE).toString().substring(0, 7).replace("-", "");
        return "files/" + month + "/" + UUID.randomUUID() + "." + format.code();
    }

    private static String sha256Hex(byte[] body) {
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
