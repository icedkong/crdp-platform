package com.fu.framework.outbox;

import org.springframework.stereotype.Component;

@Component
public class UnsupportedOutboxPublisher implements OutboxPublisher {

    @Override
    public void publish(String type, String payload) {
        throw new UnsupportedOperationException("事务外发箱将在 E09 实现");
    }
}
