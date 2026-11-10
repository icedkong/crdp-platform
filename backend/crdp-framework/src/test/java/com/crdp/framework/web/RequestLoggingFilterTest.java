package com.crdp.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * S2（M3 审查）：访问日志不得落完整一次性令牌——H5 上传（256 位令牌）、预览取流、签名下载取流、上传二维码轮询路径里的令牌段 只保留前 8 位摘要（AGENTS §5.5）。
 *
 * <p>掩码是纯函数，直接锁 {@link RequestLoggingFilter#maskTokens} 的行为；被拒请求（4xx/5xx）同样经过 finally
 * 里的同一条掩码路径，无单独分支。
 */
class RequestLoggingFilterTest {

    /** H5 上传 256 位令牌（示例为虚构随机串）：完整令牌不得出现在掩码结果里。 */
    @Test
    void h5UploadTokenMaskedToDigest() {
        String token = "a".repeat(256);
        String masked = RequestLoggingFilter.maskTokens("/api/h5/upload/" + token);
        assertEquals("/api/h5/upload/" + "a".repeat(8) + "…", masked);
    }

    /** 预览 / 签名下载取流口：令牌段同样只落摘要，路径结构保留（可对账路由）。 */
    @Test
    void previewAndDownloadTokensMasked() {
        assertEquals(
                "/api/hospital/files/preview/01234567…",
                RequestLoggingFilter.maskTokens("/api/hospital/files/preview/0123456789abcdef"));
        assertEquals(
                "/api/hospital/files/download/01234567…",
                RequestLoggingFilter.maskTokens("/api/hospital/files/download/0123456789abcdef"));
        assertEquals(
                "/api/hospital/followup/upload-tokens/01234567…",
                RequestLoggingFilter.maskTokens(
                        "/api/hospital/followup/upload-tokens/0123456789abcdef"));
    }

    /** 令牌段后面的子动作（/context、/files、/submit）保留，便于排查手机端卡在哪一步。 */
    @Test
    void subActionsAfterTokenKept() {
        assertEquals(
                "/api/h5/upload/01234567…/submit",
                RequestLoggingFilter.maskTokens("/api/h5/upload/0123456789abcdef/submit"));
        assertEquals(
                "/api/h5/upload/01234567…/files",
                RequestLoggingFilter.maskTokens("/api/h5/upload/0123456789abcdef/files"));
        assertEquals(
                "/api/h5/upload/01234567…/context",
                RequestLoggingFilter.maskTokens("/api/h5/upload/0123456789abcdef/context"));
    }

    /** 不带令牌的路径原样保留（登录、菜单、业务接口），掩码不改变正常排查能力。 */
    @Test
    void plainPathsUntouched() {
        assertEquals(
                "/api/hospital/auth/login",
                RequestLoggingFilter.maskTokens("/api/hospital/auth/login"));
        assertEquals(
                "/api/hospital/followup/patients/123/tasks",
                RequestLoggingFilter.maskTokens("/api/hospital/followup/patients/123/tasks"));
        assertEquals("/api/h5/upload/", RequestLoggingFilter.maskTokens("/api/h5/upload/"));
        assertEquals("/actuator/health", RequestLoggingFilter.maskTokens("/actuator/health"));
        assertEquals("/", RequestLoggingFilter.maskTokens("/"));
    }

    /** 短于摘要长度的段原样保留（如 1 位探测字符；无泄漏面可截）。 */
    @Test
    void shortSegmentKeptAsIs() {
        assertEquals("/api/h5/upload/x", RequestLoggingFilter.maskTokens("/api/h5/upload/x"));
    }
}
