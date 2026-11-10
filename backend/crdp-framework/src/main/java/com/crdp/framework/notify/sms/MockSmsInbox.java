package com.crdp.framework.notify.sms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/** 最近若干条模拟短信，进程内保存，重启即清空。只在 mock 通道写入。 */
@Component
public class MockSmsInbox {

    static final int CAPACITY = 50;

    private final Deque<MockSmsMessage> recent = new ArrayDeque<>();

    public synchronized void add(MockSmsMessage message) {
        recent.addFirst(message);
        while (recent.size() > CAPACITY) {
            recent.removeLast();
        }
    }

    public synchronized List<MockSmsMessage> recent() {
        return new ArrayList<>(recent);
    }
}
