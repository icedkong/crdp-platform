package com.crdp.framework.notify;

import org.springframework.stereotype.Component;

@Component
public class UnsupportedNotifySender implements NotifySender {

    @Override
    public void send(String channel, String target, String templateCode) {
        throw new UnsupportedOperationException("通知发送将在 E09 实现");
    }
}
