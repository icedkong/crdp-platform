package com.crdp.followup.notice.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 通知事件目录（A-NTF-001 / C-NTF-001；hospital-a-spec §7「关键字段 · 通知事件默认设置」）。
 *
 * <p>code 同时是三个口径的稳定标识：outbox 事件类型（业务域发布）、{@code ntf_rule.event_type}（规则配置）、 {@code
 * ntf_message.event_type}（站内信行）。A 与 C 的事件差异（C 首期无窗口预告）由租户在通知设置页停用对应事件， 不写租户分支。
 *
 * <p>默认值只作为配置缺行时的兜底与新建租户种子（followup 迁移 V22 已按同值落库）； advanceDays / sendTime
 * 描述事件的<b>触发时机</b>，由事件产生方（E14 每日调度）与汇总作业（E09-T05）读取——规则引擎本身在事件到达时即时投递。
 */
public enum NotifyEventType {
    /** 窗口预告：任务开启前 N 天（默认 7，可按节点关闭）。 */
    WINDOW_PREVIEW("窗口预告", 7, "08:30", "INBOX", "OWNER,WARD_STAFF", null),

    /** 任务开启（提醒开始）：任务开启日，提醒开始日取计划节点的提醒提前量（A-FU-016）。 */
    TASK_OPENED("任务开启", null, "08:30", "INBOX", "OWNER,WARD_STAFF", null),

    /** 窗口开启：窗口开始日；与提醒开始同一天时由事件产生方合并为一条，不另发。 */
    WINDOW_OPEN("窗口开启", null, "08:30", "INBOX", "OWNER,WARD_STAFF", null),

    /** 即将逾期：窗口结束前 N 天（默认 2）。 */
    DUE_SOON("即将逾期", 2, "08:30", "INBOX", "OWNER,WARD_STAFF", null),

    /** 已逾期：窗口结束次日；可同时抄送病区负责人（CC_WARD_LEADER）。 */
    TASK_OVERDUE(
            "已逾期", null, "08:30", "INBOX,SMS", "OWNER,WARD_STAFF,CC_WARD_LEADER", "NOTIFY_OVERDUE"),

    /** 每日汇总：每天 08:30 有待办才发（E09-T05 作业产生；接收人由作业按待办算好随负载下发）。 */
    DAILY_DIGEST("每日汇总", null, "08:30", "INBOX,SMS", "OWNER,WARD_STAFF", "NOTIFY_DAILY_SUMMARY"),

    /** 任务改派：改派后即时，接收人 = 新负责人。 */
    TASK_REASSIGNED("任务改派", null, null, "INBOX", "OWNER,WARD_STAFF", null),

    /** 约定回访到时：沟通记录中填写的下次联系时间，接收人 = 记录人。 */
    CALLBACK_DUE("约定回访到时", null, null, "INBOX", "OWNER,WARD_STAFF", null),

    /** 未联系到跟进：次日随跟进任务。 */
    UNREACHED_FOLLOWUP("未联系到跟进", null, "08:30", "INBOX", "OWNER,WARD_STAFF", null),

    /** 失访候选：窗口结束次日。 */
    LOST_CANDIDATE("失访候选", null, "08:30", "INBOX", "OWNER,WARD_STAFF", null);

    private final String label;
    private final Integer defaultAdvanceDays;
    private final String defaultSendTime;
    private final List<NotifyChannel> defaultChannels;
    private final List<RecipientOption> defaultRecipients;
    private final String defaultSmsTemplateCode;

    NotifyEventType(
            String label,
            Integer defaultAdvanceDays,
            String defaultSendTime,
            String channels,
            String recipients,
            String defaultSmsTemplateCode) {
        this.label = label;
        this.defaultAdvanceDays = defaultAdvanceDays;
        this.defaultSendTime = defaultSendTime;
        this.defaultChannels = NotifyRuleCodec.parseChannels(channels);
        this.defaultRecipients = NotifyRuleCodec.parseRecipients(recipients);
        this.defaultSmsTemplateCode = defaultSmsTemplateCode;
    }

    public static Optional<NotifyEventType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.name().equals(code.trim())).findFirst();
    }

    /** 中文名（通知设置页与默认文案展示）。 */
    public String label() {
        return label;
    }

    /** 默认提前天数（窗口预告 7、即将逾期 2，其余无）。 */
    public Integer defaultAdvanceDays() {
        return defaultAdvanceDays;
    }

    /** 默认发送时间（HH:mm；即时事件为 null）。 */
    public String defaultSendTime() {
        return defaultSendTime;
    }

    /** 默认渠道集合。 */
    public List<NotifyChannel> defaultChannels() {
        return defaultChannels;
    }

    /** 默认接收人规则集合。 */
    public List<RecipientOption> defaultRecipients() {
        return defaultRecipients;
    }

    /** 默认短信模板（只有含短信渠道的事件有）。 */
    public String defaultSmsTemplateCode() {
        return defaultSmsTemplateCode;
    }
}
