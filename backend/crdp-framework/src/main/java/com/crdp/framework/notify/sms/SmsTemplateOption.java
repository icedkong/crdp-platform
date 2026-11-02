package com.crdp.framework.notify.sms;

/**
 * 通知设置页可选短信模板（E09-T04 / A-NTF-001「短信模板只能在平台审核过的模板中选择」）。
 *
 * @param code 模板编码（ntf_rule.sms_template_code 引用）
 * @param name 模板名称（选择列表展示）
 * @param content 模板内容（{1}..{n} 占位，不含患者信息；预览用）
 */
public record SmsTemplateOption(String code, String name, String content) {}
