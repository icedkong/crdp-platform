package com.crdp.framework.crypto;

import javax.crypto.SecretKey;

/**
 * 主密钥操作的唯一缝（信封外层）。local 实现进程内包装；生产用华为云 DEW/KMS 每院一个主密钥。 wrappedKey 对调用方不透明，实现自行决定其格式。见 ADR 0004。
 */
public interface KeyProvider {

    /** 随机生成一把数据密钥并返回包装后的形态与明文。 */
    GeneratedDataKey generateDataKey();

    /** 解开包装形态，得到明文数据密钥。 */
    SecretKey unwrap(String wrappedKey);
}
