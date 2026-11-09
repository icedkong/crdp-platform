package com.crdp.framework.notify.sms;

import java.util.regex.Pattern;

/**
 * 平台短信场景（E09-T03 / PLT-CFG-006 变量占位符口径）。
 *
 * <p>每个场景声明一组<b>参数白名单正则</b>：发送前逐个参数校验（ {@link SmsSensitiveGuard}）， 结构上保证短信只含「验证码 / 数量 /
 * 日期」一类非患者值——A-NTF-003 与 A-NFR-007 要求短信按人合并为汇总、 不含姓名、病案号、电话、诊断，白名单是第一道闸，渲染结果的模式反扫是第二道。
 */
public enum SmsScene {

    /** 登录验证码（PLT-AUTH-002）：参数 = 4–8 位数字验证码。 */
    AUTH_LOGIN(Pattern.compile("^\\d{4,8}$")),

    /** 忘记密码重置验证码（PLT-AUTH-011）：与登录码分命名空间，模板文案提示「正在重置密码」。 */
    AUTH_RESET(Pattern.compile("^\\d{4,8}$")),

    /** 换绑手机号验证码（PLT-ORG-011）：文案提示「正在换绑手机号」。 */
    AUTH_BIND(Pattern.compile("^\\d{4,8}$")),

    /**
     * 代重置密码的临时密码下发（E20-T06 / PLT-VIEW-017）：参数 = 系统生成的临时密码（16 位以上、四类字符，
     * 字符集与医院用户临时密码生成器一致）——不是自由文本，是平台生成的随机凭据，与患者敏感值的白名单口径分开。
     */
    AUTH_TEMP_PASSWORD(Pattern.compile("^[A-Za-z0-9!@#$%^&*]{16,64}$")),

    /** 业务通知（A-NTF-003 汇总等）：参数 = 1–6 位数字（任务数量 / 天数）或 yyyy-MM-dd 日期。 任何自由文本（患者姓名、诊断、病案号……）都过不了白名单。 */
    NOTIFY(Pattern.compile("^\\d{1,6}$|^\\d{4}-\\d{2}-\\d{2}$"));

    private final Pattern paramPattern;

    SmsScene(Pattern paramPattern) {
        this.paramPattern = paramPattern;
    }

    /** 该场景允许的参数取值模式（单一模式，多分支用 | 合并）。 */
    public Pattern paramPattern() {
        return paramPattern;
    }
}
