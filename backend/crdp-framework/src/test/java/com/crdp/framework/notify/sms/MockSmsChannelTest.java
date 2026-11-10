package com.crdp.framework.notify.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockSmsChannelTest {

    private final SmsTemplateStore templates = mock(SmsTemplateStore.class);
    private final SmsSendLogStore sendLog = mock(SmsSendLogStore.class);
    private final MockSmsInbox inbox = new MockSmsInbox();
    private MockSmsChannel channel;

    @BeforeEach
    void setUp() {
        channel = new MockSmsChannel(templates, sendLog, inbox);
        when(templates.findByCode("AUTH_LOGIN_CODE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "AUTH_LOGIN_CODE",
                                        "AUTH_LOGIN",
                                        "登录验证码{1}，请勿泄露给他人。如非本人操作请忽略。",
                                        null,
                                        null,
                                        "APPROVED")));
    }

    @Test
    void storesMaskedPhoneAndCodeWithoutCallingAProvider() {
        String serial =
                channel.send(
                        new SmsSendCommand(
                                SmsScene.AUTH_LOGIN,
                                null,
                                "13900000002",
                                "AUTH_LOGIN_CODE",
                                List.of("437090")));

        assertTrue(serial.startsWith("mock-"));
        MockSmsMessage message = inbox.recent().get(0);
        assertEquals("139****0002", message.phoneMasked());
        assertEquals("437090", message.params());
        assertEquals("AUTH_LOGIN", message.scene());
        verify(sendLog)
                .recordSubmitted(
                        "AUTH_LOGIN", null, "AUTH_LOGIN_CODE", "mock", serial, "139****0002");
    }

    @Test
    void rejectsAPhoneThatIsNotAMainlandMobile() {
        SmsChannelException failure =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                channel.send(
                                        new SmsSendCommand(
                                                SmsScene.AUTH_LOGIN,
                                                null,
                                                "12345",
                                                "AUTH_LOGIN_CODE",
                                                List.of("437090"))));
        assertEquals("PHONE_INVALID", failure.reason());
        assertTrue(inbox.recent().isEmpty());
    }
}
