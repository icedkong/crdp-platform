package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** E05-T01（PLT-TEN-009）：信息维护修改留记录（审计），电话加密存储、审计不落明文。 */
class TenantDirectoryServiceTest {

    private static final long TENANT_ID = 7L;

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private TenantDirectoryService service;

    @BeforeAll
    static void loginAsPlatformAdmin() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new SessionPrincipal(
                                        "token",
                                        "PLATFORM",
                                        null,
                                        null,
                                        null,
                                        5L,
                                        "platform.admin",
                                        "平台演示管理员",
                                        List.of("PLATFORM_ADMIN"),
                                        List.of(),
                                        List.of("platform:tenant:edit"),
                                        List.of("base"),
                                        1L,
                                        ScopeSnapshot.allowAll()),
                                null,
                                List.of()));
    }

    @AfterAll
    static void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        service = new TenantDirectoryService(platform, routing, crypto, audit);
        when(routing.hasTenant(anyLong())).thenReturn(false);
    }

    /** plt_tenant 一行的库存值（queryForList 返回的 Map 形态）。 */
    private static Map<String, Object> storedTenant(Map<String, Object> overrides) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", TENANT_ID);
        row.put("code", "demoa");
        row.put("name", "虚构示例医院甲");
        row.put("short_name", null);
        row.put("credit_code", null);
        row.put("org_type", null);
        row.put("region", null);
        row.put("contact_name", null);
        row.put("contact_phone", null);
        row.put("contact_email", null);
        row.put("contract_no", null);
        row.put("remark", null);
        row.put("status", "ENABLED");
        row.put("expire_type", "LONG_TERM");
        row.put("expire_at", null);
        row.put("created_at", LocalDateTime.of(2026, 11, 1, 9, 0));
        row.put("updated_at", null);
        row.putAll(overrides);
        return row;
    }

    private void stubTenantRow(Map<String, Object> row) {
        when(platform.queryForList(contains("WHERE t.id = ?"), eq(TENANT_ID)))
                .thenReturn(List.of(row));
    }

    private static TenantDirectoryService.UpdateRequest updateRequest(
            String name, String shortName, String contactName, String phone, String remark) {
        return new TenantDirectoryService.UpdateRequest(
                name, shortName, null, null, null, contactName, phone, null, null, remark);
    }

    @Test
    void detailReturnsDecryptedPhoneAndFullProfile() {
        stubTenantRow(storedTenant(Map.of("contact_phone", "enc:abc", "short_name", "示例甲院")));
        when(crypto.decrypt("enc:abc")).thenReturn("13800000001");

        TenantDirectoryService.TenantDetail detail = service.detail(TENANT_ID);

        assertEquals("demoa", detail.code());
        assertEquals("示例甲院", detail.shortName());
        assertEquals("13800000001", detail.contactPhone());
        assertEquals("LONG_TERM", detail.expireType());
        assertNull(detail.expireAt());
    }

    @Test
    void detailOfMissingTenantIs40400() {
        when(platform.queryForList(contains("WHERE t.id = ?"), eq(TENANT_ID)))
                .thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> service.detail(TENANT_ID));
        assertEquals(40400, ex.code());
    }

    @Test
    void updateRejectsBlankOrTooLongValues() {
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                TENANT_ID,
                                new TenantDirectoryService.UpdateRequest(
                                        " ", null, null, null, null, null, null, null, null,
                                        null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                TENANT_ID,
                                new TenantDirectoryService.UpdateRequest(
                                        "虚构示例医院甲",
                                        "x".repeat(65),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        verify(platform, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void updateRejectsDuplicateHospitalName() {
        stubTenantRow(storedTenant(Map.of()));
        when(platform.queryForList(contains("WHERE t.name = ?"), eq("虚构示例医院乙")))
                .thenReturn(List.of(Map.of("id", 99L)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        TENANT_ID,
                                        updateRequest("虚构示例医院乙", null, null, null, null)));
        assertEquals(40901, ex.code());
        verify(platform, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void updateWritesChangedFieldsEncryptsPhoneAndAudits() {
        stubTenantRow(storedTenant(Map.of()));
        when(crypto.encrypt("plt-tenant-phone", "13800000002")).thenReturn("enc:new");

        TenantDirectoryService.TenantDetail detail =
                service.update(
                        TENANT_ID,
                        updateRequest("虚构示例医院甲", "示例甲院", "李经理（示例）", "13800000002", "续保联系人已换（示例）"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> sqlArgs = ArgumentCaptor.forClass(Object[].class);
        verify(platform).update(contains("UPDATE plt_tenant"), (Object[]) sqlArgs.capture());
        Object[] args = sqlArgs.getValue();
        // SET 列序：name, short_name, credit_code, org_type, region, contact_name,
        // contact_phone, contact_email, contract_no, remark + WHERE id
        assertEquals("虚构示例医院甲", args[0]);
        assertEquals("示例甲院", args[1]);
        assertEquals("李经理（示例）", args[5]);
        assertEquals("enc:new", args[6]);
        assertEquals("续保联系人已换（示例）", args[9]);
        assertEquals(TENANT_ID, args[10]);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("TENANT_UPDATE", event.getValue().eventType());
        assertEquals(Long.valueOf(5L), event.getValue().actorId());
        assertEquals("plt_tenant:demoa", event.getValue().target());
        String auditDetail = event.getValue().detail();
        assertTrue(auditDetail.contains("short_name"), auditDetail);
        assertTrue(auditDetail.contains("contact_phone"), auditDetail);
        assertTrue(!auditDetail.contains("13800000002"), "审计不得落电话明文：" + auditDetail);
        // 保存后重读详情返回（mock 下仍是原行，真实落库由 IT 验证）
        assertEquals("虚构示例医院甲", detail.name());
        verify(platform, org.mockito.Mockito.times(2))
                .queryForList(contains("WHERE t.id = ?"), eq(TENANT_ID));
    }

    @Test
    void updateWithoutAnyChangeSkipsWriteAndAudit() {
        stubTenantRow(storedTenant(Map.of("short_name", "示例甲院", "contact_phone", "enc:old")));
        when(crypto.decrypt("enc:old")).thenReturn("13800000001");

        service.update(TENANT_ID, updateRequest("虚构示例医院甲", "示例甲院", null, "13800000001", null));

        verify(platform, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).record(any());
        verify(crypto, never()).encrypt(anyString(), anyString());
    }

    @Test
    void updateOfMissingTenantIs40400() {
        when(platform.queryForList(contains("WHERE t.id = ?"), eq(TENANT_ID)))
                .thenReturn(List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        TENANT_ID,
                                        updateRequest("虚构示例医院甲", null, null, null, null)));
        assertEquals(40400, ex.code());
    }

    @Test
    void listAssemblesModulesAndUserCount() {
        when(platform.queryForObject(
                        contains("COUNT(*) FROM plt_tenant"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(platform.queryForList(contains("LIMIT ? OFFSET ?"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                storedTenant(
                                        Map.of(
                                                "expire_type",
                                                "FIXED",
                                                "expire_at",
                                                LocalDate.of(2027, 1, 31)))));
        when(platform.queryForList(contains("plt_tenant_module"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "tenant_id", TENANT_ID,
                                        "module_code", "base",
                                        "module_name", "平台基础")));
        when(routing.hasTenant(TENANT_ID)).thenReturn(true);
        when(platform.queryForObject(contains("sys_user"), eq(Long.class))).thenReturn(3L);

        TenantDirectoryService.PageView<TenantDirectoryService.TenantListItem> page =
                service.list(
                        new TenantDirectoryService.ListQuery(
                                null, "ENABLED", null, null, 1, 20, null));

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        TenantDirectoryService.TenantListItem item = page.items().get(0);
        assertEquals("demoa", item.code());
        assertEquals("FIXED", item.expireType());
        assertEquals(LocalDate.of(2027, 1, 31), item.expireAt());
        assertEquals(1, item.modules().size());
        assertEquals("平台基础", item.modules().get(0).name());
        assertEquals(Long.valueOf(3L), item.userCount());
    }

    @Test
    void listLeavesUserCountNullWhenTenantDatabaseUnavailable() {
        when(platform.queryForObject(
                        contains("COUNT(*) FROM plt_tenant"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(platform.queryForList(contains("LIMIT ? OFFSET ?"), any(Object[].class)))
                .thenReturn(List.of(storedTenant(Map.of())));
        when(platform.queryForList(contains("plt_tenant_module"), any(Object[].class)))
                .thenReturn(List.of());

        TenantDirectoryService.PageView<TenantDirectoryService.TenantListItem> page =
                service.list(
                        new TenantDirectoryService.ListQuery(null, null, null, null, 1, 20, null));

        assertNull(page.items().get(0).userCount(), "租户库不可达时用户数显示为空，不阻塞列表");
    }

    @Test
    void listRejectsUnknownStatusBeforeQuerying() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.list(
                                        new TenantDirectoryService.ListQuery(
                                                null, "GONE", null, null, 1, 20, null)));
        assertEquals(40001, ex.code());
    }

    @Test
    void actorResolutionToleratesMissingSession() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        try {
            stubTenantRow(storedTenant(Map.of()));
            service.update(TENANT_ID, updateRequest("虚构示例医院甲", "别名（示例）", null, null, null));
            // 权限切面在服务之前执行，这里只保证审计不因缺少会话上下文崩溃
            ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
            verify(audit).record(event.capture());
            assertNull(event.getValue().actorId());
        } finally {
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
}
