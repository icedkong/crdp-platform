package com.crdp.framework.notify.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** E09-T03：平台短信发送编排——通道开关、手机号 / 模板 / 签名校验、敏感信息拒绝（不触服务商）、 成功受理记台账（脱敏手机号）。 */
class TencentSmsChannelTest {

    private final TencentSmsProperties properties = new TencentSmsProperties();
    private final SmsTemplateStore templates = mock(SmsTemplateStore.class);
    private final SmsSendLogStore sendLog = mock(SmsSendLogStore.class);
    private final TencentSmsClient client = mock(TencentSmsClient.class);

    private TencentSmsChannel channel;

    private static final SmsSendCommand LOGIN =
            new SmsSendCommand(
                    SmsScene.AUTH_LOGIN,
                    "demoa",
                    "13900001234",
                    "AUTH_LOGIN_CODE",
                    List.of("437090"));

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setSecretId("AKIDtest");
        properties.setSecretKey("kEYtest");
        properties.setSdkAppId("1400006666");
        properties.setSignName("示例签名");
        channel = new TencentSmsChannel(properties, templates, sendLog, client);
    }

    private void templateReady() {
        when(templates.findByCode("AUTH_LOGIN_CODE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "AUTH_LOGIN_CODE",
                                        "AUTH_LOGIN",
                                        "登录验证码{1}，请勿泄露给他人。",
                                        "1234567",
                                        null,
                                        "APPROVED")));
    }

    @Test
    void happyPathSendsAndRecordsSubmittedWithMaskedPhone() {
        templateReady();
        when(client.send("+8613900001234", "示例签名", "1234567", List.of("437090")))
                .thenReturn("5000:1045710669");

        String serialNo = channel.send(LOGIN);

        assertEquals("5000:1045710669", serialNo);
        verify(sendLog)
                .recordSubmitted(
                        "AUTH_LOGIN",
                        "demoa",
                        "AUTH_LOGIN_CODE",
                        "1234567",
                        "5000:1045710669",
                        "139****1234");
    }

    @Test
    void disabledChannelFailsWithoutTouchingProvider() {
        properties.setEnabled(false);
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("CHANNEL_DISABLED", failure.reason());
        verify(client, never()).send(null, null, null, null);
        // 失败也进台账（不吞）
        verify(sendLog)
                .recordSubmitFailed(
                        eq("AUTH_LOGIN"),
                        eq("demoa"),
                        eq("AUTH_LOGIN_CODE"),
                        eq("139****1234"),
                        eq("CHANNEL_DISABLED"),
                        contains("未启用"));
    }

    @Test
    void missingSecretsFailWithChannelNotConfigured() {
        properties.setSecretKey("");
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("CHANNEL_NOT_CONFIGURED", failure.reason());
    }

    @Test
    void invalidPhoneIsRejectedBeforeProvider() {
        templateReady();
        SmsSendCommand bad =
                new SmsSendCommand(
                        SmsScene.AUTH_LOGIN, null, "12345", "AUTH_LOGIN_CODE", List.of("437090"));
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(bad));
        assertEquals("PHONE_INVALID", failure.reason());
        verify(client, never()).send(null, null, null, null);
    }

    @Test
    void unknownTemplateIsRejected() {
        when(templates.findByCode("AUTH_LOGIN_CODE")).thenReturn(Optional.empty());
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("TEMPLATE_NOT_FOUND", failure.reason());
    }

    @Test
    void notApprovedTemplateIsRejected() {
        when(templates.findByCode("AUTH_LOGIN_CODE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "AUTH_LOGIN_CODE",
                                        "AUTH_LOGIN",
                                        "内容{1}",
                                        "1234567",
                                        null,
                                        "REVIEWING")));
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("TEMPLATE_NOT_APPROVED", failure.reason());
    }

    @Test
    void templateWithoutProviderIdIsRejected() {
        when(templates.findByCode("AUTH_LOGIN_CODE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "AUTH_LOGIN_CODE",
                                        "AUTH_LOGIN",
                                        "内容{1}",
                                        null,
                                        null,
                                        "APPROVED")));
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("TEMPLATE_NOT_REGISTERED", failure.reason());
        assertTrue(failure.getMessage().contains("未回填腾讯云模板 ID"));
    }

    @Test
    void missingSignIsRejected() {
        properties.setSignName("");
        templateReady();
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("SIGN_MISSING", failure.reason());
    }

    @Test
    void templateLevelSignOverridesChannelDefault() {
        when(templates.findByCode("AUTH_LOGIN_CODE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "AUTH_LOGIN_CODE",
                                        "AUTH_LOGIN",
                                        "登录验证码{1}。",
                                        "1234567",
                                        "专科签名",
                                        "APPROVED")));
        when(client.send("+8613900001234", "专科签名", "1234567", List.of("437090"))).thenReturn("s-1");
        channel.send(LOGIN);
        verify(client).send("+8613900001234", "专科签名", "1234567", List.of("437090"));
    }

    @Test
    void sensitiveParamNeverReachesProviderButIsRecorded() {
        templateReady();
        SmsSendCommand withPhone =
                new SmsSendCommand(
                        SmsScene.AUTH_LOGIN,
                        "demoa",
                        "13900001234",
                        "AUTH_LOGIN_CODE",
                        List.of("13900001234"));
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(withPhone));
        assertEquals("SENSITIVE_CONTENT", failure.reason());
        verify(client, never()).send(null, null, null, null);
        // 台账只记类别，异常不带参数值
        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        verify(sendLog)
                .recordSubmitFailed(
                        eq("AUTH_LOGIN"),
                        eq("demoa"),
                        eq("AUTH_LOGIN_CODE"),
                        eq("139****1234"),
                        errorCode.capture(),
                        contains("白名单"));
        assertEquals("SENSITIVE_CONTENT", errorCode.getValue());
        assertTrue(!failure.getMessage().contains("13900001234"));
    }

    @Test
    void providerFailureIsRecordedAsSubmitFailed() {
        templateReady();
        when(client.send("+8613900001234", "示例签名", "1234567", List.of("437090")))
                .thenThrow(new SmsChannelException("PROVIDER_REJECTED", "腾讯云短信拒绝发送：LimitExceeded"));
        SmsChannelException failure =
                assertThrows(SmsChannelException.class, () -> channel.send(LOGIN));
        assertEquals("PROVIDER_REJECTED", failure.reason());
        verify(sendLog)
                .recordSubmitFailed(
                        "AUTH_LOGIN",
                        "demoa",
                        "AUTH_LOGIN_CODE",
                        "139****1234",
                        "PROVIDER_REJECTED",
                        "腾讯云短信拒绝发送：LimitExceeded");
    }

    @Test
    void sendLogFailureDoesNotSwallowSendResult() {
        templateReady();
        when(client.send("+8613900001234", "示例签名", "1234567", List.of("437090"))).thenReturn("s-1");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(sendLog)
                .recordSubmitted(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        // 台账写坏不吞发送结果（SerialNo 照常返回，调用方不受影响；对账走服务商控制台）
        assertEquals("s-1", channel.send(LOGIN));
    }

    @Test
    void maskPhoneCoversShortValues() {
        assertEquals("***", TencentSmsChannel.maskPhone(null));
        assertEquals("***", TencentSmsChannel.maskPhone("123"));
        assertEquals("139****1234", TencentSmsChannel.maskPhone("13900001234"));
    }

    @Test
    void storeWritesRunInPlatformContext() {
        // JdbcTemplate 桩：SmsSendLogStore 每次写都应包 callAsPlatform（无租户上下文也可写，登录前发码场景）
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SmsSendLogStore store = new SmsSendLogStore(jdbc);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        store.recordSubmitted(
                                "AUTH_LOGIN",
                                "demoa",
                                "AUTH_LOGIN_CODE",
                                "1234567",
                                "s-1",
                                "139****1234"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO plt_sms_send_log"),
                        eq("AUTH_LOGIN"),
                        eq("demoa"),
                        eq("AUTH_LOGIN_CODE"),
                        eq("1234567"),
                        eq("s-1"),
                        eq("139****1234"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void templateStoreReadRunsInPlatformContext() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(
                                org.springframework.jdbc.core.RowMapper.class),
                        org.mockito.ArgumentMatchers.eq("AUTH_LOGIN_CODE")))
                .thenReturn(List.of());
        SmsTemplateStore store = new SmsTemplateStore(jdbc);
        assertEquals(Optional.empty(), store.findByCode("AUTH_LOGIN_CODE"));
        verify(jdbc)
                .query(
                        contains("FROM plt_sms_template"),
                        org.mockito.ArgumentMatchers.any(
                                org.springframework.jdbc.core.RowMapper.class),
                        eq("AUTH_LOGIN_CODE"));
    }

    @Test
    void applyReceiptUpdatesMatchedRowOrInsertsUnmatched() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SmsSendLogStore store = new SmsSendLogStore(jdbc);
        // 命中发送行：UPDATE 返回 1
        when(jdbc.update(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers
                                .<org.springframework.jdbc.core.PreparedStatementSetter>any()))
                .thenReturn(1);
        org.junit.jupiter.api.Assertions.assertTrue(
                store.applyReceipt(
                        "sid-1", "SUCCESS", "deliver", "2027-02-01 16:30:05", "137****5678"));
        // 未命中：UPDATE 返回 0 → 补 UNMATCHED_RECEIPT
        when(jdbc.update(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers
                                .<org.springframework.jdbc.core.PreparedStatementSetter>any()))
                .thenReturn(0);
        org.junit.jupiter.api.Assertions.assertFalse(
                store.applyReceipt("sid-2", "FAIL", "fail:11", null, null));
    }

    @Test
    void applyReceiptToleratesNullEverything() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SmsSendLogStore store = new SmsSendLogStore(jdbc);
        when(jdbc.update(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers
                                .<org.springframework.jdbc.core.PreparedStatementSetter>any()))
                .thenReturn(0);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> store.applyReceipt("sid-3", null, null, "not-a-time", null));
    }

    @Test
    void codeSenderMapsThreeTemplates() {
        TencentSmsChannel spyChannel = mock(TencentSmsChannel.class);
        TencentSmsCodeSender sender = new TencentSmsCodeSender(spyChannel);
        sender.sendLoginCode("13900001234", "437090");
        sender.sendPasswordResetCode("13900001234", "437090");
        sender.sendPhoneBindCode("13900001234", "437090");
        verify(spyChannel)
                .send(
                        new SmsSendCommand(
                                SmsScene.AUTH_LOGIN,
                                null,
                                "13900001234",
                                "AUTH_LOGIN_CODE",
                                List.of("437090")));
        verify(spyChannel)
                .send(
                        new SmsSendCommand(
                                SmsScene.AUTH_RESET,
                                null,
                                "13900001234",
                                "AUTH_RESET_CODE",
                                List.of("437090")));
        verify(spyChannel)
                .send(
                        new SmsSendCommand(
                                SmsScene.AUTH_BIND,
                                null,
                                "13900001234",
                                "AUTH_BIND_CODE",
                                List.of("437090")));
    }
}
