package com.crdp.followup.task.domain;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每日调度的任务排期纯函数（E14-T04 / A-FU-001、C-FU-004、A-NFR-006）：对一条在组入组，按其固定的计划版本
 * 算出「逻辑日当天应存在的各节点各次任务」与「下次计划日期 / 开启日缓存」。不碰库。
 *
 * <p>口径（tech-plan §8「每日调度」流程图与 A-FU-001）：
 *
 * <ul>
 *   <li>任务开启日 = 提醒开始日与窗口开始日中较早的一天（{@link PlanDateCalculator#scheduleFromPlanDate}）； 开启日
 *       不晚于逻辑日的次数即为应创建（已创建与否由 uk（入组 + 节点 + 第 N 次）幂等判定，重复执行 / 补跑不重复建）；
 *   <li>未来节点不预生成：只把开启日在逻辑日之后的最早一次写入缓存列（fu_enrollment.next_plan_date /
 *       next_open_date），「待开始」是时间轴上的计算值，不落任务（A-FU-003）；
 *   <li>入组前已结束窗口的次数不属每日调度——留给 E14-T05 按节点 passed_window_action（补建补录 BACKFILL / 跳过
 *       SKIP）处理；窗口在入组后结束（如停机期间错过）照常创建；
 *   <li>计划管理周期 cycle_type=YEARS：计划日期超出「起算 + N 年」边界的次数不排（ONGOING 不设界）；
 *   <li>基准 LAST_VISIT（C 常规随访滚动）只排第 1 次（基准回落起算日期）：第 2 次起按上次实际随访日期滚动， fu_record 随 E15 落地后接入（OQ-78
 *       暂行）；
 *   <li>FIXED 子计划未存周期（配置面只收次数）：周期回落节点偏移，第 N 次 = 基准 + N × 偏移（OQ-78 暂行）； 存了周期 （cycle_value /
 *       cycle_unit 非空）则按 第 N 次 = 基准 + 偏移 +（N − 1）× 周期；
 *   <li>启用条件（C）：节点 enroll_mode 非空时须与入组模式一致（ERI 期循环节点只在 ERI 模式生效）。
 * </ul>
 *
 * <p>防御上限：单节点最多排 100 000 次（正常计划远达不到：A 长期年度 10 年也只有 11 次）， 防止退化配置（如 FIXED 周期回落 0 天 × 超大次数）把调度拖死。
 */
public final class TaskCreationPlanner {

    /** 单节点排期次数防御上限（见类注释）。 */
    private static final int MAX_OCCURRENCES = 100_000;

    private TaskCreationPlanner() {}

    /** 排期输入的节点（fu_plan_node 行的调度相关列；SUBPLAN 行携带重复方式，NODE 行携带日期参数与启用条件）。 */
    public record NodeDef(
            long id,
            Long parentId,
            String nodeType,
            String repeatMode,
            Integer repeatCount,
            Integer cycleValue,
            String cycleUnit,
            String basis,
            String enrollMode,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBeforeValue,
            String windowBeforeUnit,
            int windowAfterValue,
            String windowAfterUnit) {}

    /** 一个排期结果：应创建（或已由唯一键判存在）的任务与其完整日期组。 */
    public record PlannedTask(
            long nodeId,
            int occurrence,
            LocalDate planDate,
            LocalDate remindStart,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate openDate) {}

    /** 一次入组的排期结果：due 按开启日、计划日期、节点 id 稳定排序；next 为空 = 该计划已排完（缓存清空）。 */
    public record PlanResult(List<PlannedTask> due, PlannedTask next) {}

    /**
     * 对一条入组排期。
     *
     * @param nodes 入组固定计划版本的全部节点（SUBPLAN + NODE 混排，顺序不限）
     * @param enrollmentMode 入组模式（NORMAL / ERI；节点启用条件过滤用）
     * @param basisDate 起算日期（计划的起算字段解析值；LAST_VISIT 节点第 1 次同样回落它）
     * @param logicalDate 逻辑执行日（补跑日）
     * @param enrolledOn 入组日（窗口在此前已结束的次数留给 E14-T05）
     * @param planEnd 计划管理周期边界（cycle_type=YEARS = 起算 + N 年；ONGOING 传 null）
     */
    public static PlanResult plan(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd) {
        Map<Long, NodeDef> subplans = new LinkedHashMap<>();
        for (NodeDef node : nodes) {
            if ("SUBPLAN".equals(node.nodeType())) {
                subplans.put(node.id(), node);
            }
        }
        List<PlannedTask> due = new ArrayList<>();
        PlannedTask next = null;
        for (NodeDef node : nodes) {
            if (!"NODE".equals(node.nodeType())) {
                continue;
            }
            if (node.enrollMode() != null && !node.enrollMode().equalsIgnoreCase(enrollmentMode)) {
                continue; // C：ERI 期循环节点只在 ERI 模式生效，常规节点同理不进 ERI 入组
            }
            NodeDef subplan = subplans.get(node.parentId());
            if (subplan == null) {
                continue; // 已发布计划经 PlanNodeRule 前置不会缺父行，防御跳过
            }
            PlannedTask nodeNext =
                    planNode(node, subplan, basisDate, logicalDate, enrolledOn, planEnd, due);
            if (nodeNext != null
                    && (next == null || nodeNext.openDate().isBefore(next.openDate()))) {
                next = nodeNext; // 多节点取最早开启的下一次（A-FU-016 偏移不同）
            }
        }
        due.sort(
                Comparator.comparing(PlannedTask::openDate)
                        .thenComparing(PlannedTask::planDate)
                        .thenComparing(PlannedTask::nodeId));
        return new PlanResult(List.copyOf(due), next);
    }

    /** 排一个节点：应创建的次数追加进 due，返回该节点开启日在逻辑日之后的最早一次（无则 null）。 */
    private static PlannedTask planNode(
            NodeDef node,
            NodeDef subplan,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd,
            List<PlannedTask> due) {
        boolean rolling = "LAST_VISIT".equals(node.basis());
        String repeatMode = Objects.requireNonNullElse(subplan.repeatMode(), "CYCLE").toUpperCase();
        boolean fixed = "FIXED".equals(repeatMode);
        int repeatCount = fixed && subplan.repeatCount() != null ? subplan.repeatCount() : 0;
        // FIXED 未存周期（配置面只收次数）→ 周期回落节点偏移（第 N 次 = 基准 + N × 偏移，OQ-78 暂行）
        int cycleValue =
                subplan.cycleValue() != null ? subplan.cycleValue() : Math.abs(node.offsetValue());
        String cycleUnit =
                subplan.cycleUnit() != null
                        ? subplan.cycleUnit().toUpperCase()
                        : node.offsetUnit().toUpperCase();
        PlanDateCalculator.NodeTiming timing =
                new PlanDateCalculator.NodeTiming(
                        node.offsetValue(),
                        node.offsetUnit().toUpperCase(),
                        node.remindValue(),
                        node.remindUnit().toUpperCase(),
                        node.windowBeforeValue(),
                        node.windowBeforeUnit().toUpperCase(),
                        node.windowAfterValue(),
                        node.windowAfterUnit().toUpperCase());
        for (int occurrence = 1; occurrence <= MAX_OCCURRENCES; occurrence++) {
            if (fixed && occurrence > repeatCount) {
                return null; // 固定次数排完：无下次（该计划「随访结束」由展示层派生）
            }
            LocalDate planDate =
                    PlanDateCalculator.occurrencePlanDate(
                            basisDate, timing, cycleValue, cycleUnit, occurrence);
            if (planEnd != null && planDate.isAfter(planEnd)) {
                return null; // 管理周期（cycle_type=YEARS）边界外不排
            }
            PlanDateCalculator.NodeSchedule schedule =
                    PlanDateCalculator.scheduleFromPlanDate(timing, planDate);
            if (schedule.openDate().isAfter(logicalDate)) {
                return new PlannedTask(
                        node.id(),
                        occurrence,
                        schedule.planDate(),
                        schedule.remindStart(),
                        schedule.windowStart(),
                        schedule.windowEnd(),
                        schedule.openDate());
            }
            if (!schedule.windowEnd().isBefore(enrolledOn)) {
                due.add(
                        new PlannedTask(
                                node.id(),
                                occurrence,
                                schedule.planDate(),
                                schedule.remindStart(),
                                schedule.windowStart(),
                                schedule.windowEnd(),
                                schedule.openDate()));
            }
            if (rolling) {
                return null; // LAST_VISIT 只排第 1 次：后续按实际随访日期滚动（fu_record 随 E15，OQ-78）
            }
        }
        return null;
    }
}
