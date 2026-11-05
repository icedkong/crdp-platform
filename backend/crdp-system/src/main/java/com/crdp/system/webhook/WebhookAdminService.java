package com.crdp.system.webhook;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.WebhookDeliveryMapper;
import com.crdp.system.infra.mapper.WebhookInsert;
import com.crdp.system.infra.mapper.WebhookMapper;
import com.crdp.system.infra.mapper.WebhookRow;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 租户 Webhook 配置维护（E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007；权限 {@code tenant:webhook:edit}）。
 *
 * <p>验收「内网地址拒绝」在此入口：{@link WebhookSsrfGuard} 在保存时拒绝非 HTTPS 与内网 / 回环 / 链路本地地址（tech-plan §8）。
 *
 * <p>签名密钥：系统生成 32 字节随机数（Base64URL，43 字符），经 {@link CryptoService} 信封加密落 {@code secret_cipher}（用途
 * {@code webhook-secret}，每租户数据密钥），<b>只在生成 / 更换时回显一次</b>（notify.md §6「secret 只写不读」）； GET / PUT 不带
 * secret 字段时保持不变。增删改全写操作审计（objectType=WEBHOOK，secret 只记摘要不记明文）。
 */
@Service
public class WebhookAdminService {

    /** secret 密文的加密用途（CryptoService purpose 契约：小写、<=32 字符）。 */
    static final String SECRET_PURPOSE = "webhook-secret";

    private static final int NAME_MAX = 100;
    private static final int SECRET_MIN = 16;
    private static final int SECRET_MAX = 128;
    private static final int SECRET_BYTES = 32;
    private static final int MAX_WEBHOOKS = 20;

    private final WebhookMapper webhooks;
    private final WebhookDeliveryMapper deliveries;
    private final WebhookSsrfGuard guard;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final SecureRandom random = new SecureRandom();

    public WebhookAdminService(
            WebhookMapper webhooks,
            WebhookDeliveryMapper deliveries,
            WebhookSsrfGuard guard,
            CryptoService crypto,
            AuditLogger audit) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.guard = guard;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图与命令

    /** 配置视图（不含 secret；secretShownOnce 仅在生成 / 更换密钥的响应里出现）。 */
    public record WebhookView(
            Long id,
            String name,
            String url,
            List<String> events,
            boolean enabled,
            String lastDeliveryAt,
            String updatedAt) {

        static WebhookView of(WebhookRow row) {
            return new WebhookView(
                    row.id(),
                    row.name(),
                    row.url(),
                    WebhookEventCodec.split(row.events()),
                    row.enabled(),
                    format(row.lastDeliveryAt()),
                    format(row.updatedAt()));
        }

        private static String format(LocalDateTime time) {
            return time == null ? null : DateTimeFormatter.ISO_DATE_TIME.format(time);
        }
    }

    public record SaveResult(WebhookView webhook, String secretShownOnce) {}

    public record CreateCommand(String name, String url, String secret, List<String> events) {}

    public record UpdateCommand(
            Long id,
            String name,
            String url,
            String secret,
            List<String> events,
            Boolean enabled) {}

    // ---------------------------------------------------------------- 查询

    public List<WebhookView> list() {
        List<WebhookView> views = new ArrayList<>();
        for (WebhookRow row : webhooks.listAll()) {
            views.add(WebhookView.of(row));
        }
        return views;
    }

    // ---------------------------------------------------------------- 维护

    public SaveResult create(CreateCommand command) {
        String name = requireName(command.name());
        String url = requireUrl(command.url());
        List<WebhookEventType> events = requireEvents(command.events());
        if (webhooks.listAll().size() >= MAX_WEBHOOKS) {
            throw new ApiException(400, 40001, "本院 Webhook 不超过 " + MAX_WEBHOOKS + " 个");
        }
        String secret = useSecret(command.secret());
        long operator = operatorId();
        WebhookInsert row =
                new WebhookInsert(
                        name,
                        url,
                        crypto.encrypt(SECRET_PURPOSE, secret),
                        WebhookEventCodec.join(events),
                        true,
                        operator,
                        operator);
        webhooks.insert(row); // useGeneratedKeys 回填 id（可变插入行，仓库既有口径）
        audit.append(
                AuditEntry.operation("system", "WEBHOOK_CREATE")
                        .objectType("WEBHOOK")
                        .objectId(String.valueOf(row.getId()))
                        .description("新建 Webhook：" + name)
                        .afterJson(
                                "{\"name\":\""
                                        + name
                                        + "\",\"url\":\""
                                        + url
                                        + "\",\"events\":\""
                                        + WebhookEventCodec.join(events)
                                        + "\",\"enabled\":true}")
                        .build());
        return new SaveResult(WebhookView.of(webhooks.find(row.getId())), secret);
    }

    public SaveResult update(UpdateCommand command) {
        WebhookRow existing = webhooks.find(command.id());
        if (existing == null) {
            throw new ApiException(404, 40400, "Webhook 不存在");
        }
        WebhookView before = WebhookView.of(existing);
        String name = command.name() == null ? existing.name() : requireName(command.name());
        String url = command.url() == null ? existing.url() : requireUrl(command.url());
        List<WebhookEventType> events =
                command.events() == null
                        ? WebhookEventCodec.parse(existing.events())
                        : requireEvents(command.events());
        boolean enabled = command.enabled() == null ? existing.enabled() : command.enabled();
        String secretCipher = existing.secretCipher();
        String shownOnce = null;
        if (command.secret() != null && !command.secret().isBlank()) {
            String secret = useSecret(command.secret());
            secretCipher = crypto.encrypt(SECRET_PURPOSE, secret);
            shownOnce = secret;
        }
        long operator = operatorId();
        WebhookRow row =
                new WebhookRow(
                        existing.id(),
                        name,
                        url,
                        secretCipher,
                        WebhookEventCodec.join(events),
                        enabled,
                        existing.lastDeliveryAt(),
                        existing.createdBy(),
                        operator,
                        null,
                        existing.createdAt());
        webhooks.update(row);
        WebhookView after = WebhookView.of(webhooks.find(existing.id()));
        audit.append(
                AuditEntry.operation("system", "WEBHOOK_UPDATE")
                        .objectType("WEBHOOK")
                        .objectId(String.valueOf(existing.id()))
                        .description("修改 Webhook：" + name + (shownOnce == null ? "" : "（已更换签名密钥）"))
                        .beforeJson(compact(before))
                        .afterJson(compact(after))
                        .build());
        return new SaveResult(after, shownOnce);
    }

    public void delete(long id) {
        WebhookRow existing = webhooks.find(id);
        if (existing == null) {
            throw new ApiException(404, 40400, "Webhook 不存在");
        }
        webhooks.delete(id);
        // 投递队列 / 台账随配置一并清除：删除后不能再投，也不留可被重投的行
        deliveries.deleteByWebhook(id);
        audit.append(
                AuditEntry.operation("system", "WEBHOOK_DELETE")
                        .objectType("WEBHOOK")
                        .objectId(String.valueOf(id))
                        .description("删除 Webhook：" + existing.name())
                        .beforeJson(compact(WebhookView.of(existing)))
                        .build());
    }

    // ---------------------------------------------------------------- 校验与工具

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, 40001, "名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw new ApiException(400, 40001, "名称不超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    private String requireUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, 40001, "回调地址不能为空");
        }
        String url = raw.trim();
        guard.validate(url); // 验收「内网地址拒绝」：非 HTTPS / 内网 / 回环 / 链路本地 / 解析失败 → 40001
        return url;
    }

    private List<WebhookEventType> requireEvents(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ApiException(400, 40001, "订阅事件不能为空");
        }
        Set<WebhookEventType> parsed = new LinkedHashSet<>();
        for (String code : raw) {
            WebhookEventType type =
                    WebhookEventType.fromCode(code)
                            .filter(WebhookAdminService::subscribable)
                            .orElseThrow(
                                    () -> new ApiException(400, 40001, "未知或不可订阅的事件类型: " + code));
            parsed.add(type);
        }
        return List.copyOf(parsed);
    }

    private static boolean subscribable(WebhookEventType type) {
        return type != WebhookEventType.WEBHOOK_TEST;
    }

    /** secret 生成 / 校验：调用方未提供时系统生成 32 字节（Base64URL 43 字符）。 */
    private String useSecret(String provided) {
        if (provided == null || provided.isBlank()) {
            byte[] bytes = new byte[SECRET_BYTES];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        String secret = provided.trim();
        if (secret.length() < SECRET_MIN || secret.length() > SECRET_MAX) {
            throw new ApiException(
                    400, 40001, "签名密钥长度须在 " + SECRET_MIN + "–" + SECRET_MAX + " 个字符之间");
        }
        if (secret.codePoints().anyMatch(cp -> cp <= 0x20 || cp == 0x7F)) {
            throw new ApiException(400, 40001, "签名密钥不能包含空白字符");
        }
        return secret;
    }

    private static long operatorId() {
        return CurrentSession.requireHospital().userId();
    }

    private static String compact(WebhookView view) {
        return "{\"name\":\""
                + view.name()
                + "\",\"url\":\""
                + view.url()
                + "\",\"events\":"
                + view.events()
                + ",\"enabled\":"
                + view.enabled()
                + "}";
    }
}
