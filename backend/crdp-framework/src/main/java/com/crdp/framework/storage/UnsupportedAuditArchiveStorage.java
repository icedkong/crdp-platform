package com.crdp.framework.storage;

import java.time.LocalDate;

/**
 * 审计归档桶占位实现（E08-T03）：本机 / CI 无云凭证，作业只做按月分区维护，不上传也不删在线分区。 OBS 适配器 {@link ObsAuditArchiveStorage} 已随
 * E22-T08 落地（{@code storage.type=obs} 时装配），本实现保留为默认 {@code none} 的失败安全兜底（ADR 0005）。
 */
public class UnsupportedAuditArchiveStorage implements AuditArchiveStorage {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void putWithRetention(
            String key, byte[] body, String contentType, LocalDate retainUntil) {
        throw new UnsupportedOperationException("OBS 审计归档桶接入随 E10/E22 配置云凭证");
    }

    @Override
    public byte[] get(String key) {
        throw new UnsupportedOperationException("OBS 审计归档桶接入随 E10/E22 配置云凭证");
    }
}
