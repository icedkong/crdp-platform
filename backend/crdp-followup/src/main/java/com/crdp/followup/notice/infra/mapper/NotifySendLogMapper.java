package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知发送台账 ntf_send_log（E09-T04 / A-NTF-006 存储；E09-T07 查询与失败重试；E09-T05 汇总作业消费 QUEUED 行）。
 * 调用前必须已在租户上下文。 outbox 至少一次语义下引擎可能重放：dedup_key 唯一键 + 先查后插保证不重记（并发竞态由唯一键兜底）。 ntf_send_log 不在
 * ControlledTables（台账无患者维度，接口按权限码 followup:notice:config 控制）。
 */
@Mapper
public interface NotifySendLogMapper {

    /** 该幂等键是否已记录（重放识别）。 */
    @Select("SELECT COUNT(*) FROM ntf_send_log WHERE dedup_key = #{dedupKey}")
    int countByDedupKey(@Param("dedupKey") String dedupKey);

    /** 有待汇总短信的接收人（E09-T05 作业按人合并的入口，A-NTF-003）。 */
    @Select(
            """
            SELECT DISTINCT recipient_id FROM ntf_send_log
            WHERE channel = 'SMS' AND send_status = 'QUEUED'
            ORDER BY recipient_id
            """)
    List<Long> listQueuedSmsRecipients();

    /** 某接收人的全部待汇总短信行（同日合并为一条汇总短信，id 序稳定）。 */
    @Select(
            """
            SELECT id, event_type AS eventType, recipient_id AS recipientId, channel,
                   send_status AS sendStatus, title, detail, dedup_key AS dedupKey,
                   sent_at AS sentAt, created_at AS createdAt
            FROM ntf_send_log
            WHERE recipient_id = #{recipientId} AND channel = 'SMS' AND send_status = 'QUEUED'
            ORDER BY id
            """)
    List<NotifySendLogRow> listQueuedSms(@Param("recipientId") long recipientId);

    /** 某接收人在 [from, to) 内已成功发出的短信条数（A-NTF-005 每人每日上限的持久口径）。 */
    @Select(
            """
            SELECT COUNT(*) FROM ntf_send_log
            WHERE recipient_id = #{recipientId} AND channel = 'SMS' AND send_status = 'SENT'
              AND sent_at >= #{from} AND sent_at < #{to}
            """)
    int countSentSmsBetween(
            @Param("recipientId") long recipientId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 某接收人在 [from, to) 内已投的每日汇总站内信条数（事件侧已发过当日汇总则作业不再重复投）。 */
    @Select(
            """
            SELECT COUNT(*) FROM ntf_send_log
            WHERE recipient_id = #{recipientId} AND event_type = 'DAILY_DIGEST'
              AND channel = 'INBOX' AND created_at >= #{from} AND created_at < #{to}
            """)
    int countDigestInboxBetween(
            @Param("recipientId") long recipientId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 汇总作业批量改状态（E09-T05）：只动仍是 QUEUED 的行（发送成功 → SENT、失败 → FAILED、接收人停用 → SKIPPED）， 并发 /
     * 重跑下已是终态的行不被覆盖。
     */
    @Update(
            """
            <script>
            UPDATE ntf_send_log
            SET send_status = #{status}, sent_at = #{sentAt}, detail = #{detail}
            WHERE send_status = 'QUEUED' AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markQueued(
            @Param("ids") List<Long> ids,
            @Param("status") String status,
            @Param("sentAt") LocalDateTime sentAt,
            @Param("detail") String detail);

    /** 幂等插入：撞唯一键无操作。 */
    @Insert(
            """
            INSERT INTO ntf_send_log
              (event_type, recipient_id, channel, send_status, title, detail, dedup_key, sent_at)
            VALUES (#{eventType}, #{recipientId}, #{channel}, #{sendStatus}, #{title}, #{detail},
                    #{dedupKey}, #{sentAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insert(NotifySendLogRow row);

    /** 条件查一页（E09-T07 / A-NTF-006「按事件、渠道、结果、日期、接收人查询」），id 倒序。 */
    @Select(
            """
            <script>
            SELECT s.id, s.event_type AS eventType, s.recipient_id AS recipientId,
                   u.display_name AS recipientName, s.channel, s.send_status AS sendStatus,
                   s.title, s.detail, s.sent_at AS sentAt, s.created_at AS createdAt
            FROM ntf_send_log s
            LEFT JOIN sys_user u ON u.id = s.recipient_id
            <where>
              <if test="eventType != null">AND s.event_type = #{eventType}</if>
              <if test="channel != null">AND s.channel = #{channel}</if>
              <if test="sendStatus != null">AND s.send_status = #{sendStatus}</if>
              <if test="recipientId != null">AND s.recipient_id = #{recipientId}</if>
              <if test="dateFrom != null">AND s.created_at &gt;= #{dateFrom}</if>
              <if test="dateTo != null">AND s.created_at &lt; DATE_ADD(#{dateTo}, INTERVAL 1 DAY)</if>
            </where>
            ORDER BY s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<NotifySendLogItemRow> search(
            @Param("eventType") String eventType,
            @Param("channel") String channel,
            @Param("sendStatus") String sendStatus,
            @Param("recipientId") Long recipientId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 条件总数（与 {@link #search} 同一条件树）。 */
    @Select(
            """
            <script>
            SELECT COUNT(*) FROM ntf_send_log s
            <where>
              <if test="eventType != null">AND s.event_type = #{eventType}</if>
              <if test="channel != null">AND s.channel = #{channel}</if>
              <if test="sendStatus != null">AND s.send_status = #{sendStatus}</if>
              <if test="recipientId != null">AND s.recipient_id = #{recipientId}</if>
              <if test="dateFrom != null">AND s.created_at &gt;= #{dateFrom}</if>
              <if test="dateTo != null">AND s.created_at &lt; DATE_ADD(#{dateTo}, INTERVAL 1 DAY)</if>
            </where>
            </script>
            """)
    long count(
            @Param("eventType") String eventType,
            @Param("channel") String channel,
            @Param("sendStatus") String sendStatus,
            @Param("recipientId") Long recipientId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

    /** 单条（重试用；不存在 → 应用层 40400）。 */
    @Select(
            """
            SELECT id, event_type AS eventType, recipient_id AS recipientId, channel,
                   send_status AS sendStatus, title, detail, dedup_key AS dedupKey,
                   sent_at AS sentAt, created_at AS createdAt
            FROM ntf_send_log WHERE id = #{id}
            """)
    NotifySendLogRow find(@Param("id") long id);

    /**
     * 失败重试（A-NTF-006「失败可重试」）：FAILED → QUEUED（短信由 E09-T05 汇总作业按人合并发送， 同样受免打扰与每日上限约束）。 条件带
     * send_status='FAILED'，并发下他人已重试 / 状态已变则更新 0 行（幂等）。
     */
    @Update(
            """
            UPDATE ntf_send_log
            SET send_status = 'QUEUED', sent_at = NULL, detail = #{detail}
            WHERE id = #{id} AND send_status = 'FAILED'
            """)
    int requeue(@Param("id") long id, @Param("detail") String detail);
}
