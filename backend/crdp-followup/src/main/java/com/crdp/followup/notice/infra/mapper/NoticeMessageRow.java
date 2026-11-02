package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDateTime;

/**
 * ntf_message 一行（E09-T02）。patientNameCipher 是 {@code CryptoService} 信封密文，
 * 读取时由应用层解密后交给 @MaskedByPolicy 序列化脱敏；本对象不出租户库。
 */
public record NoticeMessageRow(
        Long id,
        long recipientId,
        String eventType,
        String title,
        String summary,
        Long patientId,
        String patientNameCipher,
        String targetType,
        Long targetId,
        String dedupKey,
        int readFlag,
        LocalDateTime readAt,
        LocalDateTime createdAt) {}
