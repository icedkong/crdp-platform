package com.crdp.framework.notify.sms;

import java.util.List;

/**
 * 平台短信发送命令（E09-T03）：经 {@code TencentSmsChannel} 校验（模板、签名、敏感信息）后调用腾讯云 SendSms。
 *
 * @param scene 发送场景（参数白名单依据）
 * @param tenantCode 发送时的租户上下文（登录验证码在登录前发出，可能为 null）
 * @param phone 大陆手机号（11 位）
 * @param templateCode 平台模板编码（plt_sms_template.code，模板须为 APPROVED 且已回填腾讯云模板 ID）
 * @param params 模板参数（按 {1}..{n} 顺序）
 */
public record SmsSendCommand(
        SmsScene scene,
        String tenantCode,
        String phone,
        String templateCode,
        List<String> params) {}
