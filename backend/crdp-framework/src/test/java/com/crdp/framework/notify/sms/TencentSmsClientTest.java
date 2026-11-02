package com.crdp.framework.notify.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** E09-T03：腾讯云 SendSms 客户端（TC3 签名请求构造 + 响应解析），HTTP 传输用桩离线验证， 不触网、不用真实密钥。 */
class TencentSmsClientTest {

    private static final Instant FIXED = Instant.parse("2027-02-01T08:30:00Z");

    private final TencentSmsProperties properties = new TencentSmsProperties();

    private TencentSmsClient client(TencentSmsClient.Transport transport) {
        properties.setSecretId("AKIDtest0000000000000000");
        properties.setSecretKey("kEYtest0000000000000000000000");
        properties.setSdkAppId("1400006666");
        properties.setSignName("示例签名");
        properties.setRegion("ap-guangzhou");
        properties.setEndpoint("sms.tencentcloudapi.com");
        Clock clock =
                new Clock() {
                    @Override
                    public Instant instant() {
                        return FIXED;
                    }

                    @Override
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }
                };
        return new TencentSmsClient(properties, new ObjectMapper(), clock, transport);
    }

    @Test
    void buildsSignedSendSmsRequestAndParsesSerialNo() {
        Map<String, String> capturedHeaders = new LinkedHashMap<>();
        StringBuilder capturedBody = new StringBuilder();
        TencentSmsClient sms =
                client(
                        (url, headers, body) -> {
                            assertEquals("https://sms.tencentcloudapi.com/", url);
                            capturedHeaders.putAll(headers);
                            capturedBody.append(body);
                            return """
                                    {"Response":{"SendStatusSet":[{"SerialNo":"5000:1045710669",
                                     "Code":"Ok","Message":"send success"}],"RequestId":"r-1"}}
                                    """;
                        });
        String serialNo = sms.send("+8613900001234", "示例签名", "1234567", List.of("437090"));
        assertEquals("5000:1045710669", serialNo);

        // 公共头与 TC3 签名头
        assertEquals("SendSms", capturedHeaders.get("X-TC-Action"));
        assertEquals("2021-01-11", capturedHeaders.get("X-TC-Version"));
        assertEquals("ap-guangzhou", capturedHeaders.get("X-TC-Region"));
        assertEquals(String.valueOf(FIXED.getEpochSecond()), capturedHeaders.get("X-TC-Timestamp"));
        assertEquals("sms.tencentcloudapi.com", capturedHeaders.get("Host"));
        String authorization = capturedHeaders.get("Authorization");
        assertTrue(
                authorization.startsWith("TC3-HMAC-SHA256 Credential=AKIDtest0000000000000000/"));
        assertTrue(authorization.contains("/sms/tc3_request"));
        assertTrue(authorization.contains("SignedHeaders=content-type;host"));
        assertTrue(authorization.contains("Signature="));

        // 请求体：单条 E.164 号码 + 应用 / 签名 / 模板 / 参数
        assertTrue(capturedBody.toString().contains("\"PhoneNumberSet\":[\"+8613900001234\"]"));
        assertTrue(capturedBody.toString().contains("\"SmsSdkAppId\":\"1400006666\""));
        assertTrue(capturedBody.toString().contains("\"SignName\":\"示例签名\""));
        assertTrue(capturedBody.toString().contains("\"TemplateId\":\"1234567\""));
        assertTrue(capturedBody.toString().contains("\"TemplateParamSet\":[\"437090\"]"));
    }

    @Test
    void sameInputsProduceDeterministicSignature() {
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        TencentSmsClient.Transport transport =
                (url, headers, body) -> {
                    (first.length() == 0 ? first : second).append(headers.get("Authorization"));
                    return """
                            {"Response":{"SendStatusSet":[{"SerialNo":"s","Code":"Ok"}]}}
                            """;
                };
        TencentSmsClient sms = client(transport);
        sms.send("+8613900001234", "示例签名", "1234567", List.of("1"));
        sms.send("+8613900001234", "示例签名", "1234567", List.of("1"));
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void providerErrorNodeIsRejected() {
        TencentSmsClient sms =
                client(
                        (url, headers, body) ->
                                """
                                {"Response":{"Error":{"Code":"AuthFailure.SignatureFailure",
                                 "Message":"签名错误"}}}
                                """);
        SmsChannelException failure =
                assertThrows(
                        SmsChannelException.class,
                        () -> sms.send("+8613900001234", "示例签名", "1234567", List.of("437090")));
        assertEquals("PROVIDER_REJECTED", failure.reason());
        assertTrue(failure.getMessage().contains("AuthFailure.SignatureFailure"));
        // 异常不带手机号与参数
        assertTrue(!failure.getMessage().contains("13900001234"));
    }

    @Test
    void sendStatusFailureCodeIsRejected() {
        TencentSmsClient sms =
                client(
                        (url, headers, body) ->
                                """
                                {"Response":{"SendStatusSet":[{"SerialNo":"s","Code":
                                 "FailedOperation.TemplateParamSetNotMatchApprovedTemplate"}]}}
                                """);
        SmsChannelException failure =
                assertThrows(
                        SmsChannelException.class,
                        () -> sms.send("+8613900001234", "示例签名", "1234567", List.of("437090")));
        assertEquals("PROVIDER_REJECTED", failure.reason());
    }

    @Test
    void transportFailureIsProviderUnreachable() {
        TencentSmsClient sms =
                client(
                        (url, headers, body) -> {
                            throw new java.io.IOException("timeout");
                        });
        SmsChannelException failure =
                assertThrows(
                        SmsChannelException.class,
                        () -> sms.send("+8613900001234", "示例签名", "1234567", List.of("437090")));
        assertEquals("PROVIDER_UNREACHABLE", failure.reason());
        // 异常不带请求体（内含手机号）
        assertTrue(!failure.getMessage().contains("13900001234"));
    }

    @Test
    void badJsonResponseIsRejected() {
        TencentSmsClient sms = client((url, headers, body) -> "<html>502</html>");
        assertThrows(
                SmsChannelException.class,
                () -> sms.send("+8613900001234", "示例签名", "1234567", List.of("437090")));
    }
}
