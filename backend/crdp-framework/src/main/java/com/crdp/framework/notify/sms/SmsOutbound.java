package com.crdp.framework.notify.sms;

/** 平台短信发送口。{@code crdp.sms.provider=tencent} 走腾讯云；{@code mock} 只记在本机，不访问外网。 业务代码依赖本接口，切换供应商只改配置。 */
public interface SmsOutbound {

    /**
     * 发送一条平台短信。
     *
     * @return 流水号（腾讯云 SerialNo，或模拟通道的 mock- 前缀）
     * @throws SmsChannelException 校验失败或服务商拒绝。模拟通道在校验通过后不会因“未外发”而失败
     */
    String send(SmsSendCommand command);
}
