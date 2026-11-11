package com.crdp.followup.compare.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E17-T01 / A-FU-027：对比规则类型与 params 键集校验单元测试（tech-plan §11 七类规则形状）。
 *
 * <p>锁定：必填键缺失 / 未知键 / 枚举与数值越界 → 40001；VALUE_HIT 的 codes 与 op+value 二选一； FLIP_TO_YES / NEW_EVENT
 * 无必填阈值键（只可开关）。全部虚构数据。
 */
class CompareRuleTypeTest {

    private static ApiException invalid(CompareRuleType type, Map<String, Object> params) {
        return assertThrows(ApiException.class, () -> type.validateParams(params), type.name());
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** Q41 种子形状可过：EF 下降 ≥ 10、LVDd 增加 ≥ 5（迁移 V67 与 E17-T05 预置同口径）。 */
    @Test
    void diffAcceptsQ41Shapes() {
        assertDoesNotThrow(
                () ->
                        CompareRuleType.DIFF.validateParams(
                                params("direction", "DROP", "threshold", 10)));
        assertDoesNotThrow(
                () ->
                        CompareRuleType.DIFF.validateParams(
                                params("direction", "RISE", "threshold", 5.5)));
    }

    @Test
    void diffRejectsBadDirectionMissingOrUnknownKeys() {
        assertEquals(
                40001,
                invalid(CompareRuleType.DIFF, params("direction", "ANY", "threshold", 10)).code());
        assertEquals(40001, invalid(CompareRuleType.DIFF, params("direction", "DROP")).code());
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.DIFF,
                                params("direction", "DROP", "threshold", 10, "field", "f248"))
                        .code());
    }

    /** threshold 须为正数（≤ 9999、最多两位小数）。 */
    @Test
    void diffRejectsNonPositiveOrOverFineThreshold() {
        assertEquals(
                40001,
                invalid(CompareRuleType.DIFF, params("direction", "DROP", "threshold", 0)).code());
        assertEquals(
                40001,
                invalid(CompareRuleType.DIFF, params("direction", "DROP", "threshold", -1)).code());
        assertEquals(
                40001,
                invalid(CompareRuleType.DIFF, params("direction", "DROP", "threshold", 10000))
                        .code());
        assertEquals(
                40001,
                invalid(CompareRuleType.DIFF, params("direction", "DROP", "threshold", 10.123))
                        .code());
        assertEquals(
                40001,
                invalid(CompareRuleType.DIFF, params("direction", "DROP", "threshold", "10"))
                        .code());
    }

    /** 变化率允许 ANY（C「阻抗升高或降低 > 50%」）；DIFF 不允许。 */
    @Test
    void changePctAcceptsAnyDirection() {
        assertDoesNotThrow(
                () ->
                        CompareRuleType.CHANGE_PCT.validateParams(
                                params("direction", "ANY", "percent", 50)));
        assertDoesNotThrow(
                () ->
                        CompareRuleType.CHANGE_PCT.validateParams(
                                params("direction", "RISE", "percent", 50.5)));
    }

    /**
     * 变化率参考注键（E21-T20 / C-FU-010「脉宽不同，仅供参考」）：noteField 与 noteText 成对出现或都省略； 单边缺失、空串、超长 → 40001；DIFF
     * 不支持参考注键（未知键）。
     */
    @Test
    void changePctValidatesNoteKeys() {
        assertDoesNotThrow(
                () ->
                        CompareRuleType.CHANGE_PCT.validateParams(
                                params(
                                        "direction",
                                        "RISE",
                                        "percent",
                                        50,
                                        "noteField",
                                        "f309",
                                        "noteText",
                                        "脉宽不同，仅供参考")));
        // 只带一边 → 40001
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.CHANGE_PCT,
                                params("direction", "RISE", "percent", 50, "noteField", "f309"))
                        .code());
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.CHANGE_PCT,
                                params("direction", "RISE", "percent", 50, "noteText", "脉宽不同，仅供参考"))
                        .code());
        // 空串 / 超长文案 → 40001
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.CHANGE_PCT,
                                params(
                                        "direction",
                                        "RISE",
                                        "percent",
                                        50,
                                        "noteField",
                                        " ",
                                        "noteText",
                                        "脉宽不同，仅供参考"))
                        .code());
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.CHANGE_PCT,
                                params(
                                        "direction",
                                        "RISE",
                                        "percent",
                                        50,
                                        "noteField",
                                        "f309",
                                        "noteText",
                                        "注".repeat(51)))
                        .code());
        // DIFF 不支持参考注键（未知键）
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.DIFF,
                                params(
                                        "direction",
                                        "RISE",
                                        "threshold",
                                        5,
                                        "noteField",
                                        "f309",
                                        "noteText",
                                        "脉宽不同，仅供参考"))
                        .code());
    }

    /** 序数升级：grades 1–5 整数；reachCodes 为字符串数组（可缺省）。 */
    @Test
    void ordinalRiseValidatesGradesAndReachCodes() {
        assertDoesNotThrow(() -> CompareRuleType.ORDINAL_RISE.validateParams(params("grades", 2)));
        assertDoesNotThrow(
                () ->
                        CompareRuleType.ORDINAL_RISE.validateParams(
                                params("grades", 2, "reachCodes", List.of("3", "4"))));
        assertEquals(40001, invalid(CompareRuleType.ORDINAL_RISE, params("grades", 0)).code());
        assertEquals(40001, invalid(CompareRuleType.ORDINAL_RISE, params("grades", 6)).code());
        assertEquals(40001, invalid(CompareRuleType.ORDINAL_RISE, params("grades", 2.5)).code());
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.ORDINAL_RISE,
                                params("grades", 2, "reachCodes", List.of("3", 4)))
                        .code());
        assertEquals(40001, invalid(CompareRuleType.ORDINAL_RISE, params()).code());
    }

    /** 新发事件 / 由否变是：无必填阈值键（只可开关）；可选键形状校验。 */
    @Test
    void switchOnlyTypesAcceptEmptyParams() {
        assertTrue(CompareRuleType.NEW_EVENT.validateParams(params()).isEmpty());
        assertTrue(CompareRuleType.FLIP_TO_YES.validateParams(params()).isEmpty());
        assertDoesNotThrow(
                () -> CompareRuleType.NEW_EVENT.validateParams(params("dateField", "f224")));
        assertEquals(
                40001,
                invalid(CompareRuleType.NEW_EVENT, params("triggerCodes", List.of())).code());
    }

    /** 超出提示范围：Q41 默认 EF<40（LT）、LVDd>60（GT）可过；op 枚举与 value 范围校验。 */
    @Test
    void outOfRangeValidatesOpAndValue() {
        assertDoesNotThrow(
                () -> CompareRuleType.OUT_OF_RANGE.validateParams(params("op", "LT", "value", 40)));
        assertDoesNotThrow(
                () -> CompareRuleType.OUT_OF_RANGE.validateParams(params("op", "GT", "value", 60)));
        assertEquals(
                40001,
                invalid(CompareRuleType.OUT_OF_RANGE, params("op", "EQ", "value", 40)).code());
        assertEquals(40001, invalid(CompareRuleType.OUT_OF_RANGE, params("op", "LT")).code());
        assertEquals(
                40001,
                invalid(CompareRuleType.OUT_OF_RANGE, params("op", "LT", "value", 20000)).code());
    }

    /** 取值命中：codes 或 op+value 二选一（同缺 / 同有都拒绝）。 */
    @Test
    void valueHitRequiresEitherCodesOrBound() {
        assertDoesNotThrow(
                () -> CompareRuleType.VALUE_HIT.validateParams(params("codes", List.of("ERI"))));
        assertDoesNotThrow(
                () -> CompareRuleType.VALUE_HIT.validateParams(params("op", "LTE", "value", 12)));
        assertEquals(40001, invalid(CompareRuleType.VALUE_HIT, params()).code());
        assertEquals(
                40001,
                invalid(
                                CompareRuleType.VALUE_HIT,
                                params("codes", List.of("ERI"), "op", "LTE", "value", 12))
                        .code());
    }

    /** 未知规则编码 → 空（调用方转 40001）；类型展示名覆盖全部七类。 */
    @Test
    void fromCodeResolvesKnownTypesAndLabelsCoverAll() {
        assertTrue(CompareRuleType.fromCode("diff").isEmpty());
        assertTrue(CompareRuleType.fromCode("").isEmpty());
        for (CompareRuleType type : CompareRuleType.values()) {
            assertEquals(type, CompareRuleType.fromCode(type.name()).orElseThrow());
            assertTrue(!type.label().isBlank());
        }
    }

    /** params 为 null → 40001（PUT 必须带阈值对象；无键类型传空对象）。 */
    @Test
    void nullParamsRejected() {
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> CompareRuleType.DIFF.validateParams(null))
                        .code());
    }
}
