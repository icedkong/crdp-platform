package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.outbox.OutboxDispatcher;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.system.webhook.WebhookDeliveryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E09-T06 端到端（二）· A-NTF-008、C-NTF-008、PLT-CFG-007：投递链路（真库 + 完整应用 + 本地接收方）。
 *
 * <p>覆盖：验收「签名可校验」——sys_outbox 事件 → 分发器 → Webhook 订阅方入队 → 轮询投递到本地接收方， 请求头 X-CRDP-Signature 用测试内独立实现的
 * HMAC-SHA256 验签（RFC 契约：hex(HMAC(secret, ts + "." + body))）； 负载只含事件类型与对象 ID；失败指数退避重试后成功；重试耗尽 FAILED
 * 可手动重投；投递记录按状态查询。 test 接口回显的签名与实际请求一致。
 *
 * <p>本地接收方是明文 HTTP（HttpServer 起不了 TLS），因此打开 {@code crdp.webhook.allow-private-addresses=true}
 * 联调开关（默认关——生产保持只 HTTPS + 内网拒绝，见 WebhookConfigIT 与 WebhookSsrfGuardTest）。 轮询与分发都由测试手动触发（间隔拉到 1
 * 小时），退避基准压到 1 秒（服务侧下限）。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.outbox.dispatch.interval-ms=3600000",
            "crdp.webhook.allow-private-addresses=true",
            // 本上下文显式保留真实轮询作业（测试 classpath 初始化器对其他 IT 上下文默认关闭：
            // 多缓存上下文共享租户库，未开联调开关的轮询会把 127.0.0.1 投递行按内网拒绝定格 FAILED）
            "crdp.webhook.delivery.scheduler-enabled=true",
            "crdp.webhook.delivery.tick-ms=3600000",
            "crdp.webhook.retry-base-ms=1000"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookDeliveryIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";
    private static final String SECRET = "it-delivery-secret-32-bytes-example!";

    /** 接收方收到的一次请求：头 + 体。 */
    record Received(Map<String, String> headers, String body) {}

    private static final ConcurrentLinkedQueue<Received> RECEIVED = new ConcurrentLinkedQueue<>();

    /** 响应脚本（逐条消费；空 = 200）。 */
    private static final ConcurrentLinkedQueue<Integer> SCRIPT = new ConcurrentLinkedQueue<>();

    private HttpServer server;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private WebhookDeliveryService delivery;

    /** 生产装配回归锁：本上下文显式 scheduler-enabled=true，轮询作业必须在（其余 IT 上下文默认关，见 WebhookConfigIT）。 */
    @Autowired private WebhookDeliveryScheduler schedulerBean;

    @BeforeEach
    void startClean() throws IOException {
        runInTenantA(
                () -> {
                    jdbc.update("DELETE FROM sys_webhook_delivery");
                    jdbc.update("DELETE FROM sys_webhook");
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE event_type IN"
                                    + " ('TASK_OPENED','TASK_OVERDUE')");
                    jdbc.update(
                            "DELETE FROM ntf_send_log WHERE event_type IN"
                                    + " ('TASK_OPENED','TASK_OVERDUE')");
                    jdbc.update(
                            "DELETE FROM ntf_message WHERE event_type IN"
                                    + " ('TASK_OPENED','TASK_OVERDUE')");
                });
        RECEIVED.clear();
        SCRIPT.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", new RecordingHandler());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @AfterAll
    void cleanUp() {
        runInTenantA(
                () -> {
                    jdbc.update("DELETE FROM sys_webhook_delivery");
                    jdbc.update("DELETE FROM sys_webhook");
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE event_type IN"
                                    + " ('TASK_OPENED','TASK_OVERDUE')");
                    jdbc.update(
                            "DELETE FROM ntf_send_log WHERE event_type IN"
                                    + " ('TASK_OPENED','TASK_OVERDUE')");
                    jdbc.update(
                            "DELETE FROM ntf_message WHERE event_type IN"
                                    + " ('TASK_OPENED','TASK_OVERDUE')");
                });
    }

    private static final class RecordingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            // 头名统一小写：HttpServer 会改写大小写，测试侧按小写取
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders()
                    .forEach(
                            (name, values) ->
                                    headers.put(
                                            name.toLowerCase(java.util.Locale.ROOT),
                                            values.get(0)));
            RECEIVED.add(new Received(headers, new String(raw, StandardCharsets.UTF_8)));
            int status = SCRIPT.isEmpty() ? 200 : SCRIPT.poll();
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        }
    }

    // ---------------------------------------------------------------- 验收：签名可校验（事件 → 投递端到端）

    @Test
    void outboxEventIsDeliveredWithVerifiableSignature() throws Exception {
        String admin = login(ADMIN);
        long webhookId = createWebhook(admin, "随访集成（示例）", hookUrl());
        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT INTO sys_outbox (event_type, payload) VALUES"
                                        + " ('TASK_OPENED', ?)",
                                "{\"taskId\":4021,\"patientId\":77,\"patientName\":\"张三（示例）\"}"));

        dispatcher.dispatchOnce(); // outbox → Webhook 订阅方入队（另含通知规则引擎，互不影响）
        assertEquals(1, pendingDeliveries(webhookId), "订阅事件已排队 PENDING");

        poll(); // 到点投递

        assertEquals(1, RECEIVED.size(), "接收方收到一次 POST：" + RECEIVED);
        Received request = RECEIVED.peek();
        String body = request.body();
        // 负载契约：只有事件类型与对象 ID（taskId），无患者信息（A-NTF-008 / A-NFR-007）
        assertTrue(body.contains("\"eventType\":\"TASK_OPENED\""), body);
        assertTrue(body.contains("\"objectType\":\"TASK\""), body);
        assertTrue(body.contains("\"objectId\":\"4021\""), body);
        assertTrue(body.contains("\"tenantCode\":\"demoa\""), body);
        // 患者信息泄漏检查按「患者字段与姓名」判（E20-T02 附带修复：原断言用裸子串 "77"，
        // 与 occurredAt 毫秒位 077/77x 碰撞随机失败——时间戳含 "77" 不是泄漏）
        assertTrue(
                !body.contains("张三")
                        && !body.contains("patientId")
                        && !body.contains("patientName"),
                "投递负载不含患者姓名与患者字段");
        // 独立验签（接收方实现）：v1=hex(HMAC-SHA256(secret, ts + "." + body))
        String timestamp = request.headers().get("x-crdp-timestamp");
        String signature = request.headers().get("x-crdp-signature");
        assertNotNull(timestamp, "时间戳头必在");
        assertNotNull(signature, "签名头必在");
        assertEquals(
                "v1=" + hmac(SECRET, Long.parseLong(timestamp), body),
                signature,
                "验收「签名可校验」：接收方可按契约验签");
        Long deliveryId = Long.valueOf(request.headers().get("x-crdp-delivery"));
        assertEquals("SENT", deliveryStatus(deliveryId), "2xx 后台账记 SENT");
        assertEquals(
                1,
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_webhook_delivery WHERE"
                                                + " webhook_id = "
                                                + webhookId
                                                + " AND status = 'SENT'",
                                        Integer.class)));

        // 分发器重放同一事件（至少一次语义）：不重复排队
        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE sys_outbox SET status = 'PENDING' WHERE event_type ="
                                        + " 'TASK_OPENED'"));
        dispatcher.dispatchOnce();
        assertEquals(
                1,
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_webhook_delivery WHERE"
                                                + " webhook_id = "
                                                + webhookId,
                                        Integer.class)),
                "同一 outbox 事件只排一次（幂等）");
    }

    // ---------------------------------------------------------------- 重试与重投

    @Test
    void retriesWithBackoffThenSucceeds() throws Exception {
        String admin = login(ADMIN);
        long webhookId = createWebhook(admin, "随访集成（示例）", hookUrl());
        publishOverdueEvent();
        dispatcher.dispatchOnce();
        SCRIPT.add(500);
        SCRIPT.add(503);
        poll(); // 第 1 次失败：PENDING、attempt=1、1 秒后
        assertEquals(1, RECEIVED.size());
        assertEquals(1, attemptOf(webhookId), "退避基准 1 秒（服务下限），第 1 次失败记 attempt=1");

        Thread.sleep(1100); // 等退避到期（避免依赖库时钟与 JVM 时钟的毫秒级偏差）
        poll(); // 第 2 次失败：attempt=2、2 秒后
        assertEquals(2, RECEIVED.size());
        assertEquals(2, attemptOf(webhookId));

        Thread.sleep(2100);
        poll(); // 第 3 次成功 → SENT
        assertEquals(3, RECEIVED.size());
        assertEquals("SENT", statusOf(webhookId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedRetriesGoFailedAndCanBeRedelivered() throws Exception {
        String admin = login(ADMIN);
        long webhookId = createWebhook(admin, "随访集成（示例）", hookUrl());
        publishOverdueEvent();
        dispatcher.dispatchOnce();
        SCRIPT.add(500);
        SCRIPT.add(500);
        SCRIPT.add(500);
        SCRIPT.add(500); // 1 + 3 次重试全失败
        poll();
        Thread.sleep(1100);
        poll();
        Thread.sleep(2100);
        poll();
        Thread.sleep(4100);
        poll();
        assertEquals(4, RECEIVED.size(), "共尝试 4 次（首次 + 3 次重试）");
        assertEquals("FAILED", statusOf(webhookId));

        // 投递记录接口：按状态过滤能看到 FAILED 行（payload 可复核）
        ResponseEntity<Map> deliveries =
                get(
                        "/api/hospital/system/webhooks/" + webhookId + "/deliveries?status=FAILED",
                        admin);
        assertEquals(0, code(deliveries));
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) ((Map<String, Object>) data(deliveries)).get("items");
        assertEquals(1, items.size());
        assertTrue(String.valueOf(items.get(0).get("payload")).contains("TASK_OVERDUE"));
        assertEquals(3, ((Number) items.get(0).get("attempt")).intValue());

        // 手动重投：FAILED → PENDING；这次接收方 200 → SENT
        long deliveryId = ((Number) items.get(0).get("id")).longValue();
        ResponseEntity<Map> redeliver =
                post(
                        "/api/hospital/system/webhooks/"
                                + webhookId
                                + "/deliveries/"
                                + deliveryId
                                + "/redeliver",
                        admin,
                        null);
        assertEquals(0, code(redeliver));
        Map<String, Object> redelivered =
                (Map<String, Object>) ((Map<String, Object>) data(redeliver)).get("delivery");
        assertEquals("PENDING", redelivered.get("status"));
        poll();
        assertEquals("SENT", statusOf(webhookId), "重投后 2xx 即 SENT");
        assertEquals(5, RECEIVED.size());

        // 非 FAILED 的重投 → 40001
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/system/webhooks/"
                                        + webhookId
                                        + "/deliveries/"
                                        + deliveryId
                                        + "/redeliver",
                                admin,
                                null)));
    }

    /** test 接口：同步一次性投递并回显签名（管理员与接收方对账）。 */
    @Test
    @SuppressWarnings("unchecked")
    void testEndpointDeliversOnceAndShowsSignature() throws Exception {
        String admin = login(ADMIN);
        long webhookId = createWebhook(admin, "随访集成（示例）", hookUrl());

        ResponseEntity<Map> tested =
                post("/api/hospital/system/webhooks/" + webhookId + "/test", admin, null);
        assertEquals(0, code(tested));
        Map<String, Object> result = data(tested);
        assertEquals("SENT", result.get("status"));
        assertEquals(200, ((Number) result.get("responseStatus")).intValue());
        assertNotNull(result.get("signatureAlgorithm"));

        Received request = RECEIVED.peek();
        assertNotNull(request, "本地接收方收到 WEBHOOK_TEST");
        assertEquals(
                "v1="
                        + hmac(
                                SECRET,
                                ((Number) result.get("timestamp")).longValue(),
                                request.body()),
                result.get("signature"),
                "回显签名与实际请求一致（可对账）");
        assertTrue(request.body().contains("\"eventType\":\"WEBHOOK_TEST\""));
    }

    // ---------------------------------------------------------------- 工具

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private long createWebhook(String token, String name, String url) {
        ResponseEntity<Map> created =
                post(
                        "/api/hospital/system/webhooks",
                        token,
                        "{\"name\":\""
                                + name
                                + "\",\"url\":\""
                                + url
                                + "\",\"secret\":\""
                                + SECRET
                                + "\",\"events\":[\"TASK_OPENED\",\"TASK_OVERDUE\"]}");
        assertEquals(0, code(created));
        return ((Number) ((Map<?, ?>) data(created).get("webhook")).get("id")).longValue();
    }

    private void publishOverdueEvent() {
        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT INTO sys_outbox (event_type, payload) VALUES"
                                        + " ('TASK_OVERDUE', '{\"taskId\":9007}')"));
    }

    private void poll() {
        runInTenantA(() -> delivery.poll());
    }

    private int pendingDeliveries(long webhookId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_webhook_delivery WHERE webhook_id = "
                                        + webhookId
                                        + " AND status = 'PENDING'",
                                Integer.class));
    }

    private String statusOf(long webhookId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM sys_webhook_delivery WHERE webhook_id = "
                                        + webhookId
                                        + " ORDER BY id DESC LIMIT 1",
                                String.class));
    }

    private String deliveryStatus(long deliveryId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM sys_webhook_delivery WHERE id = " + deliveryId,
                                String.class));
    }

    private int attemptOf(long webhookId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT attempt FROM sys_webhook_delivery WHERE webhook_id = "
                                        + webhookId
                                        + " ORDER BY id DESC LIMIT 1",
                                Integer.class));
    }

    /** 测试内独立实现的接收方验签（不复用生产代码）。 */
    private static String hmac(String secret, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String login(String username) {
        Map<?, ?> body = Map.of("tenantCode", TENANT_A, "username", username, "password", PASSWORD);
        Map<?, ?> response =
                rest.postForEntity("/api/hospital/auth/login", body, Map.class).getBody();
        assertEquals(0, ((Number) response.get("code")).intValue(), "登录成功：" + username);
        return (String) ((Map<?, ?>) response.get("data")).get("token");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static int code(ResponseEntity<Map> response) {
        return ((Number) ((Map<?, ?>) response.getBody()).get("code")).intValue();
    }

    private ResponseEntity<Map> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    private ResponseEntity<Map> post(String path, String token, String jsonBody) {
        return exchange(path, HttpMethod.POST, token, jsonBody);
    }

    private ResponseEntity<Map> exchange(
            String path, HttpMethod method, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(jsonBody, headers), Map.class);
    }

    private void runInTenantA(Runnable action) {
        TenantContext.callAsTenant(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    action.run();
                    return null;
                });
    }

    private <T> T runInTenantA(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_A_ID, TENANT_A, action::get);
    }
}
