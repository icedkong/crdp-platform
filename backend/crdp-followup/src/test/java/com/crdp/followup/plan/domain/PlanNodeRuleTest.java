package com.crdp.followup.plan.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E14-T01 / A-FU-016 节点结构校验单测：两层树（子计划 SUBPLAN / 节点 NODE）、重复方式、 偏移 / 提醒 / 窗口 /
 * 任务类型枚举、父子引用与数量上限。全部虚构数据。
 */
class PlanNodeRuleTest {

    private static PlanNodeRule.NodeInput subplan(
            String key, String repeatMode, Integer repeatCount, Integer cycleValue, String unit) {
        return new PlanNodeRule.NodeInput(
                key,
                null,
                "第 1 年随访（示例）",
                repeatMode,
                repeatCount,
                cycleValue,
                unit,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10);
    }

    private static PlanNodeRule.NodeInput node(String key, String parentKey, String formCode) {
        return new PlanNodeRule.NodeInput(
                key,
                parentKey,
                "电话随访（示例）",
                null,
                null,
                null,
                null,
                " plan_start ",
                1,
                " year ",
                7,
                " day ",
                7,
                "day",
                7,
                "day",
                " form ",
                " ward_default ",
                formCode,
                null,
                null,
                20);
    }

    @Test
    void normalizesEnumsAndDefaults() {
        List<PlanNodeRule.NormalizedNode> normalized =
                PlanNodeRule.normalize(
                        List.of(
                                subplan("s1", " cycle ", null, 1, "year"),
                                node("n1", "s1", "fu_annual_v1")));

        assertEquals(2, normalized.size());
        assertEquals("SUBPLAN", normalized.get(0).nodeType());
        assertEquals("CYCLE", normalized.get(0).repeatMode());
        assertEquals(1, normalized.get(0).cycleValue());
        assertEquals("YEAR", normalized.get(0).cycleUnit());
        assertEquals("NODE", normalized.get(1).nodeType());
        assertEquals("PLAN_START", normalized.get(1).basis());
        assertEquals("YEAR", normalized.get(1).offsetUnit());
        assertEquals("FORM", normalized.get(1).taskType());
        assertEquals("WARD_DEFAULT", normalized.get(1).ownerType());
        assertEquals("fu_annual_v1", normalized.get(1).formCode());
    }

    @Test
    void emptyTreeRejected() {
        ApiException failure =
                assertThrows(ApiException.class, () -> PlanNodeRule.normalize(List.of()));
        assertEquals(40001, failure.code());
        assertTrue(failure.getMessage().contains("子计划"));
    }

    @Test
    void subplanRepeatModeValidated() {
        ApiException fixed =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(subplan("s1", "FIXED", 0, null, null))));
        assertEquals(40001, fixed.code());
        assertTrue(fixed.getMessage().contains("固定次数"));

        ApiException cycle =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(subplan("s1", "CYCLE", null, null, "DAY"))));
        assertTrue(cycle.getMessage().contains("循环周期"));

        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(subplan("s1", "DAILY", 1, null, null))));
        assertTrue(unknown.getMessage().contains("重复方式"));
    }

    @Test
    void nodeMustHangUnderSubplanOfSamePlan() {
        ApiException orphan =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(
                                                subplan("s1", "FIXED", 3, null, null),
                                                node("n1", "ghost", "fu_v1"))));
        assertTrue(orphan.getMessage().contains("上级子计划不存在"));

        ApiException underNode =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(
                                                subplan("s1", "FIXED", 3, null, null),
                                                node("n1", "s1", "fu_v1"),
                                                new PlanNodeRule.NodeInput(
                                                        "n2",
                                                        "n1",
                                                        "站内提醒（示例）",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        "REMINDER",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        30))));
        assertTrue(underNode.getMessage().contains("只能挂在子计划下"));
    }

    @Test
    void nodeParamsValidated() {
        // FORM 任务缺表单 code
        ApiException missingForm =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(
                                                subplan("s1", "FIXED", 3, null, null),
                                                node("n1", "s1", null))));
        assertTrue(missingForm.getMessage().contains("表单 code"));

        // 负数偏移
        ApiException negative =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(
                                                subplan("s1", "FIXED", 3, null, null),
                                                new PlanNodeRule.NodeInput(
                                                        "n1",
                                                        "s1",
                                                        "电话随访（示例）",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        "PLAN_START",
                                                        -1,
                                                        "YEAR",
                                                        7,
                                                        "day",
                                                        7,
                                                        "day",
                                                        7,
                                                        "day",
                                                        "FORM",
                                                        "DOCTOR",
                                                        "fu_v1",
                                                        null,
                                                        null,
                                                        20))));
        assertTrue(negative.getMessage().contains("偏移"));

        // REMINDER 任务不要求表单，提醒单位只有天 / 周 / 月
        ApiException remindUnit =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlanNodeRule.normalize(
                                        List.of(
                                                subplan("s1", "FIXED", 3, null, null),
                                                new PlanNodeRule.NodeInput(
                                                        "n1",
                                                        "s1",
                                                        "核对联系方式（示例）",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        "PLAN_START",
                                                        1,
                                                        "YEAR",
                                                        1,
                                                        "YEAR",
                                                        0,
                                                        "day",
                                                        0,
                                                        "day",
                                                        "REMINDER",
                                                        "DOCTOR",
                                                        null,
                                                        null,
                                                        null,
                                                        20))));
        assertTrue(remindUnit.getMessage().contains("提醒提前量单位"));
    }
}
