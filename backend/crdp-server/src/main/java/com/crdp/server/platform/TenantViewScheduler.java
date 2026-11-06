package com.crdp.server.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 视角会话收尾调度（E20-T01 / PLT-VIEW-004）：每分钟扫一次 ACTIVE 台账——到时（开始 + 2 小时）按最晚结束 时间收尾、空闲 15 分钟被 Redis
 * 逐出的按当前时间收尾，结束方式写入台账与平台访问审计。视角令牌本身由 Redis TTL 刧行失效（下一次请求 40100），本调度只补记「到时 /
 * 空闲超时」的结束方式与会话摘要，不影响令牌生效。
 */
@Component
public class TenantViewScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenantViewScheduler.class);

    private final TenantViewService viewService;

    public TenantViewScheduler(TenantViewService viewService) {
        this.viewService = viewService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void finalizeExpired() {
        try {
            viewService.finalizeExpiredSessions();
        } catch (RuntimeException ex) {
            // 定时入口不让异常外溢（不影响调度线程的后续任务）；台账为幂等收尾，下轮重扫即可
            log.error("视角会话收尾执行失败", ex);
        }
    }
}
