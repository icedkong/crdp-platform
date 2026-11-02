package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogRow;
import com.crdp.framework.outbox.OutboxEvent;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E09-T04 / A-NTF-001、A-NTF-004：通知规则引擎单元测试（验收「任务开启、逾期等事件按规则发给责任人」）。
 *
 * <p>锁定：站内信按 dedup（outbox 事件 ID + 接收人 + 渠道）投递且标题不含患者姓名、短信只落 QUEUED （并入每日汇总由 E09-T05
 * 发送）、事件停用即跳过、缺配置行回落目录默认值、重放不重记、 负载解析失败抛错交分发器重试。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifyEventSubscriberTest {

    private static final long OWNER = 11L;
    private static final long EVENT_ID = 777L;

    private final NotifyRuleMapper rules = mock(NotifyRuleMapper.class);
    private final NotifySendLogMapper sendLog = mock(NotifySendLogMapper.class);
    private final NotifyRecipientResolver recipientResolver = mock(NotifyRecipientResolver.class);
    private final NoticeInboxService inbox = mock(NoticeInboxService.class);

    private final NotifyEventSubscriber subscriber =
            new NotifyEventSubscriber(rules, sendLog, recipientResolver, inbox);

    @BeforeEach
    void commonStubs() {
        lenient().when(sendLog.countByDedupKey(anyString())).thenReturn(0);
        lenient()
                .when(recipientResolver.resolve(any(), any(), any()))
                .thenReturn(NotifyRecipientResolver.Resolution.of(List.of(OWNER), "负责人命中"));
    }

    private static OutboxEvent openedEvent() {
        String payload =
                "{\"taskId\":4242,\"ownerId\":11,\"wardId\":3,\"patientId\":990001,"
                        + "\"patientName\":\"张小明（示例）\",\"nodeName\":\"第 4 年随访\","
                        + "\"windowStartDate\":\"2026-11-02\",\"windowEndDate\":\"2026-11-16\"}";
        return new OutboxEvent(EVENT_ID, "TASK_OPENED", payload, Instant.now());
    }

    /** supports 只认目录内事件（组织事件等其他 outbox 事件不受影响）。 */
    @Test
    void supportsOnlyCatalogEvents() {
        assertTrue(subscriber.supports("TASK_OPENED"));
        assertTrue(subscriber.supports("TASK_OVERDUE"));
        assertFalse(subscriber.supports("user.disabled"));
        assertFalse(subscriber.supports("it.outbox.evt"));
        assertFalse(subscriber.supports(null));
    }

    /** 任务开启（缺配置行回落默认：INBOX、OWNER+WARD_STAFF）：站内信发给责任人，标题不含患者姓名。 */
    @Test
    void openedEventDeliversInboxToOwnerWithDefaultRule() {
        when(rules.find("TASK_OPENED")).thenReturn(Optional.empty());
        when(inbox.deliver(any())).thenReturn(true);

        subscriber.onEvent(openedEvent());

        ArgumentCaptor<NoticeInboxService.Delivery> delivery =
                ArgumentCaptor.forClass(NoticeInboxService.Delivery.class);
        verify(inbox).deliver(delivery.capture());
        NoticeInboxService.Delivery sent = delivery.getValue();
        assertEquals(OWNER, sent.recipientId());
        assertEquals("TASK_OPENED", sent.eventType());
        assertEquals("任务开启：第 4 年随访", sent.title());
        assertFalse(sent.title().contains("张小明"), "标题不含患者姓名明文");
        assertTrue(sent.summary().contains("2026-11-02"), "摘要带窗口上下文：" + sent.summary());
        assertEquals("张小明（示例）", sent.patientName(), "患者姓名走密文列由收件箱加密落库");
        assertEquals("TASK", sent.targetType());
        assertEquals(4242L, sent.targetId());
        assertEquals(EVENT_ID + ":" + OWNER + ":INBOX", sent.dedupKey());

        ArgumentCaptor<NotifySendLogRow> logRow = ArgumentCaptor.forClass(NotifySendLogRow.class);
        verify(sendLog).insert(logRow.capture());
        assertEquals("SENT", logRow.getValue().sendStatus());
        assertEquals("INBOX", logRow.getValue().channel());
        assertFalse(logRow.getValue().title().contains("张小明"), "台账标题不含患者姓名");
    }

    /** 已逾期（规则 INBOX+SMS）：站内信即时投递，短信落 QUEUED 等每日汇总合并发送。 */
    @Test
    void overdueEventQueuesSmsAndDeliversInbox() {
        when(rules.find("TASK_OVERDUE"))
                .thenReturn(
                        Optional.of(
                                new NotifyRuleRow(
                                        1L,
                                        "TASK_OVERDUE",
                                        true,
                                        null,
                                        LocalTime.of(8, 30),
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF,CC_WARD_LEADER",
                                        "NOTIFY_OVERDUE",
                                        null,
                                        null)));
        when(inbox.deliver(any())).thenReturn(true);
        String payload =
                "{\"taskId\":4243,\"ownerId\":11,\"wardId\":3,\"patientId\":990002,"
                        + "\"patientName\":\"李小红（示例）\",\"nodeName\":\"第 5 年随访\","
                        + "\"windowEndDate\":\"2026-10-31\"}";
        subscriber.onEvent(new OutboxEvent(EVENT_ID + 1, "TASK_OVERDUE", payload, Instant.now()));

        // 两条台账：INBOX SENT + SMS QUEUED（短信并入每日汇总，E09-T05 发送）
        ArgumentCaptor<NotifySendLogRow> rows = ArgumentCaptor.forClass(NotifySendLogRow.class);
        verify(sendLog, org.mockito.Mockito.times(2)).insert(rows.capture());
        List<NotifySendLogRow> inserted = rows.getAllValues();
        assertEquals("INBOX", inserted.get(0).channel());
        assertEquals("SENT", inserted.get(0).sendStatus());
        assertEquals("SMS", inserted.get(1).channel());
        assertEquals("QUEUED", inserted.get(1).sendStatus());
        assertEquals((EVENT_ID + 1) + ":" + OWNER + ":SMS", inserted.get(1).dedupKey());
        assertEquals(null, inserted.get(1).sentAt(), "QUEUED 尚未发送，无发送时间");
    }

    /** 事件被租户停用：不投递、不记台账。 */
    @Test
    void disabledRuleSkipsEverything() {
        when(rules.find("WINDOW_PREVIEW"))
                .thenReturn(
                        Optional.of(
                                new NotifyRuleRow(
                                        2L,
                                        "WINDOW_PREVIEW",
                                        false,
                                        7,
                                        LocalTime.of(8, 30),
                                        "INBOX",
                                        "OWNER,WARD_STAFF",
                                        null,
                                        null,
                                        null)));

        subscriber.onEvent(new OutboxEvent(EVENT_ID, "WINDOW_PREVIEW", "{}", Instant.now()));

        verify(inbox, never()).deliver(any());
        verify(sendLog, never()).insert(any());
    }

    /** 无接收人（如负责人缺失且未配兜底）：跳过，不算失败。 */
    @Test
    void noRecipientsSkipsQuietly() {
        when(rules.find("TASK_REASSIGNED")).thenReturn(Optional.empty());
        when(recipientResolver.resolve(any(), any(), any()))
                .thenReturn(NotifyRecipientResolver.Resolution.of(List.of(), "负责人缺失"));

        subscriber.onEvent(new OutboxEvent(EVENT_ID, "TASK_REASSIGNED", "{}", Instant.now()));

        verify(inbox, never()).deliver(any());
        verify(sendLog, never()).insert(any());
    }

    /** 重放（outbox 至少一次）：台账已存在则不再投递不再记。 */
    @Test
    void replayedEventDoesNotDuplicate() {
        when(rules.find("TASK_OPENED")).thenReturn(Optional.empty());
        when(sendLog.countByDedupKey(EVENT_ID + ":" + OWNER + ":INBOX")).thenReturn(1);

        subscriber.onEvent(openedEvent());

        verify(inbox, never()).deliver(any());
        verify(sendLog, never()).insert(any());
    }

    /** 负载不是合法 JSON：抛运行时异常，交给分发器退避重试（最终死信暴露发布方缺陷）。 */
    @Test
    void malformedPayloadFailsForRetry() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        subscriber.onEvent(
                                new OutboxEvent(
                                        EVENT_ID, "TASK_OPENED", "not-json{", Instant.now())));
    }

    /** 每日汇总：接收人由负载显式下发（E09-T05 作业按待办算好）。 */
    @Test
    void dailyDigestUsesExplicitRecipientsFromPayload() {
        when(rules.find("DAILY_DIGEST")).thenReturn(Optional.empty());
        when(recipientResolver.resolve(eq(NotifyEventType.DAILY_DIGEST), any(), any()))
                .thenReturn(NotifyRecipientResolver.Resolution.of(List.of(OWNER), "显式接收人"));
        when(inbox.deliver(any())).thenReturn(true);

        subscriber.onEvent(
                new OutboxEvent(
                        EVENT_ID, "DAILY_DIGEST", "{\"recipientIds\":[11]}", Instant.now()));

        ArgumentCaptor<NoticeInboxService.Delivery> delivery =
                ArgumentCaptor.forClass(NoticeInboxService.Delivery.class);
        verify(inbox).deliver(delivery.capture());
        assertEquals("每日汇总", delivery.getValue().title());
        assertEquals(null, delivery.getValue().targetType(), "汇总无任务落点");
    }
}
