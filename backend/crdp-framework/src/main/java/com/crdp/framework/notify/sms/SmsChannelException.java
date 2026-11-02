package com.crdp.framework.notify.sms;

/**
 * 短信通道异常（E09-T03）。错误码与说明只描述失败原因类别（模板缺失、内容含敏感信息、通道未配置、 服务商返回失败等），
 * <b>绝不携带手机号、验证码、模板参数等敏感值</b>（AGENTS.md 第 5 节：日志与异常不写患者明文 / 口令 / 验证码）。
 */
public class SmsChannelException extends RuntimeException {

    /** 失败原因类别（稳定标识，供发送记录与告警归类）。 */
    private final String reason;

    public SmsChannelException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SmsChannelException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
