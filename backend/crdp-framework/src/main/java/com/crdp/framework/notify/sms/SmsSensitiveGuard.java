package com.crdp.framework.notify.sms;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短信敏感信息防护（E09-T03 / A-NTF-003、A-NFR-007：短信不含患者敏感信息）。
 *
 * <p>两道闸，任一不过即拒绝发送（{@link SmsChannelException}，reason=SENSITIVE_CONTENT）：
 *
 * <ol>
 *   <li><b>参数白名单</b>：按 {@link SmsScene} 声明的模式逐个校验模板参数——验证码只允许 4–8 位数字， 通知只允许数量 /
 *       日期；姓名、诊断、地址等自由文本在结构上就发不出去；
 *   <li><b>渲染反扫</b>：占位符替换成参数后的完整内容再按「手机号 / 居民身份证号 / ≥10 位连续数字」反扫，
 *       兜住模板文案自身带出敏感值、以及参数拼接出敏感形态的情况（病案号等长数字串）。
 * </ol>
 *
 * <p>异常与日志只写违规类别与位置（第几个参数 / 渲染结果），<b>不写参数值</b>。
 */
public final class SmsSensitiveGuard {

    /** 大陆手机号：11 位（1 开头第二位 3-9），容许前后紧邻加号或 86 前缀。 */
    private static final Pattern CN_MOBILE = Pattern.compile("(?:\\+?86)?1[3-9]\\d{9}");

    /** 居民身份证号：15 位数字，或 17 位数字 + 校验位（数字 / X / x）。 */
    private static final Pattern RESIDENT_ID = Pattern.compile("\\d{15}(?:\\d{2}[0-9Xx])?");

    /** 任意 ≥10 位连续数字（病案号 / 病历号 / 证件号等长数字串的兜底）。 */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{10,}");

    /** 模板占位符：{1}、{2}……（与腾讯云短信模板变量一致）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");

    private SmsSensitiveGuard() {}

    /**
     * 校验并渲染短信内容。
     *
     * @param scene 发送场景（决定参数白名单）
     * @param templateContent 平台模板内容（{1}..{n} 占位）
     * @param params 按占位符序号传入的参数
     * @return 渲染后的完整内容（仅用于本方法内部反扫与测试断言，调用方不得落库 / 写日志）
     * @throws SmsChannelException 违规（reason=SENSITIVE_CONTENT）或占位符与参数个数不匹配
     */
    public static String renderChecked(
            SmsScene scene, String templateContent, List<String> params) {
        if (scene == null) {
            throw sensitive("未知短信场景（不在白名单内）");
        }
        if (templateContent == null || templateContent.isBlank()) {
            throw new SmsChannelException("TEMPLATE_INVALID", "短信模板内容为空");
        }
        List<String> safeParams = params == null ? List.of() : params;
        int placeholders = countPlaceholders(templateContent);
        if (placeholders != safeParams.size()) {
            throw new SmsChannelException(
                    "PARAM_COUNT_MISMATCH",
                    "短信参数个数与模板占位符不一致：占位 " + placeholders + " 个，传入 " + safeParams.size() + " 个");
        }
        for (int i = 0; i < safeParams.size(); i++) {
            String value = safeParams.get(i);
            if (value == null || !scene.paramPattern().matcher(value).matches()) {
                // 只写序号与类别，不写值
                throw sensitive("第 " + (i + 1) + " 个短信参数不符合场景 " + scene + " 的白名单");
            }
        }
        String rendered = render(templateContent, safeParams);
        scanRendered(rendered);
        return rendered;
    }

    private static void scanRendered(String rendered) {
        Matcher mobile = CN_MOBILE.matcher(rendered);
        if (mobile.find()) {
            throw sensitive("短信内容疑似包含手机号");
        }
        Matcher id = RESIDENT_ID.matcher(rendered);
        if (id.find()) {
            throw sensitive("短信内容疑似包含证件号");
        }
        Matcher digits = LONG_DIGITS.matcher(rendered);
        if (digits.find()) {
            throw sensitive("短信内容疑似包含长数字串（病案号 / 证件号一类）");
        }
    }

    private static int countPlaceholders(String content) {
        Matcher matcher = PLACEHOLDER.matcher(content);
        int max = 0;
        int count = 0;
        while (matcher.find()) {
            count++;
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        if (max > count) {
            // {2} 出现但 {1} 缺失等非连续编号：腾讯云模板按 {1}..{n} 连续编号，视为非法模板
            throw new SmsChannelException("TEMPLATE_INVALID", "短信模板占位符编号不连续");
        }
        return count;
    }

    private static String render(String content, List<String> params) {
        String rendered = content;
        for (int i = 0; i < params.size(); i++) {
            rendered = rendered.replace("{" + (i + 1) + "}", params.get(i));
        }
        return rendered;
    }

    private static SmsChannelException sensitive(String what) {
        return new SmsChannelException("SENSITIVE_CONTENT", what + "，已拒绝发送（短信不含患者敏感信息）");
    }
}
