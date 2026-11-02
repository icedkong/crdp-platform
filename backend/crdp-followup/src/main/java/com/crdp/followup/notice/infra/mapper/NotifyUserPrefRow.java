package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDateTime;

/**
 * ntf_user_pref 一行（E09-T07 / A-NTF-007、C-NTF-007，迁移 V23）。 sms_off 为已关闭短信提醒的事件类型 CSV（NotifyEventType
 * 编码），只影响 SMS——站内信不提供个人关闭。本对象不出租户库。
 */
public record NotifyUserPrefRow(
        Long id, long userId, String smsOff, Long updatedBy, LocalDateTime updatedAt) {}
