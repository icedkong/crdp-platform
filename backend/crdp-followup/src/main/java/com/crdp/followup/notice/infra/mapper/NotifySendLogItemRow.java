package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDateTime;

/**
 * 发送台账查询行（E09-T07 / A-NTF-006、C-NTF-006）：ntf_send_log 连 sys_user 取接收人展示名。 title / detail
 * 不含患者信息（A-NFR-007）；手机号永不落台账、不展示。
 */
public record NotifySendLogItemRow(
        Long id,
        String eventType,
        long recipientId,
        String recipientName,
        String channel,
        String sendStatus,
        String title,
        String detail,
        LocalDateTime sentAt,
        LocalDateTime createdAt) {}
