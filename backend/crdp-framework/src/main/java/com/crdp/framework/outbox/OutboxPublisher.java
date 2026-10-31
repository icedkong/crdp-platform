package com.crdp.framework.outbox;

/** 事务外发箱。E09 再实现落表与分发，M0 不引入消息队列。 */
public interface OutboxPublisher {

    void publish(String type, String payload);
}
