package com.crdp.framework.notify.sms;

/**
 * 平台短信模板视图（plt_sms_template 行，PLT-CFG-006）。
 *
 * @param code 模板编码（平台侧稳定标识）
 * @param scene 发送场景
 * @param content 模板内容（{1}..{n} 占位，不含患者信息）
 * @param providerTemplateId 腾讯云模板 ID（E01-T13 报备审核通过后回填）
 * @param signName 模板级签名（空则用通道默认签名）
 * @param status 审核状态（APPROVED 才可发送）
 */
public record SmsTemplateView(
        String code,
        String scene,
        String content,
        String providerTemplateId,
        String signName,
        String status) {}
