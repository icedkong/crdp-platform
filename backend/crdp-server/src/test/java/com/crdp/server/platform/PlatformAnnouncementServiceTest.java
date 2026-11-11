package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper.AnnouncementRow;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper.TenantRow;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * E05-T11 / PLT-CFG-009：系统公告发布校验与审计——标题 / 正文 / 范围（ALL=全部租户、TENANTS=指定租户）校验、
 * 指定租户必须存在（40400）、发布与撤下写平台审计； 端到端（全部 / 指定租户可见性、40300、40001）见 PlatformAnnouncementMySqlIT。
 */
@ExtendWith(MockitoExtension.class)
class PlatformAnnouncementServiceTest {

    @Mock private PlatformAnnouncementMapper mapper;
    @Mock private AuditLogger audit;

    private PlatformAnnouncementService service;

    @BeforeEach
    void setUp() {
        service = new PlatformAnnouncementService(mapper, audit);
        TenantContext.usePlatform();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void login() {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        9L,
                        "ops.demo",
                        "王运维（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of("platform:notice:edit"),
                        List.of(),
                        1L,
                        null,
                        false,
                        null,
                        "127.0.0.1",
                        0,
                        0,
                        0);
        SecurityContextHolder.setContext(new SecurityContextImpl());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication.TestingAuthenticationToken(
                                principal, null, List.of()));
    }

    private static void assertBadRequest(Runnable runnable, String messagePart) {
        ApiException error = assertThrows(ApiException.class, runnable::run);
        assertEquals(40001, error.code(), messagePart);
    }

    // ---------------------------------------------------------------- 发布校验

    @Test
    void rejectsNullCommand() {
        assertBadRequest(() -> service.publish(null), "请求体不能为空");
    }

    @Test
    void rejectsBlankOrTooLongTitle() {
        login();
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "  ", "内容", "ALL", null)),
                "标题不能为空");
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标".repeat(129), "内容", "ALL", null)),
                "标题长度");
    }

    @Test
    void rejectsBlankOrTooLongContent() {
        login();
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标题", " ", "ALL", null)),
                "正文不能为空");
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标题", "字".repeat(5001), "ALL", null)),
                "正文长度");
    }

    @Test
    void rejectsInvalidScopeAndAllWithTenantIds() {
        login();
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标题", "内容", "SOME", List.of())),
                "范围");
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标题", "内容", "ALL", List.of(1L))),
                "全部租户不能携带指定租户");
    }

    @Test
    void rejectsTenantScopeWithoutTenantIds() {
        login();
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标题", "内容", "TENANTS", null)),
                "指定租户不能为空");
        assertBadRequest(
                () ->
                        service.publish(
                                new PlatformAnnouncementService.PublishCommand(
                                        "标题", "内容", "TENANTS", List.of())),
                "指定租户不能为空");
    }

    @Test
    void rejectsUnknownTenantWith40400() {
        login();
        when(mapper.findTenants(anyList()))
                .thenReturn(List.of(new TenantRow(1L, "demoa", "演示医院 A（示例）")));
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.publish(
                                        new PlatformAnnouncementService.PublishCommand(
                                                "标题", "内容", "TENANTS", List.of(1L, 404L))));
        assertEquals(40400, error.code(), "未知租户 40400");
        verify(mapper, never()).insert(any(AnnouncementRow.class));
    }

    // ---------------------------------------------------------------- 发布与审计

    @Test
    void publishAllWritesRowAndAudit() {
        login();
        stubInsertAssignsId();
        service.publish(
                new PlatformAnnouncementService.PublishCommand("升级公告", "今晚 22:00 升级", "ALL", null));

        ArgumentCaptor<AnnouncementRow> captor = ArgumentCaptor.forClass(AnnouncementRow.class);
        verify(mapper).insert(captor.capture());
        assertEquals("ALL", captor.getValue().getScope());
        assertEquals("PUBLISHED", captor.getValue().getStatus());
        assertEquals("王运维（示例）（ops.demo）", captor.getValue().getPublishedByName());
        verify(mapper, never()).insertTenants(anyLong(), anyList());

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("ANNOUNCEMENT_PUBLISH", event.getValue().eventType());
        assertTrue(event.getValue().detail().contains("全部租户"), "审计注明发布范围");
    }

    @Test
    void publishToTenantsDeduplicatesAndLinksTenants() {
        login();
        when(mapper.findTenants(anyList()))
                .thenReturn(
                        List.of(
                                new TenantRow(1L, "demoa", "演示医院 A（示例）"),
                                new TenantRow(2L, "demob", "演示医院 B（示例）")));
        stubInsertAssignsId();
        service.publish(
                new PlatformAnnouncementService.PublishCommand(
                        "定向公告", "仅 A、B 可见", "TENANTS", List.of(1L, 2L, 2L)));

        verify(mapper).insertTenants(101L, List.of(1L, 2L));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("ANNOUNCEMENT_PUBLISH", event.getValue().eventType());
        assertTrue(event.getValue().detail().contains("指定租户"), "审计注明发布范围");
    }

    // ---------------------------------------------------------------- 撤下

    @Test
    void revokeUnknownReturns40400() {
        login();
        when(mapper.find(anyLong())).thenReturn(null);
        assertEquals(40400, assertThrows(ApiException.class, () -> service.revoke(404L)).code());
    }

    @Test
    void revokeAlreadyRevokedConflicts40901() {
        login();
        AnnouncementRow row = rowOf(7L);
        row.setStatus("REVOKED");
        when(mapper.find(anyLong())).thenReturn(row);
        assertEquals(40901, assertThrows(ApiException.class, () -> service.revoke(7L)).code());
    }

    @Test
    void revokeMarksRevokedAndAudits() {
        login();
        when(mapper.find(7L)).thenReturn(rowOf(7L));
        when(mapper.revoke(eq(7L), any(LocalDateTime.class), any())).thenReturn(1);
        service.revoke(7L);

        verify(mapper).revoke(eq(7L), any(LocalDateTime.class), eq(9L));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("ANNOUNCEMENT_REVOKE", event.getValue().eventType());
    }

    // ---------------------------------------------------------------- 分页

    @Test
    void pageValidatesParamsAndKeywordLength() {
        assertBadRequest(() -> service.page(0, 20, null), "pageNo");
        assertBadRequest(() -> service.page(1, 101, null), "pageSize");
        assertBadRequest(() -> service.page(1, 20, "长".repeat(65)), "关键词");
    }

    @Test
    void pageReturnsNewestFirstWithTenantNames() {
        login();
        AnnouncementRow all = rowOf(11L);
        AnnouncementRow scoped = rowOf(12L);
        scoped.setScope("TENANTS");
        when(mapper.page(anyString(), anyInt(), anyInt())).thenReturn(List.of(all, scoped));
        when(mapper.count(anyString())).thenReturn(2L);
        when(mapper.findTenantsOf(anyList()))
                .thenReturn(
                        List.of(
                                new PlatformAnnouncementMapper.TenantLinkRow(
                                        12L, 1L, "demoa", "演示医院 A（示例）")));

        var page = service.page(1, 20, "公告");
        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
        assertEquals("全部租户", page.items().get(0).scopeText());
        assertEquals(1, page.items().get(1).tenants().size());
        assertEquals("demoa", page.items().get(1).tenants().get(0).tenantCode());
    }

    // ---------------------------------------------------------------- 租户视角列表

    @Test
    void listForTenantMapsRows() {
        PlatformAnnouncementMapper.TenantAnnouncementRow row =
                new PlatformAnnouncementMapper.TenantAnnouncementRow(
                        3L, "停机维护", "今晚维护", LocalDateTime.of(2027, 7, 1, 9, 0));
        when(mapper.listForTenant(1L)).thenReturn(List.of(row));
        var items = service.listForTenant(1L);
        assertEquals(1, items.size());
        assertEquals("停机维护", items.get(0).title());
        assertTrue(items.get(0).publishedAt().endsWith("+08:00"), "北京时间 ISO-8601");
    }

    // ---------------------------------------------------------------- 辅助

    /** 模拟 MyBatis useGeneratedKeys：insert 后回填主键（真实库由数据库生成）。 */
    private void stubInsertAssignsId() {
        when(mapper.insert(any(AnnouncementRow.class)))
                .thenAnswer(
                        invocation -> {
                            AnnouncementRow row = invocation.getArgument(0);
                            row.setId(101L);
                            return 1;
                        });
    }

    private static AnnouncementRow rowOf(long id) {
        AnnouncementRow row = new AnnouncementRow();
        row.setId(id);
        row.setTitle("升级公告");
        row.setContent("今晚 22:00 升级");
        row.setScope("ALL");
        row.setStatus("PUBLISHED");
        row.setPublishedAt(LocalDateTime.of(2027, 7, 20, 10, 0));
        row.setPublishedBy(9L);
        row.setPublishedByName("王运维（示例）（ops.demo）");
        return row;
    }
}
