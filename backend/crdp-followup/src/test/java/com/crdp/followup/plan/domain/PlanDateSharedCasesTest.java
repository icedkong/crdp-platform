package com.crdp.followup.plan.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * E14-T10 / A-FU-002、A-FU-016、A-NFR-006：计划引擎<b>日期计算共享用例</b>（跨年、闰年、窗口）——前后端一致性测试。
 *
 * <p>E14-T03 的 {@link PlanDateCalculatorTest} 锁定库自身的标准用例；本测试执行共享用例文件 {@code
 * shared-cases/plan-date-cases.json}（components.md §10.5，同 E11 系列共享用例口径），同一份期望结果由 后端 {@link
 * PlanDateCalculator} 与前端 {@code packages/rules/src/plan-date-cases.test}（@crdp/rules
 * plan-date，E14-T02 计算预览）各执行一遍，结果逐条一致才通过：
 *
 * <ul>
 *   <li>addDateUnitCases：单位运算的跨年（含减法回到上一年）、闰年（回闰、世纪年 2100 非闰 / 2000 闰、跨闰日的天 / 周运算）与 月末钳制（单向、不放大）；
 *   <li>scheduleCases：完整排期——偏移跨年与窗口跨年（开启日落在上一年）、闰日基准钳制与回闰、 含闰日的天窗宽度、不对称窗口（C 术后 72 小时前 3 后
 *       4）、按月窗口的月末钳制、 零宽窗口、偏移 0，以及开启日 = min（提醒开始日，窗口开始日）的两个不对称方向；
 *   <li>occurrenceCases：持续循环第 N 次 = 基准 + 偏移 +（N − 1）× 周期（月 / 年按总量一次相加）—— <b>仅后端执行</b>，前端计算预览只算第 1
 *       次，循环推进在调度端（E14-T04）。
 * </ul>
 *
 * <p>全部虚构数据（示例）。业务日期按北京时间 LocalDate 计算（A-NFR-006）。
 */
class PlanDateSharedCasesTest {

    /** 共享用例文件（前后端各执行一遍的唯一事实来源）。 */
    private static JsonNode sharedCases() throws Exception {
        try (InputStream in =
                PlanDateSharedCasesTest.class.getResourceAsStream(
                        "/shared-cases/plan-date-cases.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static PlanDateCalculator.NodeTiming timing(JsonNode node) {
        JsonNode t = node.path("timing");
        return new PlanDateCalculator.NodeTiming(
                t.path("offsetValue").asInt(),
                t.path("offsetUnit").asText(),
                t.path("remindValue").asInt(),
                t.path("remindUnit").asText(),
                t.path("windowBeforeValue").asInt(),
                t.path("windowBeforeUnit").asText(),
                t.path("windowAfterValue").asInt(),
                t.path("windowAfterUnit").asText());
    }

    // ---------------------------------------------------------------- 单位运算（跨年 / 闰年 / 月末）

    /** 共享用例：addDateUnit 的期望结果与前端逐条一致（含负数跨年减法与世纪闰年规则）。 */
    @Test
    void executesAddDateUnitCasesIdenticallyWithFrontend() throws Exception {
        int count = 0;
        for (JsonNode item : sharedCases().path("addDateUnitCases")) {
            assertEquals(
                    LocalDate.parse(item.path("expected").asText()),
                    PlanDateCalculator.addDateUnit(
                            LocalDate.parse(item.path("base").asText()),
                            item.path("value").asInt(),
                            item.path("unit").asText()),
                    item.path("name").asText() + " → addDateUnit 与共享用例一致");
            count++;
        }
        assertEquals(19, count, "addDateUnitCases 用例数（增删用例须两端同步）");
    }

    // ---------------------------------------------------------------- 完整排期（窗口 / 开启日）

    /** 共享用例：computeNodeSchedule 五个结果字段（计划日期、提醒开始日、窗口开始 / 结束日、开启日）与前端逐条一致。 */
    @Test
    void executesScheduleCasesIdenticallyWithFrontend() throws Exception {
        int count = 0;
        for (JsonNode item : sharedCases().path("scheduleCases")) {
            JsonNode expected = item.path("expected");
            String name = item.path("name").asText();
            PlanDateCalculator.NodeSchedule schedule =
                    PlanDateCalculator.computeNodeSchedule(
                            timing(item), LocalDate.parse(item.path("basis").asText()));
            assertEquals(
                    LocalDate.parse(expected.path("planDate").asText()),
                    schedule.planDate(),
                    name + " → planDate");
            assertEquals(
                    LocalDate.parse(expected.path("remindStart").asText()),
                    schedule.remindStart(),
                    name + " → remindStart");
            assertEquals(
                    LocalDate.parse(expected.path("windowStart").asText()),
                    schedule.windowStart(),
                    name + " → windowStart");
            assertEquals(
                    LocalDate.parse(expected.path("windowEnd").asText()),
                    schedule.windowEnd(),
                    name + " → windowEnd");
            assertEquals(
                    LocalDate.parse(expected.path("openDate").asText()),
                    schedule.openDate(),
                    name + " → openDate = min（remindStart, windowStart）");
            count++;
        }
        assertEquals(16, count, "scheduleCases 用例数（增删用例须两端同步）");
    }

    // ---------------------------------------------------------------- 循环第 N 次（仅后端调度端）

    /**
     * 共享用例中仅后端执行的一组：持续循环第 N 次计划日期。前端计算预览（pages.md §8）只算第 1 次， 循环推进由每日调度（E14-T04）用
     * occurrencePlanDate 完成，故这组不参与前端一致性。
     */
    @Test
    void executesOccurrenceCasesForSchedulerSide() throws Exception {
        int count = 0;
        for (JsonNode item : sharedCases().path("occurrenceCases")) {
            assertEquals(
                    LocalDate.parse(item.path("expected").asText()),
                    PlanDateCalculator.occurrencePlanDate(
                            LocalDate.parse(item.path("basis").asText()),
                            timing(item),
                            item.path("cycleValue").asInt(),
                            item.path("cycleUnit").asText(),
                            item.path("occurrence").asInt()),
                    item.path("name").asText() + " → occurrencePlanDate 与共享用例一致");
            count++;
        }
        assertEquals(8, count, "occurrenceCases 用例数（仅后端；增删用例须同步文件头说明）");
    }
}
