package com.crdp.form.record.infra.mapper;

/**
 * fe_form_version_field 行（E11-T02）：版本内字段快照（发布时的启用字段，下发口径全列）。
 *
 * <p>与 fe_field 同名列的快照，不含 status / updated_by（进快照的必然是启用字段）。范围、条件与派生定义 为 JSON 文本，应用层解析。
 */
public record VersionFieldRow(
        Long id,
        Long versionId,
        String formCode,
        int version,
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
        String dictCode,
        boolean carryOver,
        boolean required,
        String visibleWhen,
        String requiredWhen,
        String derivedDef,
        boolean sensitiveFlag,
        String storageType,
        String helpText,
        int sortNo) {

    /** 兼容构造（dict_code / carry_over 落地前形态）：不绑字典、不可带入。 */
    public VersionFieldRow(
            Long id,
            Long versionId,
            String formCode,
            int version,
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
            int sortNo) {
        this(
                id,
                versionId,
                formCode,
                version,
                fieldKey,
                fieldCode,
                label,
                categoryKey,
                type,
                unit,
                decimals,
                hardRange,
                softRange,
                optionSetCode,
                null,
                false,
                required,
                visibleWhen,
                requiredWhen,
                derivedDef,
                sensitiveFlag,
                storageType,
                helpText,
                sortNo);
    }
}
