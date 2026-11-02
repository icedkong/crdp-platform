package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.app.NotifyDigestService.DigestOutcome;
import com.crdp.followup.notice.infra.mapper.NotifyRecipientMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogRow;
import com.crdp.followup.notice.infra.mapper.NotifySettingMapper;
import com.crdp.followup.notice.infra.mapper.NotifySettingRow;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.sms.SmsChannelException;
import com.crdp.framework.notify.sms.SmsSendCommand;
import com.crdp.framework.notify.sms.TencentSmsChannel;
import com.crdp.framework.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E09-T05 / A-NTF-005、C-NTF-003、C-NTF-005 单元测试。验收原文：「08:30 汇总；21:00–08:00 不发」； A 规格 §7 「某医生当天有 5
 * 项待随访、2 项逾期，只收到 1 条汇总短信，内容不含任何患者信息」「21:30 触发的逾期短信推迟到次日 08:00 后发送」； notify.md §7「第 4 条并入次日」。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifyDigestServiceTest {

    private static final long TENANT_ID = 1L;
    private static final String TENANT_CODE = "demoa";
    private static final long DOCTOR = 11L;
    private static final LocalDateTime AT_0830 = LocalDateTime.of(2026, 11, 2, 8, 30);
    private static final LocalDateTime AT_2130 = LocalDateTime.of(2026, 11, 1, 21, 30);

    private final NotifySendLogMapper sendLog = mock(NotifySendLogMapper.class);
    private final NotifyRuleMapper rules = mock(NotifyRuleMapper.class);
    private final NotifySettingMapper settings = mock(NotifySettingMapper.class);
    private final NotifyRecipientMapper recipients = mock(NotifyRecipientMapper.class);
    private final NoticeInboxService inbox = mock(NoticeInboxService.class);
    private final TencentSmsChannel sms = mock(TencentSmsChannel.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private NotifyDigestService service;

    @BeforeEach
    void setUp() {
        // 固定时钟：poll() 的“现在”可控；digest() 直接传时间
        Clock fixed = Clock.fixed(Instant.parse("2026-11-02T08:30:00Z"), ZoneOffset.UTC);
        service =
                new NotifyDigestService(
                        sendLog, rules, settings, recipients, inbox, sms, crypto, fixed);
        lenient().when(rules.find("DAILY_DIGEST")).thenReturn(Optional.empty());
        lenient()
                .when(settings.find())
                .thenReturn(
                        new NotifySettingRow(
                                1L, LocalTime.of(21, 0), LocalTime.of(8, 0), 3, false, null, null));
        lenient().when(recipients.countEnabledUser(anyLong())).thenReturn(1);
        lenient().when(recipients.findPhone(DOCTOR)).thenReturn("cipher");
        lenient().when(crypto.decrypt("cipher")).thenReturn("13900001111");
        TenantContext.useTenant(TENANT_ID, TENANT_CODE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static NotifySendLogRow queued(long id, String eventType) {
        return new NotifySendLogRow(
                id,
                eventType,
                DOCTOR,
                "SMS",
                "QUEUED",
                "已逾期：第 5 年随访",
                null,
                "evt-" + id + ":" + DOCTOR + ":SMS",
                null,
                null);
    }

    /** 验收主链路：5 项待随访 + 2 项逾期（7 行 QUEUED）合并为 1 条短信，参数只有数量。 */
    @Test
    void mergesAllQueuedSmsIntoOneSummaryPerRecipient() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        List<NotifySendLogRow> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(queued(i, "DAILY_DIGEST"));
        }
        rows.add(queued(6, "TASK_OVERDUE"));
        rows.add(queued(7, "TASK_OVERDUE"));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(rows);
        lenient().when(sendLog.countSentSmsBetween(eq(DOCTOR), any(), any())).thenReturn(0);
        lenient().when(sendLog.countDigestInboxBetween(eq(DOCTOR), any(), any())).thenReturn(0);

        DigestOutcome outcome = service.digest(AT_0830);

        assertEquals(NotifyDigestService.DigestOutcome.RunStatus.DONE, outcome.status());
        assertFalse(outcome.quietHours());
        assertEquals(1, outcome.recipients());
        assertEquals(1, outcome.smsSent(), "7 行只发 1 条汇总短信");

        ArgumentCaptor<SmsSendCommand> command = ArgumentCaptor.forClass(SmsSendCommand.class);
        verify(sms).send(command.capture());
        assertEquals("NOTIFY_DAILY_SUMMARY", command.getValue().templateCode());
        assertEquals("13900001111", command.getValue().phone());
        assertEquals(List.of("7"), command.getValue().params(), "参数只有待办总数");
        assertEquals("NOTIFY", command.getValue().scene().name());
        assertEquals(TENANT_CODE, command.getValue().tenantCode());

        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.captor();
        verify(sendLog).markQueued(ids.capture(), eq("SENT"), eq(AT_0830), eq(null));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), ids.getValue(), "全部并入行记 SENT");
    }

    /** 短信内容不含患者信息：参数是数量，站内信汇总文案也只有数量。 */
    @Test
    void digestTextsContainNoPatientInfo() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        when(sendLog.listQueuedSms(DOCTOR))
                .thenReturn(List.of(queued(1, "TASK_OVERDUE"), queued(2, "TASK_OVERDUE")));
        lenient().when(sendLog.countSentSmsBetween(eq(DOCTOR), any(), any())).thenReturn(0);
        when(sendLog.countDigestInboxBetween(eq(DOCTOR), any(), any())).thenReturn(0);

        service.digest(AT_0830);

        ArgumentCaptor<SmsSendCommand> command = ArgumentCaptor.forClass(SmsSendCommand.class);
        verify(sms).send(command.capture());
        for (String param : command.getValue().params()) {
            assertTrue(param.matches("\\d{1,6}"), "NOTIFY 场景白名单：只允许数量 / 日期，实际=" + param);
        }
        ArgumentCaptor<NoticeInboxService.Delivery> delivery =
                ArgumentCaptor.forClass(NoticeInboxService.Delivery.class);
        verify(inbox).deliver(delivery.capture());
        String summary = delivery.getValue().summary();
        assertTrue(summary.contains("2") && summary.contains("逾期 2"), "汇总数量口径：" + summary);
        assertFalse(summary.contains("张"), "不含患者姓名（示例姓）");
        assertEquals("每日汇总", delivery.getValue().title());
        assertEquals("digest:2026-11-02:" + DOCTOR + ":INBOX", delivery.getValue().dedupKey());
    }

    /** 验收「21:00–08:00 不发」：21:30 一轮不触短信、行保持 QUEUED；站内信汇总照投（免打扰只约束短信）。 */
    @Test
    void quietHoursDefersSmsButStillDeliversInboxDigest() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.countDigestInboxBetween(eq(DOCTOR), any(), any())).thenReturn(0);

        DigestOutcome outcome = service.digest(AT_2130);

        assertTrue(outcome.quietHours());
        assertEquals(0, outcome.smsSent());
        assertEquals(1, outcome.smsDeferred());
        verify(sms, never()).send(any());
        verify(sendLog, never()).markQueued(anyList(), anyString(), any(), any());
        verify(inbox).deliver(any());
    }

    /** 免打扰边界：21:00 整静默（起点含）、08:00 整放行（终点不含，「推迟到 08:00 后发送」）。 */
    @Test
    void quietHoursBoundaries() {
        assertTrue(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(21, 0), LocalTime.of(21, 0), LocalTime.of(8, 0)),
                "21:00 整进入免打扰");
        assertTrue(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(23, 59), LocalTime.of(21, 0), LocalTime.of(8, 0)));
        assertTrue(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(0, 0), LocalTime.of(21, 0), LocalTime.of(8, 0)),
                "跨零点仍在免打扰");
        assertTrue(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(7, 59), LocalTime.of(21, 0), LocalTime.of(8, 0)));
        assertFalse(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(8, 0), LocalTime.of(21, 0), LocalTime.of(8, 0)),
                "08:00 整即可发送");
        assertFalse(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(20, 59), LocalTime.of(21, 0), LocalTime.of(8, 0)));
        // 同日时段（如午休 12:00–13:00）与租户自定义跨零点时段同样成立
        assertTrue(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(12, 30), LocalTime.of(12, 0), LocalTime.of(13, 0)));
        assertFalse(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(13, 0)));
        assertFalse(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(22, 29), LocalTime.of(22, 30), LocalTime.of(9, 30)),
                "租户自定义 22:30–09:30：22:29 尚未进入免打扰");
        assertTrue(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(9, 0), LocalTime.of(22, 30), LocalTime.of(9, 30)),
                "租户自定义 22:30–09:30：09:00 仍在免打扰");
        assertFalse(
                NotifyDigestService.inQuietHours(
                        LocalTime.of(9, 30), LocalTime.of(22, 30), LocalTime.of(9, 30)),
                "09:30 整即可发送（终点不含）");
    }

    /** 频控「第 4 条并入次日」：当日已发满上限（默认 3）→ 本轮不触短信，行保持 QUEUED。 */
    @Test
    void dailyLimitDefersToNextDay() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.countSentSmsBetween(eq(DOCTOR), any(), any())).thenReturn(3);
        when(sendLog.countDigestInboxBetween(eq(DOCTOR), any(), any())).thenReturn(0);

        DigestOutcome outcome = service.digest(AT_0830);

        assertEquals(0, outcome.smsSent());
        assertEquals(1, outcome.smsDeferred());
        verify(sms, never()).send(any());
        verify(sendLog, never()).markQueued(anyList(), anyString(), any(), any());
    }

    /** 发送失败：并入行整体记 FAILED（detail 只含失败类别，可经 E09-T07 重试回 QUEUED）。 */
    @Test
    void sendFailureMarksRowsFailedWithReason() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.countSentSmsBetween(eq(DOCTOR), any(), any())).thenReturn(0);
        when(sendLog.countDigestInboxBetween(eq(DOCTOR), any(), any())).thenReturn(0);
        when(sms.send(any())).thenThrow(new SmsChannelException("CHANNEL_DISABLED", "通道未启用"));

        DigestOutcome outcome = service.digest(AT_0830);

        assertEquals(1, outcome.smsFailed());
        assertEquals(0, outcome.smsSent());
        verify(sendLog)
                .markQueued(
                        eq(List.of(1L)),
                        eq("FAILED"),
                        eq(AT_0830),
                        eq("汇总短信发送失败：CHANNEL_DISABLED"));
    }

    /** 未绑定手机号：记 FAILED 不假装成功；接收人停用：记 SKIPPED 不再发送。 */
    @Test
    void missingPhoneFailsAndDisabledUserIsSkipped() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR, 99L));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.listQueuedSms(99L)).thenReturn(List.of(queued(2, "TASK_OVERDUE")));
        when(sendLog.countSentSmsBetween(anyLong(), any(), any())).thenReturn(0);
        when(sendLog.countDigestInboxBetween(anyLong(), any(), any())).thenReturn(0);
        when(recipients.findPhone(DOCTOR)).thenReturn(null);
        when(recipients.countEnabledUser(99L)).thenReturn(0);

        DigestOutcome outcome = service.digest(AT_0830);

        assertEquals(1, outcome.smsFailed());
        assertEquals(1, outcome.smsSkipped());
        verify(sendLog)
                .markQueued(eq(List.of(1L)), eq("FAILED"), eq(AT_0830), eq("接收人未绑定手机号，汇总短信未发送"));
        verify(sendLog).markQueued(eq(List.of(2L)), eq("SKIPPED"), eq(null), eq("接收人已停用，不再发送汇总短信"));
        verify(sms, never()).send(any());
    }

    /** 多接收人互不影响：一人失败不阻断他人发送。 */
    @Test
    void otherRecipientsStillSendWhenOneFails() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR, 12L));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.listQueuedSms(12L))
                .thenReturn(List.of(queued(2, "TASK_OVERDUE"), queued(3, "DAILY_DIGEST")));
        when(sendLog.countSentSmsBetween(anyLong(), any(), any())).thenReturn(0);
        when(sendLog.countDigestInboxBetween(anyLong(), any(), any())).thenReturn(0);
        when(recipients.findPhone(12L)).thenReturn("cipher12");
        when(crypto.decrypt("cipher12")).thenReturn("13900002222");
        when(sms.send(any()))
                .thenThrow(new SmsChannelException("CHANNEL_DISABLED", "通道未启用"))
                .thenReturn("serial-1");

        DigestOutcome outcome = service.digest(AT_0830);

        assertEquals(1, outcome.smsSent());
        assertEquals(1, outcome.smsFailed());
        verify(sendLog).markQueued(eq(List.of(2L, 3L)), eq("SENT"), eq(AT_0830), eq(null));
    }

    /** 事件侧当日已投过 DAILY_DIGEST 站内信（E14+）→ 作业不重复投。 */
    @Test
    void inboxDigestSkippedWhenEventSideAlreadyDeliveredToday() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.countSentSmsBetween(eq(DOCTOR), any(), any())).thenReturn(0);
        when(sendLog.countDigestInboxBetween(eq(DOCTOR), any(), any())).thenReturn(1);

        service.digest(AT_0830);

        verify(inbox, never()).deliver(any());
        verify(sendLog).markQueued(eq(List.of(1L)), eq("SENT"), eq(AT_0830), eq(null));
    }

    /** DAILY_DIGEST 规则停用：站内信汇总不投，但其他事件（已逾期）排队的短信照常合并发送。 */
    @Test
    void disabledDigestRuleStillSendsQueuedSmsOfOtherEvents() {
        when(rules.find("DAILY_DIGEST"))
                .thenReturn(
                        Optional.of(
                                new NotifyRuleRow(
                                        1L,
                                        "DAILY_DIGEST",
                                        false,
                                        null,
                                        LocalTime.of(8, 30),
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF",
                                        "NOTIFY_DAILY_SUMMARY",
                                        null,
                                        null)));
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of(DOCTOR));
        when(sendLog.listQueuedSms(DOCTOR)).thenReturn(List.of(queued(1, "TASK_OVERDUE")));
        when(sendLog.countSentSmsBetween(eq(DOCTOR), any(), any())).thenReturn(0);

        service.digest(AT_0830);

        verify(inbox, never()).deliver(any());
        verify(sms).send(any());
    }

    /** poll()：到点（默认 08:30，固定时钟即 08:30）触发一轮并当日只触发一次。 */
    @Test
    void pollFiresOncePerDayAtConfiguredSendTime() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of());

        DigestOutcome first = service.poll();
        assertEquals(NotifyDigestService.DigestOutcome.RunStatus.DONE, first.status());

        DigestOutcome second = service.poll();
        assertEquals(
                NotifyDigestService.DigestOutcome.RunStatus.NOT_DUE,
                second.status(),
                "当日第二次 poll 不再触发");
    }

    /** poll()：租户把发送时间配在 09:00，现在 08:30 未到点不触发（逐租户可配）。 */
    @Test
    void pollNotDueBeforeConfiguredSendTime() {
        when(rules.find("DAILY_DIGEST"))
                .thenReturn(
                        Optional.of(
                                new NotifyRuleRow(
                                        1L,
                                        "DAILY_DIGEST",
                                        true,
                                        null,
                                        LocalTime.of(9, 0),
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF",
                                        "NOTIFY_DAILY_SUMMARY",
                                        null,
                                        null)));

        DigestOutcome outcome = service.poll();
        assertEquals(NotifyDigestService.DigestOutcome.RunStatus.NOT_DUE, outcome.status());
    }

    /** 平台上下文拒绝执行（ntf_send_log 属租户库）。 */
    @Test
    void rejectsPlatformContext() {
        TenantContext.usePlatform();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> service.digest(AT_0830));
    }

    /** 无待汇总行：一轮空跑，不触短信不投站内信。 */
    @Test
    void nothingQueuedIsNoop() {
        when(sendLog.listQueuedSmsRecipients()).thenReturn(List.of());

        DigestOutcome outcome = service.digest(AT_0830);

        assertEquals(NotifyDigestService.DigestOutcome.RunStatus.DONE, outcome.status());
        assertEquals(0, outcome.recipients());
        verify(sms, never()).send(any());
        verify(inbox, never()).deliver(any());
    }
}
