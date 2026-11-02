package com.crdp.followup.file.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.app.FilePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * E10-T04 预览内容代理响应头（tech-plan §10「按流返回、不缓存」）：no-store / no-cache、inline 展示、 MIME
 * 直传、nosniff；X-Forwarded-For 首段作为限流 IP 传给服务（与 SmsReceiptController 同口径）。
 */
class FilePreviewControllerTest {

    private final FilePreviewService preview = mock(FilePreviewService.class);
    private final FilePreviewController controller = new FilePreviewController(preview);

    @Test
    void streamsWithoutCache() {
        when(preview.content("token-1", "10.0.0.8"))
                .thenReturn(
                        new FilePreviewService.PreviewContent(
                                "PDF".getBytes(), "application/pdf", "复查报告（示例）.pdf"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 172.16.0.9");

        var response = controller.preview("token-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals("PDF".getBytes(), response.getBody());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        assertEquals(
                "no-store, no-cache, must-revalidate, private",
                response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("inline"), "inline 展示：" + disposition);
        // RFC 5987：中文文件名编进 filename*
        assertTrue(disposition.contains("filename*="), "中文文件名进 filename*：" + disposition);
        verify(preview).content(eq("token-1"), eq("10.0.0.8"));
    }

    @Test
    void remoteAddrFallbackWhenNoProxyHeader() {
        when(preview.content("t", "192.168.1.2"))
                .thenReturn(
                        new FilePreviewService.PreviewContent(
                                "X".getBytes(), "audio/mpeg", "通话录音（示例）.mp3"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.2");
        controller.preview("t", request);
        verify(preview).content(eq("t"), eq("192.168.1.2"));
    }
}
