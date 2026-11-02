package com.crdp.form.field.infra.mapper;

/** fe_form 行（E11-T01 / A-FLD-001）。 */
public record FormRow(
        Long id,
        String code,
        String name,
        String usageType,
        int currentVersion,
        String categories,
        String status) {}
