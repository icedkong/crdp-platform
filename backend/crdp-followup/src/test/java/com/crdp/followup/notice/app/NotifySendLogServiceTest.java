package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.infra.mapper.NotifySendLogItemRow;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E09-T07 / A-NTF-006、C-NTF-006：发送台账查询与失败重试单元测试。
 *
 * <p>锁定：条件查询分页口径与批量查询审计（BATCH_QUERY 带条数与条件摘要）、条件校验（未知事件 / 渠道 / 状态、 分页越界、日期反序 → 40001）、重试只允许
 * FAILED（回 QUEUED、保留原失败原因、写 NOTICE_SEND_RETRY 审计）， 不存在 40400、非 FAILED 40001、并发已重试幂等返回现行值。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifySendLogServiceTest {

    private final NotifySendLogMapper sendLog = mock(NotifySendLogMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final NotifySendLogService service = new NotifySendLogService(sendLog, audit);

    private static NotifySendLogItemRow row(long id, String status) {
        return new NotifySendLogItemRow(
                id,
                "TASK_OVERDUE",
                7L,
                "张医生（示例）",
                "SMS",
                status,
                "已逾期：第 5 年随访",
                null,
                LocalDateTime.of(2026, 11, 2, 8, 30),
                LocalDateTime.of(2026, 11, 2, 8, 30));
    }

    /** 查询：条件透传（事件 / 渠道 / 状态 / 接收人 / 日期）、分页口径、写 BATCH_QUERY 审计。 */
    @Test
    void searchPassesFiltersAndAuditsBatchQuery() {
        when(sendLog.search(
                        eq("TASK_OVERDUE"),
                        eq("SMS"),
                        eq("FAILED"),
                        eq(7L),
                        eq(LocalDate.of(2026, 10, 1)),
                        eq(LocalDate.of(2026, 10, 31)),
                        eq(20),
                        eq(20)))
                .thenReturn(List.of(row(1L, "FAILED")));
        when(sendLog.count(
                        eq("TASK_OVERDUE"),
                        eq("SMS"),
                        eq("FAILED"),
                        eq(7L),
                        eq(LocalDate.of(2026, 10, 1)),
                        eq(LocalDate.of(2026, 10, 31))))
                .thenReturn(21L);

        NotifySendLogService.SendLogPage page =
                service.search(
                        new NotifySendLogService.SendLogQuery(
                                2,
                                20,
                                "TASK_OVERDUE",
                                "SMS",
                                "FAILED",
                                7L,
                                LocalDate.of(2026, 10, 1),
                                LocalDate.of(2026, 10, 31)));

        assertEquals(21L, page.total());
        assertEquals(2, page.pageNo());
        assertEquals(1, page.items().size());
        assertEquals("已逾期", page.items().get(0).eventLabel(), "事件中文名来自目录");
        assertEquals("张医生（示例）", page.items().get(0).recipientName());

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("BATCH_QUERY", entry.getValue().action());
        assertEquals(21L, entry.getValue().recordCount());
        assertTrue(entry.getValue().queryDigest().contains("TASK_OVERDUE"));
    }

    /** 查询：空条件查全部，分页回落默认（pageNo=1、pageSize=20）；null 请求体同样可用。 */
    @Test
    void searchDefaultsToFirstPageAndNoFilters() {
        when(sendLog.search(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(List.of());
        when(sendLog.count(isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(0L);

        NotifySendLogService.SendLogPage page =
                service.search(
                        new NotifySendLogService.SendLogQuery(
                                null, null, null, null, null, null, null, null));
        service.search(null);

        assertEquals(0, page.total());
        assertEquals(1, page.pageNo());
        verify(audit, org.mockito.Mockito.times(2)).append(any(AuditEntry.class));
    }

    // ---------------------------------------------------------------- 校验失败（40001）

    @Test
    void searchRejectsUnknownFiltersAndBadPaging() {
        assertThrows(
                ApiException.class,
                () ->
                        service.search(
                                new NotifySendLogService.SendLogQuery(
                                        1, 20, "NOT_EXIST", null, null, null, null, null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.search(
                                new NotifySendLogService.SendLogQuery(
                                        1, 20, null, "EMAIL", null, null, null, null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.search(
                                new NotifySendLogService.SendLogQuery(
                                        1, 20, null, null, "SENDING", null, null, null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.search(
                                new NotifySendLogService.SendLogQuery(
                                        0, 20, null, null, null, null, null, null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.search(
                                new NotifySendLogService.SendLogQuery(
                                        1, 101, null, null, null, null, null, null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.search(
                                new NotifySendLogService.SendLogQuery(
                                        1,
                                        20,
                                        null,
                                        null,
                                        null,
                                        null,
                                        LocalDate.of(2026, 11, 2),
                                        LocalDate.of(2026, 11, 1))));
        verify(sendLog, never())
                .search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---------------------------------------------------------------- 重试

    /** 失败重试：FAILED → QUEUED、detail 保留原失败原因、写 NOTICE_SEND_RETRY 审计、返回最新行。 */
    @Test
    void retryRequeuesFailedRowWithAudit() {
        when(sendLog.find(9L))
                .thenReturn(
                        new NotifySendLogRow(
                                9L,
                                "TASK_OVERDUE",
                                7L,
                                "SMS",
                                "FAILED",
                                "已逾期：第 5 年随访",
                                "通道未配置",
                                "77:7:SMS",
                                LocalDateTime.of(2026, 11, 1, 8, 30),
                                LocalDateTime.of(2026, 11, 1, 8, 30)))
                .thenReturn(
                        new NotifySendLogRow(
                                9L,
                                "TASK_OVERDUE",
                                7L,
                                "SMS",
                                "QUEUED",
                                "已逾期：第 5 年随访",
                                "手动重试，重新排队等待汇总发送（原失败原因：通道未配置）",
                                "77:7:SMS",
                                null,
                                LocalDateTime.of(2026, 11, 1, 8, 30)));
        when(sendLog.requeue(eq(9L), any())).thenReturn(1);

        NotifySendLogService.RetryResult result = service.retry(9);

        assertTrue(result.requeued());
        assertEquals("QUEUED", result.item().sendStatus());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sendLog).requeue(eq(9L), detail.capture());
        assertTrue(detail.getValue().contains("通道未配置"), "保留原失败原因：" + detail.getValue());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("NOTICE_SEND_RETRY", entry.getValue().action());
        assertEquals("9", entry.getValue().objectId());
    }

    /** 不存在 40400（不暴露存在性口径同站内信）。 */
    @Test
    void retryRejectsMissingRow() {
        when(sendLog.find(anyLong())).thenReturn(null);
        ApiException error = assertThrows(ApiException.class, () -> service.retry(404));
        assertEquals(40400, error.code());
    }

    /** 非 FAILED（QUEUED / SENT / SKIPPED）不可重试 → 40001。 */
    @Test
    void retryRejectsNonFailedRows() {
        for (String status : List.of("QUEUED", "SENT", "SKIPPED")) {
            when(sendLog.find(1L)).thenReturn(failedRowWithStatus(status));
            ApiException error = assertThrows(ApiException.class, () -> service.retry(1));
            assertEquals(40001, error.code());
        }
        verify(sendLog, never()).requeue(anyLong(), any());
    }

    /** 并发下他人已重试（条件更新 0 行）：不报错、不写审计，返回现行值（幂等）。 */
    @Test
    void retryIsIdempotentWhenConcurrentlyRequeued() {
        when(sendLog.find(5L)).thenReturn(failedRowWithStatus("FAILED"));
        when(sendLog.requeue(eq(5L), any())).thenReturn(0);
        when(sendLog.find(5L)).thenReturn(failedRowWithStatus("FAILED"));

        NotifySendLogService.RetryResult result = service.retry(5);

        assertEquals(false, result.requeued());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    private static NotifySendLogRow failedRowWithStatus(String status) {
        return new NotifySendLogRow(
                5L, "DAILY_DIGEST", 7L, "SMS", status, "每日汇总", null, "88:7:SMS", null, null);
    }
}
