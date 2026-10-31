package com.crdp.framework.notify;

/** 站内信、短信、Webhook 的发送口。M0 不接腾讯云短信。 */
public interface NotifySender {

    void send(String channel, String target, String templateCode);
}
