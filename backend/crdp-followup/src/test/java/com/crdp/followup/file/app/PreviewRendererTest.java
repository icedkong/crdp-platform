package com.crdp.followup.file.app;

import static org.assertj.core.api.Assertions.*;

import com.crdp.framework.web.ApiException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PreviewRendererTest {
    @TempDir Path temp;
    private final FilePreviewProperties properties = new FilePreviewProperties();

    private Path script(String code) throws Exception {
        Path script = temp.resolve("launcher with spaces.sh");
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + code);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        properties.setRendererLauncher(script.toString());
        return script;
    }

    @Test
    void missingLauncherFailsClosed() {
        properties.setRendererLauncher(temp.resolve("missing").toString());
        assertUnavailable(
                () -> new PreviewRenderer(properties).render(new byte[] {1}, "png", "水印（示例）"));
    }

    @Test
    void invalidOutputCannotBecomeRawFallback() throws Exception {
        script("printf 'raw-docx' > \"$1/output/result.pdf\"\n");
        assertUnavailable(
                () ->
                        new PreviewRenderer(properties)
                                .render("raw-docx".getBytes(), "docx", "水印（示例）"));
    }

    @Test
    void successfulProcessReturnsCopyAndCleansInput() throws Exception {
        Path marker = temp.resolve("last-work");
        script(
                "printf '%s' \"$1\" > '"
                        + marker
                        + "'\nprintf '%s' '%PDF-copy' > \"$1/output/result.pdf\"\n");
        var result = new PreviewRenderer(properties).render("original".getBytes(), "pdf", "水印（示例）");
        assertThat(new String(result.body())).isEqualTo("%PDF-copy");
        assertThat(result.mime()).isEqualTo("application/pdf");
        assertThat(Path.of(Files.readString(marker))).doesNotExist();
    }

    @Test
    void timeoutIsBoundedAndNeverReturnsInput() throws Exception {
        script("exec /bin/sleep 10\n");
        properties.setRenderTimeoutSeconds(1);
        var start = Instant.now();
        assertUnavailable(
                () ->
                        new PreviewRenderer(properties)
                                .render("original".getBytes(), "pdf", "水印（示例）"));
        assertThat(Duration.between(start, Instant.now()).toSeconds()).isLessThan(5);
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(50301));
    }
}
