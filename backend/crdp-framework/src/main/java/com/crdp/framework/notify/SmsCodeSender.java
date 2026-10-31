package com.crdp.framework.notify;

/**
 * E04-T04 / PLT-AUTH-002、PLT-AUTH-003：登录类短信验证码的发送口。
 *
 * <p>真实通道是腾讯云短信（签名与模板主体为平台，见 PLT-AUTH-002 与 PLT-CFG-005），在 E09-T03 接入（模板、签名、发送与回执）。实现方拿到的 code
 * 是一次性验证码，不得写日志、不得回显。
 */
public interface SmsCodeSender {

    /** 把验证码发送到目标手机号。发送失败应抛异常，让调用方不给前端「已发送」的假象。 */
    void sendLoginCode(String phone, String code);
}
