package com.crdp.followup.notice.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 站内信收件箱表 ntf_message（E09-T02 / A-NTF-002、C-NTF-002）。调用前必须已在租户上下文。 ntf_message 不在
 * ControlledTables（个人收件箱按 recipient_id 隔离，不属患者数据范围改写）， 本人以外一律 40400 由应用层判定。
 */
@Mapper
public interface NoticeMessageMapper {

    /** dedup_key 是否已投递过（重放识别；并发同键竞态由唯一键兜底）。 */
    @Select("SELECT COUNT(*) FROM ntf_message WHERE dedup_key = #{dedupKey}")
    int countByDedupKey(@Param("dedupKey") String dedupKey);

    /**
     * 幂等插入：dedup_key 撞唯一键时改成无操作（ON DUPLICATE KEY UPDATE id = id）。 连接串未开 useAffectedRows 时重复投递也可能返回
     * 1（matched 口径），是否新消息以 {@link #countByDedupKey} 先行判定。
     */
    @Insert(
            """
            INSERT INTO ntf_message
              (recipient_id, event_type, title, summary, patient_id, patient_name_cipher,
               target_type, target_id, dedup_key, read_flag)
            VALUES (#{recipientId}, #{eventType}, #{title}, #{summary}, #{patientId},
                    #{patientNameCipher}, #{targetType}, #{targetId}, #{dedupKey}, 0)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insert(NoticeMessageRow row);

    /** 本人收件箱一页：unread=true 只看未读；按 id 倒序（新消息在前），offset 从 0 起。 */
    @Select(
            """
            <script>
            SELECT id, recipient_id AS recipientId, event_type AS eventType, title, summary,
                   patient_id AS patientId, patient_name_cipher AS patientNameCipher,
                   target_type AS targetType, target_id AS targetId,
                   dedup_key AS dedupKey,
                   read_flag AS readFlag, read_at AS readAt, created_at AS createdAt
            FROM ntf_message
            WHERE recipient_id = #{recipientId}
            <if test="unread">AND read_flag = 0</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<NoticeMessageRow> page(
            @Param("recipientId") long recipientId,
            @Param("unread") boolean unread,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 条数：unread=true 只数未读（未读数即铃铛角标）。 */
    @Select(
            """
            <script>
            SELECT COUNT(*) FROM ntf_message
            WHERE recipient_id = #{recipientId}
            <if test="unread">AND read_flag = 0</if>
            </script>
            """)
    long count(@Param("recipientId") long recipientId, @Param("unread") boolean unread);

    /** 单条（应用层核对本人所有，越权 40400）。 */
    @Select(
            """
            SELECT id, recipient_id AS recipientId, event_type AS eventType, title, summary,
                   patient_id AS patientId, patient_name_cipher AS patientNameCipher,
                   target_type AS targetType, target_id AS targetId,
                   dedup_key AS dedupKey,
                   read_flag AS readFlag, read_at AS readAt, created_at AS createdAt
            FROM ntf_message WHERE id = #{id}
            """)
    NoticeMessageRow find(@Param("id") long id);

    /** 标已读（本人 + 未读才更新；已读再标幂等返回 0）。 */
    @Update(
            """
            UPDATE ntf_message
            SET read_flag = 1, read_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND recipient_id = #{recipientId} AND read_flag = 0
            """)
    int markRead(@Param("id") long id, @Param("recipientId") long recipientId);

    /** 全部已读：只影响本人未读行，返回条数。 */
    @Update(
            """
            UPDATE ntf_message
            SET read_flag = 1, read_at = CURRENT_TIMESTAMP(3)
            WHERE recipient_id = #{recipientId} AND read_flag = 0
            """)
    int markAllRead(@Param("recipientId") long recipientId);
}
