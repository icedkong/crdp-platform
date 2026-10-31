package com.crdp.framework.notify;

import org.springframework.stereotype.Component;

/**
 * 占位实现（与 {@link UnsupportedNotifySender} 同思路）：真实腾讯云短信通道在 E09-T03 接入。
 * 在此之前短信登录与平台强制双因素依赖的发送动作会失败，local/dev 通过配置关闭平台强制双因素保证演示可用。
 */
@Component
public class UnsupportedSmsCodeSender implements SmsCodeSender {

    @Override
    public void sendLoginCode(String phone, String code) {
        // 不把 code 放进异常信息（AGENTS.md 第 5 节）
        throw new UnsupportedOperationException("短信验证码通道将在 E09-T03 接入");
    }

    @Override
    public void sendPasswordResetCode(String phone, String code) {
        // 同上：真实通道接入前发送动作失败，前端不会拿到「已发送」的假象
        throw new UnsupportedOperationException("短信验证码通道将在 E09-T03 接入");
    }
}
