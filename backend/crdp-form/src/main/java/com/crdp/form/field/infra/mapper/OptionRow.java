package com.crdp.form.field.infra.mapper;

import java.time.LocalDateTime;

/** fe_option 行（E11-T01 / A-FLD-002：编码存 0/1 这类值，页面显示显示名）。 */
public record OptionRow(
        Long id,
        String setCode,
        int optionCode,
        String label,
        Integer ordinal,
        boolean exclusive,
        boolean needsDetail,
        String aliases,
        int sortNo,
        String status,
        Long updatedBy,
        LocalDateTime updatedAt) {}
