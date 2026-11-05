package com.crdp.system.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 生产 Webhook 出站适配（java.net.http）。
 *
 * <p>安全口径：{@code NEVER} 跟随重定向（重定向目标不经 SSRF 校验，直接封死）；连接 5 秒、请求 10 秒； 响应体不读内容只看状态码（接收方摘要不入库）。
 * IOException / InterruptedException 归类为网络失败交上层退避。
 */
public class HttpWebhookTransport implements WebhookTransport {

    private final HttpClient client =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private final Duration requestTimeout;

    public HttpWebhookTransport(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Override
    public Attempt post(URI url, Map<String, String> headers, String body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(url)
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body, java.nio.charset.StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        try {
            HttpResponse<Void> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return new Attempt(response.statusCode(), null);
        } catch (IOException io) {
            return new Attempt(null, "网络异常（连接或读取失败）");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Attempt(null, "投递线程被中断");
        }
    }
}
