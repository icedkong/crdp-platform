package com.crdp.framework.outbox;

/** 事务外发箱：业务事务内落表（E06-T03 起 {@link JdbcOutboxPublisher}，租户库 sys_outbox），分发随 E09。 */
public interface OutboxPublisher {

    void publish(String type, String payload);
}
