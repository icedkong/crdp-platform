package com.crdp.system.webhook;

import java.net.URI;
import java.util.Map;

/**
 * Webhook 出站 HTTP 口（E09-T06）。生产实现 {@link HttpWebhookTransport}（java.net.http，禁跟随重定向—— 防「公网 302 →
 * 内网」绕过 SSRF 校验）；单元测试注入假实现验证签名与重试逻辑，不触网。
 *
 * <p>返回 {@link Attempt}：HTTP 状态码（网络异常为 null）与失败类别（台账 error 只写类别，不写 URL 与响应体）。
 */
public interface WebhookTransport {

    /** 单次投递尝试结果：2xx 视为成功。 */
    record Attempt(Integer status, String errorCategory) {

        public static Attempt ok(int status) {
            return new Attempt(status, null);
        }

        public boolean success() {
            return status != null && status >= 200 && status < 300;
        }
    }

    /** 发送一次 POST。实现不得跟随重定向、不得重试（重试策略在 {@code WebhookDeliveryService}）， 连接 / 读取超时自行设置。 */
    Attempt post(URI url, Map<String, String> headers, String body);
}
