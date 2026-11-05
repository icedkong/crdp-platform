package com.crdp.system.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.WebhookDeliveryInsert;
import com.crdp.system.infra.mapper.WebhookDeliveryMapper;
import com.crdp.system.infra.mapper.WebhookDeliveryRow;
import com.crdp.system.infra.mapper.WebhookMapper;
import com.crdp.system.infra.mapper.WebhookRow;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E09-T06 / A-NTF-008、C-NTF-008：Webhook 投递引擎单元测试（假传输，不触网）。
 *
 * <p>锁定：负载只含事件类型与对象 ID（无患者信息）、HMAC-SHA256 签名头可被第三方独立校验（验收「签名可校验」—— 期望值在测试里用 JCE
 * 直接算，不复用生产代码自证）、指数退避重试与耗尽转 FAILED、投递前 SSRF 复核、test 一次性投递回显签名、 失败手动重投、幂等入队。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    private static final String SECRET = "it-secret-32-bytes-example-key!!";
    private static final Instant NOW = Instant.parse("2027-04-12T02:30:00Z");

    /** 独立实现的期望签名：hex(HMAC-SHA256(secret, ts + "." + body))——接收方视角。 */
    private static String expectedSignature(String secret, long ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** 记录一次出站请求的假传输；脚本逐条给出每次尝试的结果（0 = 网络异常）。 */
    static final class FakeTransport implements WebhookTransport {
        final List<Map.Entry<Map<String, String>, String>> requests = new ArrayList<>();
        final List<Attempt> script = new ArrayList<>();

        void respondWith(int... statuses) {
            for (int status : statuses) {
                script.add(
                        status == 0
                                ? new Attempt(null, "网络异常（连接或读取失败）")
                                : new Attempt(status, null));
            }
        }

        @Override
        public Attempt post(URI url, Map<String, String> headers, String body) {
            requests.add(Map.entry(headers, body));
            return script.isEmpty() ? Attempt.ok(200) : script.remove(0);
        }
    }

    private final WebhookMapper webhooks = mock(WebhookMapper.class);
    private final WebhookDeliveryMapper deliveries = mock(WebhookDeliveryMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final FakeTransport transport = new FakeTransport();

    /** 可拨动时钟：签名时间戳与退避到期可断言（重试用例在两次 poll 之间前进）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant = NOW;

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private final MutableClock clock = new MutableClock();

    private final List<WebhookDeliveryRow> queue = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private WebhookDeliveryService service;
    private long nowSecond;

    @BeforeEach
    void setUp() {
        lenient().when(crypto.decrypt(anyString())).thenReturn(SECRET);
        lenient()
                .when(webhooks.listEnabledSubscribed(anyString()))
                .thenAnswer(inv -> List.of(webhook("https://8.8.8.8/hooks")));
        lenient()
                .when(webhooks.find(anyLong()))
                .thenAnswer(inv -> webhook("https://8.8.8.8/hooks"));
        lenient()
                .when(deliveries.countByWebhookAndEvent(anyLong(), anyLong()))
                .thenAnswer(
                        inv ->
                                (int)
                                        queue.stream()
                                                .filter(
                                                        row ->
                                                                row.webhookId()
                                                                                == (long)
                                                                                        inv
                                                                                                .getArgument(
                                                                                                        0)
                                                                        && row.outboxEventId()
                                                                                != null
                                                                        && row.outboxEventId()
                                                                                == (long)
                                                                                        inv
                                                                                                .getArgument(
                                                                                                        1))
                                                .count());
        lenient()
                .doAnswer(
                        inv -> {
                            WebhookDeliveryInsert insert = inv.getArgument(0);
                            insert.setId(ids.incrementAndGet()); // 模拟 useGeneratedKeys 回填
                            queue.add(
                                    new WebhookDeliveryRow(
                                            insert.getId(),
                                            insert.getWebhookId(),
                                            insert.getOutboxEventId(),
                                            insert.getEventType(),
                                            insert.getObjectType(),
                                            insert.getObjectId(),
                                            insert.getPayload(),
                                            "PENDING",
                                            0,
                                            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                                            null,
                                            null,
                                            null,
                                            null));
                            return null;
                        })
                .when(deliveries)
                .insert(any(WebhookDeliveryInsert.class));
        lenient()
                .when(deliveries.listPending(anyInt()))
                .thenAnswer(
                        inv ->
                                queue.stream()
                                        .filter(row -> "PENDING".equals(row.status()))
                                        .toList());
        lenient()
                .when(deliveries.find(anyLong()))
                .thenAnswer(
                        inv ->
                                queue.stream()
                                        .filter(row -> row.id() == (long) inv.getArgument(0))
                                        .findFirst()
                                        .orElse(null));
        // UPDATE 语义：改写队列里的行（模拟库侧行更新）
        lenient()
                .when(deliveries.markSent(anyLong(), anyInt()))
                .thenAnswer(
                        inv -> {
                            replace(
                                    inv.getArgument(0),
                                    row ->
                                            new WebhookDeliveryRow(
                                                    row.id(),
                                                    row.webhookId(),
                                                    row.outboxEventId(),
                                                    row.eventType(),
                                                    row.objectType(),
                                                    row.objectId(),
                                                    row.payload(),
                                                    "SENT",
                                                    row.attempt(),
                                                    null,
                                                    inv.getArgument(1),
                                                    null,
                                                    LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                                                    row.createdAt()));
                            return 1;
                        });
        lenient()
                .when(deliveries.scheduleRetry(anyLong(), anyInt(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            replace(
                                    inv.getArgument(0),
                                    row ->
                                            new WebhookDeliveryRow(
                                                    row.id(),
                                                    row.webhookId(),
                                                    row.outboxEventId(),
                                                    row.eventType(),
                                                    row.objectType(),
                                                    row.objectId(),
                                                    row.payload(),
                                                    "PENDING",
                                                    inv.getArgument(1),
                                                    inv.getArgument(4),
                                                    inv.getArgument(2),
                                                    inv.getArgument(3),
                                                    null,
                                                    row.createdAt()));
                            return 1;
                        });
        lenient()
                .when(deliveries.markFailed(anyLong(), any(), anyString()))
                .thenAnswer(
                        inv -> {
                            replace(
                                    inv.getArgument(0),
                                    row ->
                                            new WebhookDeliveryRow(
                                                    row.id(),
                                                    row.webhookId(),
                                                    row.outboxEventId(),
                                                    row.eventType(),
                                                    row.objectType(),
                                                    row.objectId(),
                                                    row.payload(),
                                                    "FAILED",
                                                    row.attempt(),
                                                    null,
                                                    inv.getArgument(1),
                                                    inv.getArgument(2),
                                                    row.sentAt(),
                                                    row.createdAt()));
                            return 1;
                        });
        lenient()
                .when(deliveries.requeue(anyLong()))
                .thenAnswer(
                        inv -> {
                            replace(
                                    inv.getArgument(0),
                                    row ->
                                            new WebhookDeliveryRow(
                                                    row.id(),
                                                    row.webhookId(),
                                                    row.outboxEventId(),
                                                    row.eventType(),
                                                    row.objectType(),
                                                    row.objectId(),
                                                    row.payload(),
                                                    "PENDING",
                                                    0,
                                                    LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                                                    row.responseStatus(),
                                                    null,
                                                    row.sentAt(),
                                                    row.createdAt()));
                            return 1;
                        });
        TenantContext.useTenant(1L, "demoa");
        service =
                new WebhookDeliveryService(
                        webhooks,
                        deliveries,
                        new WebhookSsrfGuard(false),
                        new WebhookSigner(jceCrypto()),
                        crypto,
                        transport,
                        clock,
                        3,
                        60000,
                        1800000,
                        100);
        nowSecond = NOW.getEpochSecond();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        queue.clear();
        transport.requests.clear();
    }

    // ---------------------------------------------------------------- 入队

    @Test
    void enqueueBuildsMinimalPayloadAndIsIdempotent() {
        service.enqueueForEvent(
                9001L,
                "TASK_OVERDUE",
                "{\"taskId\":123,\"patientId\":456,\"patientName\":\"张三（示例）\"}",
                Instant.parse("2027-04-12T01:00:00Z"));

        assertEquals(1, queue.size());
        WebhookDeliveryRow row = queue.get(0);
        assertEquals("TASK_OVERDUE", row.eventType());
        assertEquals("TASK", row.objectType());
        assertEquals("123", row.objectId(), "对象 ID 从负载 taskId 取");
        assertTrue(
                !row.payload().contains("张三") && !row.payload().contains("456"),
                "负载不含患者姓名与患者 ID（A-NTF-008 只含事件类型与对象 ID）");
        assertTrue(row.payload().contains("\"tenantCode\":\"demoa\""));
        assertTrue(row.payload().contains("+08:00"), "occurredAt 按 +08:00 带时区序列化");

        // 分发器重放（至少一次语义）：幂等，不重排
        service.enqueueForEvent(9001L, "TASK_OVERDUE", "{\"taskId\":123}", NOW);
        assertEquals(1, queue.size(), "同一 outbox 事件只排一次");
    }

    @Test
    void enqueueRejectsBrokenPayloadJson() {
        assertThrows(
                IllegalStateException.class,
                () -> service.enqueueForEvent(9002L, "TASK_OPENED", "not-json", NOW));
        assertTrue(queue.isEmpty(), "负载非法不入队，异常上抛交分发器退避重试");
    }

    // ---------------------------------------------------------------- 投递与签名（验收「签名可校验」）

    @Test
    void pollSignsRequestVerifiably() throws Exception {
        service.enqueueForEvent(9001L, "TASK_OPENED", "{\"taskId\":777}", NOW);
        service.poll();

        assertEquals(1, transport.requests.size());
        var request = transport.requests.get(0);
        String body = request.getValue();
        assertTrue(body.contains("\"eventType\":\"TASK_OPENED\""));
        assertTrue(body.contains("\"objectId\":\"777\""));

        String timestampHeader = request.getKey().get(WebhookSigner.TIMESTAMP_HEADER);
        String signatureHeader = request.getKey().get(WebhookSigner.SIGNATURE_HEADER);
        assertEquals(String.valueOf(nowSecond), timestampHeader, "固定时钟的 Unix 秒");
        assertNotNull(signatureHeader);
        assertTrue(signatureHeader.startsWith("v1="), "算法版本前缀");
        // 接收方独立验签：hex(HMAC-SHA256(secret, ts + "." + body))
        assertEquals(
                "v1=" + expectedSignature(SECRET, Long.parseLong(timestampHeader), body),
                signatureHeader,
                "签名必须可被接收方按契约校验");
        assertEquals("TASK_OPENED", request.getKey().get(WebhookSigner.EVENT_HEADER));
        assertEquals(
                String.valueOf(queue.get(0).id()),
                request.getKey().get(WebhookSigner.DELIVERY_HEADER));

        assertEquals("SENT", queue.get(0).status());
        assertEquals(200, queue.get(0).responseStatus());
        verify(webhooks).touchDelivery(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void pollRetriesWithExponentialBackoffThenFails() {
        transport.respondWith(500, 0, 500, 503);
        service.enqueueForEvent(9001L, "TASK_OPENED", "{\"taskId\":1}", NOW);

        service.poll(); // 第 1 次失败 → attempt=1，1 分钟后重试
        assertEquals(1, transport.requests.size());
        assertEquals(1, queue.get(0).attempt());
        assertEquals(Duration.ofMinutes(1), backoffOf(queue.get(0)));
        assertEquals("PENDING", queue.get(0).status());

        clock.advance(Duration.ofMinutes(1)); // 第 1 次退避到期
        service.poll(); // 第 2 次失败 → attempt=2，2 分钟后
        assertEquals(2, queue.get(0).attempt());
        assertEquals(Duration.ofMinutes(2), backoffOf(queue.get(0)));

        clock.advance(Duration.ofMinutes(2)); // 第 2 次退避到期
        service.poll(); // 第 3 次失败 → attempt=3，4 分钟后
        assertEquals(3, queue.get(0).attempt());
        assertEquals(Duration.ofMinutes(4), backoffOf(queue.get(0)));

        clock.advance(Duration.ofMinutes(4)); // 第 3 次退避到期
        service.poll(); // 第 4 次失败 → 重试耗尽（1 + 3 次重试）
        assertEquals(4, transport.requests.size());
        assertEquals("FAILED", queue.get(0).status());
        assertTrue(queue.get(0).error().contains("503"), "失败类别带最后响应码");
    }

    @Test
    void pollRecoversOnLaterAttempt() {
        transport.respondWith(500, 200);
        service.enqueueForEvent(9001L, "TASK_OPENED", "{\"taskId\":1}", NOW);

        service.poll();
        assertEquals("PENDING", queue.get(0).status());
        clock.advance(Duration.ofMinutes(1));
        service.poll();
        assertEquals("SENT", queue.get(0).status(), "退避到点的重试成功即 SENT");
        assertEquals(2, transport.requests.size());
    }

    @Test
    void pollRechecksSsrfBeforeSending() {
        // 保存后库里被改成内网地址（或 DNS 改指内网）：投递前复核不过，直接 FAILED，不出网
        lenient()
                .when(webhooks.find(anyLong()))
                .thenAnswer(inv -> webhook("https://127.0.0.1/hooks"));
        service.enqueueForEvent(9001L, "TASK_OPENED", "{\"taskId\":1}", NOW);
        service.poll();

        assertEquals(0, transport.requests.size(), "复核不过不出网");
        assertEquals("FAILED", queue.get(0).status());
        assertTrue(queue.get(0).error().contains("内网"));
    }

    // ---------------------------------------------------------------- test / 重投

    @Test
    void testSendsOnceShowsSignatureAndDoesNotRetry() throws Exception {
        WebhookDeliveryService.TestResult result = service.test(5L);

        assertEquals("SENT", result.status());
        assertEquals(200, result.responseStatus());
        assertTrue(result.signature().startsWith("v1="));
        assertEquals(nowSecond, result.timestamp());
        assertNotNull(result.signatureAlgorithm());

        assertEquals(1, transport.requests.size());
        var request = transport.requests.get(0);
        assertEquals(
                "v1=" + expectedSignature(SECRET, result.timestamp(), request.getValue()),
                result.signature(),
                "回显的签名与实际发出的请求一致（管理员可与接收方日志比对）");
        assertTrue(request.getValue().contains("\"eventType\":\"WEBHOOK_TEST\""));
        assertEquals("WEBHOOK_TEST", queue.get(0).eventType());
        assertEquals("SENT", queue.get(0).status());
    }

    @Test
    void testFailureIsTerminalWithoutRetry() {
        transport.respondWith(0);
        WebhookDeliveryService.TestResult result = service.test(5L);

        assertEquals("FAILED", result.status());
        assertNull(result.responseStatus());
        assertEquals(1, transport.requests.size());
        assertEquals("FAILED", queue.get(0).status(), "测试失败不进重试队列");
        assertEquals(0, queue.get(0).attempt());
    }

    @Test
    void redeliverRequeuesFailedOnly() {
        transport.respondWith(500, 0, 500, 503, 500);
        service.enqueueForEvent(9001L, "TASK_OPENED", "{\"taskId\":1}", NOW);
        service.poll();
        clock.advance(Duration.ofMinutes(1));
        service.poll();
        clock.advance(Duration.ofMinutes(2));
        service.poll();
        clock.advance(Duration.ofMinutes(4));
        service.poll();
        assertEquals("FAILED", queue.get(0).status());
        long id = queue.get(0).id();

        WebhookDeliveryService.RedeliverResult result = service.redeliver(5L, id);
        assertTrue(result.requeued());
        assertEquals("PENDING", result.delivery().status());
        assertEquals(0, result.delivery().attempt(), "重投从第 1 次重计退避");

        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.redeliver(5L, id)).code(),
                "非 FAILED 不可手动重投（PENDING 由轮询自然处理）");
        assertEquals(
                40400, assertThrows(ApiException.class, () -> service.redeliver(5L, 999L)).code());
    }

    @Test
    void testRejectsMissingWebhook() {
        lenient().when(webhooks.find(404L)).thenReturn(null);
        ApiException ex = assertThrows(ApiException.class, () -> service.test(404L));
        assertEquals(40400, ex.code());
        assertTrue(transport.requests.isEmpty(), "不存在的 Webhook 不出网");
    }

    // ---------------------------------------------------------------- 工具

    private static WebhookRow webhook(String url) {
        return new WebhookRow(
                5L, "随访集成（示例）", url, "cipher:x", "TASK_OPENED", true, null, null, null, null, null);
    }

    private interface RowChange {
        WebhookDeliveryRow apply(WebhookDeliveryRow row);
    }

    private void replace(long id, RowChange change) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id() == id) {
                queue.set(i, change.apply(queue.get(i)));
                return;
            }
        }
    }

    private Duration backoffOf(WebhookDeliveryRow row) {
        return Duration.between(LocalDateTime.now(clock), row.nextAttemptAt());
    }

    /** 真 HMAC-SHA256（同框架实现的语义）：签名断言不复用被测代码路径。 */
    private static CryptoService jceCrypto() {
        return new CryptoService() {
            @Override
            public String encrypt(String purpose, String plaintext) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String decrypt(String ciphertext) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String blindIndex(String purpose, String value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String hmacSha256Hex(byte[] key, String data) {
                try {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(key, "HmacSHA256"));
                    byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                    StringBuilder hex = new StringBuilder(digest.length * 2);
                    for (byte b : digest) {
                        hex.append(String.format("%02x", b));
                    }
                    return hex.toString();
                } catch (Exception impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        };
    }
}
