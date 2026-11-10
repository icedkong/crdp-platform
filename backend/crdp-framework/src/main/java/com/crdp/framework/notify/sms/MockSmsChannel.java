package com.crdp.framework.notify.sms;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 演示用短信通道：不访问腾讯云，也不把短信发到任何手机。
 *
 * <p>校验仍走模板与 {@link SmsSensitiveGuard}（短信里不能出现患者敏感信息）。通过后把脱敏手机号写入平台发送台账，把验证码留在
 * 内存收件箱和本类日志里，供演示登录查看。只在 {@code crdp.sms.provider=mock} 时由路由选中。日志里的验证码只属于虚构账号。
 */
@Component
public class MockSmsChannel implements SmsOutbound {

    private static final Logger log = LoggerFactory.getLogger(MockSmsChannel.class);

    private static final Pattern CN_PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final SmsTemplateStore templates;
    private final SmsSendLogStore sendLog;
    private final MockSmsInbox inbox;

    public MockSmsChannel(SmsTemplateStore templates, SmsSendLogStore sendLog, MockSmsInbox inbox) {
        this.templates = templates;
        this.sendLog = sendLog;
        this.inbox = inbox;
    }

    @Override
    public String send(SmsSendCommand command) {
        if (command.phone() == null || !CN_PHONE.matcher(command.phone()).matches()) {
            throw new SmsChannelException("PHONE_INVALID", "手机号格式不合法（需 11 位大陆手机号）");
        }
        SmsTemplateView template = findTemplate(command.templateCode());
        SmsSensitiveGuard.renderChecked(command.scene(), template.content(), command.params());
        String masked = TencentSmsChannel.maskPhone(command.phone());
        String params =
                command.params() == null
                        ? ""
                        : command.params().stream().collect(Collectors.joining(","));
        String serial = "mock-" + UUID.randomUUID().toString().replace("-", "");
        inbox.add(
                new MockSmsMessage(
                        System.currentTimeMillis(),
                        command.scene().name(),
                        masked,
                        command.templateCode(),
                        params));
        log.info(
                "模拟短信（未外发，仅虚构数据演示） phone={} scene={} template={} params={}",
                masked,
                command.scene().name(),
                command.templateCode(),
                params);
        try {
            sendLog.recordSubmitted(
                    command.scene().name(),
                    command.tenantCode(),
                    command.templateCode(),
                    "mock",
                    serial,
                    masked);
        } catch (RuntimeException logFailure) {
            log.warn("模拟短信台账写入失败（收件箱与日志仍保留本条）", logFailure);
        }
        return serial;
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
        return template;
    }
}
