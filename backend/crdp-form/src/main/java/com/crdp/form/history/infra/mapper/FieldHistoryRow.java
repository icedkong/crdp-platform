package com.crdp.form.history.infra.mapper;

import java.time.LocalDateTime;

/**
 * fe_field_history 行（E11-T06 / A-PAT-005、C-PAT-011）：old_value / new_value 为 JSON 文本（值编码 同
 * fe_record.data，tech-plan §6），应用层解析；trigger_fields 为逗号分隔稳定键。
 */
public record FieldHistoryRow(
        Long id,
        String recordType,
        long recordId,
        String formCode,
        Integer templateVersion,
        String fieldKey,
        String oldValue,
        String newValue,
        String source,
        String triggerFields,
        String reason,
        Long operatorId,
        String operatorName,
        LocalDateTime createdAt) {}
