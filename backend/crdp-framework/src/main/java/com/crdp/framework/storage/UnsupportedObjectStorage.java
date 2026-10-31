package com.crdp.framework.storage;

import org.springframework.stereotype.Component;

@Component
public class UnsupportedObjectStorage implements ObjectStorage {

    @Override
    public String put(String key, byte[] body, String contentType) {
        throw new UnsupportedOperationException("对象存储将在 E10 实现");
    }
}
