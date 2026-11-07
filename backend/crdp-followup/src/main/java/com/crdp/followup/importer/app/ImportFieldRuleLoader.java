package com.crdp.followup.importer.app;

import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.form.field.app.FieldDictionaryService;
import com.crdp.form.field.domain.FieldType;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * E18-T03：从字段字典构造校验规则（{@link ImportFieldRules}）——类型、单位、小数位、硬 / 软范围、选项、必填、 敏感与派生定义（tech-plan
 * §12④「按字段字典做类型、范围、选项、必填与跨字段校验」）。
 *
 * <p>口径与 {@link ImportFieldCatalogLoader}（E18-T01 表头目录）同源：BASELINE / FOLLOWUP 各取第一张表单、 字段按 sortNo
 * 排回字典顺序、「原表列名」锚点从帮助说明解析（OQ-72）。病案号字段按显示名「病案号」定位（身份列）； 「病区/中心」字段按显示名定位（E18-T04
 * 病区值映射的接线点：该字段的取值来自病区字典而非固定选项集，A-WARD-006）。 调用需租户上下文（字段字典在租户库）。
 */
@Service
public class ImportFieldRuleLoader {

    /** 帮助说明里的原表列名锚点（与 ImportFieldCatalogLoader 同一约定）。 */
    private static final Pattern ORIGINAL_HEADER = Pattern.compile("原表列名「(.+?)」");

    private static final String MRN_LABEL = "病案号";

    /** 「病区/中心」字段显示名（A-WARD-006 的字段名，V38 种子 f007；该字段的取值来自病区字典而非固定选项集）。 */
    private static final String WARD_LABEL = "病区/中心";

    private final FieldDictionaryService dictionary;

    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public ImportFieldRuleLoader(FieldDictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    /** 一套校验规则：基线与随访两份 + 病案号、病区/中心字段键。 */
    public record ImportFieldRules(
            List<ImportFieldRule> baselineRules,
            List<ImportFieldRule> followupRules,
            String mrnFieldKey,
            String wardFieldKey) {

        public ImportFieldRules {
            baselineRules = List.copyOf(baselineRules);
            followupRules = List.copyOf(followupRules);
        }

        public Map<String, ImportFieldRule> baselineByKey() {
            return byKey(baselineRules);
        }

        public Map<String, ImportFieldRule> followupByKey() {
            return byKey(followupRules);
        }

        /** 按显示名找基线字段（宽表前置列比对用，{@link com.crdp.followup.importer.domain.WideFrontColumns}）。 */
        public ImportFieldRule baselineByLabel(String label) {
            return baselineRules.stream()
                    .filter(rule -> rule.label().equals(label))
                    .findFirst()
                    .orElse(null);
        }

        private static Map<String, ImportFieldRule> byKey(List<ImportFieldRule> rules) {
            Map<String, ImportFieldRule> index = new LinkedHashMap<>();
            rules.forEach(rule -> index.put(rule.fieldKey(), rule));
            return index;
        }
    }

    /** 加载当前租户的校验规则（病区/中心字段按显示名定位，E18-T04 病区值映射的接线点）。 */
    public ImportFieldRules load() {
        String baselineForm = firstFormCode("BASELINE");
        String followupForm = firstFormCode("FOLLOWUP");
        List<ImportFieldRule> baseline = rulesOf(baselineForm);
        List<ImportFieldRule> followup = rulesOf(followupForm);
        String mrnKey =
                baseline.stream()
                        .filter(rule -> MRN_LABEL.equals(rule.label()))
                        .findFirst()
                        .map(ImportFieldRule::fieldKey)
                        .orElse(null);
        String wardKey =
                baseline.stream()
                        .filter(rule -> WARD_LABEL.equals(rule.label()))
                        .findFirst()
                        .map(ImportFieldRule::fieldKey)
                        .orElse(null);
        return new ImportFieldRules(baseline, followup, mrnKey, wardKey);
    }

    private String firstFormCode(String usage) {
        return dictionary.forms().stream()
                .filter(form -> usage.equals(form.usage()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "字段字典未配置"
                                                + ("BASELINE".equals(usage) ? "基线" : "随访")
                                                + "表单（usage="
                                                + usage
                                                + "），不能导入"))
                .code();
    }

    private List<ImportFieldRule> rulesOf(String formCode) {
        return dictionary.fields(formCode, null).stream()
                .sorted(
                        Comparator.comparingInt(FieldDictionaryService.FieldView::sortNo)
                                .thenComparing(FieldDictionaryService.FieldView::key))
                .map(this::toRule)
                .toList();
    }

    private ImportFieldRule toRule(FieldDictionaryService.FieldView field) {
        Map<Integer, String> options = new LinkedHashMap<>();
        if (field.options() != null) {
            field.options()
                    .forEach(option -> options.put(option.code(), option.label())); // 含停用：历史值照常接受
        }
        return new ImportFieldRule(
                field.key(),
                field.label(),
                originalHeader(field.help()),
                "ENABLED".equals(field.status()),
                FieldType.ofCode(field.type())
                        .orElseThrow(() -> new IllegalStateException("未知字段类型：" + field.type())),
                field.unit(),
                field.decimals(),
                field.hardRange() == null ? null : field.hardRange().min(),
                field.hardRange() == null ? null : field.hardRange().max(),
                field.softRange() == null ? null : field.softRange().min(),
                field.softRange() == null ? null : field.softRange().max(),
                field.required(),
                field.sensitive(),
                options,
                writeJson(field.derivedDef()));
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("派生定义序列化失败", failure);
        }
    }

    private static String originalHeader(String help) {
        if (help == null || help.isBlank()) {
            return null;
        }
        Matcher matcher = ORIGINAL_HEADER.matcher(help);
        return matcher.find() ? matcher.group(1) : null;
    }
}
