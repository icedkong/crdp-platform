package com.crdp.followup.plan.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 计划节点日期计算库（E14-T03 / A-FU-002、A-NFR-006）：偏移、提醒开始日、窗口前后与任务开启日。
 *
 * <p>口径（tech-plan §8「日期计算」、A-FU-016 关键字段表）：
 *
 * <ul>
 *   <li>计划日期 = 基准日期 + 偏移；持续循环（CYCLE）子计划第 N 次 = 基准日期 + 偏移 + (N − 1) × 周期；
 *   <li>提醒开始日 = 计划日期 − 提醒提前量（0 = 当天）；窗口开始 / 结束日 = 计划日期 ∓ 窗口前 / 后；
 *   <li>任务开启日 = 提醒开始日与窗口开始日中较早的一天（A-FU-016、E14-T04 调度创建任务用）；
 *   <li>按月 / 年加遇目标月没有该日取当月最后一天（如 2 月 29 日起算的平年取 2 月 28 日，fu_plan_node V46 注释）； 偏移与周期同为月 / 年（或同为天 /
 *       周）单位时按总量<strong>一次性</strong>相加，避免逐次链加把 1-31 + 2 个月算成 3-28（应为 3-31）；混合单位先加偏移再整体加周期倍数；
 *   <li>业务日期按北京时间（Asia/Shanghai）用 {@link LocalDate} 计算（A-NFR-006），与前端
 *       packages/rules/plan-date（E14-T02 计算预览）共享 A 年度、B 3 个月、B 1 年、B 术后第 1/3/5 天验算用例。
 * </ul>
 *
 * <p>纯函数、不碰库；单位入参来自 {@link PlanNodeRule} 归一后的 fu_plan_node 列（大写 DAY/WEEK/MONTH/YEAR）， 不认识单位按编程错误抛
 * {@link IllegalArgumentException}（保存时已 40001 拦截，不该再走到这里）。
 */
public final class PlanDateCalculator {

    /** 业务时区：所有窗口与通知日期按北京时间计算（A-NFR-006）。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private PlanDateCalculator() {}

    /** 一个时刻所在的业务「今天」（北京时间），每日调度、逾期与窗口判断统一用它取当日。 */
    public static LocalDate businessToday(Instant now) {
        return LocalDate.ofInstant(now, BUSINESS_ZONE);
    }

    /** 基准日期 + 偏移（value 可为负，用于减提醒提前量 / 窗口前）。 DAY/WEEK 按天数（周 = 7 天）；MONTH/YEAR 按日历月并遇月末取当月最后一天。 */
    public static LocalDate addDateUnit(LocalDate base, int value, String unit) {
        switch (normalized(unit)) {
            case "DAY":
                return base.plusDays(value);
            case "WEEK":
                return base.plusWeeks(value);
            case "MONTH":
                return base.plusMonths(value);
            case "YEAR":
                return base.plusYears(value);
            default:
                throw new IllegalArgumentException("不认识的日期单位：" + unit);
        }
    }

    /** 节点的日期参数（fu_plan_node 的偏移 / 提醒 / 窗口列，PLAN_START 与 LAST_VISIT 基准的区别只是调用方传哪个日期）。 */
    public record NodeTiming(
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBeforeValue,
            String windowBeforeUnit,
            int windowAfterValue,
            String windowAfterUnit) {

        /** 从 {@link PlanNodeRule} 归一后的节点行取日期参数（列名同口径）。 */
        public static NodeTiming from(PlanNodeRule.NormalizedNode node) {
            return new NodeTiming(
                    node.offsetValue(),
                    node.offsetUnit(),
                    node.remindValue(),
                    node.remindUnit(),
                    node.windowBeforeValue(),
                    node.windowBeforeUnit(),
                    node.windowAfterValue(),
                    node.windowAfterUnit());
        }
    }

    /** 一次排期结果：计划日期、提醒开始日、窗口开始 / 结束日与任务开启日。 */
    public record NodeSchedule(
            LocalDate planDate,
            LocalDate remindStart,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate openDate) {}

    /** 按节点参数与基准日期（起算日期或上次实际随访日期）计算一次排期： 计划日期 = 基准 + 偏移，任务开启日 = 提醒开始日与窗口开始日中较早的一天。 */
    public static NodeSchedule computeNodeSchedule(NodeTiming timing, LocalDate basisDate) {
        LocalDate planDate = addDateUnit(basisDate, timing.offsetValue(), timing.offsetUnit());
        return scheduleFromPlanDate(timing, planDate);
    }

    /**
     * 持续循环（CYCLE）或固定次数（FIXED）子计划下节点第 occurrence 次（从 1 起）的计划日期： {@code 基准 + 偏移 + (occurrence − 1) ×
     * 周期}。偏移与周期同为月 / 年（或天 / 周）单位时按总量一次相加 （1-31 + 偏移 1 月 + 周期 1 月的第 2 次 = 3-31）；混合单位先加偏移再整体加周期倍数。
     */
    public static LocalDate occurrencePlanDate(
            LocalDate basisDate,
            NodeTiming timing,
            int cycleValue,
            String cycleUnit,
            int occurrence) {
        if (occurrence < 1) {
            throw new IllegalArgumentException("次数从 1 起计：" + occurrence);
        }
        String offsetUnit = normalized(timing.offsetUnit());
        String unit = normalized(cycleUnit);
        int cycles = occurrence - 1;
        if (cycles == 0) {
            return addDateUnit(basisDate, timing.offsetValue(), offsetUnit);
        }
        if (isCalendarUnit(offsetUnit) && isCalendarUnit(unit)) {
            // 月 / 年统一折成月数一次相加，保持月末口径不因链加漂移
            long totalMonths =
                    monthsOf(timing.offsetValue(), offsetUnit)
                            + (long) monthsOf(cycleValue, unit) * cycles;
            return basisDate.plusMonths(totalMonths);
        }
        if (!isCalendarUnit(offsetUnit) && !isCalendarUnit(unit)) {
            // 天 / 周统一折成天数一次相加
            long totalDays =
                    daysOf(timing.offsetValue(), offsetUnit)
                            + (long) daysOf(cycleValue, unit) * cycles;
            return basisDate.plusDays(totalDays);
        }
        // 混合单位：先偏移后周期，各自一次相加
        LocalDate shifted = addDateUnit(basisDate, timing.offsetValue(), offsetUnit);
        if (isCalendarUnit(unit)) {
            return shifted.plusMonths((long) monthsOf(cycleValue, unit) * cycles);
        }
        return shifted.plusDays((long) daysOf(cycleValue, unit) * cycles);
    }

    private static NodeSchedule scheduleFromPlanDate(NodeTiming timing, LocalDate planDate) {
        LocalDate remindStart =
                addDateUnit(planDate, -timing.remindValue(), normalized(timing.remindUnit()));
        LocalDate windowStart =
                addDateUnit(
                        planDate,
                        -timing.windowBeforeValue(),
                        normalized(timing.windowBeforeUnit()));
        LocalDate windowEnd =
                addDateUnit(
                        planDate, timing.windowAfterValue(), normalized(timing.windowAfterUnit()));
        LocalDate openDate = remindStart.isBefore(windowStart) ? remindStart : windowStart;
        return new NodeSchedule(planDate, remindStart, windowStart, windowEnd, openDate);
    }

    private static boolean isCalendarUnit(String unit) {
        return "MONTH".equals(unit) || "YEAR".equals(unit);
    }

    private static int monthsOf(int value, String unit) {
        return "YEAR".equals(unit) ? value * 12 : value;
    }

    private static int daysOf(int value, String unit) {
        return "WEEK".equals(unit) ? value * 7 : value;
    }

    private static String normalized(String unit) {
        if (unit == null) {
            throw new IllegalArgumentException("日期单位不能为空");
        }
        String trimmed = unit.trim().toUpperCase();
        switch (trimmed) {
            case "DAY":
            case "WEEK":
            case "MONTH":
            case "YEAR":
                return trimmed;
            default:
                throw new IllegalArgumentException("不认识的日期单位：" + unit);
        }
    }
}
