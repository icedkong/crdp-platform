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
import com.crdp.framework.web.ClientIpResolver;
import com.crdp.framework.web.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * E10-T04 预览内容代理响应头（tech-plan §10「按流返回、不缓存」）：no-store / no-cache、inline 展示、 MIME 直传、nosniff。 G9（M3
 * 审查）：限流 IP 统一走 ClientIpResolver——默认只信 RemoteAddr（伪造 X-Forwarded-For
 * 不能刷新限流），crdp.web.trust-xff=true（部署侧确认反代覆盖后）才取转发头首段。
 */
class FilePreviewControllerTest {

    private final FilePreviewService preview = mock(FilePreviewService.class);
    private final FilePreviewController controller =
            new FilePreviewController(preview, new ClientIpResolver(new WebProperties()));
    private final FilePreviewController trustXffController =
            new FilePreviewController(preview, new ClientIpResolver(trustXff()));

    /** trust-xff 开（OQ-42：部署侧确认反代 / WAF 覆盖该头后才允许）。 */
    private static WebProperties trustXff() {
        WebProperties properties = new WebProperties();
        properties.setTrustXff(true);
        return properties;
    }

    @Test
    void streamsWithoutCache() {
        when(preview.content("token-1", "10.0.0.8"))
                .thenReturn(
                        new FilePreviewService.PreviewContent(
                                "PDF".getBytes(), "application/pdf", "复查报告（示例）.pdf"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 172.16.0.9");

        var response = trustXffController.preview("token-1", request);

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
        // trust-xff=true：转发头首段作为限流 IP 传给服务
        verify(preview).content(eq("token-1"), eq("10.0.0.8"));
    }

    /** G9：默认（trust-xff=false）只信 RemoteAddr——带伪造转发头也按 RemoteAddr 限流。 */
    @Test
    void forwardedHeaderIgnoredUnlessTrustXffEnabled() {
        when(preview.content("t", "192.168.1.2"))
                .thenReturn(
                        new FilePreviewService.PreviewContent(
                                "X".getBytes(), "audio/mpeg", "通话录音（示例）.mp3"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.2");
        request.addHeader("X-Forwarded-For", "10.99.99.99"); // 客户端伪造，默认不采信
        controller.preview("t", request);
        verify(preview).content(eq("t"), eq("192.168.1.2"));
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
