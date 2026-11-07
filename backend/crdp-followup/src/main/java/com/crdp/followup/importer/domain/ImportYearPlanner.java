package com.crdp.followup.importer.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * E18-T05：历史随访记录的年度规划纯函数（tech-plan §12⑦「生成随访数据」、A-PAT-018 流程第 6 步 / 宽表流程第 6 步）。
 *
 * <p>输入是某患者某节点各次（第 N 年）的排期（计划日期 / 窗口）与「有数据的年度」集合（宽表非全空组 = 第 N 年、长表随访行 的年度列），输出每个需要生成记录的年度与其口径：
 *
 * <ul>
 *   <li><b>有数据的年度 → 历史导入（HIST_IMPORT）</b>：不受 ±7 天窗口限制，按实际随访日期记录并标为「历史导入」 （A 规格 §4「宽表导入的随访记录不受 ±7
 *       天窗口限制」）；
 *   <li><b>窗口已结束且无数据的年度 → 历史未随访（HIST_MISSED）</b>（Q06）：不计入逾期与通知、不计入已随访与完整度， 可补录；
 *   <li><b>窗口未结束且无数据的年度不生成任何记录</b>：当前窗口由每日定时任务创建当年任务（A-FU-001），未来年度只在
 *       时间轴上显示下次计划日期——历史导入不预建任务（E14-T04 已定：导入按自身规则生成历史记录，不建补录任务）。
 * </ul>
 *
 * <p>有数据但排期缺失的年度（起算日期不可解析等）仍生成记录——年度归属按导入年度（随访年度 N），窗口锚点留空； 只对
 * 「无数据年度」要求窗口已结束（否则无从判定「历史未随访」）。不碰库、不依赖其他子域。
 */
public final class ImportYearPlanner {

    private ImportYearPlanner() {}

    /** 一个年度的排期（第 N 次的计划日期与窗口；由计划节点参数按起算日期算出，E14-T03 同一套口径）。 */
    public record YearSchedule(
            int occurrence, LocalDate planDate, LocalDate windowStart, LocalDate windowEnd) {}

    /** 一个年度的生成口径：hasData = 历史导入（SUBMITTED），否则历史未随访（DRAFT 待补录，Q06）。 */
    public record YearPlan(
            int occurrence,
            boolean hasData,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd) {}

    /** 排期次数防御上限（与 TaskCreationPlanner.MAX_OCCURRENCES 同量级：正常年度计划远达不到）。 */
    private static final int MAX_SCHEDULES = 100_000;

    /**
     * 规划一个患者的年度记录清单。
     *
     * @param schedules 该患者各年度的排期（按 occurrence 升序；可为空——起算日期不可解析时）
     * @param yearsWithData 导入数据里有值的年度（宽表非全空组序号 / 长表随访年度列）
     * @param today 逻辑日（判定「窗口已结束」，北京时间）
     */
    public static List<YearPlan> plan(
            List<YearSchedule> schedules, Set<Integer> yearsWithData, LocalDate today) {
        List<YearPlan> result = new ArrayList<>();
        for (YearSchedule schedule : schedules) {
            if (schedule.occurrence() < 1 || result.size() >= MAX_SCHEDULES) {
                continue; // 防御：次数从 1 起 + 上限
            }
            boolean hasData = yearsWithData.contains(schedule.occurrence());
            boolean windowEnded =
                    schedule.windowEnd() != null && schedule.windowEnd().isBefore(today);
            if (hasData || windowEnded) {
                result.add(
                        new YearPlan(
                                schedule.occurrence(),
                                hasData,
                                schedule.planDate(),
                                schedule.windowStart(),
                                schedule.windowEnd()));
            }
        }
        return result;
    }

    /**
     * 有数据但排期覆盖不到的年度（文件里第 N 年有值、排期算不到 N 次——如起算日期缺失或文件年度数超过计划次数防御）： 仍按年度生成「历史导入」记录（年度归属以随访年度为准，A 规格
     * §5「年度归属以子计划为准」的无排期回落），窗口锚点留空。
     */
    public static List<Integer> unscheduledDataYears(
            List<YearSchedule> schedules, Set<Integer> yearsWithData) {
        Set<Integer> scheduled = new java.util.HashSet<>();
        schedules.forEach(schedule -> scheduled.add(schedule.occurrence()));
        return yearsWithData.stream()
                .filter(year -> year != null && year >= 1 && !scheduled.contains(year))
                .sorted()
                .toList();
    }
}
