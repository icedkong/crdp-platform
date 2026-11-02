package com.crdp.followup.file.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.app.FileDownloadService;
import com.crdp.framework.web.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * E10-T05 下载响应头（tech-plan §10「下载」）：attachment 附件返回、no-store / no-cache、nosniff、 中文文件名进
 * filename*（RFC 5987）；X-Forwarded-For 首段作为限流 IP 传给服务（与 FilePreviewController 同口径）。
 */
class FileDownloadControllerTest {

    private final FileDownloadService download = mock(FileDownloadService.class);
    private final FileDownloadController controller = new FileDownloadController(download);

    /** 公开取流口：附件返回（attachment）、不缓存、nosniff，MIME 直传。 */
    @Test
    void streamsAsAttachmentWithoutCache() {
        when(download.content("token-1", "10.0.0.8"))
                .thenReturn(
                        new FileDownloadService.DownloadContent(
                                "PDF".getBytes(), "application/pdf", "复查报告（示例）.pdf"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 172.16.0.9");

        var response = controller.download("token-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals("PDF".getBytes(), response.getBody());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        assertEquals(
                "no-store, no-cache, must-revalidate, private",
                response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment"), "attachment 附件下载：" + disposition);
        // RFC 5987：中文文件名编进 filename*
        assertTrue(disposition.contains("filename*="), "中文文件名进 filename*：" + disposition);
        verify(download).content(eq("token-1"), eq("10.0.0.8"));
    }

    /** 无反代头时取 remoteAddr 作为限流 IP。 */
    @Test
    void remoteAddrFallbackWhenNoProxyHeader() {
        when(download.content("t", "192.168.1.2"))
                .thenReturn(
                        new FileDownloadService.DownloadContent(
                                "X".getBytes(), "audio/mpeg", "通话录音（示例）.mp3"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.2");
        controller.download("t", request);
        verify(download).content(eq("t"), eq("192.168.1.2"));
    }

    /** 签发口（登录 + followup:scan:download）：返回短时链接与展示字段。 */
    @Test
    void downloadUrlReturnsGrant() {
        when(download.downloadUrl(9L))
                .thenReturn(
                        new FileDownloadService.DownloadGrant(
                                "/api/hospital/files/download/abc.def",
                                1790000000L,
                                "pdf",
                                "application/pdf",
                                "复查报告（示例）.pdf",
                                8));
        ApiResponse<FileDownloadService.DownloadGrant> response = controller.downloadUrl(9L);
        assertEquals(0, response.code());
        assertEquals("/api/hospital/files/download/abc.def", response.data().url());
        assertEquals(1790000000L, response.data().expiresAtEpochSecond());
    }
}
