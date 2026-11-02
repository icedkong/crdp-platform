package com.crdp.framework.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.MissingTenantContextException;
import com.crdp.framework.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * E09-T01：sys_outbox 分发器（验收：业务提交后消息必达，失败重试）。租户扇出、按 id 序投递、失败退避重试、
 * 超限死信（status='FAILED'）；表结构不变（OQ-52），重试台账在内存。
 */
class OutboxDispatcherTest {

    /** 可拨时钟：验证退避到期前不重试、到期后重试。 */
    static final class SettableClock extends Clock {
        Instant now = Instant.parse("2027-02-01T00:00:00Z");

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantDirectory directory = mock(TenantDirectory.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxSubscriber> subscribers = mock(ObjectProvider.class);

    private final SettableClock clock = new SettableClock();

    private static final long RETRY_BASE_MS = 100;

    @BeforeEach
    void cleanContext() {
        TenantContext.clear();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private OutboxDispatcher dispatcher(int maxAttempts) {
        return new OutboxDispatcher(
                subscribers, jdbc, directory, clock, 50, RETRY_BASE_MS, 60_000, maxAttempts);
    }

    private void subscribersAre(OutboxSubscriber... list) {
        when(subscribers.orderedStream()).thenAnswer(inv -> List.of(list).stream());
    }

    private static OutboxSubscriber subscriberFor(
            String eventType, java.util.function.Consumer<OutboxEvent> delegate) {
        return new OutboxSubscriber() {
            @Override
            public boolean supports(String type) {
                return eventType == null || eventType.equals(type);
            }

            @Override
            public void onEvent(OutboxEvent event) {
                delegate.accept(event);
            }
        };
    }

    private void pendingRowsAre(OutboxEvent... events) {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<OutboxEvent>>any(), any()))
                .thenReturn(List.of(events));
    }

    private static final Instant OCCURRED = Instant.parse("2027-01-31T09:30:00Z");

    // ---------------------------------------------------------------- 必达：提交后投递并标记 SENT

    @Test
    void deliversEventsToInterestedSubscribersInsideEachTenantContextAndMarksSent() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));
        List<String> contextsSeen = new java.util.ArrayList<>();
        List<String> received = new java.util.ArrayList<>();
        OutboxSubscriber interested =
                subscriberFor(
                        "user.disabled",
                        event -> {
                            contextsSeen.add(TenantContext.lookupKey());
                            received.add(event.eventType() + "|" + event.payload());
                        });
        OutboxSubscriber notInterested =
                subscriberFor(
                        "other.event",
                        event -> {
                            throw new IllegalStateException("不应收到不关心的事件");
                        });
        subscribersAre(interested, notInterested);
        pendingRowsAre(new OutboxEvent(7L, "user.disabled", "{\"userId\":9}", OCCURRED));

        DispatchResult result = dispatcher(6).dispatchOnce();

        assertEquals(new DispatchResult(2, 0, 0), result, "两个租户各投递一条");
        assertEquals(List.of("tenant:1", "tenant:2"), contextsSeen, "订阅方在各自租户上下文内执行");
        assertEquals(
                List.of("user.disabled|{\"userId\":9}", "user.disabled|{\"userId\":9}"), received);
        verify(jdbc, times(2)).update(contains("status = 'SENT'"), eq(7L));
        assertTrue(TenantContext.current().isEmpty(), "调度线程自身不残留租户上下文");
    }

    @Test
    void anEventNoSubscriberCaresAboutStaysPendingUntilItsSubscriberLands() {
        when(directory.enabledTenants()).thenReturn(List.of(new TenantRef(1L, "demoa")));
        subscribersAre(subscriberFor("user.disabled", event -> {}));
        pendingRowsAre(new OutboxEvent(8L, "ward.disabled", "{}", OCCURRED));

        DispatchResult result = dispatcher(6).dispatchOnce();

        // E09-T04 起与「无订阅方不消费」同口径：无人感兴趣的事件保持 PENDING，
        // 避免被无关订阅方连带标记 SENT 而丢失（组织事件的订阅方随 E14+ 落地）
        assertEquals(new DispatchResult(0, 0, 0), result, "无人感兴趣的事件不消费");
        verify(jdbc, never()).update(contains("status = 'SENT'"), eq(8L));
    }

    @Test
    void noSubscribersAtAllLeavesEverythingPending() {
        when(directory.enabledTenants()).thenReturn(List.of(new TenantRef(1L, "demoa")));
        subscribersAre();

        DispatchResult result = dispatcher(6).dispatchOnce();

        assertEquals(new DispatchResult(0, 0, 0), result, "尚无订阅方时不消费事件");
        verify(jdbc, never())
                .query(anyString(), ArgumentMatchers.<RowMapper<OutboxEvent>>any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ---------------------------------------------------------------- 失败重试：退避、死信

    @Test
    void aFailingSubscriberKeepsTheRowPendingAndRetriesAfterBackoff() {
        when(directory.enabledTenants()).thenReturn(List.of(new TenantRef(1L, "demoa")));
        AtomicInteger attempts = new AtomicInteger();
        subscribersAre(
                subscriberFor(
                        null,
                        event -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("首次投递失败");
                            }
                        }));
        pendingRowsAre(new OutboxEvent(9L, "user.disabled", "{}", OCCURRED));
        OutboxDispatcher dispatcher = dispatcher(6);

        assertEquals(new DispatchResult(0, 1, 0), dispatcher.dispatchOnce(), "失败后留在 PENDING");
        verify(jdbc, never()).update(anyString(), any(Object[].class));

        assertEquals(new DispatchResult(0, 1, 0), dispatcher.dispatchOnce(), "退避未到期，本轮跳过不重试");
        assertEquals(1, attempts.get(), "退避期内不再次调用订阅方");

        clock.advanceMillis(RETRY_BASE_MS);
        assertEquals(new DispatchResult(1, 0, 0), dispatcher.dispatchOnce(), "到期后重试成功");
        assertEquals(2, attempts.get());
        verify(jdbc).update(contains("status = 'SENT'"), eq(9L));
    }

    @Test
    void exhaustedRetriesDeadLetterTheEventAsFailed() {
        when(directory.enabledTenants()).thenReturn(List.of(new TenantRef(1L, "demoa")));
        AtomicInteger attempts = new AtomicInteger();
        subscribersAre(
                subscriberFor(
                        null,
                        event -> {
                            attempts.incrementAndGet();
                            throw new IllegalStateException("持续失败");
                        }));
        pendingRowsAre(new OutboxEvent(10L, "user.disabled", "{}", OCCURRED));
        OutboxDispatcher dispatcher = dispatcher(2);

        assertEquals(new DispatchResult(0, 1, 0), dispatcher.dispatchOnce());
        clock.advanceMillis(RETRY_BASE_MS);
        assertEquals(new DispatchResult(0, 0, 1), dispatcher.dispatchOnce(), "第二次失败即死信");
        verify(jdbc).update(contains("status = 'FAILED'"), eq(10L));
        verify(jdbc, never()).update(contains("status = 'SENT'"), eq(10L));

        // 死信行在库里已是 FAILED，下一轮 SELECT 不再返回它（桩改为空模拟真实查询结果）
        pendingRowsAre();
        clock.advanceMillis(60_000);
        assertEquals(new DispatchResult(0, 0, 0), dispatcher.dispatchOnce());
        assertEquals(2, attempts.get(), "死信后不再重试");
    }

    @Test
    void aSucceededAttemptAfterEarlierFailureClearsTheRetryLedger() {
        when(directory.enabledTenants()).thenReturn(List.of(new TenantRef(1L, "demoa")));
        AtomicInteger attempts = new AtomicInteger();
        subscribersAre(
                subscriberFor(
                        null,
                        event -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("首次投递失败");
                            }
                        }));
        OutboxDispatcher dispatcher = dispatcher(2);
        pendingRowsAre(new OutboxEvent(11L, "user.disabled", "{}", OCCURRED));

        dispatcher.dispatchOnce();
        clock.advanceMillis(RETRY_BASE_MS);
        assertEquals(new DispatchResult(1, 0, 0), dispatcher.dispatchOnce());

        // 同一事件若再次进入 PENDING（如人工重置重投），失败计数应从头开始而不是立即死信
        attempts.set(0);
        AtomicInteger secondRound = new AtomicInteger();
        subscribersAre(
                subscriberFor(
                        null,
                        event -> {
                            if (secondRound.incrementAndGet() == 1) {
                                throw new IllegalStateException("重置后再次失败");
                            }
                        }));
        assertEquals(new DispatchResult(0, 1, 0), dispatcher.dispatchOnce());
        clock.advanceMillis(RETRY_BASE_MS * 2);
        assertEquals(new DispatchResult(1, 0, 0), dispatcher.dispatchOnce(), "计数清零后可完整重试");
        verify(jdbc, times(2)).update(contains("status = 'SENT'"), eq(11L));
    }

    // ---------------------------------------------------------------- 租户扇出与上下文

    @Test
    void oneTenantDatabaseFailureDoesNotStopTheOthers() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));
        AtomicInteger delivered = new AtomicInteger();
        subscribersAre(subscriberFor(null, event -> delivered.incrementAndGet()));
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<OutboxEvent>>any(),
                        any()))
                .thenThrow(new RuntimeException("租户库不可达"))
                .thenReturn(List.of(new OutboxEvent(12L, "user.disabled", "{}", OCCURRED)));

        DispatchResult result = dispatcher(6).dispatchOnce();

        assertEquals(new DispatchResult(1, 0, 0), result, "另一租户照常分发");
        verify(jdbc).update(contains("status = 'SENT'"), eq(12L));
    }

    @Test
    void dispatchForCurrentTenantRejectsMissingAndPlatformContext() {
        subscribersAre(subscriberFor(null, event -> {}));

        assertThrows(
                MissingTenantContextException.class,
                () -> dispatcher(6).dispatchForCurrentTenant(),
                "没有租户上下文不允许访问租户库");

        TenantContext.usePlatform();
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> dispatcher(6).dispatchForCurrentTenant(),
                        "sys_outbox 属租户库，平台上下文直接拒绝");
        assertTrue(ex.getMessage().contains("租户上下文"));
    }
}
