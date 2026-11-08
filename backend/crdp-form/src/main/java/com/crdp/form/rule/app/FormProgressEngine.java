package com.crdp.form.rule.app;

import com.crdp.form.field.domain.FieldType;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * E11-T11 / A-PAT-021、C-PAT-018（口径 A-PAT-019）：表单引擎<b>已填 / 应填进度</b>——按分类 / 分节汇总 「已填必填 ÷ 应填必填」，供分类条 /
 * 分组条（components.md §6 CategoryBar，E13-T11）与完整度统计 （A-DASH-010 / A-DASH-011）共用同一口径；E19-T05 增字段粒度形态
 * {@link #fieldProgress}（完整度卡片的「最常缺失字段」与缺失钻取），两形态同一应填集合。
 *
 * <p>与前端 {@code packages/rules} 的 {@code computeCategoryProgress} 同语义（验收「前后端结果一致」）， 共享用例锁定： {@code
 * shared-cases/form-progress-cases.json}（components.md §10.5）。
 *
 * <ul>
 *   <li><b>应填 total</b>：显示中（visibleWhen 求值）且有效必填（required || requiredWhen 成立，{@link
 *       FormConditionEngine#requiredNow}）且 storage=json 且非派生的字段——与提交校验的必填集合同一口径
 *       （FormRuleEngine.checkRequired），「应填 − 已填」恰为 missingRequired；派生字段由 E11-T05 重算、 实体 / 加密列的值不经表单
 *       JSON（必填随业务接口 E13），都不计入。
 *   <li><b>已填 filled</b>：应填字段中值非空的字段。空值口径与条件 DSL 同（{@link FormConditionEngine#isEmptyValue}）：null /
 *       缺键 / 空串 / 空数组 / 分量全缺的 pair_number 对象为空， {@code 0} 与 {@code false} 是已答。
 *   <li><b>条件不显示的字段不计入分母</b>（A-PAT-019「因条件不满足而不显示的字段不计入分母」）——隐藏字段的 残留值也不算已填。
 *   <li>分类按声明 sortNo 升序（稳定排序）输出；字段引用未声明分类时按字段首现序兜底追加（label=key）， 与 FormService.buildSchema
 *       的防御口径一致；无应填字段的分类输出 0/0（分类条仍显示该分组）。
 * </ul>
 */
@Component
public class FormProgressEngine {

    private final FormConditionEngine conditionEngine;

    public FormProgressEngine(FormConditionEngine conditionEngine) {
        this.conditionEngine = conditionEngine;
    }

    /** 分类声明（fe_form_version.categories 的 [{key,label,sortNo}]，应用层解析后传入）。 */
    public record Category(String key, String label, int order) {}

    /** 一个分类 / 分节的进度（列表本身有序：声明 sortNo 升序 + 未声明兜底追加）。 */
    public record CategoryProgress(String key, String label, int filled, int total) {}

    /**
     * 一个<b>应填字段</b>的进度（E19-T05 / A-DASH-010、A-DASH-011：完整度卡片的「最常缺失字段」与缺失钻取需要字段粒度）： 只出现应填（显示中且有效必填且
     * storage=json 且非派生）的字段，filled = 值非空（0 与 false 是已答）。 与 {@link #progress} 同一口径（progress
     * 即本结果按分类加和），label / categoryKey 供卡片缺失字段列显示与分类归属。
     */
    public record FieldProgress(
            String fieldKey, String label, String categoryKey, boolean filled) {}

    /**
     * 字段级已填 / 应填（{@link #progress} 的字段粒度形态，口径完全一致）： 应填字段按入参顺序（fe_form_version_field 的 category_key
     * / sort_no 序）输出，逐字段给出 filled。 data / context 为空（null）按空表单处理。
     */
    public List<FieldProgress> fieldProgress(
            List<VersionFieldRow> fields, Map<String, Object> data, Map<String, Object> context) {
        Map<String, Object> values = data == null ? Map.of() : data;
        Map<String, Object> ctx = context == null ? Map.of() : context;
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        List<FieldProgress> items = new ArrayList<>(fields.size());
        for (VersionFieldRow field : fields) {
            if (!countable(field, values, ctx)) {
                continue;
            }
            items.add(
                    new FieldProgress(
                            field.fieldKey(),
                            field.label(),
                            field.categoryKey(),
                            !FormConditionEngine.isEmptyValue(values.get(field.fieldKey()))));
        }
        return List.copyOf(items);
    }

    /** 按分类汇总已填 / 应填。data / context 为空（null）按空表单处理（无条件字段恒显示，ctx 叶子不成立）。 */
    public List<CategoryProgress> progress(
            List<VersionFieldRow> fields,
            List<Category> categories,
            Map<String, Object> data,
            Map<String, Object> context) {
        Map<String, int[]> counts = new LinkedHashMap<>(); // key → [filled, total]，首现序
        for (FieldProgress field : fieldProgress(fields, data, context)) {
            int[] count = counts.computeIfAbsent(field.categoryKey(), key -> new int[2]);
            count[1]++; // 应填
            if (field.filled()) {
                count[0]++; // 已填（空值口径与条件 DSL 同）
            }
        }
        LinkedHashSet<String> declared = new LinkedHashSet<>();
        List<CategoryProgress> items = new ArrayList<>();
        if (categories != null) {
            for (Category category : categories) {
                if (category == null || category.key() == null || category.key().isBlank()) {
                    continue; // 坏声明防御跳过
                }
                declared.add(category.key());
                items.add(toItem(category.key(), category.label(), counts));
            }
        }
        for (String key : counts.keySet()) { // 字段首现序兜底追加（label=key）
            if (!declared.contains(key)) {
                items.add(toItem(key, key, counts));
            }
        }
        return List.copyOf(items);
    }

    /** 应填判定（与 FormRuleEngine.checkRequired 同一过滤）：storage=json、非派生、显示中且有效必填。 */
    private boolean countable(
            VersionFieldRow field, Map<String, Object> data, Map<String, Object> context) {
        if (!"json".equals(field.storageType()) || FieldType.DERIVED.code().equals(field.type())) {
            return false; // 派生由 E11-T05 重算；实体 / 加密列必填随业务接口（E13）
        }
        return conditionEngine.requiredNow(field, data, context);
    }

    private static CategoryProgress toItem(String key, String label, Map<String, int[]> counts) {
        int[] count = counts.getOrDefault(key, new int[2]);
        String text = label == null || label.isBlank() ? key : label;
        return new CategoryProgress(key, text, count[0], count[1]);
    }
}
