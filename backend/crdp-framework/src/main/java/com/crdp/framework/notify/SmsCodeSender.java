package com.crdp.framework.notify;

/**
 * E04-T04 / PLT-AUTH-002、PLT-AUTH-003：登录类短信验证码的发送口。
 *
 * <p>实现为腾讯云短信通道（{@code TencentSmsCodeSender}，E09-T03：平台统一签名与审核过的模板，回执入库见 plt_sms_send_log）。实现方拿到的
 * code 是一次性验证码，不得写日志、不得回显。
 */
public interface SmsCodeSender {

    /** 把验证码发送到目标手机号。发送失败应抛异常，让调用方不给前端「已发送」的假象。 */
    void sendLoginCode(String phone, String code);

    /**
     * 忘记密码重置验证码（E04-T07 / PLT-AUTH-011）。与登录验证码分开发送： 短信文案须提示「正在重置密码」，避免用户把重置码当登录码用（或反之）。 code
     * 同样不得写日志、不得回显。
     */
    void sendPasswordResetCode(String phone, String code);

    /**
     * 个人中心换绑手机号验证码（E06-T11 / PLT-ORG-011）。发往<b>新手机号</b>， 文案须提示「正在换绑手机号」，与登录 / 重置验证码分命名空间互不通用； code
     * 同样不得写日志、不得回显。
     */
    void sendPhoneBindCode(String phone, String code);
}
