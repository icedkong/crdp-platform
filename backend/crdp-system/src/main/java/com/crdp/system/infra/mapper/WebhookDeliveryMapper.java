package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Webhook 投递台账 sys_webhook_delivery（E09-T06 重试队列 + PLT-CFG-007 投递记录）。调用前必须已在租户上下文。 (webhook_id,
 * outbox_event_id) 唯一键保证 outbox 至少一次语义下订阅方重放不重排（测试投递 event 为 NULL 不受约束）。
 */
@Mapper
public interface WebhookDeliveryMapper {

    String COLUMNS =
            "id, webhook_id AS webhookId, outbox_event_id AS outboxEventId, event_type AS"
                    + " eventType, object_type AS objectType, object_id AS objectId, payload,"
                    + " status, attempt, next_attempt_at AS nextAttemptAt, response_status AS"
                    + " responseStatus, error, sent_at AS sentAt, created_at AS createdAt";

    /**
     * 待投递行（轮询作业入口）。<b>到点过滤在 Java 侧做</b>：next_attempt_at 由 JVM 时钟写入， MySQL 容器时区可能与 JVM 不同（本地 / CI 是
     * UTC vs Asia/Shanghai），SQL 里与 NOW(3) 比较会错位 8 小时； 服务用同一 JVM 时钟筛选后投递。
     */
    @Select(
            """
            SELECT id, webhook_id AS webhookId, outbox_event_id AS outboxEventId,
                   event_type AS eventType, object_type AS objectType, object_id AS objectId,
                   payload, status, attempt, next_attempt_at AS nextAttemptAt,
                   response_status AS responseStatus, error, sent_at AS sentAt, created_at AS createdAt
            FROM sys_webhook_delivery
            WHERE status = 'PENDING'
            ORDER BY id
            LIMIT #{limit}
            """)
    List<WebhookDeliveryRow> listPending(@Param("limit") int limit);

    /** 该来源事件是否已为该 Webhook 排过队（订阅方幂等）。 */
    @Select(
            """
            SELECT COUNT(*) FROM sys_webhook_delivery
            WHERE webhook_id = #{webhookId} AND outbox_event_id = #{outboxEventId}
            """)
    int countByWebhookAndEvent(
            @Param("webhookId") long webhookId, @Param("outboxEventId") long outboxEventId);

    @Insert(
            """
            INSERT INTO sys_webhook_delivery
              (webhook_id, outbox_event_id, event_type, object_type, object_id, payload,
               status, attempt, next_attempt_at)
            VALUES
              (#{webhookId}, #{outboxEventId}, #{eventType}, #{objectType}, #{objectId},
               #{payload}, 'PENDING', 0, NOW(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(WebhookDeliveryInsert row);

    /** 投递成功：SENT + 响应码 + 时间。 */
    @Update(
            """
            UPDATE sys_webhook_delivery
            SET status = 'SENT', response_status = #{responseStatus}, error = NULL,
                sent_at = NOW(3), next_attempt_at = NULL
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markSent(@Param("id") long id, @Param("responseStatus") int responseStatus);

    /** 投递失败且还有重试额度：记次数、失败类别与下一次退避时间。 */
    @Update(
            """
            UPDATE sys_webhook_delivery
            SET attempt = #{attempt}, response_status = #{responseStatus}, error = #{error},
                next_attempt_at = #{nextAttemptAt}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int scheduleRetry(
            @Param("id") long id,
            @Param("attempt") int attempt,
            @Param("responseStatus") Integer responseStatus,
            @Param("error") String error,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    /** 重试耗尽：FAILED 定格（保留最后一次响应码与失败类别）。 */
    @Update(
            """
            UPDATE sys_webhook_delivery
            SET status = 'FAILED', response_status = #{responseStatus}, error = #{error},
                next_attempt_at = NULL
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markFailed(
            @Param("id") long id,
            @Param("responseStatus") Integer responseStatus,
            @Param("error") String error);

    /** 手动重投（PLT-CFG-007）：FAILED → PENDING，从第 1 次重新计退避。 */
    @Update(
            """
            UPDATE sys_webhook_delivery
            SET status = 'PENDING', attempt = 0, next_attempt_at = NOW(3)
            WHERE id = #{id} AND status = 'FAILED'
            """)
    int requeue(@Param("id") long id);

    /** 某 Webhook 的投递记录（配置页「投递记录」视图，按状态过滤）。 */
    @Select(
            """
            <script>
            SELECT id, webhook_id AS webhookId, outbox_event_id AS outboxEventId,
                   event_type AS eventType, object_type AS objectType, object_id AS objectId,
                   payload, status, attempt, next_attempt_at AS nextAttemptAt,
                   response_status AS responseStatus, error, sent_at AS sentAt, created_at AS createdAt
            FROM sys_webhook_delivery
            WHERE webhook_id = #{webhookId}
            <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<WebhookDeliveryRow> listByWebhook(
            @Param("webhookId") long webhookId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select(
            """
            <script>
            SELECT COUNT(*) FROM sys_webhook_delivery
            WHERE webhook_id = #{webhookId}
            <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countByWebhook(@Param("webhookId") long webhookId, @Param("status") String status);

    @Select("SELECT " + COLUMNS + " FROM sys_webhook_delivery WHERE id = #{id}")
    WebhookDeliveryRow find(@Param("id") long id);

    /** 配置删除时清掉该 Webhook 的队列与台账（删除后不可再投、不留可重投的行）。 */
    @Delete("DELETE FROM sys_webhook_delivery WHERE webhook_id = #{webhookId}")
    int deleteByWebhook(@Param("webhookId") long webhookId);
}
