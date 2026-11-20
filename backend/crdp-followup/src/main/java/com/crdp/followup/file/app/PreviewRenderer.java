package com.crdp.followup.file.app;

import com.crdp.framework.web.ApiException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 只执行固定参数的本机沙箱启动器；超时 / 缺失 / 超预算一律拒绝，不回退原件。 */
@Component
public class PreviewRenderer {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private final FilePreviewProperties properties;
    private final Semaphore slots = new Semaphore(2);

    public PreviewRenderer(FilePreviewProperties properties) {
        this.properties = properties;
    }

    public record Rendered(byte[] body, String mime, String extension) {}

    public Rendered render(byte[] input, String format, String watermark) {
        if (input.length > MAX_BYTES || !Set.of("png", "jpg", "pdf", "docx").contains(format))
            throw unavailable();
        if (!slots.tryAcquire()) throw unavailable();
        Path work = null;
        Process process = null;
        try {
            Path launcher = Path.of(properties.getRendererLauncher());
            if (!launcher.isAbsolute() || !Files.isExecutable(launcher)) throw unavailable();
            work =
                    Files.createTempDirectory(
                            "crdp-preview-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            Files.createDirectory(work.resolve("output"));
            Files.write(work.resolve("input.bin"), input);
            Files.writeString(work.resolve("watermark.txt"), watermark);
            var builder =
                    new ProcessBuilder(launcher.toString(), work.toString(), format)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            String home = System.getenv("HOME");
            builder.environment().clear();
            builder.environment().put("PATH", "/usr/bin:/bin:/opt/homebrew/bin");
            builder.environment().put("LANG", "C.UTF-8");
            if (home != null) builder.environment().put("HOME", home);
            process = builder.start();
            if (!process.waitFor(properties.getRenderTimeoutSeconds(), TimeUnit.SECONDS)
                    || process.exitValue() != 0) throw unavailable();
            boolean image = Set.of("png", "jpg").contains(format);
            Path result = work.resolve(image ? "output/result.png" : "output/result.pdf");
            if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(result) == 0
                    || Files.size(result) > MAX_BYTES) throw unavailable();
            byte[] body = Files.readAllBytes(result);
            if (image
                    ? body.length < 8
                            || body[0] != (byte) 137
                            || body[1] != 'P'
                            || body[2] != 'N'
                            || body[3] != 'G'
                    : body.length < 5
                            || body[0] != '%'
                            || body[1] != 'P'
                            || body[2] != 'D'
                            || body[3] != 'F'
                            || body[4] != '-') throw unavailable();
            return new Rendered(
                    body, image ? "image/png" : "application/pdf", image ? "png" : "pdf");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    process.destroy();
                    try {
                        process.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (work != null)
                try (var paths = Files.walk(work)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    /* 仅临时目录；运维清理残留，绝不输出路径或患者数据。 */
                }
            slots.release();
        }
    }

    static ApiException unavailable() {
        return new ApiException(503, 50301, "安全预览生成失败或暂不可用，请稍后重试；原件下载需单独权限");
    }
}
