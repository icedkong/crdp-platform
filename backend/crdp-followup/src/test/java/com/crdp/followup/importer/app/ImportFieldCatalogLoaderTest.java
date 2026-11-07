package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.form.field.app.FieldDictionaryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E18-T01 字段目录加载器测试：从字段字典（fe_form / fe_field 视图）构造导入目录—— 表单按用途 BASELINE / FOLLOWUP 选取、字段按 sortNo
 * 排回字典顺序（mapper 按分类字母排序与附录顺序不同）、 原表列名锚点从帮助说明解析（OQ-72①）、停用字段保留锚点但标记不启用（#217 仅宽表，OQ-72②）、
 * 病案号字段按显示名定位。
 */
class ImportFieldCatalogLoaderTest {

    private final FieldDictionaryService dictionary = mock(FieldDictionaryService.class);
    private final ImportFieldCatalogLoader loader = new ImportFieldCatalogLoader(dictionary);

    @Test
    void loadsCatalogSortedBySortNoWithAnchorsAndStatus() {
        when(dictionary.forms())
                .thenReturn(
                        List.of(
                                form("A_ANNUAL_FU", "FOLLOWUP"),
                                form("A_BASELINE", "BASELINE"),
                                form("RPT_ECHO", "REPORT")));
        // 故意按分类字母序返回（mapper 的 ORDER BY category_key, sort_no），加载器应按 sortNo 排回字典顺序
        when(dictionary.fields("A_BASELINE", null))
                .thenReturn(
                        List.of(
                                field(
                                        "A_BASELINE",
                                        "f005",
                                        "手术年龄",
                                        "原表列名「年龄」；手术日期 − 出生日期",
                                        50,
                                        "ENABLED"),
                                field(
                                        "A_BASELINE",
                                        "f267",
                                        "出生日期",
                                        "A-FLD-013 新增：用于自动计算手术年龄",
                                        45,
                                        "ENABLED"),
                                field("A_BASELINE", "f001", "手术日期", "原表列名「手术日期」", 10, "ENABLED"),
                                field(
                                        "A_BASELINE",
                                        "f002",
                                        "病案号",
                                        "原表列名「病案号」；敏感字段",
                                        20,
                                        "ENABLED")));
        when(dictionary.fields("A_ANNUAL_FU", null))
                .thenReturn(
                        List.of(
                                field(
                                        "A_ANNUAL_FU",
                                        "f217",
                                        "死亡随访时间",
                                        "原表列名「死亡随访时间」；与 #216 合并（Q22）",
                                        2170,
                                        "DISABLED"),
                                field(
                                        "A_ANNUAL_FU",
                                        "f214",
                                        "随访日期",
                                        "原表列名「随访日期」",
                                        2140,
                                        "ENABLED")));

        ImportFieldCatalog catalog = loader.load();

        assertEquals(
                List.of("f001", "f002", "f267", "f005"),
                catalog.baselineFields().stream()
                        .map(ImportFieldCatalog.CatalogField::fieldKey)
                        .toList(),
                "按 sortNo 排回字典顺序（f267 插在 f004 与 f005 之间是字典事实，这里夹具取子集）");
        assertEquals("手术日期", catalog.baselineFields().get(0).originalHeader());
        assertEquals("年龄", catalog.baselineFields().get(3).originalHeader(), "锚点 ≠ 显示名（OQ-72①）");
        assertTrue(catalog.baselineFields().get(1).enabled());
        assertEquals(Optional.of("f002"), catalog.mrnFieldKey(), "病案号字段按显示名定位");

        assertEquals(2, catalog.followupFields().size());
        assertEquals("f214", catalog.followupFields().get(0).fieldKey());
        assertEquals("死亡随访时间", catalog.followupFields().get(1).originalHeader());
        assertTrue(!catalog.followupFields().get(1).enabled(), "#217 停用（仅宽表列，长表模板不含）");
    }

    @Test
    void failsWhenBaselineOrFollowupFormMissing() {
        when(dictionary.forms()).thenReturn(List.of(form("A_BASELINE", "BASELINE")));
        IllegalStateException ex = assertThrows(IllegalStateException.class, loader::load);
        assertTrue(ex.getMessage().contains("随访"), ex.getMessage());

        when(dictionary.forms()).thenReturn(List.of(form("RPT_ECHO", "REPORT")));
        IllegalStateException both = assertThrows(IllegalStateException.class, loader::load);
        assertTrue(both.getMessage().contains("基线"), both.getMessage());
    }

    // ---------------------------------------------------------------- 夹具

    private static FieldDictionaryService.FormSummaryView form(String code, String usage) {
        return new FieldDictionaryService.FormSummaryView(code, code, usage, 1, "ENABLED");
    }

    private static FieldDictionaryService.FieldView field(
            String formCode, String key, String label, String help, int sortNo, String status) {
        return new FieldDictionaryService.FieldView(
                formCode, formCode, key, key, label, null, null, null, null, null, null, null, null,
                false, null, null, null, false, null, help, sortNo, status, null);
    }
}
