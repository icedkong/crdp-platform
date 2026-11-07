package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.form.field.app.FieldDictionaryService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T04 规则加载器测试：「病区/中心」字段按显示名定位（A-WARD-006 的字段名，V38 种子 f007「病区/中心」； 与病案号按「病案号」定位同一口径）——
 * 该字段的取值来自病区字典而非固定选项集，校验层据 wardFieldKey 走病区值映射； 字典没有该字段时 wardFieldKey 为 null（其他租户形态，不做病区检查）。
 */
class ImportFieldRuleLoaderTest {

    private final FieldDictionaryService dictionary = mock(FieldDictionaryService.class);
    private final ImportFieldRuleLoader loader = new ImportFieldRuleLoader(dictionary);

    @Test
    void locatesWardFieldByLabel() {
        when(dictionary.forms())
                .thenReturn(
                        List.of(form("A_BASELINE", "BASELINE"), form("A_ANNUAL_FU", "FOLLOWUP")));
        when(dictionary.fields("A_BASELINE", null))
                .thenReturn(
                        List.of(field("f002", "病案号", "text"), field("f007", "病区/中心", "select")));
        when(dictionary.fields("A_ANNUAL_FU", null))
                .thenReturn(List.of(field("f214", "随访日期", "date")));

        ImportFieldRules rules = loader.load();

        assertEquals("f002", rules.mrnFieldKey());
        assertEquals("f007", rules.wardFieldKey(), "「病区/中心」字段按显示名定位（A-WARD-006）");
    }

    @Test
    void wardFieldKeyIsNullWhenDictionaryHasNoWardField() {
        when(dictionary.forms())
                .thenReturn(
                        List.of(form("A_BASELINE", "BASELINE"), form("A_ANNUAL_FU", "FOLLOWUP")));
        when(dictionary.fields("A_BASELINE", null))
                .thenReturn(List.of(field("f002", "病案号", "text")));
        when(dictionary.fields("A_ANNUAL_FU", null))
                .thenReturn(List.of(field("f214", "随访日期", "date")));

        ImportFieldRules rules = loader.load();

        assertNull(rules.wardFieldKey());
    }

    // ---------------------------------------------------------------- 夹具

    private static FieldDictionaryService.FormSummaryView form(String code, String usage) {
        return new FieldDictionaryService.FormSummaryView(code, code, usage, 1, "ENABLED");
    }

    private static FieldDictionaryService.FieldView field(String key, String label, String type) {
        return new FieldDictionaryService.FieldView(
                "A_BASELINE",
                "A_BASELINE",
                key,
                key,
                label,
                null,
                null,
                type,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                false,
                null,
                null,
                10,
                "ENABLED",
                null);
    }
}
