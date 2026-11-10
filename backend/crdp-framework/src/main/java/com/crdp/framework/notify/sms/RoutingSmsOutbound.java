package com.crdp.framework.notify.sms;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 按 {@code crdp.sms.provider} 选择通道。{@code mock}（local / demo 默认）不外发；{@code tencent} 走腾讯云，未启用时仍返回
 * CHANNEL_DISABLED。
 */
@Component
@Primary
public class RoutingSmsOutbound implements SmsOutbound {

    private final TencentSmsProperties properties;
    private final TencentSmsChannel tencent;
    private final MockSmsChannel mock;

    public RoutingSmsOutbound(
            TencentSmsProperties properties, TencentSmsChannel tencent, MockSmsChannel mock) {
        this.properties = properties;
        this.tencent = tencent;
        this.mock = mock;
    }

    @Override
    public String send(SmsSendCommand command) {
        if ("mock".equalsIgnoreCase(properties.getProvider())) {
            return mock.send(command);
        }
        if (!"tencent".equalsIgnoreCase(properties.getProvider())) {
            throw new SmsChannelException(
                    "CHANNEL_NOT_CONFIGURED",
                    "未知的 crdp.sms.provider: " + properties.getProvider() + "（只允许 mock 或 tencent）");
        }
        return tencent.send(command);
    }
}
