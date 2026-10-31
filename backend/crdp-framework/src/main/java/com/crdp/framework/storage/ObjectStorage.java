package com.crdp.framework.storage;

/** 对象存储。每院一个桶、SSE-KMS 在 E10 接入，M0 不放云凭证。 */
public interface ObjectStorage {

    String put(String key, byte[] body, String contentType);
}
