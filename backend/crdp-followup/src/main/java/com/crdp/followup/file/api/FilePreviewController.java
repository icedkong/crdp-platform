package com.crdp.followup.file.api;

import com.crdp.followup.file.app.FilePreviewService;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预览内容代理（E10-T04 / tech-plan §10「预览」：经应用代理按流返回、不缓存）。
 *
 * <p>pdf.js、docx-preview 与原生 {@code <audio>} 是浏览器子资源请求，带不了登录令牌——内容取流走 {@code GET
 * /api/hospital/files/preview/{token}}：{@code @PublicApi} 登记（ApiResourceGuardFilter 放行）+
 * SecurityConfig permitAll，凭 preview-url 签发的 ≤ 5 分钟随机令牌定位租户与文件（限流与令牌校验在 {@link
 * FilePreviewService#content}）。
 *
 * <p>响应不缓存（{@code no-store}，规格「不缓存」）、inline 展示、MIME 取台账登记值（只可能是上传白名单内的类型）。
 */
@RestController
public class FilePreviewController {

    private static final String URL_PREFIX = "/api/hospital/files/preview/";

    private final FilePreviewService preview;
    private final ClientIpResolver clientIps;

    public FilePreviewController(FilePreviewService preview, ClientIpResolver clientIps) {
        this.preview = preview;
        this.clientIps = clientIps;
    }

    @GetMapping(URL_PREFIX + "{token}")
    @PublicApi
    public ResponseEntity<byte[]> preview(@PathVariable String token, HttpServletRequest request) {
        FilePreviewService.PreviewContent content =
                preview.content(token, clientIps.resolve(request));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(content.originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(content.body().length)
                .contentType(
                        MediaType.parseMediaType(
                                content.mime() == null || content.mime().isBlank()
                                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                        : content.mime()))
                .body(content.body());
    }

    // G9（M3 审查）：限流 IP 统一走 ClientIpResolver（默认只信 RemoteAddr，
    // crdp.web.trust-xff=true 才取 X-Forwarded-For 首段；与各公开口同口径，OQ-42）
}
