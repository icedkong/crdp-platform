package com.crdp.followup.notice.app;

import com.crdp.followup.notice.infra.mapper.NoticeMessageMapper;
import com.crdp.followup.notice.infra.mapper.NoticeMessageRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.MaskedByPolicy;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 站内信收件箱用例（E09-T02 / A-NTF-002、C-NTF-002；docs/api/notify.md §1）。
 *
 * <p>投递入口 {@link #deliver}：通知规则引擎（E09-T04）经 outbox 分发器（E09-T01，至少一次语义） 调用；dedup_key
 * 唯一键保证重复投递不重发。患者姓名只落 {@link CryptoService} 信封密文（{@code ntf-patient-name}）， 读取时解密成交由 {@link
 * MaskedByPolicy} 序列化面按接收人当前字段策略脱敏——不在落库时写死脱敏值（spec：每条可点击进入任务； 患者姓名按接收人权限脱敏）。
 *
 * <p>收件箱按 recipient_id 隔离（本人以外 / 不存在一律 40400，不暴露对象存在），ntf_message 不在 ControlledTables：
 * 站内信是个人待办提醒，不是患者数据范围改写的受控资源；点击消息跳到任务后由任务接口按数据范围再校验（越范围 40400， 随 E14/E15 fu_task 接口落地，fu_task 已登记在
 * ControlledTables）。
 */
@Service
public class NoticeInboxService {

    /** 患者姓名信封加密用途码（Ciphertext purpose 校验：小写与连字符）。 */
    public static final String PATIENT_NAME_PURPOSE = "ntf-patient-name";

    /** 分页口径（AGENTS.md §6）：pageNo 从 1 起、pageSize 默认 20、上限 100。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    private final NoticeMessageMapper mapper;
    private final CryptoService crypto;
    private final AuditLogger audit;

    public NoticeInboxService(NoticeMessageMapper mapper, CryptoService crypto, AuditLogger audit) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.audit = audit;
    }

    /** 点击跳转落点（notify.md §1 target）。 */
    public enum TargetKind {
        TASK,
        RECORD,
        PATIENT
    }

    /** 通道投递命令（E09-T04 规则引擎调用）：dedupKey = outbox 事件 ID + 接收人。 */
    public record Delivery(
            long recipientId,
            String eventType,
            String title,
            String summary,
            Long patientId,
            String patientName,
            String targetType,
            Long targetId,
            String dedupKey) {}

    /** 跳转落点视图：type + id，无落点的消息（如每日汇总）为 null。 */
    public record Target(TargetKind type, Long id) {}

    /**
     * 站内信视图（docs/api/notify.md §1）。patientName 由序列化面按接收人字段策略脱敏 / 隐藏（ HIDDEN 时整字段不出现——配 NON_NULL）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageView(
            long id,
            String eventType,
            String title,
            String summary,
            Long patientId,
            @MaskedByPolicy(resource = "patient", field = "name") String patientName,
            Target target,
            boolean read,
            Instant createdAt) {}

    /** 收件箱一页（统一分页口径）。 */
    public record InboxPage(List<MessageView> items, long total, int pageNo, int pageSize) {}

    /**
     * 投递一条站内信。幂等：dedup_key 撞唯一键时无操作。
     *
     * @return true = 新插入；false = 重复投递（至少一次语义下的重放）
     */
    public boolean deliver(Delivery delivery) {
        requireText(delivery.eventType(), "eventType");
        requireText(delivery.title(), "title");
        requireText(delivery.dedupKey(), "dedupKey");
        if (delivery.recipientId() <= 0) {
            throw invalid("recipientId");
        }
        TargetKind targetKind = null;
        if (delivery.targetType() != null && !delivery.targetType().isBlank()) {
            try {
                targetKind = TargetKind.valueOf(delivery.targetType().trim());
            } catch (IllegalArgumentException notAllowed) {
                throw invalid("targetType");
            }
            if (delivery.targetId() == null || delivery.targetId() <= 0) {
                throw invalid("targetId");
            }
        }
        NoticeMessageRow row =
                new NoticeMessageRow(
                        null,
                        delivery.recipientId(),
                        delivery.eventType().trim(),
                        delivery.title().trim(),
                        blankToNull(delivery.summary()),
                        delivery.patientId(),
                        encryptName(delivery.patientName()),
                        targetKind == null ? null : targetKind.name(),
                        targetKind == null ? null : delivery.targetId(),
                        delivery.dedupKey().trim(),
                        0,
                        null,
                        null);
        if (mapper.countByDedupKey(row.dedupKey()) > 0) {
            return false;
        }
        mapper.insert(row);
        return true;
    }

    /** 本人收件箱一页：unread=true 只看未读；按 id 倒序。 */
    public InboxPage page(Boolean unread, Integer pageNo, Integer pageSize) {
        long me = CurrentSession.requireHospital().userId();
        int no = pageNo == null ? 1 : pageNo;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (no < 1) {
            throw invalid("pageNo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("pageSize");
        }
        boolean onlyUnread = Boolean.TRUE.equals(unread);
        long total = mapper.count(me, onlyUnread);
        List<MessageView> items =
                mapper.page(me, onlyUnread, (no - 1) * size, size).stream()
                        .map(this::toView)
                        .toList();
        return new InboxPage(items, total, no, size);
    }

    /** 未读数（顶栏铃铛角标）。 */
    public long unreadCount() {
        long me = CurrentSession.requireHospital().userId();
        return mapper.count(me, true);
    }

    /** 标一条已读。别人的消息 / 不存在一律 40400（不暴露对象存在）并写 DENIED 操作审计； 已读的再标幂等成功。 */
    public void markRead(long id) {
        long me = CurrentSession.requireHospital().userId();
        NoticeMessageRow row = mapper.find(id);
        if (row == null || row.recipientId() != me) {
            audit.append(
                    AuditEntry.operation("notify", "NOTICE_INBOX_READ")
                            .objectType("NOTICE_MESSAGE")
                            .objectId(String.valueOf(id))
                            .description("标记他人或不存在的站内信为已读（不暴露对象存在）")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .build());
            throw new ApiException(404, 40400, "站内信不存在");
        }
        mapper.markRead(id, me);
    }

    /** 全部已读，返回置为已读的条数。 */
    public int markAllRead() {
        long me = CurrentSession.requireHospital().userId();
        return mapper.markAllRead(me);
    }

    private MessageView toView(NoticeMessageRow row) {
        Target target =
                row.targetType() == null
                        ? null
                        : new Target(TargetKind.valueOf(row.targetType()), row.targetId());
        return new MessageView(
                row.id(),
                row.eventType(),
                row.title(),
                row.summary(),
                row.patientId(),
                decryptName(row.patientNameCipher()),
                target,
                row.readFlag() == 1,
                row.createdAt() == null
                        ? null
                        : row.createdAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    private String encryptName(String plain) {
        return plain == null ? null : crypto.encrypt(PATIENT_NAME_PURPOSE, plain);
    }

    private String decryptName(String cipher) {
        if (cipher == null) {
            return null;
        }
        try {
            return crypto.decrypt(cipher);
        } catch (RuntimeException broken) {
            // 解不开按无姓名处理（收件箱不能因单条密文损坏打不开），不留明文日志
            return null;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
