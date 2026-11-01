package com.crdp.framework.storage;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * 审计归档桶占位实现：本机 / CI 无云凭证，作业只做按月分区维护，不上传也不删在线分区。 OBS 适配器（华为云 SDK，桶级 WORM 由运维 E01-T08 开启）随 E10/E22
 * 接入时替换本实现（ADR 0005）。
 */
@Component
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
