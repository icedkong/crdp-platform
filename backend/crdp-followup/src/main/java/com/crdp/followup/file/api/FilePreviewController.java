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
 * 会话鉴权的预览代理。前端携带登录令牌获取水印副本，再以临时内存地址展示。
 *
 * <p>{@code @PublicApi} 仅豁免资源码登记，不豁免 SecurityConfig 登录校验；服务层复核会话、范围和文件版本。
 * 图片及文档只返回服务器水印副本，音频保留原格式。响应禁用缓存。
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
    @com.crdp.framework.security.RequireModule("followup")
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
