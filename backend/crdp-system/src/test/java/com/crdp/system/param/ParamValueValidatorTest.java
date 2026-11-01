package com.crdp.system.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * E06-T10 / PLT-CFG-003、004：参数取值与取值范围校验（纯函数）。 平台侧范围本身的合法性、各值类型的 取值校验、边界值（min/max 闭区间、maxLength
 * 边界、枚举命中）。
 */
class ParamValueValidatorTest {

    // --------------------------------------------------------------- 取值范围本身

    @Test
    void rangeIsRequiredWhenOverridableExceptBooleanAndJson() {
        assertEquals(
                "租户可覆盖的 NUMBER 参数必须配置取值范围", ParamValueValidator.rangeError("NUMBER", null, true));
        assertEquals("租户可覆盖的 TEXT 参数必须配置取值范围", ParamValueValidator.rangeError("TEXT", " ", true));
        assertEquals("租户可覆盖的 ENUM 参数必须配置取值范围", ParamValueValidator.rangeError("ENUM", null, true));
        // 平台锁定或 BOOLEAN / JSON：范围可省
        assertNull(ParamValueValidator.rangeError("NUMBER", null, false));
        assertNull(ParamValueValidator.rangeError("BOOLEAN", null, true));
        assertNull(ParamValueValidator.rangeError("JSON", null, true));
    }

    @Test
    void rangeMustMatchValueTypeShape() {
        assertEquals(
                "数值参数的取值范围 min / max 必须是数字",
                ParamValueValidator.rangeError("NUMBER", "{\"min\":\"a\",\"max\":10}", false));
        assertEquals(
                "数值参数的取值范围必须包含 min 或 max 至少一端",
                ParamValueValidator.rangeError("NUMBER", "{}", false));
        assertEquals(
                "文本参数的取值范围必须是 1–512 的 maxLength",
                ParamValueValidator.rangeError("TEXT", "{\"maxLength\":0}", false));
        assertEquals(
                "枚举参数的取值范围必须是非空 options 数组",
                ParamValueValidator.rangeError("ENUM", "{\"options\":[]}", false));
        assertEquals(
                "枚举参数的 options 必须是非空字符串",
                ParamValueValidator.rangeError("ENUM", "{\"options\":[\"A\",\"\"]}", false));
        assertEquals(
                "布尔参数没有取值范围（取值只能是 true / false）",
                ParamValueValidator.rangeError("BOOLEAN", "{\"min\":1}", false));
        assertEquals(
                "JSON 参数没有取值范围",
                ParamValueValidator.rangeError("JSON", "{\"maxLength\":10}", false));
        assertEquals("取值范围必须是 JSON 对象", ParamValueValidator.rangeError("TEXT", "not-json", false));
        assertEquals(
                "值类型必须是 TEXT / NUMBER / BOOLEAN / ENUM / JSON",
                ParamValueValidator.rangeError("COLOR", null, false));
    }

    // --------------------------------------------------------------- 取值校验

    @Test
    void numberValueMustStayInClosedRange() {
        String range = "{\"min\":5,\"max\":50}";
        assertNull(ParamValueValidator.valueError("NUMBER", range, "5"));
        assertNull(ParamValueValidator.valueError("NUMBER", range, "50"));
        assertNull(ParamValueValidator.valueError("NUMBER", range, " 20.5 "));
        assertEquals("取值不得小于 5", ParamValueValidator.valueError("NUMBER", range, "4.9"));
        assertEquals("取值不得大于 50", ParamValueValidator.valueError("NUMBER", range, "51"));
        assertEquals("数值参数取值必须是数字", ParamValueValidator.valueError("NUMBER", range, "abc"));
        // 只给一端：另一端不设限
        assertNull(ParamValueValidator.valueError("NUMBER", "{\"min\":1}", "9999"));
        assertEquals("取值不得小于 1", ParamValueValidator.valueError("NUMBER", "{\"min\":1}", "0"));
    }

    @Test
    void textValueMaxLength() {
        assertNull(ParamValueValidator.valueError("TEXT", "{\"maxLength\":5}", "abcde"));
        assertEquals(
                "文本长度不能超过 5",
                ParamValueValidator.valueError("TEXT", "{\"maxLength\":5}", "abcdef"));
        assertEquals(
                "文本参数取值不能为空白", ParamValueValidator.valueError("TEXT", "{\"maxLength\":5}", "  "));
        // 无范围（平台锁定）回落列宽上限
        assertNull(ParamValueValidator.valueError("TEXT", null, "a".repeat(512)));
        assertEquals(
                "取值不能超过 512 个字符", ParamValueValidator.valueError("TEXT", null, "a".repeat(513)));
    }

    @Test
    void booleanAndEnumAndJsonValues() {
        assertNull(ParamValueValidator.valueError("BOOLEAN", null, "true"));
        assertNull(ParamValueValidator.valueError("BOOLEAN", null, "false"));
        assertEquals(
                "布尔参数取值只能是 true 或 false", ParamValueValidator.valueError("BOOLEAN", null, "TRUE"));

        String enumRange = "{\"options\":[\"DAILY\",\"WEEKLY\"]}";
        assertNull(ParamValueValidator.valueError("ENUM", enumRange, "DAILY"));
        assertEquals(
                "取值必须是枚举选项之一：DAILY / WEEKLY",
                ParamValueValidator.valueError("ENUM", enumRange, "MONTHLY"));

        assertNull(ParamValueValidator.valueError("JSON", null, "{\"page\":20}"));
        assertEquals(
                "JSON 参数取值必须是合法 JSON", ParamValueValidator.valueError("JSON", null, "{page:20}"));
        assertEquals("JSON 参数取值不能是空白", ParamValueValidator.valueError("JSON", null, " "));
    }

    @Test
    void nullOrUnknownTypeAlwaysRejected() {
        assertEquals("取值不能为空", ParamValueValidator.valueError("TEXT", null, null));
        assertTrue(
                ParamValueValidator.valueError("COLOR", null, "x").startsWith("值类型必须是"),
                "未知值类型按类型错误处理");
    }
}
