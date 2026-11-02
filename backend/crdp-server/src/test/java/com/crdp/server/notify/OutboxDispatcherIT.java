package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.outbox.OutboxDispatcher;
import com.crdp.framework.outbox.OutboxEvent;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.outbox.OutboxSubscriber;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E09-T01 端到端（完整应用 + 真实 MySQL/Redis）。验收原文：业务提交后消息必达，失败重试。
 *
 * <ol>
 *   <li>事务外发：业务事务内 publish，提交则事件可见，回滚则事件消失；
 *   <li>必达：dispatchOnce 后 demoa / demob 两院的事件都投给订阅方（在各自租户上下文内）并标记 SENT；
 *   <li>失败重试：订阅方首次抛错 → 行留 PENDING，退避（{@code retry-base-ms=50}）到期后重试成功 → SENT；
 *   <li>死信：持续失败（{@code max-attempts=2}）两次后转 FAILED，不再重试。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。轮询间隔拉到 1 小时，由测试手动触发分发， 避免后台调度与断言竞争。数据全部虚构。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.outbox.dispatch.interval-ms=3600000",
            "crdp.outbox.dispatch.retry-base-ms=50",
            "crdp.outbox.dispatch.max-attempts=2"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class OutboxDispatcherIT {

    /** 订阅方收到的事件：tenantKey|eventType|payload。 */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    /** 首次投递抛错的事件 id（重试可成功）。 */
    private static final Set<Long> FAIL_ONCE = ConcurrentHashMap.newKeySet();

    /** 持续抛错的事件 id（验证死信）。 */
    private static final Set<Long> FAIL_FOREVER = ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicInteger POISON_ATTEMPTS =
            new java.util.concurrent.atomic.AtomicInteger();

    @TestConfiguration
    static class RecordingSubscriberConfig {

        @Bean
        OutboxSubscriber recordingOutboxSubscriber() {
            return new OutboxSubscriber() {
                @Override
                public boolean supports(String eventType) {
                    return true;
                }

                @Override
                public void onEvent(OutboxEvent event) {
                    if (FAIL_FOREVER.contains(event.id())) {
                        POISON_ATTEMPTS.incrementAndGet();
                        throw new IllegalStateException("持续投递失败（示例）");
                    }
                    if (FAIL_ONCE.remove(event.id())) {
                        throw new IllegalStateException("首次投递失败（示例）");
                    }
                    RECEIVED.add(
                            TenantContext.lookupKey()
                                    + "|"
                                    + event.eventType()
                                    + "|"
                                    + event.payload());
                }
            };
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(tenant, () -> jdbc.update("DELETE FROM sys_outbox"));
        }
        RECEIVED.clear();
        FAIL_ONCE.clear();
        FAIL_FOREVER.clear();
        POISON_ATTEMPTS.set(0);
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        for (String tenant : new String[] {"demoa", "demob"}) {
            TenantContext.runAsTenant(
                    "demob".equals(tenant) ? 2L : 1L,
                    tenant,
                    () -> jdbc.update("DELETE FROM sys_outbox"));
        }
    }

    // ---------------------------------------------------------------- 验收 1：业务提交后消息必达

    @Test
    void committedBusinessEventsReachSubscribersAndAreMarkedSent() {
        // demoa：业务事务内发布；回滚的事务不发事件（事务外发）
        runInTenant(
                "demoa",
                () -> {
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.executeWithoutResult(
                            status ->
                                    publisher.publish(
                                            "it.outbox.evt", "{\"marker\":\"demoa-ok\"}"));
                    try {
                        tx.executeWithoutResult(
                                status -> {
                                    publisher.publish(
                                            "it.outbox.rollback", "{\"marker\":\"rolled-back\"}");
                                    throw new IllegalStateException("业务回滚（示例）");
                                });
                        throw new AssertionError("回滚事务的异常应传播出 TransactionTemplate");
                    } catch (IllegalStateException expected) {
                        // 业务抛错 → 事务回滚 → 事件一并消失
                    }
                });
        // demob：另一院库里的事件
        runInTenant(
                "demob",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)",
                                "it.outbox.evt-b",
                                "{\"marker\":\"demob-ok\"}"));

        assertEquals(1, countPending("demoa", "it.outbox.evt"), "提交后事件在 sys_outbox 等 PENDING");
        assertEquals(0, countPending("demoa", "it.outbox.rollback"), "回滚事务不产生事件");
        assertTrue(RECEIVED.isEmpty(), "分发前订阅方不应收到");

        dispatcher.dispatchOnce();

        assertTrue(
                delivered("tenant:1", "it.outbox.evt", "demoa-ok"),
                "demoa 事件在租户 1 上下文内投递：" + RECEIVED);
        assertTrue(
                delivered("tenant:2", "it.outbox.evt-b", "demob-ok"),
                "demob 事件在租户 2 上下文内投递：" + RECEIVED);
        assertEquals(2, RECEIVED.size(), "只有两条事件、各投一次：" + RECEIVED);
        assertEquals("SENT", statusOf("demoa", "it.outbox.evt"), "全部订阅方成功后标记 SENT");
        assertEquals("SENT", statusOf("demob", "it.outbox.evt-b"));
    }

    // ---------------------------------------------------------------- 验收 2：失败重试

    @Test
    void failedDeliveryStaysPendingThenSucceedsAfterBackoff() {
        long id =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)",
                                    "it.outbox.flaky",
                                    "{\"marker\":\"flaky\"}");
                            return latestId("it.outbox.flaky");
                        });
        FAIL_ONCE.add(id);

        dispatcher.dispatchOnce();

        assertEquals("PENDING", statusOf("demoa", "it.outbox.flaky"), "投递失败留在 PENDING");
        assertFalse(
                RECEIVED.stream().anyMatch(line -> line.contains("it.outbox.flaky")),
                "失败的那次不算送达：" + RECEIVED);

        awaitBackoff();
        dispatcher.dispatchOnce();

        assertEquals("SENT", statusOf("demoa", "it.outbox.flaky"), "退避到期后重试成功");
        assertTrue(delivered("tenant:1", "it.outbox.flaky", "flaky"), "重试后送达一次：" + RECEIVED);
    }

    @Test
    void exhaustedRetriesEndInDeadLetterAndStop() {
        long id =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)",
                                    "it.outbox.poison",
                                    "{\"marker\":\"poison\"}");
                            return latestId("it.outbox.poison");
                        });
        FAIL_FOREVER.add(id);

        dispatcher.dispatchOnce();
        assertEquals("PENDING", statusOf("demoa", "it.outbox.poison"));
        awaitBackoff();
        dispatcher.dispatchOnce();
        assertEquals("FAILED", statusOf("demoa", "it.outbox.poison"), "两次失败后转入死信");

        awaitBackoff();
        dispatcher.dispatchOnce();
        assertEquals("FAILED", statusOf("demoa", "it.outbox.poison"), "死信后不再重试");
        assertEquals(2, POISON_ATTEMPTS.get(), "总共只尝试 max-attempts 次");
    }

    // ---------------------------------------------------------------- 辅助

    /** MySQL JSON 列读回会规范化空白，断言按「租户键 + 事件类型 + 标记」匹配。 */
    private static boolean delivered(String tenantKey, String eventType, String marker) {
        return RECEIVED.stream()
                .anyMatch(
                        line ->
                                line.startsWith(tenantKey + "|")
                                        && line.contains("|" + eventType + "|")
                                        && line.contains(marker));
    }

    /** 退避基准 50ms，等 300ms 足够到期（避免时序抖动导致的偶发失败）。 */
    private static void awaitBackoff() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private int countPending(String tenant, String type) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = ? AND status = 'PENDING'",
                                Integer.class,
                                type));
    }

    private String statusOf(String tenant, String type) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM sys_outbox WHERE event_type = ?"
                                        + " ORDER BY id DESC LIMIT 1",
                                String.class,
                                type));
    }

    private long latestId(String type) {
        return jdbc.queryForObject(
                "SELECT id FROM sys_outbox WHERE event_type = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                type);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
