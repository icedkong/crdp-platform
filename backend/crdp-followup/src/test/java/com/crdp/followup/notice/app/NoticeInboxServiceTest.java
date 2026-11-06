package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.infra.mapper.NoticeMessageMapper;
import com.crdp.followup.notice.infra.mapper.NoticeMessageRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E09-T02 / A-NTF-002、C-NTF-002：站内信收件箱用例单元测试（docs/api/notify.md §1）。
 *
 * <p>锁定：投递幂等（outbox 至少一次语义，重复 dedup_key 不报错不重发）、患者姓名信封加密落库、 读取时解密（脱敏由 @MaskedByPolicy 序列化面负责，见
 * PolicyMaskedSerializerTest）、分页口径（pageNo 从 1、pageSize 默认 20 上限 100）、 标已读越权 40400 + DENIED
 * 审计。全部虚构数据。
 */
class NoticeInboxServiceTest {

    private static final long ME = 7L;
    private static final long OTHER = 8L;
    private static final String PLAIN_NAME = "张小明（示例）";
    private static final String NAME_CIPHER = "cipher:name";

    private final NoticeMessageMapper mapper = mock(NoticeMessageMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final NoticeInboxService service = new NoticeInboxService(mapper, crypto, audit);

    @BeforeEach
    void login() {
        when(crypto.encrypt(NoticeInboxService.PATIENT_NAME_PURPOSE, PLAIN_NAME))
                .thenReturn(NAME_CIPHER);
        when(crypto.decrypt(NAME_CIPHER)).thenReturn(PLAIN_NAME);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 投递（通道入口，E09-T04 规则引擎调用）

    /** 正常投递：姓名信封加密落库、target 与 dedup 透传；返回 true 表示新插入。 */
    @Test
    void deliverInsertsEncryptedRow() {
        when(mapper.insert(any(NoticeMessageRow.class))).thenReturn(1);

        boolean inserted =
                service.deliver(
                        new NoticeInboxService.Delivery(
                                ME,
                                "TASK_OPENED",
                                "随访任务已开启",
                                "第 4 年随访窗口已开启（示例）",
                                990_001L,
                                PLAIN_NAME,
                                "TASK",
                                4242L,
                                "outbox-17:7"));

        assertTrue(inserted);
        ArgumentCaptor<NoticeMessageRow> row = ArgumentCaptor.forClass(NoticeMessageRow.class);
        verify(mapper).insert(row.capture());
        assertEquals(NAME_CIPHER, row.getValue().patientNameCipher(), "姓名必须密文落库");
        assertEquals(ME, row.getValue().recipientId());
        assertEquals("TASK_OPENED", row.getValue().eventType());
        assertEquals("TASK", row.getValue().targetType());
        assertEquals(4242L, row.getValue().targetId());
        assertEquals("outbox-17:7", row.getValue().dedupKey());
        assertEquals(0, row.getValue().readFlag());
    }

    /** 幂等（至少一次语义）：同一 dedup_key 重复投递不报错、不重复插入。 */
    @Test
    void deliverIsIdempotentOnDuplicateDedupKey() {
        when(mapper.countByDedupKey("outbox-17:7")).thenReturn(1);

        boolean inserted =
                service.deliver(
                        new NoticeInboxService.Delivery(
                                ME,
                                "TASK_OPENED",
                                "随访任务已开启",
                                null,
                                null,
                                null,
                                "TASK",
                                4242L,
                                "outbox-17:7"));

        assertFalse(inserted, "重复投递不应视为新消息");
        verify(mapper, never()).insert(any());
    }

    /** 校验失败（40001）：缺接收人 / 事件类型 / 标题 / 幂等键。 */
    @Test
    void deliverRejectsBlankFields() {
        NoticeInboxService.Delivery base =
                new NoticeInboxService.Delivery(
                        ME, "TASK_OPENED", "随访任务已开启", null, null, null, "TASK", 4242L, "k");

        assertThrows(
                ApiException.class,
                () ->
                        service.deliver(
                                new NoticeInboxService.Delivery(
                                        0,
                                        base.eventType(),
                                        base.title(),
                                        null,
                                        null,
                                        null,
                                        base.targetType(),
                                        base.targetId(),
                                        base.dedupKey())));
        assertThrows(
                ApiException.class,
                () ->
                        service.deliver(
                                new NoticeInboxService.Delivery(
                                        ME,
                                        " ",
                                        base.title(),
                                        null,
                                        null,
                                        null,
                                        base.targetType(),
                                        base.targetId(),
                                        base.dedupKey())));
        assertThrows(
                ApiException.class,
                () ->
                        service.deliver(
                                new NoticeInboxService.Delivery(
                                        ME,
                                        base.eventType(),
                                        " ",
                                        null,
                                        null,
                                        null,
                                        base.targetType(),
                                        base.targetId(),
                                        base.dedupKey())));
        assertThrows(
                ApiException.class,
                () ->
                        service.deliver(
                                new NoticeInboxService.Delivery(
                                        ME,
                                        base.eventType(),
                                        base.title(),
                                        null,
                                        null,
                                        null,
                                        base.targetType(),
                                        base.targetId(),
                                        " ")));
        verify(mapper, never()).insert(any());
    }

    /** target_type 只允许 TASK | RECORD | PATIENT（点击跳转的落点口径）。 */
    @Test
    void deliverRejectsUnknownTargetType() {
        when(mapper.insert(any(NoticeMessageRow.class))).thenReturn(1);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.deliver(
                                        new NoticeInboxService.Delivery(
                                                ME,
                                                "TASK_OPENED",
                                                "随访任务已开启",
                                                null,
                                                null,
                                                null,
                                                "VISIT",
                                                1L,
                                                "k")));
        assertEquals(40001, error.code());
        verify(mapper, never()).insert(any());
    }

    // ---------------------------------------------------------------- 收件箱查询

    /** 列表：只查本人（recipient 条件由服务层填充）、未读过滤透传、姓名密文解密成明文交给序列化面脱敏。 */
    @Test
    void pageMapsRowsForCurrentRecipientAndDecryptsName() {
        when(mapper.count(ME, true)).thenReturn(1L);
        when(mapper.page(ME, true, 0, 20))
                .thenReturn(
                        List.of(
                                new NoticeMessageRow(
                                        11L,
                                        ME,
                                        "TASK_OPENED",
                                        "随访任务已开启",
                                        "第 4 年随访窗口已开启（示例）",
                                        990_001L,
                                        NAME_CIPHER,
                                        "TASK",
                                        4242L,
                                        "outbox-17:7",
                                        0,
                                        null,
                                        LocalDateTime.of(2026, 11, 2, 8, 30, 0))));

        NoticeInboxService.InboxPage page = service.page(true, 1, 20);

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        NoticeInboxService.MessageView view = page.items().getFirst();
        assertEquals(11L, view.id());
        assertEquals("TASK_OPENED", view.eventType());
        assertEquals(PLAIN_NAME, view.patientName(), "读取时解密，脱敏交给 @MaskedByPolicy");
        assertEquals(NoticeInboxService.TargetKind.TASK, view.target().type());
        assertEquals(4242L, view.target().id());
        assertFalse(view.read());
        assertEquals(
                LocalDateTime.of(2026, 11, 2, 8, 30, 0)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant(),
                view.createdAt(),
                "库内 DATETIME 按会话时区转 Instant（接口 ISO-8601 带时区，与 ProfileService 同口径）");
    }

    /** 分页口径（与 AuditQuerySupport 同规）：pageNo 从 1 起、pageSize 默认 20、上限 100；越界 40001。 */
    @Test
    void pageValidatesPaging() {
        assertEquals(
                NoticeInboxService.DEFAULT_PAGE_SIZE, service.page(null, null, null).pageSize());
        assertEquals(1, service.page(null, null, null).pageNo());

        assertEquals(
                40001, assertThrows(ApiException.class, () -> service.page(null, 0, null)).code());
        assertEquals(
                40001, assertThrows(ApiException.class, () -> service.page(null, 1, 0)).code());
        assertEquals(
                40001, assertThrows(ApiException.class, () -> service.page(null, 1, 101)).code());
    }

    /** unread=true 过滤未读；不传看全部。 */
    @Test
    void pagePassesUnreadFilter() {
        when(mapper.count(ME, true)).thenReturn(0L);
        when(mapper.count(ME, false)).thenReturn(5L);

        service.page(Boolean.TRUE, 1, 20);
        verify(mapper).page(ME, true, 0, 20);

        service.page(null, 1, 20);
        verify(mapper).page(ME, false, 0, 20);
    }

    /** 未读数：本人未读条数（铃铛角标）。 */
    @Test
    void unreadCountIsScopedToCurrentUser() {
        when(mapper.count(ME, true)).thenReturn(3L);
        assertEquals(3L, service.unreadCount());
        verify(mapper).count(ME, true);
    }

    // ---------------------------------------------------------------- 已读

    /** 标已读：本人消息置 read_flag=1；已读的再标一次仍成功（幂等）。 */
    @Test
    void markReadOwnMessage() {
        when(mapper.find(11L)).thenReturn(row(11L, ME, 0));
        when(mapper.markRead(11L, ME)).thenReturn(1);

        service.markRead(11L);
        verify(mapper).markRead(11L, ME);
        verify(audit, never()).append(any());
    }

    @Test
    void markReadAlreadyReadIsIdempotent() {
        when(mapper.find(11L)).thenReturn(row(11L, ME, 1));
        when(mapper.markRead(11L, ME)).thenReturn(0);

        service.markRead(11L);
    }

    /** 越范围（40400）：别人的消息 / 不存在的消息一律 40400 不暴露存在，并写 DENIED 审计（不记标题等业务内容）。 */
    @Test
    void markReadOtherUsersOrMissingMessageGets40400AndDeniedAudit() {
        when(mapper.find(11L)).thenReturn(row(11L, OTHER, 0));
        when(mapper.find(12L)).thenReturn(null);

        assertEquals(40400, assertThrows(ApiException.class, () -> service.markRead(11L)).code());
        assertEquals(40400, assertThrows(ApiException.class, () -> service.markRead(12L)).code());
        verify(mapper, never()).markRead(anyLong(), anyLong());

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).append(entry.capture());
        assertEquals(AuditResult.DENIED, entry.getValue().result());
        assertEquals(40400, entry.getValue().errorCode());
        assertEquals("NOTICE_INBOX_READ", entry.getValue().action());
    }

    /** 全部已读：只影响本人未读行，返回条数。 */
    @Test
    void markAllReadOnlyTouchesOwnRows() {
        when(mapper.markAllRead(ME)).thenReturn(4);
        assertEquals(4, service.markAllRead());
        verify(mapper).markAllRead(ME);
    }

    // ---------------------------------------------------------------- 基础设施

    private static NoticeMessageRow row(long id, long recipient, int readFlag) {
        return new NoticeMessageRow(
                id,
                recipient,
                "TASK_OPENED",
                "随访任务已开启",
                null,
                null,
                null,
                "TASK",
                4242L,
                "k",
                readFlag,
                null,
                LocalDateTime.of(2026, 11, 2, 8, 30, 0));
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                ME,
                "doctor.demo",
                "张医生（示例）",
                List.of("DOCTOR"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
