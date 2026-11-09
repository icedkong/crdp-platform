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

    /**
     * 代重置密码的临时密码下发（E20-T06 / PLT-VIEW-017，已确认）：平台账号在租户视角中代医院用户重置密码时，
     * 新密码<b>只</b>经本口发给用户本人手机——调用方（视角代理口）绝不可把 password 放进任何 HTTP 响应、日志或审计。 发送失败抛异常，调用方在落库前中止（重置不生效）。
     */
    void sendTemporaryPassword(String phone, String password);
}
