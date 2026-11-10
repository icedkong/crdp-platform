package com.crdp.followup.file.api;

import com.crdp.followup.file.app.FileDownloadService;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.framework.web.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签名下载（E10-T05 / tech-plan §10「下载」：5 分钟签名链接，每次下载记审计）。
 *
 * <p>登录态调 {@code POST /api/hospital/files/{fileId}/download-url}（followup:scan:download 敏感权限）换 HMAC
 * 签名链接；公开取流口 {@code GET /api/hospital/files/download/{token}} 凭签名定位租户与文件后**附件**返回原文件：
 * {@code @PublicApi} 登记（ApiResourceGuardFilter 放行）+ SecurityConfig permitAll，限流与验签在 {@link
 * FileDownloadService#content}。与预览代理（inline）不同，下载以 attachment 落盘；响应同样不缓存、nosniff。
 */
@Tag(name = "医院端文件下载")
@RestController
public class FileDownloadController {

    private static final String URL_PREFIX = "/api/hospital/files/download/";

    private final FileDownloadService download;
    private final ClientIpResolver clientIps;

    public FileDownloadController(FileDownloadService download, ClientIpResolver clientIps) {
        this.download = download;
        this.clientIps = clientIps;
    }

    @Operation(
            summary =
                    "签发下载短时链接（E10-T05，tech-plan §10「下载」，A-ATT-006）：5 分钟有效的 HMAC 签名链接，"
                            + "每次签发写 DOWNLOAD 数据访问审计（含文件 SHA-256）；"
                            + "返回 { url, expiresAt, format, mime, originalName, sizeBytes }；"
                            + "报告单数据范围校验随 E16 报告单子域")
    @PostMapping("/api/hospital/files/{fileId}/download-url")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:download")
    public ApiResponse<FileDownloadService.DownloadGrant> downloadUrl(@PathVariable long fileId) {
        return ApiResponse.ok(download.downloadUrl(fileId));
    }

    @Operation(summary = "下载内容取流（公开口，签名链接 + 按 IP 每小时限流）：附件返回原文件")
    @GetMapping(URL_PREFIX + "{token}")
    @PublicApi
    public ResponseEntity<byte[]> download(@PathVariable String token, HttpServletRequest request) {
        FileDownloadService.DownloadContent content =
                download.content(token, clientIps.resolve(request));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
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
