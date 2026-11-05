package com.crdp.system.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.WebhookDeliveryMapper;
import com.crdp.system.infra.mapper.WebhookInsert;
import com.crdp.system.infra.mapper.WebhookMapper;
import com.crdp.system.infra.mapper.WebhookRow;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007：Webhook 配置维护单元测试。
 *
 * <p>锁定：内网 / 非 https 地址保存拒绝（验收「内网地址拒绝」，公网用字面量 IP 不触 DNS）、签名密钥系统生成且只回显一次、 更新不传 secret
 * 保持不变、事件编码校验（WEBHOOK_TEST 不可订阅）、增删改写 WEBHOOK_* 审计（secret 明文不进审计）、 删除连投递台账一起清、数量上限。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class WebhookAdminServiceTest {

    private final WebhookMapper webhooks = mock(WebhookMapper.class);
    private final WebhookDeliveryMapper deliveries = mock(WebhookDeliveryMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    /** 生产口径的防护（内网拒绝）；公网地址用字面量 IP 避免 DNS。 */
    private final WebhookAdminService service =
            new WebhookAdminService(
                    webhooks, deliveries, new WebhookSsrfGuard(false), crypto, audit);

    private final AtomicLong ids = new AtomicLong(10);
    private final List<WebhookRow> store = new ArrayList<>();

    @BeforeEach
    void loginAndStubStore() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient()
                .when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(inv -> "cipher:" + inv.getArgument(1));
        lenient().when(webhooks.listAll()).thenReturn(store);
        lenient()
                .when(webhooks.find(anyLong()))
                .thenAnswer(
                        inv ->
                                store.stream()
                                        .filter(row -> row.id() == inv.getArgument(0))
                                        .findFirst()
                                        .orElse(null));
        lenient()
                .doAnswer(
                        inv -> {
                            WebhookInsert insert = inv.getArgument(0);
                            insert.setId(ids.incrementAndGet()); // 模拟 useGeneratedKeys 回填
                            store.add(
                                    new WebhookRow(
                                            insert.getId(),
                                            insert.getName(),
                                            insert.getUrl(),
                                            insert.getSecretCipher(),
                                            insert.getEvents(),
                                            insert.isEnabled(),
                                            null,
                                            insert.getCreatedBy(),
                                            insert.getUpdatedBy(),
                                            null,
                                            null));
                            return null;
                        })
                .when(webhooks)
                .insert(any(WebhookInsert.class));
        lenient()
                .when(webhooks.update(any(WebhookRow.class)))
                .thenAnswer(
                        inv -> {
                            WebhookRow row = inv.getArgument(0);
                            for (int i = 0; i < store.size(); i++) {
                                if (store.get(i).id().equals(row.id())) {
                                    store.set(i, row);
                                    return 1;
                                }
                            }
                            return 0;
                        });
        lenient()
                .when(webhooks.delete(anyLong()))
                .thenAnswer(inv -> store.removeIf(row -> row.id() == inv.getArgument(0)) ? 1 : 0);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
        store.clear();
    }

    // ---------------------------------------------------------------- 新建

    @Test
    void createGeneratesSecretAndShowsItOnce() {
        WebhookAdminService.SaveResult result =
                service.create(
                        new WebhookAdminService.CreateCommand(
                                "随访集成（示例）",
                                "https://8.8.8.8/hooks",
                                null,
                                List.of("TASK_OPENED", "TASK_OVERDUE")));

        assertNotNull(result.webhook().id());
        assertEquals(List.of("TASK_OPENED", "TASK_OVERDUE"), result.webhook().events());
        assertEquals(43, result.secretShownOnce().length(), "系统生成 32 字节 Base64URL 是 43 字符");
        assertEquals("cipher:" + result.secretShownOnce(), store.get(0).secretCipher());
        // 视图结构上没有 secret 字段（只写不读），列表只回配置
        assertEquals(1, service.list().size());
        assertEquals("随访集成（示例）", service.list().get(0).name());
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void createRejectsIntranetUrlAndNonHttps() {
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "内网（示例）",
                                                "https://10.0.0.5/hooks",
                                                null,
                                                List.of("TASK_OPENED")))),
                "验收「内网地址拒绝」");
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "回环（示例）",
                                                "https://127.0.0.1/hooks",
                                                null,
                                                List.of("TASK_OPENED")))));
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "链路本地（示例）",
                                                "https://169.254.169.254/hooks",
                                                null,
                                                List.of("TASK_OPENED")))),
                "云元数据端点同样拒绝");
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "明文（示例）",
                                                "http://8.8.8.8/hooks",
                                                null,
                                                List.of("TASK_OPENED")))),
                "只接受 HTTPS");
        assertTrue(store.isEmpty(), "拒绝的配置不落库");
    }

    @Test
    void createValidatesNameEventsAndSecret() {
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "",
                                                "https://8.8.8.8/hooks",
                                                null,
                                                List.of("TASK_OPENED")))),
                "名称必填");
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "名称（示例）",
                                                "https://8.8.8.8/hooks",
                                                null,
                                                List.of()))),
                "订阅事件必填");
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "名称（示例）",
                                                "https://8.8.8.8/hooks",
                                                null,
                                                List.of("WEBHOOK_TEST")))),
                "WEBHOOK_TEST 是测试载荷类型，不可订阅");
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "名称（示例）",
                                                "https://8.8.8.8/hooks",
                                                null,
                                                List.of("NOT_AN_EVENT")))),
                "未知事件拒绝");
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "名称（示例）",
                                                "https://8.8.8.8/hooks",
                                                "short",
                                                List.of("TASK_OPENED")))),
                "自备密钥过短拒绝");
    }

    // ---------------------------------------------------------------- 修改 / 删除

    @Test
    void updateKeepsSecretUnlessReplaced() {
        WebhookAdminService.SaveResult created =
                service.create(
                        new WebhookAdminService.CreateCommand(
                                "随访集成（示例）", "https://8.8.8.8/hooks", null, List.of("TASK_OPENED")));
        long id = created.webhook().id();

        WebhookAdminService.SaveResult renamed =
                service.update(
                        new WebhookAdminService.UpdateCommand(
                                id, "改名（示例）", null, null, null, false));
        assertEquals("改名（示例）", renamed.webhook().name());
        assertFalse(renamed.webhook().enabled());
        assertNull(renamed.secretShownOnce(), "不传 secret 不回显");
        assertEquals("cipher:" + created.secretShownOnce(), store.get(0).secretCipher());

        WebhookAdminService.SaveResult rotated =
                service.update(
                        new WebhookAdminService.UpdateCommand(
                                id,
                                null,
                                null,
                                "rotated-secret-32-bytes-example-key",
                                List.of("PATIENT_DEAD"),
                                true));
        assertEquals("rotated-secret-32-bytes-example-key", rotated.secretShownOnce(), "换钥回显一次");
        assertEquals("cipher:rotated-secret-32-bytes-example-key", store.get(0).secretCipher());
        assertEquals(List.of("PATIENT_DEAD"), rotated.webhook().events());
    }

    @Test
    void updateRejectsIntranetUrlAndMissingWebhook() {
        WebhookAdminService.SaveResult created =
                service.create(
                        new WebhookAdminService.CreateCommand(
                                "随访集成（示例）", "https://8.8.8.8/hooks", null, List.of("TASK_OPENED")));
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.update(
                                        new WebhookAdminService.UpdateCommand(
                                                created.webhook().id(),
                                                null,
                                                "https://192.168.1.5/hooks",
                                                null,
                                                null,
                                                null))),
                "改成内网地址同样拒绝");
        assertEquals(
                40400,
                codeOf(
                        () ->
                                service.update(
                                        new WebhookAdminService.UpdateCommand(
                                                999L, null, null, null, null, null))),
                "不存在 40400");
    }

    @Test
    void deleteRemovesWebhookAndItsDeliveriesAndAudits() {
        WebhookAdminService.SaveResult created =
                service.create(
                        new WebhookAdminService.CreateCommand(
                                "随访集成（示例）", "https://8.8.8.8/hooks", null, List.of("TASK_OPENED")));
        service.delete(created.webhook().id());

        assertTrue(store.isEmpty());
        verify(deliveries).deleteByWebhook(created.webhook().id());
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, atLeastOnce()).append(entries.capture());
        assertTrue(
                entries.getAllValues().stream()
                        .anyMatch(entry -> "WEBHOOK_DELETE".equals(entry.action())));
    }

    @Test
    void auditNeverContainsTheSecret() {
        service.create(
                new WebhookAdminService.CreateCommand(
                        "随访集成（示例）",
                        "https://8.8.8.8/hooks",
                        "audit-safe-secret-32-bytes-example",
                        List.of("TASK_OPENED")));

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entries.capture());
        AuditEntry entry = entries.getValue();
        assertEquals("WEBHOOK_CREATE", entry.action());
        assertNotNull(entry.afterJson());
        assertFalse(
                entry.afterJson().contains("audit-safe-secret-32-bytes-example"), "审计不落 secret 明文");
    }

    @Test
    void createCapsWebhookCount() {
        for (int i = 0; i < 20; i++) {
            store.add(
                    new WebhookRow(
                            (long) (i + 1),
                            "已有（示例）" + i,
                            "https://8.8.8.8/hooks",
                            "cipher:x",
                            "TASK_OPENED",
                            true,
                            null,
                            null,
                            null,
                            null,
                            null));
        }
        assertEquals(
                40001,
                codeOf(
                        () ->
                                service.create(
                                        new WebhookAdminService.CreateCommand(
                                                "超限（示例）",
                                                "https://8.8.8.8/hooks",
                                                null,
                                                List.of("TASK_OPENED")))));
        verify(webhooks, never()).insert(any(WebhookInsert.class));
    }

    private int codeOf(Runnable call) {
        ApiException ex = assertThrows(ApiException.class, call::run);
        return ex.code();
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                7L,
                "nurse.demo",
                "李护士（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("tenant:webhook:edit"),
                List.of(),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                null);
    }
}
