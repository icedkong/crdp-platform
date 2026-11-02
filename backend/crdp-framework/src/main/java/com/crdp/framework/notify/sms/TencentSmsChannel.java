package com.crdp.framework.notify.sms;

import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 平台短信发送编排（E09-T03 / A-NTF-003：平台统一账号、签名与审核过的模板）。
 *
 * <p>顺序：通道开关与密钥自检 → 手机号校验 → 模板校验（存在 / APPROVED / 已回填腾讯云模板 ID）→ 签名（模板级，空则通道默认）→ {@link
 * SmsSensitiveGuard} 敏感信息两道闸 → 腾讯云 SendSms → 发送台账。
 *
 * <p>每一次发送尝试（无论成败）都写台账：受理成功记 SUBMITTED + 流水号；任一步失败记 SUBMIT_FAILED + 失败类别 ——只记类别与脱敏手机号，
 * 不记模板参数与明文。调用方拿不到「已发送」的假象（{@link SmsChannelException}）。
 */
@Component
public class TencentSmsChannel {

    private static final Logger log = LoggerFactory.getLogger(TencentSmsChannel.class);

    /** 大陆手机号（登录、双因素、随访通知的接收人都在国内）。 */
    private static final Pattern CN_PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final TencentSmsProperties properties;
    private final SmsTemplateStore templates;
    private final SmsSendLogStore sendLog;
    private final TencentSmsClient client;

    public TencentSmsChannel(
            TencentSmsProperties properties,
            SmsTemplateStore templates,
            SmsSendLogStore sendLog,
            TencentSmsClient client) {
        this.properties = properties;
        this.templates = templates;
        this.sendLog = sendLog;
        this.client = client;
    }

    /**
     * 发送一条平台短信。
     *
     * @return 腾讯云流水号（SerialNo），回执按它回填台账
     * @throws SmsChannelException 任一前置校验或服务商调用失败
     */
    public String send(SmsSendCommand command) {
        Accepted accepted;
        try {
            accepted = doSend(command);
        } catch (SmsChannelException failure) {
            recordFailure(command, failure);
            throw failure;
        }
        try {
            sendLog.recordSubmitted(
                    command.scene().name(),
                    command.tenantCode(),
                    command.templateCode(),
                    accepted.providerTemplateId(),
                    accepted.serialNo(),
                    maskPhone(command.phone()));
        } catch (RuntimeException logFailure) {
            // 短信已被服务商受理，台账写坏不能把「已发送」报成失败（验证码已到用户手机）；
            // 对账兜底：回执到达时按流水号入库为 UNMATCHED_RECEIPT
            log.warn(
                    "短信发送台账写入异常（短信已受理，回执将按流水号补对账）serialNoLength={}",
                    accepted.serialNo() == null ? 0 : accepted.serialNo().length(),
                    logFailure);
        }
        return accepted.serialNo();
    }

    /** 受理结果：流水号 + 实际使用的腾讯云模板 ID（台账用）。 */
    private record Accepted(String serialNo, String providerTemplateId) {}

    private Accepted doSend(SmsSendCommand command) {
        requireChannelReady();
        if (command.phone() == null || !CN_PHONE.matcher(command.phone()).matches()) {
            throw new SmsChannelException("PHONE_INVALID", "手机号格式不合法（需 11 位大陆手机号）");
        }
        SmsTemplateView template = findTemplate(command.templateCode());
        String signName = resolveSign(template);
        // 两道闸：参数白名单 + 渲染反扫（不过即抛 SENSITIVE_CONTENT，不会到服务商）
        SmsSensitiveGuard.renderChecked(command.scene(), template.content(), command.params());
        String serialNo =
                client.send(
                        "+86" + command.phone(),
                        signName,
                        template.providerTemplateId(),
                        command.params());
        return new Accepted(serialNo, template.providerTemplateId());
    }

    private void recordFailure(SmsSendCommand command, SmsChannelException failure) {
        try {
            sendLog.recordSubmitFailed(
                    command.scene().name(),
                    command.tenantCode(),
                    command.templateCode(),
                    command.phone() == null ? null : maskPhone(command.phone()),
                    failure.reason(),
                    failure.getMessage());
        } catch (RuntimeException logFailure) {
            // 台账写不进去不能吞掉原始失败
            log.warn("短信发送失败台账写入异常 reason={}", failure.reason(), logFailure);
        }
    }

    private void requireChannelReady() {
        if (!properties.isEnabled()) {
            throw new SmsChannelException("CHANNEL_DISABLED", "腾讯云短信通道未启用（crdp.sms.enabled=false）");
        }
        if (isBlank(properties.getSecretId())
                || isBlank(properties.getSecretKey())
                || isBlank(properties.getSdkAppId())) {
            throw new SmsChannelException("CHANNEL_NOT_CONFIGURED", "腾讯云短信通道未配置密钥或 SdkAppId");
        }
    }

    private SmsTemplateView findTemplate(String templateCode) {
        Optional<SmsTemplateView> found = templates.findByCode(templateCode);
        if (found.isEmpty()) {
            throw new SmsChannelException("TEMPLATE_NOT_FOUND", "短信模板不存在：" + templateCode);
        }
        SmsTemplateView template = found.get();
        if (!"APPROVED".equals(template.status())) {
            throw new SmsChannelException(
                    "TEMPLATE_NOT_APPROVED", "短信模板未审核通过，不能发送：" + templateCode);
        }
        if (isBlank(template.providerTemplateId())) {
            throw new SmsChannelException(
                    "TEMPLATE_NOT_REGISTERED", "短信模板未回填腾讯云模板 ID：" + templateCode);
        }
        return template;
    }

    private String resolveSign(SmsTemplateView template) {
        String sign = isBlank(template.signName()) ? properties.getSignName() : template.signName();
        if (isBlank(sign)) {
            throw new SmsChannelException("SIGN_MISSING", "短信签名为空（模板与通道均未配置）");
        }
        return sign;
    }

    /** 台账 / 日志里的手机号：138****0000。 */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
