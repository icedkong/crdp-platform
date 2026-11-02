package com.crdp.followup.notice.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知发送台账 ntf_send_log（E09-T04 / A-NTF-006 存储；查询与失败重试接口随 E09-T07）。 调用前必须已在租户上下文。 outbox
 * 至少一次语义下引擎可能重放：dedup_key 唯一键 + 先查后插保证不重记（并发竞态由唯一键兜底）。
 */
@Mapper
public interface NotifySendLogMapper {

    /** 该幂等键是否已记录（重放识别）。 */
    @Select("SELECT COUNT(*) FROM ntf_send_log WHERE dedup_key = #{dedupKey}")
    int countByDedupKey(@Param("dedupKey") String dedupKey);

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
}
