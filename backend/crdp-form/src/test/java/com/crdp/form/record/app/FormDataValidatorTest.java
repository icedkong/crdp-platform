package com.crdp.form.record.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.form.field.app.FormDictPort;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E11-T02 / A-NFR-002、C-NFR-002：表单数据 JSON 写入闸门单元测试（tech-plan §6 值编码口径）。
 *
 * <p>锁定：键必须在版本内且为 json 存储（未知键、实体列 / 加密列拒绝）；数值存数字、是否存 true/false、日期 YYYY-MM-DD、日期时间
 * YYYY-MM-DDTHH:mm、单选存编码整数、多选存编码数组（不重复）、数值×2 存 {length,width}； null 放行（清空）；派生字段不查类型（E11-T05
 * 重算）。全部虚构数据。
 */
class FormDataValidatorTest {

    private final FormFieldMapper fieldMapper = mock(FormFieldMapper.class);
    private final FormDictPort dictPort = code -> List.of();

    private final FormDataValidator validator = new FormDataValidator(fieldMapper, dictPort);

    private static VersionFieldRow field(
            String key, String type, String storage, String optionSet) {
        return new VersionFieldRow(
                1L,
                1L,
                "A_BASELINE",
                1,
                key,
                key + "_code",
                "字段「" + key + "」",
                "basic_info",
                type,
                null,
                null,
                null,
                null,
                optionSet,
                false,
                null,
                null,
                null,
                false,
                storage,
                null,
                0);
    }

    private void options(String... codes) {
        when(fieldMapper.findOptions("yesno"))
                .thenReturn(
                        codes == null
                                ? List.of()
                                : java.util.Arrays.stream(codes)
                                        .map(
                                                code ->
                                                        new OptionRow(
                                                                1L,
                                                                "yesno",
                                                                Integer.parseInt(code),
                                                                "选项（示例）",
                                                                null,
                                                                false,
                                                                false,
                                                                null,
                                                                0,
                                                                "ENABLED",
                                                                null,
                                                                null))
                                        .toList());
    }

    // ---------------------------------------------------------------- 键合法性

    @Test
    void rejectsUnknownKeyAndNonJsonStorage() {
        VersionFieldRow height = field("f011", "number", "json", null);
        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () -> validator.validateData(Map.of("f999", 1), List.of(height)));
        assertEquals(40001, unknown.code());

        ApiException column =
                assertThrows(
                        ApiException.class,
                        () ->
                                validator.validateData(
                                        Map.of("f020", "2024-01-01"),
                                        List.of(field("f020", "date", "column", null))));
        assertEquals(40001, column.code());

        ApiException encrypted =
                assertThrows(
                        ApiException.class,
                        () ->
                                validator.validateData(
                                        Map.of("f001", "张三（示例）"),
                                        List.of(field("f001", "text", "encrypted", null))));
        assertEquals(40001, encrypted.code());
    }

    @Test
    void allowsNullValueAndEmptyData() {
        assertDoesNotThrow(() -> validator.validateData(Map.of(), List.of()));
        assertDoesNotThrow(
                () ->
                        validator.validateData(
                                java.util.Collections.singletonMap("f011", null),
                                List.of(field("f011", "number", "json", null))));
    }

    // ---------------------------------------------------------------- 类型检查

    @Test
    void checksNumberBooleanAndText() {
        VersionFieldRow height = field("f011", "number", "json", null);
        assertDoesNotThrow(() -> validator.validateData(Map.of("f011", 175.5), List.of(height)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f011", "175"), List.of(height)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> validator.validateData(Map.of("f011", true), List.of(height)))
                        .code());

        VersionFieldRow smoking = field("f010", "boolean", "json", null);
        assertDoesNotThrow(() -> validator.validateData(Map.of("f010", true), List.of(smoking)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> validator.validateData(Map.of("f010", 1), List.of(smoking)))
                        .code());

        VersionFieldRow note = field("f100", "textarea", "json", null);
        assertDoesNotThrow(() -> validator.validateData(Map.of("f100", "备注（示例）"), List.of(note)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> validator.validateData(Map.of("f100", 5), List.of(note)))
                        .code());
    }

    @Test
    void checksDateAndDatetimeFormats() {
        VersionFieldRow surgery = field("f020", "date", "json", null);
        assertDoesNotThrow(
                () -> validator.validateData(Map.of("f020", "2024-05-06"), List.of(surgery)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f020", "2024/05/06"), List.of(surgery)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f020", "2024-02-30"), List.of(surgery)))
                        .code());

        VersionFieldRow start = field("f021", "datetime", "json", null);
        assertDoesNotThrow(
                () -> validator.validateData(Map.of("f021", "2024-05-06T08:30"), List.of(start)));
        assertDoesNotThrow(
                () ->
                        validator.validateData(
                                Map.of("f021", "2024-05-06T08:30:15"), List.of(start)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f021", "2024-05-06 08:30"), List.of(start)))
                        .code());
    }

    @Test
    void checksOptionCodes() {
        options("0", "1", "2");
        VersionFieldRow radio = field("f010", "radio", "json", "yesno");
        assertDoesNotThrow(() -> validator.validateData(Map.of("f010", 1), List.of(radio)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> validator.validateData(Map.of("f010", 9), List.of(radio)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> validator.validateData(Map.of("f010", 1.5), List.of(radio)))
                        .code());

        VersionFieldRow multi = field("f012", "multiselect", "json", "yesno");
        assertDoesNotThrow(
                () -> validator.validateData(Map.of("f012", List.of(0, 2)), List.of(multi)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f012", List.of(0, 0)), List.of(multi)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> validator.validateData(Map.of("f012", 0), List.of(multi)))
                        .code());
    }

    @Test
    void checksPairNumberShape() {
        VersionFieldRow size = field("f040", "pair_number", "json", null);
        assertDoesNotThrow(
                () ->
                        validator.validateData(
                                Map.of("f040", Map.of("length", 5.2, "width", 3)), List.of(size)));
        assertDoesNotThrow(
                () -> validator.validateData(Map.of("f040", Map.of("length", 5.2)), List.of(size)));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f040", List.of(5.2, 3)), List.of(size)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        validator.validateData(
                                                Map.of("f040", Map.of("diameter", 5.2)),
                                                List.of(size)))
                        .code());
    }

    @Test
    void derivedFieldSkipsTypeCheck() {
        VersionFieldRow days = field("f030", "derived", "json", null);
        assertDoesNotThrow(() -> validator.validateData(Map.of("f030", 7), List.of(days)));
        assertDoesNotThrow(() -> validator.validateData(Map.of("f030", "7（手工，示例）"), List.of(days)));
    }

    // ------------------------------------------- 字典绑定字段（E23-T20 / OQ-69④：字符串编码）

    private static VersionFieldRow dictField(String key, String type, String dictCode) {
        return new VersionFieldRow(
                1L,
                1L,
                "C_PROGRAM_FU",
                1,
                key,
                key + "_code",
                "字段「" + key + "」",
                "pacing",
                type,
                null,
                null,
                null,
                null,
                null,
                dictCode,
                false,
                false,
                null,
                null,
                null,
                false,
                "json",
                null,
                0);
    }

    @Test
    void dictBoundFieldAcceptsStringCodesFromDictItems() {
        FormDictPort pacemakerTypes =
                code ->
                        List.of(
                                new FormDictPort.DictItem("SSI", "单腔起搏器(SSI)", 10, true),
                                new FormDictPort.DictItem("CRT_D", "CRT-D", 20, true),
                                new FormDictPort.DictItem("LEADLESS", "无导线起搏器", 30, false));
        FormDataValidator validator = new FormDataValidator(fieldMapper, pacemakerTypes);
        assertDoesNotThrow(
                () ->
                        validator.validateData(
                                Map.of("f318", "SSI"),
                                List.of(dictField("f318", "select", "pacemaker_type"))),
                "绑定字典的单选存字符串编码（cfg_dict 稳定码）");
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                validator.validateData(
                                        Map.of("f318", 1),
                                        List.of(dictField("f318", "select", "pacemaker_type"))));
        assertEquals(40001, failure.code(), "整数编码不再是字典字段的合法值");
        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () ->
                                validator.validateData(
                                        Map.of("f318", "XXX"),
                                        List.of(dictField("f318", "select", "pacemaker_type"))));
        assertEquals(40001, unknown.code(), "字典外编码拒绝（与 ImplantService 同口径）");
    }

    @Test
    void dictBoundMultiselectStoresStringArrayAndAllowsDisabledItems() {
        FormDictPort modes =
                code ->
                        List.of(
                                new FormDictPort.DictItem("DDD", "双腔起搏器(DDD)", 10, true),
                                new FormDictPort.DictItem("VVI", "VVI", 20, false));
        FormDataValidator validator = new FormDataValidator(fieldMapper, modes);
        assertDoesNotThrow(
                () ->
                        validator.validateData(
                                Map.of("f318x", List.of("DDD", "VVI")),
                                List.of(dictField("f318x", "multiselect", "pacing_mode"))),
                "多选存字符串数组；停用项的历史值可保存（C-FLD-009）");
        ApiException duplicate =
                assertThrows(
                        ApiException.class,
                        () ->
                                validator.validateData(
                                        Map.of("f318x", List.of("DDD", "DDD")),
                                        List.of(dictField("f318x", "multiselect", "pacing_mode"))));
        assertEquals(40001, duplicate.code(), "编码重复拒绝");
    }
}
