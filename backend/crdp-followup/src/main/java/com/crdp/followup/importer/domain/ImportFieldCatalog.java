package com.crdp.followup.importer.domain;

import java.util.List;
import java.util.Optional;

/**
 * E18-T01：导入字段目录口——版面适配器靠它把表头对到字段（tech-plan §12「表头按字段字典的显示名与别名匹配」）。
 *
 * <p>实现：{@link StaticImportFieldCatalog}（测试 / 固定目录）与 crdp-followup importer app 的字典加载器 （fe_form /
 * fe_field，E18-T02 的导入作业调用）。字段顺序 = 字典顺序（sortNo），宽表列「表头与字典顺序相同」按它逐列对位。
 *
 * <p>列集口径（OQ-72）：{@code originalHeader} 是帮助说明里保留的「原表列名」锚点—— 宽表列 = 带锚点的字段（医院原表 212 基线 + 54 随访；停用的
 * #217 死亡随访时间带锚点、仍是宽表列）； 长表模板 = 启用字段（213 基线含新字段出生日期、53 随访不含停用的 #217），表头用显示名（锚点也认）。
 */
public interface ImportFieldCatalog {

    /** 基线表单的字段（字典顺序）。 */
    List<CatalogField> baselineFields();

    /** 随访表单的字段（字典顺序）。 */
    List<CatalogField> followupFields();

    /** 病案号字段的稳定键（患者身份列）；目录没有时为空（宽表回落前置列病案号）。 */
    Optional<String> mrnFieldKey();

    /**
     * 目录字段。
     *
     * @param fieldKey 稳定键（模型与 data JSON 的键）
     * @param label 显示名（长表模板表头）
     * @param originalHeader 原表列名锚点（宽表表头；无锚点的字段不是宽表列，如新字段出生日期）
     * @param enabled 是否启用（停用字段不进长表模板，但带锚点的停用字段仍是宽表列）
     */
    record CatalogField(String fieldKey, String label, String originalHeader, boolean enabled) {

        /** 表头可匹配的名字（显示名 + 原表列名锚点，去重；顺序即优先展示顺序）。 */
        public List<String> matchNames() {
            if (originalHeader == null || originalHeader.equals(label)) {
                return List.of(label);
            }
            return List.of(originalHeader, label);
        }

        /** 表头是否匹配本字段（去空白精确比较，不做模糊猜测）。 */
        public boolean matches(String header) {
            String text = header == null ? "" : header.strip();
            return matchNames().contains(text);
        }
    }
}
