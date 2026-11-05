package com.crdp.system.webhook;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.WebhookDeliveryInsert;
import com.crdp.system.infra.mapper.WebhookDeliveryMapper;
import com.crdp.system.infra.mapper.WebhookDeliveryRow;
import com.crdp.system.infra.mapper.WebhookMapper;
import com.crdp.system.infra.mapper.WebhookRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Webhook 投递引擎（E09-T06 / A-NTF-008、C-NTF-008）：入队（outbox 订阅方调用）→ 轮询投递 → 指数退避重试 → 台账。
 *
 * <p><b>与分发器的关系</b>（OutboxSubscriber 契约：外部调用不阻塞分发线程）：sys_outbox 消费侧只做入队（一次 INSERT）； 真正的 HTTP
 * 出站由轮询作业（{@code WebhookDeliveryScheduler}）在独立线程逐租户执行。这样 outbox 事件不会被慢接收方拖住退避重试， Webhook
 * 自己的重试也有独立台账（attempt / next_attempt_at 持久化，重启不丢退避进度——比分发器的内存台账口径更强）。
 *
 * <p><b>重试</b>（tech-plan §9「失败重试 3 次（退避）」）：一次投递失败 → 记 attempt、按 {@code retryBaseMs << (attempt-1)}
 * 指数退避排队（封顶 {@code maxBackoffMs}），累计失败 {@code maxRetries} 次后（含首次共 maxRetries+1 次尝试）定格 FAILED，
 * 可在配置页手动重投。
 *
 * <p><b>负载契约</b>（notify.md §6）：{@code { eventType, objectType, objectId, tenantCode, occurredAt
 * }}——只有事件类型与对象 ID（字符串），不含患者信息（A-NTF-008 / A-NFR-007）；签名头见 {@link WebhookSigner}。
 *
 * <p><b>SSRF</b>：保存时 {@link WebhookSsrfGuard#validate} 已过闸，每次投递前再 {@link
 * WebhookSsrfGuard#isDeliverable} 复核（保存后 DNS 改指向内网 / rebinding 直接按失败处理，不投）。
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_SENT = "SENT";
    static final String STATUS_FAILED = "FAILED";

    private final WebhookMapper webhooks;
    private final WebhookDeliveryMapper deliveries;
    private final WebhookSsrfGuard guard;
    private final WebhookSigner signer;
    private final CryptoService crypto;
    private final WebhookTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    private final int maxRetries;
    private final long retryBaseMs;
    private final long maxBackoffMs;
    private final int pollBatch;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookDeliveryService(
            WebhookMapper webhooks,
            WebhookDeliveryMapper deliveries,
            WebhookSsrfGuard guard,
            WebhookSigner signer,
            CryptoService crypto,
            WebhookTransport transport,
            @Value("${crdp.webhook.max-retries:3}") int maxRetries,
            @Value("${crdp.webhook.retry-base-ms:60000}") long retryBaseMs,
            @Value("${crdp.webhook.max-backoff-ms:1800000}") long maxBackoffMs,
            @Value("${crdp.webhook.poll-batch:100}") int pollBatch) {
        this(
                webhooks,
                deliveries,
                guard,
                signer,
                crypto,
                transport,
                Clock.systemDefaultZone(),
                maxRetries,
                retryBaseMs,
                maxBackoffMs,
                pollBatch);
    }

    WebhookDeliveryService(
            WebhookMapper webhooks,
            WebhookDeliveryMapper deliveries,
            WebhookSsrfGuard guard,
            WebhookSigner signer,
            CryptoService crypto,
            WebhookTransport transport,
            Clock clock,
            int maxRetries,
            long retryBaseMs,
            long maxBackoffMs,
            int pollBatch) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.guard = guard;
        this.signer = signer;
        this.crypto = crypto;
        this.transport = transport;
        this.clock = clock;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBaseMs = Math.max(1000, retryBaseMs);
        this.maxBackoffMs = Math.max(this.retryBaseMs, maxBackoffMs);
        this.pollBatch = Math.max(1, pollBatch);
    }

    // ---------------------------------------------------------------- 视图

    /** 投递记录视图（配置页「投递记录」；payload 是原始请求体，便于按签名复核）。 */
    public record DeliveryView(
            Long id,
            Long webhookId,
            String eventType,
            String objectType,
            String objectId,
            String payload,
            String status,
            int attempt,
            String nextAttemptAt,
            Integer responseStatus,
            String error,
            String sentAt,
            String createdAt) {

        static DeliveryView of(WebhookDeliveryRow row) {
            return new DeliveryView(
                    row.id(),
                    row.webhookId(),
                    row.eventType(),
                    row.objectType(),
                    row.objectId(),
                    row.payload(),
                    row.status(),
                    row.attempt(),
                    format(row.nextAttemptAt()),
                    row.responseStatus(),
                    row.error(),
                    format(row.sentAt()),
                    format(row.createdAt()));
        }

        private static String format(LocalDateTime time) {
            return time == null
                    ? null
                    : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                            time.atOffset(ZoneOffset.ofHours(8)));
        }
    }

    /** 手动重投结果。 */
    public record RedeliverResult(DeliveryView delivery, boolean requeued) {}

    /** test 接口结果：回显本次签名头值，管理员可与接收方日志比对（验收「签名可校验」的自证口）。 */
    public record TestResult(
            String status,
            Integer responseStatus,
            String error,
            long timestamp,
            String signature,
            String signatureAlgorithm) {}

    /** 投递记录分页（pageNo 从 1，pageSize 默认 20 上限 100，与全局分页约定一致）。 */
    public record DeliveryPage(List<DeliveryView> items, long total, int pageNo, int pageSize) {}

    // ---------------------------------------------------------------- 入队（outbox 订阅方）

    /**
     * 为一条 outbox 事件排队投递：订阅该事件且启用的 Webhook 各得一行 PENDING。 幂等：(webhook_id, outbox_event_id)
     * 唯一，订阅方重放只查不插。必须在事件所属租户上下文内调用。
     */
    public void enqueueForEvent(
            long outboxEventId, String eventType, String eventPayload, Instant occurredAt) {
        WebhookEventType type =
                WebhookEventType.fromCode(eventType)
                        .filter(t -> t != WebhookEventType.WEBHOOK_TEST)
                        .orElseThrow(() -> new IllegalStateException("非订阅事件: " + eventType));
        List<WebhookRow> subscribed = webhooks.listEnabledSubscribed(type.name());
        if (subscribed.isEmpty()) {
            return;
        }
        String tenantCode = TenantContext.require().tenantCode();
        String objectId = extractObjectId(type, eventPayload);
        String body = buildBody(type, objectId, tenantCode, occurredAt);
        for (WebhookRow webhook : subscribed) {
            if (deliveries.countByWebhookAndEvent(webhook.id(), outboxEventId) > 0) {
                continue; // 至少一次语义下的重放
            }
            deliveries.insert(
                    new WebhookDeliveryInsert(
                            webhook.id(),
                            outboxEventId,
                            type.name(),
                            type.objectType(),
                            objectId,
                            body),
                    LocalDateTime.now(clock)); // 入队即到期（JVM 时钟，与轮询判定同钟，见 mapper 注释）
        }
    }

    // ---------------------------------------------------------------- 轮询投递（定时作业）

    /**
     * 投递一轮到点行。必须在租户上下文内调用（sys_webhook_delivery 属租户库）。 到点判定用 JVM 时钟（next_attempt_at 的写入时钟）——不与 MySQL
     * NOW() 比较，容器时区与 JVM 不同时不错位。
     */
    public int poll() {
        int processed = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (WebhookDeliveryRow row : deliveries.listPending(Math.max(pollBatch * 5, 500))) {
            if (row.nextAttemptAt() != null && row.nextAttemptAt().isAfter(now)) {
                continue; // 退避未到点
            }
            attempt(row, true);
            processed++;
        }
        return processed;
    }

    // ---------------------------------------------------------------- 单次尝试与重试

    /** 单条投递（重试策略在此）。{@code allowRetry=false} 供 test 接口一次性验证：失败直接 FAILED 不占重试队列。 */
    private void attempt(WebhookDeliveryRow delivery, boolean allowRetry) {
        WebhookRow webhook = webhooks.find(delivery.webhookId());
        if (webhook == null) {
            // 配置已被删除：清队列语义由删除口保证，这里的孤儿行直接定格 FAILED
            deliveries.markFailed(delivery.id(), null, "Webhook 已删除");
            return;
        }
        if (!guard.isDeliverable(webhook.url())) {
            // 投递前复核不过（保存后 DNS 改指向内网等）：重试也不会好，直接定格
            deliveries.markFailed(delivery.id(), null, "回调地址校验未通过（内网或非 HTTPS）");
            return;
        }
        WebhookTransport.Attempt result = send(webhook, delivery).attempt();
        if (result.success()) {
            deliveries.markSent(delivery.id(), result.status());
            webhooks.touchDelivery(webhook.id(), LocalDateTime.now(clock));
            log.info(
                    "Webhook 投递成功 webhook={} delivery={} event={} status={}",
                    webhook.id(),
                    delivery.id(),
                    delivery.eventType(),
                    result.status());
            return;
        }
        int failedAttempts = delivery.attempt() + 1;
        String error = errorOf(result);
        if (allowRetry && failedAttempts <= maxRetries) {
            // 指数退避：1×base、2×base、4×base …（attempt 是已失败次数），封顶 maxBackoff
            long backoff = Math.min(retryBaseMs << Math.min(failedAttempts - 1, 20), maxBackoffMs);
            deliveries.scheduleRetry(
                    delivery.id(),
                    failedAttempts,
                    result.status(),
                    error,
                    LocalDateTime.now(clock).plus(Duration.ofMillis(backoff)));
            log.warn(
                    "Webhook 投递失败，退避重试 webhook={} delivery={} attempt={}/{} error={}",
                    webhook.id(),
                    delivery.id(),
                    failedAttempts,
                    maxRetries,
                    error);
            return;
        }
        deliveries.markFailed(delivery.id(), result.status(), error);
        log.error(
                "Webhook 投递重试耗尽，转 FAILED webhook={} delivery={} attempts={} error={}",
                webhook.id(),
                delivery.id(),
                failedAttempts,
                error);
    }

    /** 组头（时间戳 + 签名 + 事件 + 投递号）并发出一次 POST；不跟重定向、不重试（{@link WebhookTransport} 契约）。 */
    private Sent send(WebhookRow webhook, WebhookDeliveryRow delivery) {
        long timestamp = clock.instant().getEpochSecond();
        String secret = crypto.decrypt(webhook.secretCipher());
        String signature =
                signer.signatureHeader(
                        secret.getBytes(StandardCharsets.UTF_8), timestamp, delivery.payload());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(WebhookSigner.TIMESTAMP_HEADER, String.valueOf(timestamp));
        headers.put(WebhookSigner.SIGNATURE_HEADER, signature);
        headers.put(WebhookSigner.EVENT_HEADER, delivery.eventType());
        headers.put(WebhookSigner.DELIVERY_HEADER, String.valueOf(delivery.id()));
        return new Sent(
                transport.post(URI.create(webhook.url()), headers, delivery.payload()),
                timestamp,
                signature);
    }

    /** 一次发送的结果与签名材料（test 接口回显用）。 */
    private record Sent(WebhookTransport.Attempt attempt, long timestamp, String signature) {}

    // ---------------------------------------------------------------- test / 重投 / 记录查询

    /** 连通性测试（POST /webhooks/{id}/test）：同步发一条 WEBHOOK_TEST，一次性不重试，失败定格 FAILED。 */
    public TestResult test(long webhookId) {
        WebhookRow webhook = webhooks.find(webhookId);
        if (webhook == null) {
            throw new ApiException(404, 40400, "Webhook 不存在");
        }
        String tenantCode = TenantContext.require().tenantCode();
        String body =
                buildBody(
                        WebhookEventType.WEBHOOK_TEST,
                        String.valueOf(webhookId),
                        tenantCode,
                        clock.instant());
        WebhookDeliveryInsert insert =
                new WebhookDeliveryInsert(
                        webhookId,
                        null,
                        WebhookEventType.WEBHOOK_TEST.name(),
                        WebhookEventType.WEBHOOK_TEST.objectType(),
                        String.valueOf(webhookId),
                        body);
        deliveries.insert(
                insert, LocalDateTime.now(clock)); // useGeneratedKeys 回填投递号（X-CRDP-Delivery）
        WebhookDeliveryRow row =
                new WebhookDeliveryRow(
                        insert.getId(),
                        webhookId,
                        null,
                        WebhookEventType.WEBHOOK_TEST.name(),
                        WebhookEventType.WEBHOOK_TEST.objectType(),
                        String.valueOf(webhookId),
                        body,
                        STATUS_PENDING,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null);
        WebhookTransport.Attempt result;
        long timestamp = clock.instant().getEpochSecond();
        String signature;
        if (!guard.isDeliverable(webhook.url())) {
            result = new WebhookTransport.Attempt(null, "回调地址校验未通过（内网或非 HTTPS）");
            signature =
                    signer.signatureHeader(
                            crypto.decrypt(webhook.secretCipher()).getBytes(StandardCharsets.UTF_8),
                            timestamp,
                            body);
            deliveries.markFailed(row.id(), null, errorOf(result));
        } else {
            Sent sent = send(webhook, row);
            result = sent.attempt();
            timestamp = sent.timestamp();
            signature = sent.signature();
            if (result.success()) {
                deliveries.markSent(row.id(), result.status());
            } else {
                deliveries.markFailed(row.id(), result.status(), errorOf(result));
            }
        }
        return new TestResult(
                result.success() ? STATUS_SENT : STATUS_FAILED,
                result.status(),
                result.success() ? null : errorOf(result),
                timestamp,
                signature,
                "HMAC-SHA256（hex(HMAC(secret, 时间戳 + \".\" + 请求体))，头 X-CRDP-Signature: v1=…）");
    }

    /** 失败手动重投（PLT-CFG-007「失败可手动重投」）：FAILED → PENDING 重新计退避；并发已重投幂等。 */
    public RedeliverResult redeliver(long webhookId, long deliveryId) {
        WebhookRow webhook = webhooks.find(webhookId);
        if (webhook == null) {
            throw new ApiException(404, 40400, "Webhook 不存在");
        }
        WebhookDeliveryRow delivery = deliveries.find(deliveryId);
        if (delivery == null || delivery.webhookId() != webhookId) {
            throw new ApiException(404, 40400, "投递记录不存在");
        }
        if (!STATUS_FAILED.equals(delivery.status())) {
            throw new ApiException(400, 40001, "只有失败的投递可以重投");
        }
        boolean requeued = deliveries.requeue(deliveryId, LocalDateTime.now(clock)) > 0;
        return new RedeliverResult(DeliveryView.of(deliveries.find(deliveryId)), requeued);
    }

    /** 投递记录分页查询（状态过滤可空）；Webhook 不存在 40400（不暴露他人配置的存在性差异）。 */
    public DeliveryPage deliveries(long webhookId, String status, int pageNo, int pageSize) {
        if (webhooks.find(webhookId) == null) {
            throw new ApiException(404, 40400, "Webhook 不存在");
        }
        int page = pageNo < 1 ? 1 : pageNo;
        int size = pageSize < 1 ? 20 : Math.min(pageSize, 100);
        long total = deliveries.countByWebhook(webhookId, status);
        List<DeliveryView> items =
                deliveries.listByWebhook(webhookId, status, size, (page - 1) * size).stream()
                        .map(DeliveryView::of)
                        .toList();
        return new DeliveryPage(items, total, page, size);
    }

    // ---------------------------------------------------------------- 内部

    /** 负载构造：固定字段顺序（eventType, objectType, objectId, tenantCode, occurredAt），不含患者信息。 */
    private String buildBody(
            WebhookEventType type, String objectId, String tenantCode, Instant occurredAt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("eventType", type.name());
        body.put("objectType", type.objectType());
        if (objectId != null) {
            body.put("objectId", objectId);
        }
        body.put("tenantCode", tenantCode);
        body.put(
                "occurredAt",
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        occurredAt.atOffset(ZoneOffset.ofHours(8))));
        return body.toString();
    }

    /** 从事件负载取对象 ID（NotifyEventPayload 契约，notify.md §2；未知字段忽略）。 */
    private String extractObjectId(WebhookEventType type, String eventPayload) {
        if (type.payloadIdField() == null || eventPayload == null || eventPayload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(eventPayload).path(type.payloadIdField());
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            // 负载非法属于发布方缺陷：交给上层抛错 → outbox 分发器退避重试（不静默丢）
            throw new IllegalStateException("通知事件负载不是合法 JSON，无法提取对象 ID", broken);
        }
    }

    private static String errorOf(WebhookTransport.Attempt result) {
        if (result.errorCategory() != null) {
            return result.errorCategory();
        }
        return "接收方返回非 2xx：" + result.status();
    }
}
