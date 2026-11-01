package com.crdp.framework.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * E08-T02 / NFR-CRY-001、006：哈希与完整性经密码服务组件计算。SHA-256 摘要组件输出稳定的 小写十六进制值（审计哈希链 PLT-AUD-008
 * 使用），同一输入必须得到同一输出（验链重算的前提）。
 */
class Sha256DigestServiceTest {

    private final Sha256DigestService digest = new Sha256DigestService();

    @Test
    void sameInputProducesSameHexDigest() {
        assertThat(digest.hashHex("crdp")).isEqualTo(digest.hashHex("crdp"));
    }

    @Test
    void outputIs64CharLowercaseHex() {
        String value = digest.hashHex("审计哈希链（示例）");
        assertThat(value).matches("[0-9a-f]{64}");
    }

    @Test
    void differentInputsProduceDifferentDigests() {
        assertThat(digest.hashHex("a")).isNotEqualTo(digest.hashHex("b"));
    }

    @Test
    void matchesWellKnownSha256Vector() {
        // SHA-256("abc") 的标准测试向量，验证算法正确性
        assertThat(digest.hashHex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223" + "b00361a396177a9cb410ff61f20015ad");
    }
}
