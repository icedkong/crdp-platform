package com.crdp.framework.outbox;

import com.crdp.framework.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 事务外发箱 · 租户库落表实现（E06-T03 / PLT-ORG-013 起启用，替换 M0 占位）。
 *
 * <p>在<b>当前事务内</b>把领域事件写入租户库 {@code sys_outbox}（PENDING）：业务提交则事件一并提交、
 * 业务回滚则事件回滚——「变更发事件」不依赖调用方记得补发。分发（PENDING → SENT、投递给订阅方） 属 E09 通知任务，本实现只保证落表。
 *
 * <p>只能在<b>租户上下文</b>内调用（sys_outbox 每个租户库一张，平台库没有同名表）；平台侧事件 （如安全告警发平台管理员，PLT-AUD-012）等 E09
 * 定义平台侧发箱时再扩展，此处直接拒绝避免静默丢失。
 */
@Component
public class JdbcOutboxPublisher implements OutboxPublisher {

    private final JdbcTemplate jdbc;

    public JdbcOutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void publish(String type, String payload) {
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            throw new IllegalStateException("outbox 仅在租户上下文内发布（sys_outbox 属租户库）；平台侧事件随 E09 平台发箱落地");
        }
        jdbc.update("INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)", type, payload);
    }
}
