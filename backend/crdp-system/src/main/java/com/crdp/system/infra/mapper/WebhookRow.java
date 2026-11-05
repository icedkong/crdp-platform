package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;

/** sys_webhook 一行（E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007）。secret 只存 CryptoService 密文，出库不出明文。 */
public record WebhookRow(
        Long id,
        String name,
        String url,
        String secretCipher,
        String events,
        boolean enabled,
        LocalDateTime lastDeliveryAt,
        Long createdBy,
        Long updatedBy,
        LocalDateTime updatedAt,
        LocalDateTime createdAt) {}
