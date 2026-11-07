package com.crdp.followup.importer.domain;

import java.util.List;
import java.util.Optional;

/**
 * E18-T01：固定内容 的 {@link ImportFieldCatalog}（测试与预置目录用；字典侧加载器也产出它）。
 *
 * @param baselineFields 基线字段（字典顺序）
 * @param followupFields 随访字段（字典顺序）
 * @param mrnKey 病案号字段的稳定键（可空）
 */
public record StaticImportFieldCatalog(
        List<ImportFieldCatalog.CatalogField> baselineFields,
        List<ImportFieldCatalog.CatalogField> followupFields,
        String mrnKey)
        implements ImportFieldCatalog {

    public StaticImportFieldCatalog {
        baselineFields = List.copyOf(baselineFields);
        followupFields = List.copyOf(followupFields);
    }

    @Override
    public Optional<String> mrnFieldKey() {
        return Optional.ofNullable(mrnKey);
    }
}
