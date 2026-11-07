package com.crdp.followup.importer.app;

import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.StaticImportFieldCatalog;
import com.crdp.form.field.app.FieldDictionaryService;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * E18-T01：从字段字典构造导入目录（{@link ImportFieldCatalog}），版面适配器的表头匹配依据。
 *
 * <p>口径（OQ-72）：
 *
 * <ul>
 *   <li>表单按用途取：BASELINE → 基线列、FOLLOWUP → 随访列（多张时按表单编码取第一张）；
 *   <li>字段按 sortNo 排回<strong>字典顺序</strong>——mapper 的 ORDER BY category_key, sort_no 是分类字母序， 与附录 /
 *       宽表列顺序不同（宽表「表头与字典显示名一致、顺序相同」按 sortNo 对位）；
 *   <li>「原表列名」锚点从帮助说明的「原表列名「…」」解析（宽表表头 = 医院原表列名；无锚点的字段不是宽表列， 如 A-FLD-013
 *       新增的出生日期）；停用字段保留锚点与停用标记（#217 死亡随访时间按 Q22 合并停用， 仍是宽表第 54 列、不进长表模板）；
 *   <li>病案号字段按显示名「病案号」定位（患者身份列，宽表基线列与长表「基线」表都用它）。
 * </ul>
 *
 * <p>调用需在租户上下文（字段字典在租户库）。E18-T02 的导入作业与模板下载共用本加载器。
 */
@Service
public class ImportFieldCatalogLoader {

    /** 帮助说明里的原表列名锚点（scripts/gen_a_field_dict.py 的生成约定，AFieldDictSqlTest 同口径核对）。 */
    private static final Pattern ORIGINAL_HEADER = Pattern.compile("原表列名「(.+?)」");

    private static final String BASELINE_USAGE = "BASELINE";

    private static final String FOLLOWUP_USAGE = "FOLLOWUP";

    private static final String MRN_LABEL = "病案号";

    private final FieldDictionaryService dictionary;

    public ImportFieldCatalogLoader(FieldDictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    /** 加载当前租户的导入字段目录。 */
    public ImportFieldCatalog load() {
        String baselineForm =
                dictionary.forms().stream()
                        .filter(form -> BASELINE_USAGE.equals(form.usage()))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("字段字典未配置基线表单（usage=BASELINE），不能导入"))
                        .code();
        String followupForm =
                dictionary.forms().stream()
                        .filter(form -> FOLLOWUP_USAGE.equals(form.usage()))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("字段字典未配置随访表单（usage=FOLLOWUP），不能导入"))
                        .code();
        List<ImportFieldCatalog.CatalogField> baseline = catalogFields(baselineForm);
        List<ImportFieldCatalog.CatalogField> followup = catalogFields(followupForm);
        String mrnKey =
                baseline.stream()
                        .filter(field -> MRN_LABEL.equals(field.label()))
                        .findFirst()
                        .map(ImportFieldCatalog.CatalogField::fieldKey)
                        .orElse(null);
        return new StaticImportFieldCatalog(baseline, followup, mrnKey);
    }

    private List<ImportFieldCatalog.CatalogField> catalogFields(String formCode) {
        return dictionary.fields(formCode, null).stream()
                .sorted(
                        Comparator.comparingInt(FieldDictionaryService.FieldView::sortNo)
                                .thenComparing(FieldDictionaryService.FieldView::key))
                .map(
                        field ->
                                new ImportFieldCatalog.CatalogField(
                                        field.key(),
                                        field.label(),
                                        originalHeader(field.help()),
                                        "ENABLED".equals(field.status())))
                .toList();
    }

    /** 「原表列名「X」」锚点；无锚点（如新字段出生日期）返回 null → 不是宽表列。 */
    private static String originalHeader(String help) {
        if (help == null || help.isBlank()) {
            return null;
        }
        Matcher matcher = ORIGINAL_HEADER.matcher(help);
        return matcher.find() ? matcher.group(1) : null;
    }
}
