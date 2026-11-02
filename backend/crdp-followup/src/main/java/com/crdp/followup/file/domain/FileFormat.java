package com.crdp.followup.file.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 上传文件的「真实类型目录」（E10-T01 / A-ATT-008、C-SCAN-018、PLT-CFG-010）。
 *
 * <p>目录之外的格式（可执行文件、脚本、宏 Office、压缩包等）一律不支持：扩展名与 Tika 识别出的 MIME
 * 都必须命中同一项才放行，改扩展名伪装的可执行文件在内容识别一步被拒（验收：改扩展名的 可执行文件被拒）。租户经参数 {@code file.upload.allowed.types}
 * 只能从本目录中挑选开放哪些格式。
 *
 * <p>AMR 只保存与下载（不在线播放，预览随 E10-T04）；DICOM 首期不做（A-ATT-009）。
 */
public enum FileFormat {
    PDF("pdf", List.of("pdf"), "PDF 文档", FileCategory.DOCUMENT, List.of("application/pdf")),
    JPG("jpg", List.of("jpg", "jpeg"), "JPEG 图片", FileCategory.DOCUMENT, List.of("image/jpeg")),
    PNG("png", List.of("png"), "PNG 图片", FileCategory.DOCUMENT, List.of("image/png")),
    DOCX(
            "docx",
            List.of("docx"),
            "Word 文档",
            FileCategory.DOCUMENT,
            List.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    XLSX(
            "xlsx",
            List.of("xlsx"),
            "Excel 工作簿",
            FileCategory.DOCUMENT,
            List.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    MP3("mp3", List.of("mp3"), "MP3 录音", FileCategory.AUDIO, List.of("audio/mpeg")),
    M4A("m4a", List.of("m4a"), "M4A 录音", FileCategory.AUDIO, List.of("audio/mp4", "audio/x-m4a")),
    WAV(
            "wav",
            List.of("wav"),
            "WAV 录音",
            FileCategory.AUDIO,
            List.of("audio/x-wav", "audio/wav", "audio/vnd.wave")),
    AMR("amr", List.of("amr"), "AMR 录音（只保存与下载）", FileCategory.AUDIO, List.of("audio/amr"));

    /** 扩展名（小写）→ 格式。含别名（jpeg → JPG）。 */
    private static final Map<String, FileFormat> BY_EXTENSION =
            Stream.of(values())
                    .flatMap(
                            format ->
                                    format.extensions().stream().map(ext -> Map.entry(ext, format)))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /** Tika 识别 MIME（小写）→ 格式。 */
    private static final Map<String, FileFormat> BY_MIME =
            Stream.of(values())
                    .flatMap(format -> format.mimes().stream().map(mime -> Map.entry(mime, format)))
                    .collect(
                            Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    private final String code;
    private final List<String> extensions;
    private final String label;
    private final FileCategory category;
    private final List<String> mimes;

    FileFormat(
            String code,
            List<String> extensions,
            String label,
            FileCategory category,
            List<String> mimes) {
        this.code = code;
        this.extensions = extensions;
        this.label = label;
        this.category = category;
        this.mimes = mimes;
    }

    /** 格式编码（同时是规范扩展名，小写）。 */
    public String code() {
        return code;
    }

    /** 可用的扩展名（小写，含别名）。 */
    public List<String> extensions() {
        return extensions;
    }

    public String label() {
        return label;
    }

    public FileCategory category() {
        return category;
    }

    /** Tika 可能识别出的 MIME（小写）。 */
    public List<String> mimes() {
        return mimes;
    }

    /** 按扩展名查格式（大小写不敏感；目录外返回空）。 */
    public static Optional<FileFormat> ofExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_EXTENSION.get(extension.toLowerCase(Locale.ROOT)));
    }

    /** 按 Tika 识别出的 MIME 查格式（大小写不敏感；目录外返回空，如 exe 的 application/x-msdownload）。 */
    public static Optional<FileFormat> ofMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_MIME.get(mime.toLowerCase(Locale.ROOT)));
    }

    /** 是否图片（E10-T03 缩略图口径：只有图片生成派生缩略图；录音不转码、文档无缩略图）。 */
    public boolean isImage() {
        return this == JPG || this == PNG;
    }

    /** 全部格式编码（按声明顺序）。 */
    public static List<String> allCodes() {
        return Arrays.stream(values()).map(FileFormat::code).toList();
    }
}
