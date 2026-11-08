package com.crdp.form.rule.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.form.field.domain.FieldType;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E19-T05 / A-DASH-010、A-DASH-011、A-PAT-019：表单引擎<b>字段级</b>已填 / 应填进度（{@link
 * FormProgressEngine#fieldProgress}）。 完整度卡片的「最常缺失字段」与「缺失患者钻取」需要落到字段粒度—— 口径必须与分类进度 {@link
 * FormProgressEngine#progress}（共享用例锁定的唯一事实）完全一致： 应填 = 显示中且有效必填且 storage=json 且非派生；已填 = 其中值非空（0 与
 * false 是已答）。 本测试锁定：同输入下两口径逐字段可加和一致。全部虚构数据。
 */
class FormProgressFieldTest {

    private final FormConditionEngine conditionEngine = new FormConditionEngine();
    private final FormProgressEngine progressEngine = new FormProgressEngine(conditionEngine);

    /** 应填字段（含条件必填与隐藏字段；0 / false 已答、空串 / 空数组未答）。 */
    private static VersionFieldRow jsonField(
            String key, String categoryKey, boolean required, String visibleWhen) {
        return new VersionFieldRow(
                1L,
                1L,
                "A_ANNUAL_FU",
                1,
                key,
                key,
                key + "（示例）",
                categoryKey,
                "text",
                null,
                null,
                null,
                null,
                null,
                required,
                visibleWhen,
                null,
                null,
                false,
                "json",
                null,
                1);
    }

    @Test
    void fieldProgressSumsToCategoryProgressOnSameInput() {
        List<VersionFieldRow> fields =
                List.of(
                        jsonField("f1", "fu_contact", true, null),
                        jsonField("f2", "fu_contact", true, null),
                        jsonField("f3", "fu_event", true, null),
                        jsonField("f4", "fu_event", false, null) // 非必填：两个口径都不计
                        );
        Map<String, Object> data =
                Map.of(
                        "f1", "已答",
                        "f2", "",
                        "f3", 0);

        var fieldLevel = progressEngine.fieldProgress(fields, data, Map.of());
        var categoryLevel =
                progressEngine.progress(
                        fields,
                        List.of(
                                new FormProgressEngine.Category("fu_contact", "联系", 10),
                                new FormProgressEngine.Category("fu_event", "事件", 20)),
                        data,
                        Map.of());

        assertEquals(3, fieldLevel.size(), "只有应填字段出现（f4 非必填不计）");
        assertEquals("f1", fieldLevel.get(0).fieldKey());
        assertEquals(true, fieldLevel.get(0).filled(), "非空字符串 = 已填");
        assertEquals("f2", fieldLevel.get(1).fieldKey());
        assertEquals(false, fieldLevel.get(1).filled(), "空串 = 未填");
        assertEquals("f3", fieldLevel.get(2).fieldKey());
        assertEquals(true, fieldLevel.get(2).filled(), "0 是已答");
        // 与分类进度可加和一致（同一应填集合）
        int contactFilled = 0;
        int contactTotal = 0;
        for (FormProgressEngine.FieldProgress field : fieldLevel) {
            if ("fu_contact".equals(field.categoryKey())) {
                contactTotal++;
                contactFilled += field.filled() ? 1 : 0;
            }
        }
        assertEquals(contactFilled, categoryLevel.get(0).filled(), "fu_contact 已填与分类口径一致");
        assertEquals(contactTotal, categoryLevel.get(0).total(), "fu_contact 应填与分类口径一致");
    }

    @Test
    void fieldProgressExcludesHiddenConditionalColumnAndDerivedFields() {
        List<VersionFieldRow> fields =
                List.of(
                        jsonField("f1", "fu_contact", true, null),
                        // 条件不显示（f1 无值 → eq 不成立）：不计分母，残留值也不算已填
                        jsonField(
                                "f2",
                                "fu_contact",
                                true,
                                "{\"any\":[{\"field\":\"f1\",\"op\":\"eq\",\"value\":1}]}"),
                        // 实体列存储：必填随业务接口（E13），不进表单进度
                        new VersionFieldRow(
                                1L,
                                1L,
                                "A_ANNUAL_FU",
                                1,
                                "f3",
                                "f3",
                                "f3（示例）",
                                "fu_contact",
                                "text",
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                null,
                                null,
                                null,
                                false,
                                "column",
                                null,
                                2),
                        // 派生字段：由 E11-T05 重算，不计
                        new VersionFieldRow(
                                1L,
                                1L,
                                "A_ANNUAL_FU",
                                1,
                                "f4",
                                "f4",
                                "f4（示例）",
                                "fu_contact",
                                FieldType.DERIVED.code(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                null,
                                null,
                                null,
                                false,
                                "json",
                                null,
                                3));
        Map<String, Object> data = Map.of("f2", "残留值（示例）");

        var fieldLevel = progressEngine.fieldProgress(fields, data, Map.of());

        assertEquals(1, fieldLevel.size(), "隐藏 / 实体列 / 派生字段都不计入");
        assertEquals("f1", fieldLevel.get(0).fieldKey());
        assertEquals("f1（示例）", fieldLevel.get(0).label(), "label 供卡片缺失字段列显示");
        assertEquals(false, fieldLevel.get(0).filled(), "f1 缺键 = 未填");
    }

    @Test
    void fieldProgressTreatsZeroFalseAsAnsweredAndEmptyCollectionAsMissing() {
        List<VersionFieldRow> fields =
                List.of(
                        jsonField("zero", "fu_contact", true, null),
                        jsonField("flag", "fu_contact", true, null),
                        jsonField("array", "fu_contact", true, null));

        var fieldLevel =
                progressEngine.fieldProgress(
                        fields, Map.of("zero", 0, "flag", false, "array", List.of()), Map.of());

        assertEquals(true, fieldLevel.get(0).filled(), "0 是已答");
        assertEquals(true, fieldLevel.get(1).filled(), "false 是已答");
        assertEquals(false, fieldLevel.get(2).filled(), "空数组 = 未填");
    }
}
