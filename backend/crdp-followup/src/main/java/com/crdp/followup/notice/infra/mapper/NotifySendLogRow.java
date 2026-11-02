package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDateTime;

/**
 * ntf_send_log 一行（E09-T04 存储、E09-T07 查询）。send_status：QUEUED（短信待汇总发送， E09-T05 消费）/ SENT / FAILED /
 * SKIPPED。title/detail 不含患者信息；短信模板参数由汇总作业在发送时按模板生成。
 */
public record NotifySendLogRow(
        Long id,
        String eventType,
        long recipientId,
        String channel,
        String sendStatus,
        String title,
        String detail,
        String dedupKey,
        LocalDateTime sentAt,
        LocalDateTime createdAt) {}
