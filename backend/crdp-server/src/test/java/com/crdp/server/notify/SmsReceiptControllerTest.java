package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.notify.sms.SmsReceiptHandler;
import com.crdp.framework.notify.sms.TencentSmsProperties;
import com.crdp.framework.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * E22-T10 / 渗透修复自查 PT-SELF-02：回执公开回调的来源校验锁定。配置了回执凭据（控制台「状态回执推送」的 Basic Auth，经环境变量注入）后必须凭据匹配才进
 * handler——伪造回执（污染 notify_send_log 对账数据）按 401 拒绝； 凭据未配置时保持既有行为（仅 IP
 * 限流），迁移期由启动告警与运维清单（docs/ops/pentest-remediation.md §5）兜住。
 */
class SmsReceiptControllerTest {

    private final SmsReceiptHandler handler = mock(SmsReceiptHandler.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private TencentSmsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TencentSmsProperties();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.increment("crdp:sms-receipt:ip:127.0.0.1")).thenReturn(1L);
        when(handler.handle("{}")).thenReturn(1);
    }

    private SmsReceiptController controller() {
        return new SmsReceiptController(handler, redis, properties);
    }

    private static HttpServletRequest requestWithAuth(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContentType("application/json");
        if (header != null) {
            request.addHeader("Authorization", header);
        }
        // 无 X-Forwarded-For，走 remoteAddr（MockHttpServletRequest 默认 127.0.0.1）
        return request;
    }

    private static String basic(String user, String password) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void configuredCredentialsCorrectBasicAuthPassesToHandler() {
        properties.setReceiptAuthUsername("callback");
        properties.setReceiptAuthPassword("s3cret-pass");

        var ack = controller().receive("{}", requestWithAuth(basic("callback", "s3cret-pass")));

        assertEquals(0, ack.result());
        verify(handler).handle("{}");
    }

    @Test
    void configuredCredentialsMissingAuthorizationRejectedWithoutHandlerCall() {
        properties.setReceiptAuthUsername("callback");
        properties.setReceiptAuthPassword("s3cret-pass");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller().receive("{}", requestWithAuth(null)));

        assertEquals(401, ex.httpStatus());
        verify(handler, never()).handle("{}");
    }

    @Test
    void configuredCredentialsWrongPasswordRejected() {
        properties.setReceiptAuthUsername("callback");
        properties.setReceiptAuthPassword("s3cret-pass");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                controller()
                                        .receive(
                                                "{}", requestWithAuth(basic("callback", "wrong"))));

        assertEquals(401, ex.httpStatus());
        verify(handler, never()).handle("{}");
    }

    @Test
    void configuredCredentialsWrongSchemeRejected() {
        properties.setReceiptAuthUsername("callback");
        properties.setReceiptAuthPassword("s3cret-pass");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller().receive("{}", requestWithAuth("Bearer some-token")));

        assertEquals(401, ex.httpStatus());
        verify(handler, never()).handle("{}");
    }

    @Test
    void configuredUsernameOnlyCountsAsNotConfiguredAndStaysLegacyOpen() {
        // 只配了用户名没配密码 = 未完成配置：保持迁移期行为（限流 + 放行），由启动告警暴露配置缺失
        properties.setReceiptAuthUsername("callback");

        var ack = controller().receive("{}", requestWithAuth(null));

        assertEquals(0, ack.result());
        verify(handler).handle("{}");
    }

    @Test
    void notConfiguredKeepsLegacyRateLimitedPathForMigrationWindow() {
        var ack = controller().receive("{}", requestWithAuth(null));

        assertEquals(0, ack.result());
        verify(handler).handle("{}");
    }
}
