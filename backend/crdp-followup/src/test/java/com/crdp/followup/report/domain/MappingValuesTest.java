package com.crdp.followup.report.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E16-T07 / A-ATT-013、C-SCAN-005：指标结果文本 → 目标字段类型的值转换（报告单映射写入的前置口径）。
 *
 * <p>目标字段类型按 fe_field.type（FormDataValidator 同一套）：number 存 JSON 数值、date 存 YYYY-MM-DD 文本、 单选 /
 * 下拉存选项编码整数（结果可填编码或选项名，A 指标模板按项目名填写）、pair_number 存 {length,width}（室壁瘤大小「30×20」）； 值比较（冲突判定）与
 * fe_field_history 严格同型口径一致（数值按值比较）。
 */
class MappingValuesTest {

    private static final List<MappingValues.Option> REFLUX =
            List.of(
                    new MappingValues.Option(0, "无"),
                    new MappingValues.Option(1, "微量"),
                    new MappingValues.Option(2, "少量"),
                    new MappingValues.Option(3, "中量"),
                    new MappingValues.Option(4, "大量"));

    @Test
    void convertsNumbersWithIntegralAndDecimalForms() {
        assertEquals(55, MappingValues.convert("EF 射血分数", "number", List.of(), "55").value());
        assertEquals(55.5, MappingValues.convert("EF 射血分数", "number", List.of(), "55.5").value());
        assertEquals(55, MappingValues.convert("EF 射血分数", "number", List.of(), " 55 ").value());
        assertNull(MappingValues.convert("EF 射血分数", "number", List.of(), "55").error());

        MappingValues.Converted bad = MappingValues.convert("EF 射血分数", "number", List.of(), "未见");
        assertNull(bad.value());
        assertTrue(bad.error().contains("EF 射血分数"), bad.error());
        assertTrue(bad.error().contains("数值"), bad.error());
    }

    @Test
    void convertsDatesFromReportTimeText() {
        assertEquals(
                "2026-10-28",
                MappingValues.convert("超声检查时间", "date", List.of(), "2026-10-28").value());
        MappingValues.Converted bad = MappingValues.convert("超声检查时间", "date", List.of(), "10/28");
        assertNull(bad.value());
        assertTrue(bad.error().contains("YYYY-MM-DD"), bad.error());
    }

    @Test
    void convertsOptionsByCodeOrLabel() {
        assertEquals(2, MappingValues.convert("二尖瓣返流程度", "select", REFLUX, "少量").value());
        assertEquals(2, MappingValues.convert("二尖瓣返流程度", "select", REFLUX, "2").value());
        // radio 同口径（附带写入「复查超声 = 是」的取值文本 → 编码 1）
        assertEquals(
                1,
                MappingValues.convert(
                                "复查超声",
                                "radio",
                                List.of(
                                        new MappingValues.Option(0, "否"),
                                        new MappingValues.Option(1, "是")),
                                "1")
                        .value());

        MappingValues.Converted bad = MappingValues.convert("二尖瓣返流程度", "select", REFLUX, "重度（示例）");
        assertNull(bad.value());
        assertTrue(bad.error().contains("无 / 微量 / 少量 / 中量 / 大量"), bad.error());
    }

    /**
     * E23-T21 / C-SCAN-005：多选字段（ICD 恰当 / 不恰当治疗）——顿号 / 逗号 / 分号分隔的选项名或编码 → 编码数组（FormDataValidator
     * 多选口径），去重保序；任一项不在选项内整行报错。
     */
    @Test
    void convertsMultiSelectByLabelsOrCodes() {
        List<MappingValues.Option> icdTherapy =
                List.of(
                        new MappingValues.Option(0, "无"),
                        new MappingValues.Option(1, "ATP"),
                        new MappingValues.Option(2, "SHOCK"));
        assertEquals(
                List.of(1, 2),
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "ATP、SHOCK").value());
        assertEquals(
                List.of(0),
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "无").value());
        assertEquals(
                List.of(1, 2),
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "1,2").value(),
                "编码原样可写（顿号 / 逗号 / 分号分隔）");
        assertEquals(
                List.of(1, 2),
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "ATP， SHOCK；").value(),
                "分隔符混用与空白容忍");
        assertEquals(
                List.of(1),
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "ATP、ATP").value(),
                "重复取值去重（编码不重复）");

        MappingValues.Converted bad =
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "ATP、放电（示例）");
        assertNull(bad.value());
        assertTrue(bad.error().contains("无 / ATP / SHOCK"), bad.error());

        MappingValues.Converted empty =
                MappingValues.convert("ICD恰当治疗", "multiselect", icdTherapy, "、");
        assertNull(empty.value());
        assertTrue(empty.error().contains("不能写入"), empty.error());
        assertNull(
                MappingValues.convert("并发症", "multiselect", List.of(), "1").value(), "未配置选项集不能写入");
        assertTrue(
                MappingValues.convert("并发症", "multiselect", List.of(), "1")
                        .error()
                        .contains("未配置选项集"));
    }

    /** E23-T20 / OQ-69④：字典绑定单选（程控起搏模式，字符串编码）——选项名或编码原样都可写。 */
    @Test
    void convertsDictBoundSelectsByStringCodeOrLabel() {
        List<MappingValues.Option> modes =
                List.of(
                        new MappingValues.Option("DDD", "DDD（双腔起搏）"),
                        new MappingValues.Option("VVI", "VVI（心室起搏）"));
        assertEquals(
                "DDD",
                MappingValues.convert("程控起搏模式", "select", modes, "DDD（双腔起搏）").value(),
                "按选项名匹配");
        assertEquals(
                "VVI",
                MappingValues.convert("程控起搏模式", "select", modes, "VVI").value(),
                "字典编码为字符串、原样可写（与整数码路径同口径）");

        MappingValues.Converted bad = MappingValues.convert("程控起搏模式", "select", modes, "AAI（示例）");
        assertNull(bad.value());
        assertTrue(bad.error().contains("DDD（双腔起搏） / VVI（心室起搏）"), bad.error());
    }

    @Test
    void convertsPairNumbersLikeAneurysmSize() {
        Map<?, ?> pair =
                (Map<?, ?>)
                        MappingValues.convert("随访室壁瘤大小", "pair_number", List.of(), "30×20").value();
        assertEquals(30, pair.get("length"));
        assertEquals(20, pair.get("width"));

        Map<?, ?> asterisk =
                (Map<?, ?>)
                        MappingValues.convert("随访室壁瘤大小", "pair_number", List.of(), "35.5*22")
                                .value();
        assertEquals(35.5, asterisk.get("length"));
        assertEquals(22, asterisk.get("width"));

        MappingValues.Converted bad =
                MappingValues.convert("随访室壁瘤大小", "pair_number", List.of(), "30");
        assertNull(bad.value());
        assertTrue(bad.error().contains("长×宽"), bad.error());
    }

    @Test
    void passesTextThroughAndRejectsUnsupportedTypes() {
        assertEquals(
                "室壁运动正常（示例）",
                MappingValues.convert("所见", "textarea", List.of(), "室壁运动正常（示例）").value());

        assertNull(MappingValues.convert("BMI", "derived", List.of(), "22").value());
        assertTrue(MappingValues.convert("BMI", "derived", List.of(), "22").error().contains("派生"));
        assertNull(
                MappingValues.convert("通话时间", "datetime", List.of(), "2026-10-28 10:00").value(),
                "日期时间仍不支持（与 E16-T07 相同，多选已随 E23-T21 开放）");
    }

    @Test
    void conflictComparesValuesWithNumberTolerance() {
        // 空对空不算冲突（OQ-65 ① 空值口径：null / 空串 / 空数组）
        assertFalse(MappingValues.conflict(null, null));
        assertFalse(MappingValues.conflict("", 55));
        // 数值按值比较（55 与 55.0 同值）
        assertFalse(MappingValues.conflict(55, 55.0));
        assertTrue(MappingValues.conflict(50, 55));
        // 编码整数与文本不同（严格同型）
        assertTrue(MappingValues.conflict(1, "1"));
        // 同值不算冲突
        assertFalse(MappingValues.conflict("2026-10-28", "2026-10-28"));
    }
}
