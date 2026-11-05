package com.crdp.system.webhook;

import com.crdp.framework.crypto.CryptoService;

/**
 * Webhook 请求签名（E09-T06；tech-plan §9：请求头带时间戳与 HMAC-SHA256 签名）。
 *
 * <p>签名串 = {@code 时间戳 + "." + 请求体}（时间戳是 Unix 秒），HMAC-SHA256 经 {@link CryptoService}（外部共享密钥， AGENTS
 * §5.3 业务代码不直接调算法库）。接收方校验方式（notify.md §6 已同步）：
 *
 * <pre>{@code
 * expected = hex(HMAC-SHA256(secret, X-CRDP-Timestamp + "." + rawBody))
 * 常量时间比较 expected == X-CRDP-Signature 去掉 "v1=" 前缀
 * }</pre>
 *
 * <p>{@code v1=} 前缀为算法版本预留（换 HMAC-SM3 时升 v2，接收方按前缀分派）。测试侧的独立期望值计算 在各测试内用 JCE 直接实现（验证发送侧确实走了
 * HMAC-SHA256，而不是自证）。
 */
public class WebhookSigner {

    /** 签名头：{@code v1=} + 64 位小写十六进制。 */
    public static final String SIGNATURE_HEADER = "X-CRDP-Signature";

    /** 时间戳头：Unix 秒（接收方同时做 ±5 分钟时钟偏差校验，防重放）。 */
    public static final String TIMESTAMP_HEADER = "X-CRDP-Timestamp";

    /** 事件类型头（接收方路由用，负载里同样有 eventType）。 */
    public static final String EVENT_HEADER = "X-CRDP-Event";

    /** 投递号头（接收方幂等键：同一投递的重试共用一个号）。 */
    public static final String DELIVERY_HEADER = "X-CRDP-Delivery";

    static final String SIGNATURE_PREFIX = "v1=";

    private final CryptoService crypto;

    public WebhookSigner(CryptoService crypto) {
        this.crypto = crypto;
    }

    /** 按契约计算签名值（含 v1= 前缀，即直接放进请求头的值）。 */
    public String signatureHeader(byte[] secret, long timestampEpochSecond, String body) {
        String signed = timestampEpochSecond + "." + body;
        return SIGNATURE_PREFIX + crypto.hmacSha256Hex(secret, signed);
    }
}
