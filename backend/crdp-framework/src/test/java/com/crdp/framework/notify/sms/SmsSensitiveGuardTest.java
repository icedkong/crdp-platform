package com.crdp.framework.notify.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** E09-T03 验收一：短信不含患者敏感信息（A-NTF-003、A-NFR-007）。两道闸——场景参数白名单 + 渲染反扫（手机号 / 证件号 / 长数字串）。 */
class SmsSensitiveGuardTest {

    @Test
    void loginCodePassesWhitelist() {
        String rendered =
                SmsSensitiveGuard.renderChecked(
                        SmsScene.AUTH_LOGIN, "登录验证码{1}，请勿泄露给他人。", List.of("437090"));
        assertEquals("登录验证码437090，请勿泄露给他人。", rendered);
    }

    @Test
    void notifyParamsAllowCountAndDateOnly() {
        assertEquals(
                "您有12项随访任务待处理。",
                SmsSensitiveGuard.renderChecked(SmsScene.NOTIFY, "您有{1}项随访任务待处理。", List.of("12")));
        assertEquals(
                "截至2026-11-02有3项待办。",
                SmsSensitiveGuard.renderChecked(
                        SmsScene.NOTIFY, "截至{1}有{2}项待办。", List.of("2026-11-02", "3")));
    }

    @Test
    void patientNameInParamIsRejectedByWhitelist() {
        // 任何自由文本（姓名、诊断、病案号带字母前缀）都过不了 NOTIFY 白名单
        SmsChannelException name =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.NOTIFY, "患者{1}有随访任务。", List.of("张三（示例）")));
        assertEquals("SENSITIVE_CONTENT", name.reason());
        assertTrue(!name.getMessage().contains("张三"));
    }

    @Test
    void patientPhoneInParamIsRejected() {
        SmsChannelException phone =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.NOTIFY, "请联系患者{1}。", List.of("13900001234")));
        assertEquals("SENSITIVE_CONTENT", phone.reason());
    }

    @Test
    void residentIdInParamIsRejected() {
        // 18 位证件号虽是纯数字，但 NOTIFY 白名单只放 1-6 位数字与日期
        assertThrows(
                SmsChannelException.class,
                () ->
                        SmsSensitiveGuard.renderChecked(
                                SmsScene.NOTIFY, "证号{1}。", List.of("11010119900307867X")));
    }

    @Test
    void longDigitMedicalRecordNumberIsRejected() {
        // 8 位病案号数字（AUTH 场景只收 4-8 位验证码，NOTIFY 只收 1-6 位——这里用 NOTIFY 的 7 位溢出）
        assertThrows(
                SmsChannelException.class,
                () ->
                        SmsSensitiveGuard.renderChecked(
                                SmsScene.NOTIFY, "病案{1}。", List.of("1234567")));
    }

    @Test
    void renderedContentIsRescannedForSensitivePatterns() {
        // 第二道闸兜模板文案自身：模板里带手机号，参数合法也拒绝
        SmsChannelException template =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.AUTH_LOGIN,
                                        "验证码{1}，咨询13800001234。",
                                        List.of("437090")));
        assertEquals("SENSITIVE_CONTENT", template.reason());

        // 模板里带 18 位证件号同样拒绝
        SmsChannelException idInTemplate =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.AUTH_LOGIN,
                                        "证号11010119900307867X验证码{1}。",
                                        List.of("437090")));
        assertEquals("SENSITIVE_CONTENT", idInTemplate.reason());
    }

    @Test
    void concatenatedParamsFormingLongDigitsAreRejected() {
        // 第二道闸兜「拼接成型」：两个参数各自过白名单（6 位 / 4 位数字），渲染后连成 10 位连续数字
        SmsChannelException longDigits =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.NOTIFY, "编号{1}{2}。", List.of("123456", "7890")));
        assertEquals("SENSITIVE_CONTENT", longDigits.reason());
    }

    @Test
    void paramCountMismatchIsRejected() {
        SmsChannelException mismatch =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.AUTH_LOGIN,
                                        "验证码{1}，{2}分钟内有效。",
                                        List.of("437090")));
        assertEquals("PARAM_COUNT_MISMATCH", mismatch.reason());
    }

    @Test
    void unknownSceneIsRejected() {
        SmsChannelException unknown =
                assertThrows(
                        SmsChannelException.class,
                        () -> SmsSensitiveGuard.renderChecked(null, "任意{1}", List.of("437090")));
        assertEquals("SENSITIVE_CONTENT", unknown.reason());
    }

    @Test
    void blankTemplateIsRejected() {
        SmsChannelException blank =
                assertThrows(
                        SmsChannelException.class,
                        () ->
                                SmsSensitiveGuard.renderChecked(
                                        SmsScene.AUTH_LOGIN, "  ", List.of("437090")));
        assertEquals("TEMPLATE_INVALID", blank.reason());
    }
}
