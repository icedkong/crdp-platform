package com.crdp.followup.patient.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.patient.domain.AdvancedConditionCompiler.Compiled;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.OptionValue;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.SearchFieldSpec;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.ValueType;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E13-T06 / A-PAT-014、A-NFR-009、C-PAT-013 单元测试（验收「只能引用字典字段；无原始 SQL」）。
 *
 * <p>锁定口径：条件树只能引用目录（字段字典生成）中的字段——未知字段 / 运算符与类型不匹配 / 取值 非法一律
 * 40001；编译产物只含白名单列名与固定运算符，取值全部走参数位（advancedBinds）， 任何请求原文都不进 SQL 文本；包含（CONTAINS）转义 LIKE
 * 通配符；分组嵌套与行数有上限； 病区条件不进条件树，由数据范围拦截器自动追加（E07-T02）。全部虚构数据。
 */
class AdvancedConditionCompilerTest {

    private final ObjectMapper json = new ObjectMapper();

    private final Map<String, SearchFieldSpec> catalog = sampleCatalog();

    /** 与 {@link PatientSearchFieldService} 实际口径同构的样例目录（列绑定 + 选项）。 */
    private static Map<String, SearchFieldSpec> sampleCatalog() {
        Map<String, SearchFieldSpec> fields = new LinkedHashMap<>();
        fields.put(
                "discharge_date",
                new SearchFieldSpec(
                        "discharge_date", "出院日期", ValueType.DATE, "p.discharge_date", List.of()));
        fields.put(
                "gender",
                new SearchFieldSpec(
                        "gender",
                        "性别",
                        ValueType.OPTION,
                        "p.gender",
                        List.of(new OptionValue("M", "男"), new OptionValue("F", "女"))));
        fields.put(
                "patient_type",
                new SearchFieldSpec(
                        "patient_type", "患者类型", ValueType.TEXT, "p.patient_type", List.of()));
        fields.put(
                "ward",
                new SearchFieldSpec(
                        "ward",
                        "病区/中心",
                        ValueType.OPTION,
                        "p.ward_id",
                        List.of(new OptionValue(3L, "示例一病区"), new OptionValue(4L, "示例二病区"))));
        return fields;
    }

    private Compiled compile(String conditionJson) {
        try {
            return AdvancedConditionCompiler.compile(json.readTree(conditionJson), catalog);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 行：日期

    @Test
    void compilesDateRowWithParameterizedValue() {
        Compiled compiled =
                compile(
                        """
                        {"logic":"AND","items":[{"field":"discharge_date","op":"GT","value":"2026-01-01"}]}
                        """);

        assertEquals("(p.discharge_date > #{c.advancedBinds[0]})", compiled.where());
        assertEquals(List.of(LocalDate.of(2026, 1, 1)), compiled.binds());
        assertEquals(1, compiled.rows());
    }

    @Test
    void compilesBetweenWithTwoBindsInOrder() {
        Compiled compiled =
                compile(
                        """
                        {"items":[{"field":"discharge_date","op":"BETWEEN","value":["2026-01-01","2026-06-30"]}]}
                        """);

        assertEquals(
                "(p.discharge_date BETWEEN #{c.advancedBinds[0]} AND #{c.advancedBinds[1]})",
                compiled.where());
        assertEquals(
                List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)), compiled.binds());
    }

    @Test
    void rejectsBetweenWithReversedDates() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                compile(
                                        """
                                        {"items":[{"field":"discharge_date","op":"BETWEEN","value":["2026-06-30","2026-01-01"]}]}
                                        """));
        assertEquals(40001, e.code());
    }

    @Test
    void rejectsMalformedDate() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                compile(
                                        """
                                        {"items":[{"field":"discharge_date","op":"EQ","value":"2026/01/01"}]}
                                        """));
        assertEquals(40001, e.code());
    }

    @Test
    void compilesNullOperatorsWithoutBinds() {
        Compiled compiled =
                compile(
                        """
                        {"items":[{"field":"discharge_date","op":"IS_NULL"},{"field":"gender","op":"NOT_NULL"}]}
                        """);
        assertEquals("(p.discharge_date IS NULL AND p.gender IS NOT NULL)", compiled.where());
        assertTrue(compiled.binds().isEmpty(), "空 / 非空不产生参数位");
    }

    // ---------------------------------------------------------------- 行：选项、文本

    @Test
    void compilesOptionRowOnlyForKnownOptionValue() {
        Compiled compiled =
                compile(
                        """
                        {"items":[{"field":"gender","op":"EQ","value":"M"}]}
                        """);
        assertEquals("(p.gender = #{c.advancedBinds[0]})", compiled.where());
        assertEquals(List.of("M"), compiled.binds());

        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () ->
                                compile(
                                        """
                                {"items":[{"field":"gender","op":"EQ","value":"X"}]}
                                """));
        assertEquals(40001, unknown.code(), "选项白名单外取值 40001");
    }

    @Test
    void compilesWardRowBindingLongId() {
        Compiled compiled =
                compile(
                        """
                        {"items":[{"field":"ward","op":"EQ","value":4}]}
                        """);
        assertEquals("(p.ward_id = #{c.advancedBinds[0]})", compiled.where());
        assertEquals(List.of(4L), compiled.binds());
    }

    @Test
    void rejectsContainsOnOptionField() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                compile(
                                        """
                                {"items":[{"field":"gender","op":"CONTAINS","value":"男"}]}
                                """));
        assertEquals(40001, e.code(), "选项字段不提供包含运算（按编码取值）");
    }

    @Test
    void compilesContainsWithEscapedLikeWildcards() {
        Compiled compiled =
                compile(
                        """
                        {"items":[{"field":"patient_type","op":"CONTAINS","value":"急诊_50%"}]}
                        """);
        assertEquals("(p.patient_type LIKE #{c.advancedBinds[0]})", compiled.where());
        assertEquals(List.of("%急诊\\_50\\%%"), compiled.binds(), "LIKE 通配符被转义");
    }

    @Test
    void compilesTextIsNullIncludingEmptyString() {
        Compiled compiled =
                compile(
                        """
                        {"items":[{"field":"patient_type","op":"IS_NULL"}]}
                        """);
        assertEquals(
                "((p.patient_type IS NULL OR p.patient_type = ''))",
                compiled.where(),
                "文本空判断把空串一并算空，整行自括号");
    }

    // ---------------------------------------------------------------- 无原始 SQL（验收）

    @Test
    void unknownFieldOrOperatorRejected() {
        ApiException unknownField =
                assertThrows(
                        ApiException.class,
                        () ->
                                compile(
                                        """
                                        {"items":[{"field":"name_cipher; DROP TABLE pt_patient","op":"EQ","value":"x"}]}
                                        """));
        assertEquals(40001, unknownField.code(), "目录外字段（字典字段才可引用）40001");

        ApiException unknownOp =
                assertThrows(
                        ApiException.class,
                        () ->
                                compile(
                                        """
                                        {"items":[{"field":"discharge_date","op":"SQL_INJECTION","value":"2026-01-01"}]}
                                        """));
        assertEquals(40001, unknownOp.code(), "白名单外运算符 40001");
    }

    @Test
    void compiledWhereNeverContainsRequestText() {
        Compiled compiled =
                compile(
                        """
                        {"logic":"OR","items":[
                          {"field":"patient_type","op":"CONTAINS","value":"' OR 1=1 --"},
                          {"field":"discharge_date","op":"LT","value":"2027-01-01"}]}
                        """);
        String where = compiled.where();
        assertTrue(
                !where.contains("'") && !where.contains("1=1"), "请求原文只进参数位，不出现在 SQL 文本：" + where);
        assertEquals(
                "(p.patient_type LIKE #{c.advancedBinds[0]} OR p.discharge_date < #{c.advancedBinds[1]})",
                where);
        assertEquals(List.of("%' OR 1=1 --%", LocalDate.of(2027, 1, 1)), compiled.binds());
    }

    // ---------------------------------------------------------------- 分组

    @Test
    void compilesNestedGroupsWithParentheses() {
        Compiled compiled =
                compile(
                        """
                        {"logic":"AND","items":[
                          {"field":"gender","op":"EQ","value":"M"},
                          {"logic":"OR","items":[
                            {"field":"discharge_date","op":"IS_NULL"},
                            {"field":"patient_type","op":"NOT_NULL"}]}]}
                        """);
        assertEquals(
                "(p.gender = #{c.advancedBinds[0]} AND (p.discharge_date IS NULL"
                        + " OR (p.patient_type IS NOT NULL AND p.patient_type <> '')))",
                compiled.where());
        assertEquals(3, compiled.rows());
    }

    @Test
    void rejectsUnknownLogicAndBadShapes() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        compile(
                                                """
                                                {"logic":"XOR","items":[{"field":"gender","op":"EQ","value":"M"}]}
                                                """))
                        .code(),
                "logic 只允许 AND / OR");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        compile(
                                                """
                                        {"items":[{"op":"EQ","value":"M"}]}
                                        """))
                        .code(),
                "缺 field 40001");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        compile(
                                                """
                                        {"items":[{"field":"gender","value":"M"}]}
                                        """))
                        .code(),
                "缺 op 40001");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        compile(
                                                """
                                        {"items":["not-an-object"]}
                                        """))
                        .code(),
                "items 只能是条件行或条件组");
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> compile("{\"items\":[{\"items\":[]}]}"))
                        .code(),
                "空条件组 40001");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        compile(
                                                """
                                                {"items":[{"field":"gender","op":"EQ","value":"M","items":[]}]}
                                                """))
                        .code(),
                "条件行与条件组混用字段 40001");
    }

    @Test
    void enforcesDepthAndRowLimits() {
        StringBuilder deep = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 4; i++) {
            deep.append("{\"items\":[");
        }
        deep.append("{\"field\":\"gender\",\"op\":\"EQ\",\"value\":\"M\"}");
        for (int i = 0; i < 4; i++) {
            deep.append("]}");
        }
        deep.append("]}");
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> compile(deep.toString())).code(),
                "嵌套深度超过 3 层 40001");

        StringBuilder many = new StringBuilder("{\"logic\":\"OR\",\"items\":[");
        for (int i = 0; i < 31; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"field\":\"gender\",\"op\":\"EQ\",\"value\":\"M\"}");
        }
        many.append("]}");
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> compile(many.toString())).code(),
                "条件行超过 30 行 40001");
    }

    // ---------------------------------------------------------------- 空条件

    @Test
    void nullOrEmptyConditionMeansNoFilter() {
        assertNull(AdvancedConditionCompiler.compile(null, catalog).where());
        assertNull(compile("{}").where());
        assertNull(compile("{\"items\":[]}").where());
    }
}
