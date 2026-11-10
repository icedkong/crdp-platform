package com.crdp.framework.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 限流用客户端 IP 的统一取值口（G9 / M3 审查；登录图形验证码、文件预览、签名下载、手机上传 H5、短信回执五处公开口共用）。
 *
 * <p>口径（OQ-42 定论）：默认**只信 {@code RemoteAddr}**——客户端能直连应用时可伪造 {@code X-Forwarded-For} 刷新限流次数；{@code
 * crdp.web.trust-xff=true}（部署侧确认反代 / WAF 已覆盖该头后开启）才取 X-Forwarded-For 首段（多级代理取最前一个）。
 */
@Component
public class ClientIpResolver {

    private final boolean trustXff;

    public ClientIpResolver(WebProperties properties) {
        this.trustXff = properties.isTrustXff();
    }

    /** 限流 IP：trust-xff 开 → X-Forwarded-For 首段（空白头回落 RemoteAddr）；关 → RemoteAddr。 */
    public String resolve(HttpServletRequest request) {
        if (trustXff) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
