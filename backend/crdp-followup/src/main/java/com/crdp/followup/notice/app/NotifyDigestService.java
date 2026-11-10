package com.crdp.followup.notice.app;

import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.infra.mapper.NotifyRecipientMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogRow;
import com.crdp.followup.notice.infra.mapper.NotifySettingMapper;
import com.crdp.followup.notice.infra.mapper.NotifySettingRow;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.sms.SmsChannelException;
import com.crdp.framework.notify.sms.SmsOutbound;
import com.crdp.framework.notify.sms.SmsScene;
import com.crdp.framework.notify.sms.SmsSendCommand;
import com.crdp.framework.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 每日汇总作业（E09-T05 / A-NTF-005、C-NTF-003、C-NTF-005；验收「08:30 汇总；21:00–08:00 不发」）。
 *
 * <p>消费 {@code ntf_send_log} 的 QUEUED 短信行（规则引擎只记队列，OQ-60 ②），按人合并成<b>一条</b>汇总短信经 平台通道发出（A-NTF-003
 * 「按人合并、不按患者逐条发送」；参数只有数量，白名单与渲染反扫两道闸兜底）： 同日多个事件（如 5 项待随访 + 2 项逾期）只发一条「您有 N 项随访任务待处理」。
 *
 * <p>免打扰与频控（A-NTF-005 / C-NTF-005，租户可在 ntf_setting 调整，C 沿用 A 默认值）：
 *
 * <ul>
 *   <li>免打扰 21:00–08:00（可跨零点）：时段内短信一律不发、行保持 QUEUED，下一轮（次日 08:30，默认）再合并发出 ——「21:30 触发的逾期短信推迟到次日
 *       08:00 后发送」。免打扰只约束短信，站内信汇总照投；
 *   <li>每人每日短信上限（默认 3，建议值）：当日已发满上限的接收人本轮跳过、行并入次日汇总——「第 4 条并入次日」。
 * </ul>
 *
 * <p>触发时机读 DAILY_DIGEST 规则的 send_time（默认 08:30，逐租户可配），由 {@code
 * NotifyDigestScheduler}（crdp-server）每分钟级 tick 调 {@link #poll}；每租户每日至多触发一次（内存标记，重启清零——每日上限是持久兜底，
 * 多跑不会超发）。触发即<b>先标记后执行</b>：作业中断当日不补跑， 未发出的 QUEUED 行不丢，次日汇总照常带上。
 *
 * <p>站内信侧（A-NTF-001 每日汇总默认渠道 站内信+短信）：作业按当日待汇总行给每人投一条「每日汇总」站内信（数量口径与短信一致， 不含患者信息）； 若事件侧（E14+ 按任务发布的
 * DAILY_DIGEST 事件）当日已投过汇总站内信则不重复。E14 任务域落地后，待办统计可改为按 fu_task 口径复核（见 docs/api/notify.md §3）。
 *
 * <p>幂等与取舍：短信先发后记账（服务商受理而记账失败会重发一条，至少一次；反向会出现「记 SENT 实未发」，验收更不可接受）。 状态批量更新带 QUEUED 条件，重跑 /
 * 并发不覆盖终态行。
 */
@Service
public class NotifyDigestService {

    private static final Logger log = LoggerFactory.getLogger(NotifyDigestService.class);

    /** 业务时间按北京时间（AGENTS.md §6：业务日期按 Asia/Shanghai 计算）。 */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 免打扰默认值（医院已确认）与每日上限建议值（E09-T07 ntf_setting 种子同口径）。 */
    static final LocalTime DEFAULT_DND_START = LocalTime.of(21, 0);

    static final LocalTime DEFAULT_DND_END = LocalTime.of(8, 0);

    static final int DEFAULT_SMS_DAILY_LIMIT = 3;

    /** 每日汇总默认发送时间（A 规格「每天 08:30」；DAILY_DIGEST 规则缺行 / send_time 为空时回落）。 */
    static final LocalTime DEFAULT_SEND_TIME = LocalTime.of(8, 30);

    /** 到点容差窗口：tick 延迟几轮内仍算到点（每租户每日只触发一次）。 */
    static final int FIRE_WINDOW_MINUTES = 5;

    private final NotifySendLogMapper sendLog;
    private final NotifyRuleMapper rules;
    private final NotifySettingMapper settings;
    private final NotifyRecipientMapper recipients;
    private final NoticeInboxService inbox;
    private final SmsOutbound sms;
    private final CryptoService crypto;
    private final Clock clock;

    /** 每租户最近一次触发日（tenantCode → 日期）；重启清零，每日上限兜底防超发。 */
    private final ConcurrentHashMap<String, LocalDate> fired = new ConcurrentHashMap<>();

    @Autowired
    public NotifyDigestService(
            NotifySendLogMapper sendLog,
            NotifyRuleMapper rules,
            NotifySettingMapper settings,
            NotifyRecipientMapper recipients,
            NoticeInboxService inbox,
            SmsOutbound sms,
            CryptoService crypto) {
        this(sendLog, rules, settings, recipients, inbox, sms, crypto, Clock.system(BUSINESS_ZONE));
    }

    NotifyDigestService(
            NotifySendLogMapper sendLog,
            NotifyRuleMapper rules,
            NotifySettingMapper settings,
            NotifyRecipientMapper recipients,
            NoticeInboxService inbox,
            SmsOutbound sms,
            CryptoService crypto,
            Clock clock) {
        this.sendLog = sendLog;
        this.rules = rules;
        this.settings = settings;
        this.recipients = recipients;
        this.inbox = inbox;
        this.sms = sms;
        this.crypto = crypto;
        this.clock = clock;
    }

    /** 一轮结果（日志与测试断言用；不含患者信息）。 */
    public record DigestOutcome(
            RunStatus status,
            boolean quietHours,
            int recipients,
            int smsSent,
            int smsFailed,
            int smsDeferred,
            int smsSkipped) {

        /** 执行状态：未到点（或当日已触发） / 已执行一轮。 */
        public enum RunStatus {
            /** 未到本租户今日发送时间（或今日已触发过）。 */
            NOT_DUE,
            /** 已执行一轮。 */
            DONE
        }

        public static DigestOutcome notDue() {
            return new DigestOutcome(RunStatus.NOT_DUE, false, 0, 0, 0, 0, 0);
        }

        static DigestOutcome done(
                boolean quietHours,
                int recipients,
                int smsSent,
                int smsFailed,
                int smsDeferred,
                int smsSkipped) {
            return new DigestOutcome(
                    RunStatus.DONE,
                    quietHours,
                    recipients,
                    smsSent,
                    smsFailed,
                    smsDeferred,
                    smsSkipped);
        }
    }

    /** 生效设置（缺行 / 空值防御性回落默认值，口径与 E09-T07 NotifySettingsService 一致）。 */
    private record EffectiveSettings(LocalTime dndStart, LocalTime dndEnd, int smsDailyLimit) {}

    /**
     * 定时入口（租户上下文内，每分钟级 tick）：到本租户 DAILY_DIGEST 发送时间（默认 08:30，容差 {@link #FIRE_WINDOW_MINUTES}
     * 分钟）跑一轮，每日一次。未到点 / 已跑过返回 NOT_DUE。
     */
    public DigestOutcome poll() {
        LocalDateTime now = LocalDateTime.now(clock);
        String tenantCode = TenantContext.require().tenantCode();
        LocalTime sendTime = effectiveSendTime();
        if (!dueToday(sendTime, now, tenantCode)) {
            return DigestOutcome.notDue();
        }
        log.info("每日汇总作业触发 tenant={} sendTime={}", tenantCode, sendTime);
        return digest(now);
    }

    /** 立即执行一轮汇总（租户上下文内；定时到点、运维补跑与测试共用）。 免打扰时段照常投站内信汇总但短信全部推迟（行保持 QUEUED）。 */
    public DigestOutcome digest(LocalDateTime now) {
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            throw new IllegalStateException("每日汇总作业只在租户上下文内执行（ntf_send_log 属租户库）");
        }
        EffectiveSettings setting = loadSettings();
        boolean quiet = inQuietHours(now.toLocalTime(), setting.dndStart(), setting.dndEnd());
        NotifyRuleRow digestRule = rules.find(NotifyEventType.DAILY_DIGEST.name()).orElse(null);
        String templateCode = effectiveTemplate(digestRule);
        boolean inboxDigestOn = digestRule == null || digestRule.enabled();
        List<Long> recipientIds = sendLog.listQueuedSmsRecipients();
        int smsSent = 0;
        int smsFailed = 0;
        int smsDeferred = 0;
        int smsSkipped = 0;
        for (long recipientId : recipientIds) {
            List<NotifySendLogRow> rows = sendLog.listQueuedSms(recipientId);
            if (rows.isEmpty()) {
                continue;
            }
            int total = rows.size();
            int overdue =
                    (int)
                            rows.stream()
                                    .filter(
                                            row ->
                                                    NotifyEventType.TASK_OVERDUE
                                                            .name()
                                                            .equals(row.eventType()))
                                    .count();
            List<Long> rowIds = rows.stream().map(NotifySendLogRow::id).toList();
            if (inboxDigestOn) {
                deliverDigestInbox(recipientId, now, total, overdue);
            }
            // 免打扰：短信推迟（行保持 QUEUED，次日汇总带上），站内信已投不受影响
            if (quiet) {
                smsDeferred++;
                continue;
            }
            // 频控：当日已发满上限 → 并入次日汇总（A-NTF-005）
            LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
            if (sendLog.countSentSmsBetween(recipientId, dayStart, dayStart.plusDays(1))
                    >= setting.smsDailyLimit()) {
                smsDeferred++;
                log.info(
                        "汇总短信并入次日：接收人当日短信已达上限 tenant={} recipientId={} limit={}",
                        state.tenantCode(),
                        recipientId,
                        setting.smsDailyLimit());
                continue;
            }
            if (recipients.countEnabledUser(recipientId) == 0) {
                sendLog.markQueued(rowIds, "SKIPPED", null, "接收人已停用，不再发送汇总短信");
                smsSkipped++;
                continue;
            }
            String phone = phoneOf(recipientId);
            if (phone == null) {
                sendLog.markQueued(rowIds, "FAILED", now, "接收人未绑定手机号，汇总短信未发送");
                smsFailed++;
                continue;
            }
            try {
                // 参数只有数量（平台 NOTIFY 模板均为单数量占位），不含任何患者信息（A-NTF-003 / A-NFR-007）
                sms.send(
                        new SmsSendCommand(
                                SmsScene.NOTIFY,
                                state.tenantCode(),
                                phone,
                                templateCode,
                                List.of(String.valueOf(total))));
                sendLog.markQueued(rowIds, "SENT", now, null);
                smsSent++;
            } catch (SmsChannelException failure) {
                sendLog.markQueued(rowIds, "FAILED", now, "汇总短信发送失败：" + failure.reason());
                smsFailed++;
                log.warn(
                        "汇总短信发送失败 tenant={} recipientId={} reason={}",
                        state.tenantCode(),
                        recipientId,
                        failure.reason());
            }
        }
        DigestOutcome outcome =
                DigestOutcome.done(
                        quiet, recipientIds.size(), smsSent, smsFailed, smsDeferred, smsSkipped);
        log.info(
                "每日汇总完成 tenant={} quietHours={} recipients={} smsSent={} smsFailed={}"
                        + " smsDeferred={} smsSkipped={}",
                state.tenantCode(),
                quiet,
                outcome.recipients(),
                outcome.smsSent(),
                outcome.smsFailed(),
                outcome.smsDeferred(),
                outcome.smsSkipped());
        return outcome;
    }

    /**
     * 免打扰判定（A-NTF-005「21:00 至次日 08:00 不发短信」）：起点含、终点不含——08:00 整即可发（「推迟到 08:00 后发送」）。 start &lt; end
     * 为同日时段，start &gt; end 为跨零点时段；起止相同视为配置无效不静默（校验面已拦）。
     */
    static boolean inQuietHours(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    /** 到点判定：当前时间落在 [sendTime, sendTime + 容差窗口) 且今日未触发过（每租户每日一次）。 */
    private boolean dueToday(LocalTime sendTime, LocalDateTime now, String tenantCode) {
        LocalDate today = now.toLocalDate();
        if (today.equals(fired.get(tenantCode))) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        boolean due =
                !time.isBefore(sendTime)
                        && time.isBefore(sendTime.plusMinutes(FIRE_WINDOW_MINUTES));
        if (due) {
            // 先标记后执行：作业当日中断不重试（QUEUED 行不丢，次日汇总带上），宁可少发不重复发短信
            fired.put(tenantCode, today);
        }
        return due;
    }

    /** 每日汇总发送时间：DAILY_DIGEST 规则 send_time，缺行 / 空值回落 08:30（目录默认值）。 */
    private LocalTime effectiveSendTime() {
        NotifyRuleRow row = rules.find(NotifyEventType.DAILY_DIGEST.name()).orElse(null);
        return row != null && row.sendTime() != null ? row.sendTime() : DEFAULT_SEND_TIME;
    }

    /** 汇总短信模板：DAILY_DIGEST 规则配置，空回落目录默认 NOTIFY_DAILY_SUMMARY。 */
    private String effectiveTemplate(NotifyRuleRow digestRule) {
        if (digestRule != null
                && digestRule.smsTemplateCode() != null
                && !digestRule.smsTemplateCode().isBlank()) {
            return digestRule.smsTemplateCode();
        }
        String fallback = NotifyEventType.DAILY_DIGEST.defaultSmsTemplateCode();
        return fallback == null ? "NOTIFY_DAILY_SUMMARY" : fallback;
    }

    private EffectiveSettings loadSettings() {
        NotifySettingRow row = settings.find();
        if (row == null) {
            return new EffectiveSettings(
                    DEFAULT_DND_START, DEFAULT_DND_END, DEFAULT_SMS_DAILY_LIMIT);
        }
        return new EffectiveSettings(
                row.dndStart() == null ? DEFAULT_DND_START : row.dndStart(),
                row.dndEnd() == null ? DEFAULT_DND_END : row.dndEnd(),
                row.smsDailyLimit() > 0 ? row.smsDailyLimit() : DEFAULT_SMS_DAILY_LIMIT);
    }

    /** 投一条「每日汇总」站内信（数量口径与短信一致；dedup 按日幂等）。事件侧当日已投过则跳过。 */
    private void deliverDigestInbox(long recipientId, LocalDateTime now, int total, int overdue) {
        LocalDate today = now.toLocalDate();
        LocalDateTime dayStart = today.atStartOfDay();
        if (sendLog.countDigestInboxBetween(recipientId, dayStart, dayStart.plusDays(1)) > 0) {
            return;
        }
        String dedupKey = "digest:" + today + ":" + recipientId + ":INBOX";
        String title = NotifyEventType.DAILY_DIGEST.label();
        String summary = "您今日有 " + total + " 项随访待处理，其中逾期 " + overdue + " 项，请登录平台查看。";
        inbox.deliver(
                new NoticeInboxService.Delivery(
                        recipientId,
                        NotifyEventType.DAILY_DIGEST.name(),
                        title,
                        summary,
                        null,
                        null,
                        null,
                        null,
                        dedupKey));
        sendLog.insert(
                new NotifySendLogRow(
                        null,
                        NotifyEventType.DAILY_DIGEST.name(),
                        recipientId,
                        "INBOX",
                        "SENT",
                        title,
                        null,
                        dedupKey,
                        now,
                        null));
    }

    /** 接收人手机号（解密）；未绑定 / 解密失败返回 null（调用方按台账 FAILED 记原因，可经重试再发）。 */
    private String phoneOf(long recipientId) {
        String cipher = recipients.findPhone(recipientId);
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            return crypto.decrypt(cipher);
        } catch (RuntimeException broken) {
            log.warn("接收人手机号解密失败，按未绑定处理 recipientId={}", recipientId);
            return null;
        }
    }
}
