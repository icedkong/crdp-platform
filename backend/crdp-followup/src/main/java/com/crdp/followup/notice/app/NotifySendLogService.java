package com.crdp.followup.notice.app;

import com.crdp.followup.notice.domain.NotifyChannel;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.domain.NotifySendStatus;
import com.crdp.followup.notice.infra.mapper.NotifySendLogItemRow;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知发送记录查询与失败重试（E09-T07 / A-NTF-006、C-NTF-006；docs/api/notify.md §4， 通知设置页「发送记录」页签）。
 *
 * <p>验收「可查每条发送结果」：按事件、渠道、结果、日期、接收人条件查询台账（A 规格「通知发送记录」页）。 查询是批量查询，写 PLT-AUD-003
 * 数据访问审计（BATCH_QUERY，常规读不阻断业务）。验收「失败可重试」： FAILED 行回 QUEUED——短信由 E09-T05 汇总作业按人合并发送，同样受免打扰与每日上限约束（当前
 * E09-T05 未落地，重试后队列等待不丢）。重试写 NOTICE_SEND_RETRY 操作审计。
 */
@Service
public class NotifySendLogService {

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 100;

    private final NotifySendLogMapper sendLog;
    private final AuditLogger audit;

    public NotifySendLogService(NotifySendLogMapper sendLog, AuditLogger audit) {
        this.sendLog = sendLog;
        this.audit = audit;
    }

    /** 查询条件（全部可空；dateFrom/dateTo 按创建时间过滤，闭区间按日）。 */
    public record SendLogQuery(
            Integer pageNo,
            Integer pageSize,
            String eventType,
            String channel,
            String sendStatus,
            Long recipientId,
            LocalDate dateFrom,
            LocalDate dateTo) {}

    /** 台账行视图（notify.md §4）：接收人展示名来自 sys_user；title/detail/sentAt 可空（无值字段不出现）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendLogItem(
            long id,
            String eventType,
            String eventLabel,
            long recipientId,
            String recipientName,
            String channel,
            String sendStatus,
            String title,
            String detail,
            java.time.LocalDateTime sentAt,
            java.time.LocalDateTime createdAt) {}

    /** 分页结果（统一分页口径）。 */
    public record SendLogPage(List<SendLogItem> items, long total, int pageNo, int pageSize) {}

    /** 重试结果：返回最新行。 */
    public record RetryResult(SendLogItem item, boolean requeued) {}

    /** 条件查询一页（校验条件 → 查询 → 批量查询审计）。 */
    public SendLogPage search(SendLogQuery query) {
        SendLogQuery safe =
                query == null
                        ? new SendLogQuery(null, null, null, null, null, null, null, null)
                        : query;
        int pageNo = safe.pageNo() == null ? 1 : safe.pageNo();
        int pageSize = safe.pageSize() == null ? PAGE_SIZE_DEFAULT : safe.pageSize();
        if (pageNo < 1) {
            throw invalid("pageNo（从 1 开始）");
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAX) {
            throw invalid("pageSize（1–" + PAGE_SIZE_MAX + "）");
        }
        String eventType = validateEventType(safe.eventType());
        String channel = validateChannel(safe.channel());
        String sendStatus = validateStatus(safe.sendStatus());
        if (safe.dateFrom() != null
                && safe.dateTo() != null
                && safe.dateFrom().isAfter(safe.dateTo())) {
            throw invalid("dateFrom / dateTo（开始日期不能晚于结束日期）");
        }
        int offset = (pageNo - 1) * pageSize;
        List<SendLogItem> items = new ArrayList<>();
        for (NotifySendLogItemRow row :
                sendLog.search(
                        eventType,
                        channel,
                        sendStatus,
                        safe.recipientId(),
                        safe.dateFrom(),
                        safe.dateTo(),
                        offset,
                        pageSize)) {
            items.add(toItem(row));
        }
        long total =
                sendLog.count(
                        eventType,
                        channel,
                        sendStatus,
                        safe.recipientId(),
                        safe.dateFrom(),
                        safe.dateTo());
        audit.append(
                AuditEntry.dataAccess("notify", "BATCH_QUERY")
                        .objectType("NTF_SEND_LOG")
                        .description("查询通知发送记录")
                        .recordCount(total)
                        .queryDigest(digest(safe))
                        .build());
        return new SendLogPage(items, total, pageNo, pageSize);
    }

    /** 失败重试：FAILED → QUEUED（幂等——并发下他人已重试则不再更新，返回现行值）。 不存在 40400；非 FAILED 40001。 */
    @Transactional
    public RetryResult retry(long id) {
        NotifySendLogRow row = sendLog.find(id);
        if (row == null) {
            throw new ApiException(404, 40400, "发送记录不存在");
        }
        if (!NotifySendStatus.FAILED.name().equals(row.sendStatus())) {
            throw invalid("sendStatus（仅失败记录可重试）");
        }
        String detail =
                "手动重试，重新排队等待汇总发送（原失败原因：" + (row.detail() == null ? "未记录" : row.detail()) + "）";
        boolean requeued = sendLog.requeue(id, detail) > 0;
        if (requeued) {
            audit.append(
                    AuditEntry.operation("notify", "NOTICE_SEND_RETRY")
                            .objectType("NTF_SEND_LOG")
                            .objectId(String.valueOf(id))
                            .description("重试失败的通知发送")
                            .afterJson(
                                    "{\"sendStatus\":\"QUEUED\",\"channel\":\""
                                            + row.channel()
                                            + "\",\"eventType\":\""
                                            + row.eventType()
                                            + "\"}")
                            .build());
        }
        NotifySendLogRow latest = sendLog.find(id);
        return new RetryResult(
                toItem(
                        new NotifySendLogItemRow(
                                latest.id(),
                                latest.eventType(),
                                latest.recipientId(),
                                null,
                                latest.channel(),
                                latest.sendStatus(),
                                latest.title(),
                                latest.detail(),
                                latest.sentAt(),
                                latest.createdAt())),
                requeued);
    }

    // ---------------------------------------------------------------- 内部

    private SendLogItem toItem(NotifySendLogItemRow row) {
        return new SendLogItem(
                row.id(),
                row.eventType(),
                NotifyEventType.fromCode(row.eventType())
                        .map(NotifyEventType::label)
                        .orElse(row.eventType()),
                row.recipientId(),
                row.recipientName(),
                row.channel(),
                row.sendStatus(),
                row.title(),
                row.detail(),
                row.sentAt(),
                row.createdAt());
    }

    private String validateEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        if (NotifyEventType.fromCode(eventType).isEmpty()) {
            throw invalid("eventType（未知通知事件）");
        }
        return eventType.trim();
    }

    private String validateChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return NotifyChannel.valueOf(channel.trim()).name();
        } catch (IllegalArgumentException unknown) {
            throw invalid("channel（INBOX / SMS）");
        }
    }

    private String validateStatus(String sendStatus) {
        if (sendStatus == null || sendStatus.isBlank()) {
            return null;
        }
        return NotifySendStatus.fromCode(sendStatus)
                .orElseThrow(() -> invalid("sendStatus（QUEUED / SENT / FAILED / SKIPPED）"))
                .name();
    }

    private static String digest(SendLogQuery query) {
        return "{\"eventType\":\""
                + query.eventType()
                + "\",\"channel\":\""
                + query.channel()
                + "\",\"sendStatus\":\""
                + query.sendStatus()
                + "\",\"recipientId\":"
                + query.recipientId()
                + ",\"dateFrom\":\""
                + query.dateFrom()
                + "\",\"dateTo\":\""
                + query.dateTo()
                + "\"}";
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
