package com.crdp.framework.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 只记方法、路径、状态与耗时，不记请求体，避免口令或患者信息进日志。
 *
 * <p>S2（M3 审查）：路径里的一次性令牌（H5 上传 {@code /api/h5/upload/{token}}、预览 / 签名下载取流口、上传二维码轮询） 只落摘要（前 8 位 +
 * …），不落完整令牌——持有日志的人在令牌有效窗口内本可凭完整令牌冒用上传（AGENTS §5.5：日志不写令牌）。 被拒请求同样走本掩码（finally 里统一输出）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 摘要保留的令牌前缀长度（够排查对账，不够还原使用）。 */
    static final int TOKEN_DIGEST_LENGTH = 8;

    /** 路径段里携带令牌的路由前缀（含尾斜杠；令牌是前缀后的第一个路径段，后面可再跟 /context、/files 等子动作）。 */
    private static final List<String> TOKEN_PATH_PREFIXES =
            List.of(
                    "/api/h5/upload/",
                    "/api/hospital/files/preview/",
                    "/api/hospital/files/download/",
                    "/api/hospital/followup/upload-tokens/");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000L;
            log.info(
                    "{} {} -> {} ({} ms)",
                    request.getMethod(),
                    maskTokens(request.getRequestURI()),
                    response.getStatus(),
                    millis);
        }
    }

    /** 把已知令牌段替换为「前 8 位 + …」，其余路径原样保留（含被拒请求路径）。 不认识的路由不动——宁可少掩也不猜错段把业务 ID 打花。 */
    static String maskTokens(String uri) {
        if (uri == null || uri.isEmpty()) {
            return uri;
        }
        for (String prefix : TOKEN_PATH_PREFIXES) {
            if (!uri.startsWith(prefix)) {
                continue;
            }
            int tokenStart = prefix.length();
            int tokenEnd = uri.indexOf('/', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = uri.length();
            }
            String token = uri.substring(tokenStart, tokenEnd);
            if (token.isEmpty()) {
                return uri;
            }
            return uri.substring(0, tokenStart) + digest(token) + uri.substring(tokenEnd);
        }
        return uri;
    }

    /** 令牌摘要：长于 8 位取前 8 位加省略号；短令牌原样（无泄漏面可截）。 */
    private static String digest(String token) {
        if (token.length() <= TOKEN_DIGEST_LENGTH) {
            return token;
        }
        return token.substring(0, TOKEN_DIGEST_LENGTH) + "…";
    }
}
