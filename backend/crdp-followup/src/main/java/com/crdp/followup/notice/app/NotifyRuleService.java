package com.crdp.followup.notice.app;

import com.crdp.followup.notice.domain.NotifyChannel;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.domain.NotifyRuleCodec;
import com.crdp.followup.notice.domain.RecipientOption;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.notify.sms.SmsScene;
import com.crdp.framework.notify.sms.SmsTemplateOption;
import com.crdp.framework.notify.sms.SmsTemplateStore;
import com.crdp.framework.notify.sms.SmsTemplateView;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知事件规则维护（E09-T04 / A-NTF-001、C-NTF-001；docs/api/notify.md §2，通知设置页 /system/notice）。
 *
 * <p>GET 返回目录全集（缺行回落 {@link NotifyEventType} 默认值——与迁移 V22 种子同值）；PUT 全量覆盖单事件配置并写 NOTICE_RULE_UPDATE
 * 配置审计（PLT-AUD-001 口径：配置变更必审计）。 短信模板只能在平台审核过的 NOTIFY 场景模板中选择（短信模板主体与签名都是平台的，租户只选不写，PLT-CFG-006）。
 */
@Service
public class NotifyRuleService {

    /** send_time 的 HH:mm 口径（细则见 notify.md §2；即时事件允许 null）。 */
    private static final Pattern SEND_TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private static final int ADVANCE_DAYS_MAX = 365;

    private final NotifyRuleMapper mapper;
    private final SmsTemplateStore templates;
    private final AuditLogger audit;

    public NotifyRuleService(
            NotifyRuleMapper mapper, SmsTemplateStore templates, AuditLogger audit) {
        this.mapper = mapper;
        this.templates = templates;
        this.audit = audit;
    }

    /** 规则视图（notify.md §2）：目录全集逐事件，advanceDays / sendTime / smsTemplateCode 可空（ 无值字段不出现）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleView(
            String eventType,
            String label,
            boolean enabled,
            Integer advanceDays,
            String sendTime,
            List<NotifyChannel> channels,
            List<RecipientOption> recipients,
            String smsTemplateCode) {}

    /** PUT 请求体：全量覆盖该事件配置。 */
    public record RuleUpdate(
            Boolean enabled,
            Integer advanceDays,
            String sendTime,
            List<String> channels,
            List<String> recipients,
            String smsTemplateCode) {}

    /** 可选短信模板（只读：平台审核过的 NOTIFY 场景模板）。 */
    public record SmsTemplateItem(String code, String name, String content) {}

    /** 全部事件规则（目录序）。 */
    public List<RuleView> list() {
        var configured = new java.util.HashMap<String, NotifyRuleRow>();
        for (NotifyRuleRow row : mapper.findAll()) {
            configured.put(row.eventType(), row);
        }
        List<RuleView> items = new ArrayList<>();
        for (NotifyEventType type : NotifyEventType.values()) {
            items.add(toView(type, configured.get(type.name())));
        }
        return items;
    }

    /** 修改单事件规则：校验 → 落库 → 审计；返回合并后的最新值。 */
    @Transactional
    public RuleView update(String eventType, RuleUpdate request) {
        NotifyEventType type =
                NotifyEventType.fromCode(eventType).orElseThrow(() -> invalid("eventType（未知通知事件）"));
        if (request == null) {
            throw invalid("请求体");
        }
        if (request.enabled() == null) {
            throw invalid("enabled");
        }
        Integer advanceDays = validateAdvanceDays(request.advanceDays());
        String sendTime = validateSendTime(request.sendTime());
        List<NotifyChannel> channels = validateChannels(request.channels());
        List<RecipientOption> recipients = validateRecipients(request.recipients());
        String smsTemplateCode = validateSmsTemplate(request.smsTemplateCode(), channels, type);

        NotifyRuleRow existing = mapper.find(type.name()).orElse(null);
        RuleView before = toView(type, existing);
        long operatorId = CurrentSession.requireHospital().userId();
        NotifyRuleRow row =
                new NotifyRuleRow(
                        existing == null ? null : existing.id(),
                        type.name(),
                        request.enabled(),
                        advanceDays,
                        sendTime == null ? null : LocalTime.parse(sendTime),
                        NotifyRuleCodec.join(channels),
                        NotifyRuleCodec.join(recipients),
                        smsTemplateCode,
                        operatorId,
                        null);
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.update(row);
        }
        RuleView after = toView(type, mapper.find(type.name()).orElse(null));
        audit.append(
                AuditEntry.operation("notify", "NOTICE_RULE_UPDATE")
                        .objectType("NTF_RULE")
                        .objectId(type.name())
                        .description("修改通知事件规则：" + type.label())
                        .beforeJson(compact(before))
                        .afterJson(compact(after))
                        .build());
        return after;
    }

    /** 可选短信模板清单（平台审核过的 NOTIFY 场景模板，只读）。 */
    public List<SmsTemplateItem> smsTemplates() {
        List<SmsTemplateItem> items = new ArrayList<>();
        for (SmsTemplateOption option : templates.listApprovedByScene(SmsScene.NOTIFY.name())) {
            items.add(new SmsTemplateItem(option.code(), option.name(), option.content()));
        }
        return items;
    }

    // ---------------------------------------------------------------- 内部

    private RuleView toView(NotifyEventType type, NotifyRuleRow row) {
        if (row == null) {
            return new RuleView(
                    type.name(),
                    type.label(),
                    true,
                    type.defaultAdvanceDays(),
                    type.defaultSendTime(),
                    type.defaultChannels(),
                    type.defaultRecipients(),
                    type.defaultSmsTemplateCode());
        }
        return new RuleView(
                type.name(),
                type.label(),
                row.enabled(),
                row.advanceDays(),
                row.sendTime() == null ? null : row.sendTime().toString(),
                NotifyRuleCodec.parseChannels(row.channels()),
                NotifyRuleCodec.parseRecipients(row.recipients()),
                row.smsTemplateCode());
    }

    private Integer validateAdvanceDays(Integer advanceDays) {
        if (advanceDays == null) {
            return null;
        }
        if (advanceDays < 0 || advanceDays > ADVANCE_DAYS_MAX) {
            throw invalid("advanceDays（0–" + ADVANCE_DAYS_MAX + "）");
        }
        return advanceDays;
    }

    private String validateSendTime(String sendTime) {
        if (sendTime == null || sendTime.isBlank()) {
            return null;
        }
        String trimmed = sendTime.trim();
        if (!SEND_TIME.matcher(trimmed).matches()) {
            throw invalid("sendTime（HH:mm）");
        }
        try {
            LocalTime.parse(trimmed);
        } catch (DateTimeParseException broken) {
            throw invalid("sendTime（HH:mm）");
        }
        return trimmed;
    }

    private List<NotifyChannel> validateChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            throw invalid("channels（至少一个渠道）");
        }
        LinkedHashSet<NotifyChannel> parsed = new LinkedHashSet<>();
        for (String channel : channels) {
            try {
                parsed.add(NotifyChannel.valueOf(channel == null ? "" : channel.trim()));
            } catch (IllegalArgumentException unknown) {
                throw invalid("channels（未知渠道 " + channel + "）");
            }
        }
        return List.copyOf(parsed);
    }

    private List<RecipientOption> validateRecipients(List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            throw invalid("recipients（至少一条接收人规则）");
        }
        LinkedHashSet<RecipientOption> parsed = new LinkedHashSet<>();
        for (String recipient : recipients) {
            try {
                parsed.add(RecipientOption.valueOf(recipient == null ? "" : recipient.trim()));
            } catch (IllegalArgumentException unknown) {
                throw invalid("recipients（未知接收人规则 " + recipient + "）");
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * 短信模板校验：渠道含 SMS 时必填且必须是平台审核过的 NOTIFY 场景模板；不含 SMS 时必须为空。 模板未回填腾讯云模板 ID 不在配置层拦（发送层有准确失败原因），审核状态为
     * APPROVED 才可配置。
     */
    private String validateSmsTemplate(
            String smsTemplateCode, List<NotifyChannel> channels, NotifyEventType type) {
        boolean sms = channels.contains(NotifyChannel.SMS);
        if (!sms) {
            if (smsTemplateCode != null && !smsTemplateCode.isBlank()) {
                throw invalid("smsTemplateCode（渠道不含 SMS 时不能设置短信模板）");
            }
            return null;
        }
        if (smsTemplateCode == null || smsTemplateCode.isBlank()) {
            throw invalid("smsTemplateCode（渠道含 SMS 时必填）");
        }
        SmsTemplateView template =
                templates
                        .findByCode(smsTemplateCode.trim())
                        .orElseThrow(() -> invalid("smsTemplateCode（模板不存在）"));
        if (!SmsScene.NOTIFY.name().equals(template.scene())) {
            throw invalid("smsTemplateCode（只能选择通知场景模板）");
        }
        if (!"APPROVED".equals(template.status())) {
            throw invalid("smsTemplateCode（模板未通过平台审核）");
        }
        return smsTemplateCode.trim();
    }

    private static String compact(RuleView view) {
        return "{\"enabled\":"
                + view.enabled()
                + ",\"advanceDays\":"
                + view.advanceDays()
                + ",\"sendTime\":\""
                + view.sendTime()
                + "\",\"channels\":\""
                + NotifyRuleCodec.join(view.channels())
                + "\",\"recipients\":\""
                + NotifyRuleCodec.join(view.recipients())
                + "\",\"smsTemplateCode\":\""
                + view.smsTemplateCode()
                + "\"}";
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
