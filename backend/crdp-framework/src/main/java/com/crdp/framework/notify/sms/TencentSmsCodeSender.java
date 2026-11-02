package com.crdp.framework.notify.sms;

import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 登录类验证码短信（E04-T04 / E04-T07 / E06-T11 的发送口，E09-T03 接入真实腾讯云通道， 替换 {@code
 * UnsupportedSmsCodeSender}）。
 *
 * <p>三类验证码分模板（PLT-AUTH-011 / PLT-ORG-011：文案须提示「正在重置密码」「正在换绑手机号」，避免混用）， 参数只有验证码本身 （4–8
 * 位数字，场景白名单内）。验证码不写日志、不回显（SmsChannelException 只带失败类别）。 登录验证码在登录前发出（无租户上下文）， 台账租户列取当前上下文、取不到记空。
 */
@Component
public class TencentSmsCodeSender implements SmsCodeSender {

    /** 登录验证码模板（plt_sms_template.code，迁移种子）。 */
    public static final String TEMPLATE_LOGIN_CODE = "AUTH_LOGIN_CODE";

    /** 忘记密码重置验证码模板。 */
    public static final String TEMPLATE_RESET_CODE = "AUTH_RESET_CODE";

    /** 换绑手机号验证码模板。 */
    public static final String TEMPLATE_BIND_CODE = "AUTH_BIND_CODE";

    private final TencentSmsChannel channel;

    public TencentSmsCodeSender(TencentSmsChannel channel) {
        this.channel = channel;
    }

    @Override
    public void sendLoginCode(String phone, String code) {
        channel.send(
                new SmsSendCommand(
                        SmsScene.AUTH_LOGIN,
                        currentTenantCode(),
                        phone,
                        TEMPLATE_LOGIN_CODE,
                        List.of(code)));
    }

    @Override
    public void sendPasswordResetCode(String phone, String code) {
        channel.send(
                new SmsSendCommand(
                        SmsScene.AUTH_RESET,
                        currentTenantCode(),
                        phone,
                        TEMPLATE_RESET_CODE,
                        List.of(code)));
    }

    @Override
    public void sendPhoneBindCode(String phone, String code) {
        channel.send(
                new SmsSendCommand(
                        SmsScene.AUTH_BIND,
                        currentTenantCode(),
                        phone,
                        TEMPLATE_BIND_CODE,
                        List.of(code)));
    }

    private static String currentTenantCode() {
        return TenantContext.current()
                .filter(state -> !state.platform())
                .map(TenantContext.State::tenantCode)
                .orElse(null);
    }
}
