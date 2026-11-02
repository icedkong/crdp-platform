package com.crdp.form.record.infra.mapper;

import java.time.LocalDateTime;

/** fe_record 行（E11-T02 / A-NFR-002、C-NFR-002）：data / data_meta 为 JSON 文本，应用层解析。 */
public record FormRecordRow(
        Long id,
        String formCode,
        int templateVersion,
        String data,
        String dataMeta,
        int version,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        Long updatedBy,
        LocalDateTime updatedAt) {}
