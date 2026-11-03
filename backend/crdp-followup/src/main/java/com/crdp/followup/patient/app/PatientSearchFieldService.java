package com.crdp.followup.patient.app;

import com.crdp.followup.patient.domain.AdvancedSearchCatalog.OptionValue;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.SearchFieldSpec;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.ValueType;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.form.field.app.FieldDictionaryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 高级检索字段目录（E13-T06 / A-PAT-014、A-NFR-009、C-PAT-013）：把字段字典（fe_field）转换为条件 构造器可引用的字段清单。
 *
 * <p>字段要进目录必须同时满足两条（缺一不可，「只能引用字典字段」的服务端闸门）：
 *
 * <ol>
 *   <li>字典侧：BASELINE 表单中启用、非敏感（sensitive_flag=0，敏感加密字段走 E13-T04 keyword 盲索引
 *       精确检索，不进条件树）、实体列存储（storage=column；JSON 分节存储随 E13-T02 落地后扩绑定）；
 *   <li>绑定侧：field_code 在代码内白名单 {@link Binding} 里登记了查询列与取值形态——请求中的任何 文本都不可能成为列名（A-NFR-009「不允许输入原始
 *       SQL」）。
 * </ol>
 *
 * <p>选项来源：字段选项集（fe_option，如出院状态）、病区字典（ward，启用项）；性别字典建议编码 1/2 与患者主档 M / F 存储口径不同（V38
 * help_text），目录按存储口径下发 M=男 / F=女。患者类型为业务字典 （A-FLD-012，平台预置 + 租户覆盖，生效项合并随字典服务口径），条件先按文本编码等值取值 （与
 * E13-T04 简单筛选 patient_type 同口径）。
 */
@Service
public class PatientSearchFieldService {

    /** 列绑定：字段编码 → 查询列 + 取值形态 + 选项来源（与 fe_field.field_code、pt_patient 列一致）。 */
    private record Binding(ValueType type, String column, OptionSource optionSource) {}

    private enum OptionSource {
        /** 字段选项集（fe_option，经字典服务）。 */
        OPTION_SET,
        /** 固定选项（性别：患者主档存 M / F）。 */
        GENDER,
        /** 病区字典（启用病区，值为 ward.id）。 */
        WARD,
        /** 无选项（文本 / 日期 / 数值）。 */
        NONE
    }

    private static final Map<String, Binding> BINDINGS = bindings();

    private static Map<String, Binding> bindings() {
        Map<String, Binding> map = new LinkedHashMap<>();
        map.put("surgery_date", new Binding(ValueType.DATE, "p.surgery_date", OptionSource.NONE));
        map.put("birth_date", new Binding(ValueType.DATE, "p.birth_date", OptionSource.NONE));
        map.put(
                "admission_date",
                new Binding(ValueType.DATE, "p.admission_date", OptionSource.NONE));
        map.put(
                "discharge_date",
                new Binding(ValueType.DATE, "p.discharge_date", OptionSource.NONE));
        map.put("gender", new Binding(ValueType.OPTION, "p.gender", OptionSource.GENDER));
        map.put("ward", new Binding(ValueType.OPTION, "p.ward_id", OptionSource.WARD));
        map.put(
                "discharge_status",
                new Binding(ValueType.OPTION, "p.discharge_status", OptionSource.OPTION_SET));
        map.put("patient_type", new Binding(ValueType.TEXT, "p.patient_type", OptionSource.NONE));
        return Map.copyOf(map);
    }

    private static final List<OptionValue> GENDER_OPTIONS =
            List.of(new OptionValue("M", "男"), new OptionValue("F", "女"));

    /** 常用条件口径：基本信息 / 病例基本信息两个分类（手机高级检索只留常用条件，pages.md §3）。 */
    private static final Set<String> COMMON_CATEGORIES =
            new LinkedHashSet<>(List.of("basic", "case"));

    private final FieldDictionaryService fieldDictionary;
    private final PatientMapper patientMapper;

    public PatientSearchFieldService(
            FieldDictionaryService fieldDictionary, PatientMapper patientMapper) {
        this.fieldDictionary = fieldDictionary;
        this.patientMapper = patientMapper;
    }

    // ---------------------------------------------------------------- 视图

    /** 目录视图（接口下发）：type 为取值形态（DATE/NUMBER/TEXT/OPTION），ops 由本目录下发。 */
    public record SearchFieldView(
            String field,
            String label,
            String type,
            String categoryKey,
            String categoryLabel,
            String unit,
            List<String> ops,
            List<OptionValueView> options,
            boolean common) {}

    /** 选项（value = 数据库存储编码：性别 M/F、病区 id、出院状态 1–3）。 */
    public record OptionValueView(Object value, String label) {}

    public record CatalogView(List<SearchFieldView> fields) {}

    /** 一次遍历的产物：编译用规格（field → spec）与接口视图（同序）。 */
    private record Catalog(Map<String, SearchFieldSpec> specs, List<SearchFieldView> views) {}

    /** 目录（每次按当前字典与病区字典现算，字典 / 病区变更即时生效）。 */
    public CatalogView catalog() {
        return new CatalogView(build().views());
    }

    /** 字段编码 → 编译用规格（{@link com.crdp.followup.patient.domain.AdvancedConditionCompiler} 入参）。 */
    public Map<String, SearchFieldSpec> specs() {
        return build().specs();
    }

    private Catalog build() {
        List<WardView> wards =
                patientMapper.findWards().stream()
                        .map(ward -> new WardView(ward.id(), ward.name()))
                        .toList();
        Map<String, SearchFieldSpec> specs = new LinkedHashMap<>();
        List<SearchFieldView> views = new ArrayList<>();
        for (FieldDictionaryService.FormSummaryView form : fieldDictionary.forms()) {
            if (!"BASELINE".equals(form.usage()) || !"ENABLED".equals(form.status())) {
                continue;
            }
            for (FieldDictionaryService.FieldView field :
                    fieldDictionary.fields(form.code(), null)) {
                // 目录闸门：启用 + 非敏感 + 实体列 + 已登记绑定（见类注释）
                if (!"ENABLED".equals(field.status())
                        || field.sensitive()
                        || !"column".equals(field.storage())
                        || !BINDINGS.containsKey(field.code())
                        || specs.containsKey(field.code())) {
                    continue;
                }
                Binding binding = BINDINGS.get(field.code());
                List<OptionValue> options = optionsOf(field, binding, wards);
                specs.put(
                        field.code(),
                        new SearchFieldSpec(
                                field.code(),
                                field.label(),
                                binding.type(),
                                binding.column(),
                                options));
                views.add(
                        new SearchFieldView(
                                field.code(),
                                field.label(),
                                binding.type().name(),
                                field.categoryKey(),
                                field.categoryLabel(),
                                field.unit(),
                                opsOf(binding.type()),
                                options.stream()
                                        .map(
                                                option ->
                                                        new OptionValueView(
                                                                option.value(), option.label()))
                                        .toList(),
                                COMMON_CATEGORIES.contains(field.categoryKey())));
            }
        }
        return new Catalog(Map.copyOf(specs), List.copyOf(views));
    }

    /** 病区候选（id 复用筛选候选同源）。 */
    private record WardView(long id, String name) {}

    private List<OptionValue> optionsOf(
            FieldDictionaryService.FieldView field, Binding binding, List<WardView> wards) {
        return switch (binding.optionSource()) {
            case GENDER -> GENDER_OPTIONS;
            case WARD ->
                    wards.stream().map(ward -> new OptionValue(ward.id(), ward.name())).toList();
            case OPTION_SET ->
                    field.options().stream()
                            .filter(option -> "ENABLED".equals(option.status()))
                            .map(option -> new OptionValue(option.code(), option.label()))
                            .toList();
            case NONE -> List.of();
        };
    }

    /** 取值形态 → 可用运算符（与编译器白名单一致）。 */
    private static List<String> opsOf(ValueType type) {
        return switch (type) {
            case DATE, NUMBER -> List.of("EQ", "NE", "GT", "LT", "BETWEEN", "IS_NULL", "NOT_NULL");
            case TEXT -> List.of("EQ", "NE", "CONTAINS", "IS_NULL", "NOT_NULL");
            case OPTION -> List.of("EQ", "NE", "IS_NULL", "NOT_NULL");
        };
    }
}
