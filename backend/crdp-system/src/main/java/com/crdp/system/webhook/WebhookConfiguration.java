package com.crdp.system.webhook;

import com.crdp.framework.crypto.CryptoService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook 组件装配（E09-T06）。{@code crdp.webhook.allow-private-addresses} 默认 false： 只在本机联调 / 集成测试把接收方起在
 * 127.0.0.1 时打开（生产保持内网拒绝，验收口径不受影响）。
 */
@Configuration
public class WebhookConfiguration {

    @Bean
    public WebhookSsrfGuard webhookSsrfGuard(
            @Value("${crdp.webhook.allow-private-addresses:false}")
                    boolean allowPrivateForLocalTest) {
        return new WebhookSsrfGuard(allowPrivateForLocalTest);
    }

    @Bean
    public WebhookSigner webhookSigner(CryptoService crypto) {
        return new WebhookSigner(crypto);
    }

    @Bean
    public WebhookTransport webhookTransport(
            @Value("${crdp.webhook.request-timeout-ms:10000}") long requestTimeoutMs) {
        return new HttpWebhookTransport(Duration.ofMillis(Math.max(1000, requestTimeoutMs)));
    }
}
