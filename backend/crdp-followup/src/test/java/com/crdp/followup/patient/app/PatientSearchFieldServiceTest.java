package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.patient.domain.AdvancedSearchCatalog.SearchFieldSpec;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.ValueType;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.form.field.app.FieldDictionaryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * E13-T06 / A-PAT-014、A-NFR-009 单元测试（高级检索字段目录：「只能引用字典字段」的服务端闸门）。
 *
 * <p>锁定口径：目录 = 字段字典（BASELINE 表单、启用、非敏感、实体列存储）∩ 代码内列绑定白名单—— 敏感字段（姓名 / 病案号 / 电话，走 keyword
 * 盲索引精确检索）、JSON 存储字段（随 E13-T02）、 未登记绑定的字段都不出现；性别按存储口径下发 M / F；病区选项来自病区字典；出院状态来自字段选项集
 * （只含启用项）。全部虚构数据。
 */
class PatientSearchFieldServiceTest {

    private final FieldDictionaryService fieldDictionary =
            Mockito.mock(FieldDictionaryService.class);
    private final PatientMapper patientMapper = Mockito.mock(PatientMapper.class);

    private final PatientSearchFieldService service =
            new PatientSearchFieldService(fieldDictionary, patientMapper);

    private static FieldDictionaryService.FormSummaryView form(String code) {
        return new FieldDictionaryService.FormSummaryView(
                code, "基线档案（示例）", "BASELINE", 1, "ENABLED");
    }

    private static FieldDictionaryService.FieldView field(
            String code, String label, String type, String storage, boolean sensitive) {
        return new FieldDictionaryService.FieldView(
                "A_BASELINE",
                "基线档案（示例）",
                "f" + code,
                code,
                label,
                "basic",
                "基本信息",
                type,
                null,
                null,
                null,
                null,
                List.of(),
                false,
                null,
                null,
                null,
                sensitive,
                storage,
                null,
                10,
                "ENABLED",
                null);
    }

    private static FieldDictionaryService.OptionView option(int code, String label, String status) {
        return new FieldDictionaryService.OptionView(code, label, null, false, false, 10, status);
    }

    private void stubDictionary(List<FieldDictionaryService.FieldView> fields) {
        Mockito.when(fieldDictionary.forms()).thenReturn(List.of(form("A_BASELINE")));
        Mockito.when(fieldDictionary.fields("A_BASELINE", null)).thenReturn(fields);
    }

    @Test
    void catalogIntersectsDictionaryWithBindingsOnly() {
        stubDictionary(
                List.of(
                        field("discharge_date", "出院日期", "date", "column", false),
                        field("patient_name", "姓名", "text", "encrypted", true),
                        field("height_cm", "身高", "number", "json", false),
                        field("followup_note", "随访备注", "textarea", "json", false),
                        field("some_unbound", "未登记绑定（示例）", "text", "column", false)));
        Mockito.when(patientMapper.findWards()).thenReturn(List.of());

        Map<String, SearchFieldSpec> specs = service.specs();

        assertTrue(specs.containsKey("discharge_date"), "字典 + 绑定都命中才进目录");
        assertEquals(ValueType.DATE, specs.get("discharge_date").type());
        assertEquals("p.discharge_date", specs.get("discharge_date").column());
        assertFalse(specs.containsKey("patient_name"), "敏感加密字段不进条件树（走 keyword 盲索引）");
        assertFalse(specs.containsKey("height_cm"), "JSON 存储随 E13-T02 落地后再进目录");
        assertFalse(specs.containsKey("followup_note"), "textarea 不进目录");
        assertFalse(specs.containsKey("some_unbound"), "只有字典没有列绑定（存储未落地）不进目录");
    }

    @Test
    void catalogSkipsDisabledFieldsAndNonBaselineForms() {
        FieldDictionaryService.FieldView disabledField =
                new FieldDictionaryService.FieldView(
                        "A_BASELINE",
                        "基线档案（示例）",
                        "fward",
                        "ward",
                        "病区/中心",
                        "basic",
                        "基本信息",
                        "select",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        false,
                        "column",
                        null,
                        10,
                        "DISABLED",
                        null);
        Mockito.when(fieldDictionary.forms())
                .thenReturn(
                        List.of(
                                form("A_BASELINE"),
                                new FieldDictionaryService.FormSummaryView(
                                        "A_ANNUAL_FU", "年度随访（示例）", "FOLLOWUP", 1, "ENABLED")));
        Mockito.when(fieldDictionary.fields("A_BASELINE", null)).thenReturn(List.of(disabledField));
        Mockito.when(patientMapper.findWards()).thenReturn(List.of());

        assertTrue(service.specs().isEmpty(), "停用字段不进目录");
        Mockito.verify(fieldDictionary, Mockito.never()).fields("A_ANNUAL_FU", null);
    }

    @Test
    void catalogUsesStorageCodedOptionsForGenderWardAndOptionSet() {
        FieldDictionaryService.FieldView gender =
                new FieldDictionaryService.FieldView(
                        "A_BASELINE",
                        "基线档案（示例）",
                        "f004",
                        "gender",
                        "性别",
                        "basic",
                        "基本信息",
                        "select",
                        null,
                        null,
                        null,
                        null,
                        List.of(option(1, "男", "ENABLED"), option(2, "女", "ENABLED")),
                        false,
                        null,
                        null,
                        null,
                        false,
                        "column",
                        null,
                        40,
                        "ENABLED",
                        null);
        FieldDictionaryService.FieldView ward = field("ward", "病区/中心", "select", "column", false);
        FieldDictionaryService.FieldView dischargeStatus =
                new FieldDictionaryService.FieldView(
                        "A_BASELINE",
                        "基线档案（示例）",
                        "f191",
                        "discharge_status",
                        "出院状态",
                        "periop",
                        "围手术期",
                        "select",
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                option(1, "存活", "ENABLED"),
                                option(2, "病重自动出院", "ENABLED"),
                                option(3, "死亡", "DISABLED")),
                        false,
                        null,
                        null,
                        null,
                        false,
                        "column",
                        null,
                        1910,
                        "ENABLED",
                        null);
        stubDictionary(List.of(gender, ward, dischargeStatus));
        Mockito.when(patientMapper.findWards())
                .thenReturn(
                        List.of(
                                new PatientMapper.OptionRow(3L, "示例一病区"),
                                new PatientMapper.OptionRow(4L, "示例二病区")));

        PatientSearchFieldService.CatalogView catalog = service.catalog();

        PatientSearchFieldService.SearchFieldView genderView =
                catalog.fields().stream()
                        .filter(f -> f.field().equals("gender"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                List.of("M", "F"),
                genderView.options().stream().map(o -> o.value()).toList(),
                "性别按患者主档存储口径 M / F 下发（字典建议编码 1/2 不用于检索）");
        assertTrue(genderView.ops().containsAll(List.of("EQ", "NE", "IS_NULL", "NOT_NULL")));
        assertFalse(genderView.ops().contains("BETWEEN"), "选项字段不支持介于");

        PatientSearchFieldService.SearchFieldView wardView =
                catalog.fields().stream()
                        .filter(f -> f.field().equals("ward"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                List.of(3L, 4L),
                wardView.options().stream().map(o -> o.value()).toList(),
                "病区选项来自病区字典（值为 ward.id）");

        PatientSearchFieldService.SearchFieldView statusView =
                catalog.fields().stream()
                        .filter(f -> f.field().equals("discharge_status"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                List.of(1, 2),
                statusView.options().stream().map(o -> o.value()).toList(),
                "选项集只含启用项（停用的「死亡」不出现）");
        assertEquals("围手术期", statusView.categoryLabel());
    }

    @Test
    void specsFeedCompilerWithSameColumnBindings() {
        stubDictionary(List.of(field("discharge_date", "出院日期", "date", "column", false)));
        Mockito.when(patientMapper.findWards()).thenReturn(List.of());

        Map<String, SearchFieldSpec> specs = service.specs();
        assertEquals("p.discharge_date", specs.get("discharge_date").column());
        assertEquals(List.of(), specs.get("discharge_date").options());
    }
}
