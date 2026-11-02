package com.crdp.followup.file.app;

import com.crdp.followup.file.domain.FileCategory;
import com.crdp.followup.file.domain.FileFormat;
import com.crdp.followup.file.infra.mapper.UploadParamMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上传策略（E10-T01 / PLT-CFG-010「文件上传配置：文件大小与类型白名单」）。
 *
 * <p>生效值＝本院 cfg_param 覆盖值，否则平台库 plt_param 默认值，再回落代码内置缺省 （平台定义缺失或覆盖值不可解析时；A-ATT-004：文档图片 20 MB、录音 50
 * MB、每份 10 个， 白名单为格式目录全集）。医院 C（每份 50 个、不放 DOCX/XLSX 等）经租户覆盖实现，不写租户分支。
 *
 * <p>白名单 CSV 只认 {@link FileFormat} 目录内的扩展名：目录外的令牌忽略并告警（覆盖值只能收窄 / 放开目录内格式，新增格式 =
 * 改代码目录，属产品决策）；全部不可解析时回落目录全集，保证不因配置 错误把上传整站锁死。
 */
@Service
public class FilePolicyService {

    private static final Logger log = LoggerFactory.getLogger(FilePolicyService.class);

    /** 参数键（平台库 V33 登记，创建后不改名）。 */
    public static final String KEY_ALLOWED_TYPES = "file.upload.allowed.types";

    public static final String KEY_MAX_SIZE_MB = "file.upload.max.size.mb";
    public static final String KEY_AUDIO_MAX_SIZE_MB = "file.upload.audio.max.size.mb";
    public static final String KEY_MAX_FILES_PER_REPORT = "file.upload.max.files.per.report";

    /** 代码内置缺省（plt_param 缺行或值不可解析时兜底）。 */
    static final int DEFAULT_MAX_SIZE_MB = 20;

    static final int DEFAULT_AUDIO_MAX_SIZE_MB = 50;
    static final int DEFAULT_MAX_FILES_PER_REPORT = 10;

    private static final long MB = 1024L * 1024L;

    private final FileUploadParamDefaults defaults;
    private final UploadParamMapper overrides;

    public FilePolicyService(FileUploadParamDefaults defaults, UploadParamMapper overrides) {
        this.defaults = defaults;
        this.overrides = overrides;
    }

    /** 单个格式（扩展名含别名）与其类别大小上限。 */
    public record FormatPolicy(
            String format,
            String label,
            List<String> extensions,
            FileCategory category,
            long maxBytes) {

        /** 大小上限的 MB 展示值（向上取整，策略值本身是整数 MB）。 */
        public int maxMb() {
            return Math.toIntExact(maxBytes / MB);
        }
    }

    /** 本院生效的上传策略。 */
    public record UploadPolicy(
            List<FormatPolicy> formats, int maxDocumentMb, int maxAudioMb, int maxFilesPerReport) {

        /** 按扩展名取放行的格式策略（大小写不敏感；未开放返回空）。 */
        public Optional<FormatPolicy> byExtension(String extension) {
            String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
            return formats.stream()
                    .filter(format -> format.extensions().contains(normalized))
                    .findFirst();
        }

        /** 每份报告单文件数校验（A-ATT-004 最多 10 个、C-SCAN-006 每份最多 50 个，按院配置）。 */
        public boolean withinFileCount(int fileCount) {
            return fileCount > 0 && fileCount <= maxFilesPerReport;
        }
    }

    /** 本院生效策略（每次读取实时合并覆盖值；平台侧默认值由适配器缓存 60 秒）。 */
    public UploadPolicy policy() {
        List<FileFormat> allowed = allowedFormats();
        int documentMb = effectiveMb(KEY_MAX_SIZE_MB, DEFAULT_MAX_SIZE_MB);
        int audioMb = effectiveMb(KEY_AUDIO_MAX_SIZE_MB, DEFAULT_AUDIO_MAX_SIZE_MB);
        int maxFiles = effectiveMb(KEY_MAX_FILES_PER_REPORT, DEFAULT_MAX_FILES_PER_REPORT);
        List<FormatPolicy> formats = new ArrayList<>();
        for (FileFormat format : allowed) {
            long maxBytes = (format.category() == FileCategory.AUDIO ? audioMb : documentMb) * MB;
            formats.add(
                    new FormatPolicy(
                            format.code(),
                            format.label(),
                            format.extensions(),
                            format.category(),
                            maxBytes));
        }
        return new UploadPolicy(formats, documentMb, audioMb, maxFiles);
    }

    // ---------------------------------------------------------------- 私有

    /** 白名单解析：覆盖值 → 平台默认值 → 目录全集；目录外令牌忽略，全部无效回落目录全集。 */
    private List<FileFormat> allowedFormats() {
        String raw = effectiveText(KEY_ALLOWED_TYPES, String.join(",", FileFormat.allCodes()));
        Map<String, FileFormat> picked = new LinkedHashMap<>();
        for (String token : raw.split(",")) {
            String ext = token.trim().toLowerCase(Locale.ROOT);
            if (ext.isEmpty()) {
                continue;
            }
            Optional<FileFormat> format = FileFormat.ofExtension(ext);
            if (format.isPresent()) {
                picked.putIfAbsent(format.get().code(), format.get());
            } else {
                log.warn("上传白名单含不支持的扩展名 {}，已忽略（支持目录：{}）", ext, FileFormat.allCodes());
            }
        }
        if (picked.isEmpty()) {
            log.warn("上传白名单 {} 解析后为空，回落格式目录全集", KEY_ALLOWED_TYPES);
            return List.of(FileFormat.values());
        }
        return List.copyOf(picked.values());
    }

    /** 生效文本值：本院覆盖 → 平台默认 → fallback。 */
    private String effectiveText(String key, String fallback) {
        String override = overrides.findOverride(key);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return defaults.param(key)
                .map(FileUploadParamDefaults.ParamDefault::platformValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(fallback);
    }

    /** 生效数值（MB / 个）：不可解析（覆盖或默认）逐级回落，最终用代码缺省，绝不因配置错误放行超大文件。 */
    private int effectiveMb(String key, int fallback) {
        String override = overrides.findOverride(key);
        if (override != null) {
            Integer parsed = parseInt(override);
            if (parsed != null) {
                return parsed;
            }
            log.warn("租户覆盖参数 {}={} 不可解析，回落平台默认", key, override);
        }
        Integer platform =
                defaults.param(key)
                        .map(FileUploadParamDefaults.ParamDefault::platformValue)
                        .map(FilePolicyService::parseInt)
                        .orElse(null);
        return platform != null ? platform : fallback;
    }

    private static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
