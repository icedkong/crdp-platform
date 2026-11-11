package com.crdp.framework.crypto;

/**
 * 整包字节加密端口（E05-T08 / PLT-TEN-019、021）：迁出包与法定保留封存包的 AES-256 加密容器。
 *
 * <p>与 {@link CryptoService}（字段级信封加密，密钥来自每租户数据密钥）互补：这里是「整包加密压缩」—— 调用方生成一次性解压密码 （{@link
 * #randomPassword()}），包体用该密码经 PBKDF2 派生的 AES-256-GCM 密钥加密； 密码本身再经 CryptoService 信封加密入库。
 * 业务代码不直接调用算法库（AGENTS §5.3），算法集中在框架实现 {@link AesGcmArchiveCipher}（JDK 自带，无第三方依赖）。
 *
 * <p>容器格式（.crdpa）：{@code "CRDPA1\0" + salt(16) + iv(12) + AES-256-GCM 密文与认证标签}。 错误密码或篡改在 GCM
 * 认证标签处失败，整包拒绝。
 */
public interface ArchiveCipher {

    /** 生成一次性解压密码（≥16 字符，字母数字，供短信下发与手工输入）。 */
    String randomPassword();

    /** 用密码派生密钥封装整包明文。 */
    byte[] seal(byte[] plain, char[] password);

    /**
     * 解开封装。密码错误或容器被篡改抛 {@link IllegalArgumentException}（GCM 认证失败）。
     *
     * @param sealed {@link #seal} 产出的容器字节
     */
    byte[] open(byte[] sealed, char[] password);
}
