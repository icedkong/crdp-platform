package com.crdp.server.notify;

import com.crdp.framework.outbox.DispatchResult;
import com.crdp.framework.outbox.OutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * outbox 分发的定时注册（E09-T01 / PLT-CFG-005）。技术方案第 9 章：分发器每 5 秒轮询（可用 {@code
 * crdp.outbox.dispatch.interval-ms} 调整）；租户扇出、退避重试与死信在 {@link OutboxDispatcher}。
 *
 * <p>应用启动就绪后再跑一轮：尽快消化停机期间积压的 PENDING 事件（幂等，订阅方自行去重）。定时入口不让异常外溢， 失败详情已按租户 / 按事件记 WARN / ERROR。
 */
@Component
public class OutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

    private final OutboxDispatcher dispatcher;

    public OutboxDispatchScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${crdp.outbox.dispatch.interval-ms:5000}")
    public void poll() {
        run("poll");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        run("startup");
    }

    private void run(String trigger) {
        try {
            DispatchResult result = dispatcher.dispatchOnce();
            if (result.total() > 0) {
                log.info(
                        "outbox 分发完成 trigger={} dispatched={} retryPending={} deadLettered={}",
                        trigger,
                        result.dispatched(),
                        result.pendingRetry(),
                        result.deadLettered());
            }
        } catch (RuntimeException ex) {
            log.error("outbox 分发执行失败 trigger={}", trigger, ex);
        }
    }
}
