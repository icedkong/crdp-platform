package com.crdp.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * G9（M3 审查）/ OQ-42 定论：限流客户端 IP 默认只信 RemoteAddr（客户端能直连应用时可伪造 X-Forwarded-For
 * 刷新限流次数）；crdp.web.trust-xff=true（部署侧确认反代 / WAF 覆盖后）才取 转发头首段。两种模式都锁定。
 */
class ClientIpResolverTest {

    @Test
    void defaultTrustsRemoteAddrOnlyAndIgnoresForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.2");
        request.addHeader("X-Forwarded-For", "10.99.99.99, 172.16.0.9");

        assertEquals("192.168.1.2", resolver.resolve(request), "默认不采信转发头（防伪造刷新限流）");
    }

    @Test
    void trustXffEnabledTakesFirstForwardedSegment() {
        WebProperties properties = new WebProperties();
        properties.setTrustXff(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.9");
        request.addHeader("X-Forwarded-For", "10.0.0.8, 172.16.0.9");

        assertEquals("10.0.0.8", resolver.resolve(request), "多级代理取最前一段");
    }

    @Test
    void trustXffEnabledFallsBackToRemoteAddrWhenHeaderBlank() {
        WebProperties properties = new WebProperties();
        properties.setTrustXff(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.2");
        request.addHeader("X-Forwarded-For", "  ");

        assertEquals("192.168.1.2", resolver.resolve(request), "空白转发头回落 RemoteAddr");
    }
}
