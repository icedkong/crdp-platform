package com.crdp.framework.notify.sms;

/**
 * 一条模拟短信（未发到运营商）。手机号只保留脱敏形式；参数原文仅供演示查看验证码，不入库。
 *
 * @param sentAtEpochMilli 记录时间
 * @param scene 场景名
 * @param phoneMasked 脱敏手机号
 * @param templateCode 平台模板编码
 * @param params 模板参数（验证码或数量），演示页用来读码
 */
public record MockSmsMessage(
        long sentAtEpochMilli,
        String scene,
        String phoneMasked,
        String templateCode,
        String params) {}
