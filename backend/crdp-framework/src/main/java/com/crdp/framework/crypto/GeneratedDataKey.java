package com.crdp.framework.crypto;

import javax.crypto.SecretKey;

/** 新生成的数据密钥：{@code wrappedKey} 是被主密钥包装后可落库的形态（信封外层），{@code key} 是 仅存在于内存的明文密钥。 */
public record GeneratedDataKey(String wrappedKey, SecretKey key) {}
