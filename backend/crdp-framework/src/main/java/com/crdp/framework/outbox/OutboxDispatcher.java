package com.crdp.framework.outbox;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * sys_outbox 分发器（E09-T01 / PLT-CFG-005 通知渠道底座；验收：业务提交后消息必达，失败重试）。
 *
 * <p>业务事务内 {@link OutboxPublisher} 落表的事件，由本分发器按租户逐个消费：PENDING 行按 id 序投递给全部 感兴趣的 {@link
 * OutboxSubscriber}，全部成功后标记 SENT；任一订阅方失败则留在 PENDING，按指数退避重试（基准 {@code
 * crdp.outbox.dispatch.retry-base-ms}，默认 5 秒，逐次翻倍封顶 {@code max-backoff-ms}），重试 {@code max-attempts}
 * 次仍失败转入死信（status='FAILED'），等运维排查后人工重置重投。
 *
 * <p>设计取舍（OQ-52 暂行做法：消费 PENDING 行，<b>sys_outbox 表结构不变</b>）：
 *
 * <ul>
 *   <li>重试台账（失败次数与下次到期时间）只在内存——重启后清零，事件不丢（行仍是 PENDING），只是退避与死信计数从头再来； 需要跨重启的持久台账就得加列 / 加表，属产品拍板；
 *   <li>「必达」是至少一次：标记 SENT 前进程中断会重发，订阅方必须幂等（见 {@link OutboxSubscriber}）；
 *   <li>应用里一个订阅方都没有时不消费（保持 PENDING），避免订阅方落地（E09-T02+）之前把 E06-T03 已写入的事件标记 SENT 而丢失；
 *   <li>M1 单实例、分发线程串行（ADR 0003 第 4 条），不做并发抢占；多实例时按 shedlock 预留补锁。
 * </ul>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** 退避移位上限，防止配置极端值时 long 溢出。 */
    private static final int MAX_BACKOFF_SHIFT = 30;

    private static final String SELECT_PENDING =
            "SELECT id, event_type, payload, created_at FROM sys_outbox"
                    + " WHERE status = 'PENDING' ORDER BY id LIMIT ?";

    private static final String MARK_SENT =
            "UPDATE sys_outbox SET status = 'SENT' WHERE id = ? AND status = 'PENDING'";

    private static final String MARK_FAILED =
            "UPDATE sys_outbox SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'";

    private static final RowMapper<OutboxEvent> EVENT_MAPPER = (rs, rowNum) -> mapEvent(rs);

    private final ObjectProvider<OutboxSubscriber> subscribers;
    private final JdbcTemplate jdbc;
    private final TenantDirectory directory;
    private final Clock clock;
    private final int batchSize;
    private final long retryBaseMs;
    private final long maxBackoffMs;
    private final int maxAttempts;

    /** 重试台账：租户键 + 事件 id → 已失败次数与下次到期时间；行离开 PENDING 即清除。 */
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();

    @Autowired
    public OutboxDispatcher(
            ObjectProvider<OutboxSubscriber> subscribers,
            JdbcTemplate jdbc,
            TenantDirectory directory,
            @Value("${crdp.outbox.dispatch.batch-size:200}") int batchSize,
            @Value("${crdp.outbox.dispatch.retry-base-ms:5000}") long retryBaseMs,
            @Value("${crdp.outbox.dispatch.max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${crdp.outbox.dispatch.max-attempts:6}") int maxAttempts) {
        this(
                subscribers,
                jdbc,
                directory,
                Clock.systemDefaultZone(),
                batchSize,
                retryBaseMs,
                maxBackoffMs,
                maxAttempts);
    }

    OutboxDispatcher(
            ObjectProvider<OutboxSubscriber> subscribers,
            JdbcTemplate jdbc,
            TenantDirectory directory,
            Clock clock,
            int batchSize,
            long retryBaseMs,
            long maxBackoffMs,
            int maxAttempts) {
        this.subscribers = subscribers;
        this.jdbc = jdbc;
        this.directory = directory;
        this.clock = clock;
        this.batchSize = batchSize;
        this.retryBaseMs = retryBaseMs;
        this.maxBackoffMs = maxBackoffMs;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 对全部启用 / 试用 / 宽限期租户各跑一轮分发（租户清单经 {@link TenantDirectory}，口径与每日作业一致）。 单个租户库不可达只记
     * WARN，不影响其他租户。定时注册在 crdp-server（默认每 5 秒，技术方案第 9 章）。
     */
    public DispatchResult dispatchOnce() {
        List<OutboxSubscriber> registered = subscribers.orderedStream().toList();
        if (registered.isEmpty()) {
            // 尚无任何订阅方：不消费，事件保持 PENDING 等订阅方（E09-T02+）落地
            log.debug("outbox 无订阅方，本轮跳过（事件保持 PENDING）");
            return DispatchResult.EMPTY;
        }
        int dispatched = 0;
        int pendingRetry = 0;
        int deadLettered = 0;
        for (TenantRef tenant : directory.enabledTenants()) {
            try {
                DispatchResult one =
                        TenantContext.callAsTenant(
                                tenant.tenantId(),
                                tenant.tenantCode(),
                                () -> dispatchForCurrentTenant(registered));
                dispatched += one.dispatched();
                pendingRetry += one.pendingRetry();
                deadLettered += one.deadLettered();
            } catch (RuntimeException ex) {
                // 租户库不可达等：本租户本轮作废（行仍 PENDING），继续其他租户
                log.warn("outbox 分发失败，本租户本轮跳过 tenant={}", tenant.tenantCode(), ex);
            }
        }
        return new DispatchResult(dispatched, pendingRetry, deadLettered);
    }

    /**
     * 分发当前租户库的 PENDING 事件。必须在租户上下文内调用（sys_outbox 属租户库，平台上下文与无上下文直接拒绝， 与 {@link JdbcOutboxPublisher}
     * 同口径）。
     */
    public DispatchResult dispatchForCurrentTenant() {
        return dispatchForCurrentTenant(subscribers.orderedStream().toList());
    }

    private DispatchResult dispatchForCurrentTenant(List<OutboxSubscriber> registered) {
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            throw new IllegalStateException("outbox 分发只在租户上下文内执行（sys_outbox 属租户库）");
        }
        if (registered.isEmpty()) {
            return DispatchResult.EMPTY;
        }
        List<OutboxEvent> pending = jdbc.query(SELECT_PENDING, EVENT_MAPPER, batchSize);
        int dispatched = 0;
        int pendingRetry = 0;
        int deadLettered = 0;
        for (OutboxEvent event : pending) {
            String ledgerKey = TenantContext.lookupKey() + ":" + event.id();
            Retry previous = retries.get(ledgerKey);
            if (previous != null && clock.instant().isBefore(previous.nextDue())) {
                pendingRetry++;
                continue;
            }
            List<OutboxSubscriber> interested =
                    registered.stream().filter(s -> s.supports(event.eventType())).toList();
            try {
                for (OutboxSubscriber subscriber : interested) {
                    subscriber.onEvent(event);
                }
            } catch (RuntimeException ex) {
                int failures = (previous == null ? 0 : previous.failures()) + 1;
                if (failures >= maxAttempts) {
                    jdbc.update(MARK_FAILED, event.id());
                    retries.remove(ledgerKey);
                    deadLettered++;
                    log.error(
                            "outbox 事件重试 {} 次仍失败，转入死信 status=FAILED tenant={} id={} type={}",
                            failures,
                            state.tenantCode(),
                            event.id(),
                            event.eventType(),
                            ex);
                } else {
                    retries.put(
                            ledgerKey,
                            new Retry(failures, clock.instant().plusMillis(backoffOf(failures))));
                    pendingRetry++;
                    log.warn(
                            "outbox 事件投递失败，留在 PENDING 退避重试 tenant={} id={} type={} 失败 {}/{}",
                            state.tenantCode(),
                            event.id(),
                            event.eventType(),
                            failures,
                            maxAttempts,
                            ex);
                }
                continue;
            }
            jdbc.update(MARK_SENT, event.id());
            retries.remove(ledgerKey);
            dispatched++;
        }
        return new DispatchResult(dispatched, pendingRetry, deadLettered);
    }

    private long backoffOf(int failures) {
        long shift = Math.min(failures - 1, MAX_BACKOFF_SHIFT);
        long backoff = retryBaseMs << shift;
        return Math.min(backoff, maxBackoffMs);
    }

    private static OutboxEvent mapEvent(ResultSet rs) throws SQLException {
        return new OutboxEvent(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                Instant.ofEpochMilli(rs.getTimestamp("created_at").getTime()));
    }

    /** 重试台账条目：已连续失败次数与下一次应尝试的时间。 */
    private record Retry(int failures, Instant nextDue) {}
}
