package com.crdp.framework.crypto;

/**
 * 哈希服务组件（NFR-CRY-002 / NFR-CRY-006）：完整性哈希统一经此接口计算，业务与框架代码不直接调用算法库。
 *
 * <p>当前实现 SHA-256（NFR-CRY-001 首期算法）；切换国密 SM3 时替换实现即可，审计哈希链的存量重算另行走 重算作业（与 E03 密钥轮换同法），本接口不感知密钥。
 */
public interface DigestService {

    /** 返回输入字符串 UTF-8 字节的 SHA-256 摘要，64 位小写十六进制。 */
    String hashHex(String input);
}
