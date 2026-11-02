package com.crdp.followup.file.infra.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * E10-T05 下载签名链接（{@link FileDownloadSigner}）单元测试：签发 / 验签往返、防篡改（改有效期、换签名段）、 过期失效、密钥不符与负载结构不合法的 fail
 * closed 口径（任何解析失败一律空，调用方按 40400 处理，不暴露存在性）。
 */
class FileDownloadSignerTest {

    /** 32 字节开发测试密钥（Base64）。 */
    private static final String KEY = Base64.getEncoder().encodeToString("k".repeat(32).getBytes());

    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("j".repeat(32).getBytes());

    private final FileDownloadSigner signer = new FileDownloadSigner(KEY);

    /** 签发 → 验签往返：负载带版本、租户、文件与到期时间，链接在有效期内可用。 */
    @Test
    void signAndVerifyRoundTrip() {
        Instant expires = Instant.now().plusSeconds(300);
        String token = signer.sign(1L, "demoa", 9L, expires);

        Optional<FileDownloadSigner.Grant> grant = signer.verify(token, Instant.now());
        assertTrue(grant.isPresent());
        assertEquals(1L, grant.orElseThrow().tenantId());
        assertEquals("demoa", grant.orElseThrow().tenantCode());
        assertEquals(9L, grant.orElseThrow().fileId());
        assertEquals(expires.getEpochSecond(), grant.orElseThrow().expiresAtEpochSecond());
    }

    /** 到期时间进签名：把负载里的到期改远、签名不动 → 验签失败（短时有效不可顺延）。 */
    @Test
    void tamperedExpiryRejected() {
        String token = signer.sign(1L, "demoa", 9L, Instant.now().plusSeconds(300));
        String[] parts = token.split("\\.");
        String payload =
                new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String stretched = payload.replaceAll("\\|\\d+$", "|9999999999");
        String forged =
                Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(stretched.getBytes(StandardCharsets.UTF_8))
                        + "."
                        + parts[1];
        assertFalse(signer.verify(forged, Instant.now()).isPresent());
    }

    /** 签名段被替换（拼另一个合法链接的签名）→ 失败。 */
    @Test
    void swappedSignatureRejected() {
        String tokenA = signer.sign(1L, "demoa", 9L, Instant.now().plusSeconds(300));
        String tokenB = signer.sign(1L, "demoa", 10L, Instant.now().plusSeconds(300));
        String forged =
                tokenA.substring(0, tokenA.indexOf('.'))
                        + "."
                        + tokenB.substring(tokenB.indexOf('.') + 1);
        assertFalse(signer.verify(forged, Instant.now()).isPresent());
    }

    /** 另一把密钥签的链接 → 失败（伪造 / 密钥轮换后的旧链接）。 */
    @Test
    void foreignKeyRejected() {
        FileDownloadSigner other = new FileDownloadSigner(OTHER_KEY);
        String token = other.sign(1L, "demoa", 9L, Instant.now().plusSeconds(300));
        assertFalse(signer.verify(token, Instant.now()).isPresent());
    }

    /** 过期链接（到期时间已过）→ 空（tech-plan §10「5 分钟有效」）。 */
    @Test
    void expiredTokenRejected() {
        String token = signer.sign(1L, "demoa", 9L, Instant.now().minusSeconds(1));
        assertFalse(signer.verify(token, Instant.now()).isPresent());
    }

    /** 格式不对（null / 空 / 多点号 / 空段 / 非 base64url 字符）→ 不抛异常，一律空。 */
    @Test
    void malformedTokenRejectedQuietly() {
        assertFalse(signer.verify(null, Instant.now()).isPresent());
        assertFalse(signer.verify("", Instant.now()).isPresent());
        assertFalse(signer.verify("a.b.c", Instant.now()).isPresent());
        assertFalse(signer.verify(".sig", Instant.now()).isPresent());
        assertFalse(signer.verify("payload.", Instant.now()).isPresent());
        assertFalse(signer.verify("../etc/passwd", Instant.now()).isPresent());
    }

    /** 负载结构不合法但**签名正确**（版本不对 / 段数不对 / 非数字 / 租户编码为空）→ 空： 验签不只看 MAC，结构校验同样 fail closed。 */
    @Test
    void corruptedPayloadRejectedDespiteValidSignature() {
        assertFalse(signer.verify(signed("v0|1|demoa|9|9999999999"), Instant.now()).isPresent());
        assertFalse(signer.verify(signed("v1|1|demoa|9"), Instant.now()).isPresent());
        assertFalse(signer.verify(signed("v1|one|demoa|9|9999999999"), Instant.now()).isPresent());
        assertFalse(signer.verify(signed("v1|1||9|9999999999"), Instant.now()).isPresent());
    }

    /** 密钥配置不合法（空 / 非 Base64 / 短于 32 字节）启动即失败，不放行弱签名。 */
    @Test
    void weakKeyRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new FileDownloadSigner(""));
        assertThrows(IllegalStateException.class, () -> new FileDownloadSigner("not-base64!!"));
        String shortKey = Base64.getEncoder().encodeToString("k".repeat(16).getBytes());
        assertThrows(IllegalStateException.class, () -> new FileDownloadSigner(shortKey));
    }

    // ---------------------------------------------------------------- 工具

    /** 测试自己在本地用同一密钥对任意负载做 HMAC，构造「签名正确但负载结构不合法」的链接。 */
    private static String signed(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
