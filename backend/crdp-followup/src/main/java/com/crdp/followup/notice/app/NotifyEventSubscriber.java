package com.crdp.followup.notice.app;

import com.crdp.followup.notice.domain.NotifyChannel;
import com.crdp.followup.notice.domain.NotifyEventPayload;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.domain.NotifyRuleCodec;
import com.crdp.followup.notice.domain.NotifyTexts;
import com.crdp.followup.notice.domain.RecipientOption;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogRow;
import com.crdp.framework.outbox.OutboxEvent;
import com.crdp.framework.outbox.OutboxSubscriber;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通知规则引擎（E09-T04 / A-NTF-001、A-NTF-004、C-NTF-001、C-NTF-004）：sys_outbox 订阅方， 验收「任务开启、逾期等事件按规则发给责任人」。
 *
 * <p>事件由随访任务域（E14+ 每日调度与状态流转）、沟通域（约定回访到时）与汇总作业（E09-T05）在业务事务内发布， 负载契约 {@link
 * NotifyEventPayload}；本引擎在事件所属租户上下文内消费（分发器保证），逐条：
 *
 * <ol>
 *   <li>查 ntf_rule（缺行用目录默认值）：事件停用 → 跳过（不投递不记台账）；
 *   <li>{@link NotifyRecipientResolver} 按接收人规则解析（负责人复核 / 病区成员兜底 / 抄送病区负责人）；
 *   <li>逐接收人 × 渠道分发：INBOX 立即投 {@link NoticeInboxService#deliver}（dedup_key 幂等）； SMS 只落 ntf_send_log
 *       QUEUED——按人并入每日汇总、免打扰与频控由 E09-T05 汇总作业统一执行（A 规格： 同一天同一接收人的多条短信合并为每日汇总）。
 * </ol>
 *
 * <p>幂等（outbox 至少一次）：ntf_message.dedup_key 与 ntf_send_log.dedup_key 都是「outbox 事件 ID + 接收人 + 渠道」
 * 唯一键，重放不重发不重记。负载解析失败抛异常交给分发器退避重试（发布方缺陷最终转死信，不静默丢）。
 */
@Component
public class NotifyEventSubscriber implements OutboxSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NotifyEventSubscriber.class);

    /** 台账状态：短信待汇总发送（E09-T05 消费）。 */
    static final String STATUS_QUEUED = "QUEUED";

    /** 台账状态：已投递（站内信落 ntf_message）。 */
    static final String STATUS_SENT = "SENT";

    private final NotifyRuleMapper rules;
    private final NotifySendLogMapper sendLog;
    private final NotifyRecipientResolver recipients;
    private final NoticeInboxService inbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public NotifyEventSubscriber(
            NotifyRuleMapper rules,
            NotifySendLogMapper sendLog,
            NotifyRecipientResolver recipients,
            NoticeInboxService inbox) {
        this.rules = rules;
        this.sendLog = sendLog;
        this.recipients = recipients;
        this.inbox = inbox;
    }

    @Override
    public boolean supports(String eventType) {
        return NotifyEventType.fromCode(eventType).isPresent();
    }

    @Override
    public void onEvent(OutboxEvent event) {
        NotifyEventType type =
                NotifyEventType.fromCode(event.eventType())
                        .orElseThrow(() -> new IllegalStateException("未登记的通知事件类型"));
        NotifyEventPayload payload = parse(event.payload());
        EffectiveRule rule = loadRule(type);
        if (!rule.enabled()) {
            log.debug("通知事件已停用，跳过 type={} tenantEventId={}", type, event.id());
            return;
        }
        NotifyRecipientResolver.Resolution resolution =
                recipients.resolve(type, rule.recipients(), payload);
        if (resolution.recipientIds().isEmpty()) {
            log.info(
                    "通知事件无接收人，跳过 type={} tenantEventId={} note={}",
                    type,
                    event.id(),
                    resolution.note());
            return;
        }
        String title = NotifyTexts.title(type, payload);
        String summary = NotifyTexts.summary(type, payload);
        for (long recipientId : resolution.recipientIds()) {
            for (NotifyChannel channel : rule.channels()) {
                dispatch(type, event, payload, recipientId, channel, title, summary);
            }
        }
        log.info(
                "通知事件已分发 type={} tenantEventId={} recipients={} channels={} note={}",
                type,
                event.id(),
                resolution.recipientIds().size(),
                rule.channels(),
                resolution.note());
    }

    // ---------------------------------------------------------------- 内部

    private void dispatch(
            NotifyEventType type,
            OutboxEvent event,
            NotifyEventPayload payload,
            long recipientId,
            NotifyChannel channel,
            String title,
            String summary) {
        String dedupKey = event.id() + ":" + recipientId + ":" + channel.name();
        if (sendLog.countByDedupKey(dedupKey) > 0) {
            return; // 重放（至少一次语义），首投台账已在
        }
        if (channel == NotifyChannel.INBOX) {
            boolean inserted =
                    inbox.deliver(
                            new NoticeInboxService.Delivery(
                                    recipientId,
                                    type.name(),
                                    title,
                                    summary,
                                    payload.patientId(),
                                    payload.patientName(),
                                    payload.taskId() == null ? null : "TASK",
                                    payload.taskId(),
                                    dedupKey));
            sendLog.insert(
                    new NotifySendLogRow(
                            null,
                            type.name(),
                            recipientId,
                            channel.name(),
                            STATUS_SENT,
                            title,
                            inserted ? null : "重复投递，站内信已存在（幂等）",
                            dedupKey,
                            LocalDateTime.now(),
                            null));
            return;
        }
        // SMS：并入每日汇总发送（E09-T05 按人合并、免打扰与频控），此处只记 QUEUED。
        // 模板参数（待办数量等）由汇总作业发送时按当日待办统计生成，短信不含患者信息（A-NTF-003）。
        sendLog.insert(
                new NotifySendLogRow(
                        null,
                        type.name(),
                        recipientId,
                        channel.name(),
                        STATUS_QUEUED,
                        title,
                        null,
                        dedupKey,
                        null,
                        null));
    }

    private EffectiveRule loadRule(NotifyEventType type) {
        NotifyRuleRow row = rules.find(type.name()).orElse(null);
        if (row == null) {
            return new EffectiveRule(true, type.defaultChannels(), type.defaultRecipients());
        }
        return new EffectiveRule(
                row.enabled(),
                NotifyRuleCodec.parseChannels(row.channels()),
                NotifyRuleCodec.parseRecipients(row.recipients()));
    }

    private NotifyEventPayload parse(String payload) {
        try {
            return mapper.readValue(payload == null ? "{}" : payload, NotifyEventPayload.class);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("通知事件负载不是合法 JSON，无法投递", broken);
        }
    }

    /** 生效规则（缺行回落目录默认值）。 */
    private record EffectiveRule(
            boolean enabled, List<NotifyChannel> channels, List<RecipientOption> recipients) {}
}
