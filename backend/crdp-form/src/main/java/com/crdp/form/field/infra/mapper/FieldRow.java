package com.crdp.form.field.infra.mapper;

import java.time.LocalDateTime;

/** fe_field 行（E11-T01 / A-FLD-001）。范围、条件与派生定义为 JSON 文本，应用层解析。 */
public record FieldRow(
        Long id,
        String formCode,
        String fieldKey,
        String fieldCode,
        String label,
        String categoryKey,
        String type,
        String unit,
        Integer decimals,
        String hardRange,
        String softRange,
        String optionSetCode,
        boolean required,
        String visibleWhen,
        String requiredWhen,
        String derivedDef,
        boolean sensitiveFlag,
        String storageType,
        String helpText,
        int sortNo,
        String status,
        Long updatedBy,
        LocalDateTime updatedAt) {}
