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
 * 任务排期纯函数（E14-T04 / A-FU-001、C-FU-004；E14-T05 入组即建；E14-T06 恢复补偿）：对一条在组入组，按其固定的计划版本算出「某逻辑日
 * 应存在的各节点各次任务」与「下次计划日期 / 开启日缓存」。不碰库。
 *
 * <p>三个入口、同一套次数推进口径（tech-plan §8「日期计算」）：
 *
 * <ul>
 *   <li>{@link #plan}（每日调度，E14-T04）：任务开启日 = 提醒开始日与窗口开始日中较早的一天 （{@link
 *       PlanDateCalculator#scheduleFromPlanDate}）；开启日不晚于逻辑日的次数即为应创建（已创建与否由 uk（入组 + 节点 + 第 N
 *       次）幂等判定）；入组前已结束窗口的次数不属每日调度——窗口结束日早于入组日的一律跳过（OQ-78③）；
 *   <li>{@link #planForEnrollment}（入组即建，E14-T05 / A 规格第 5 章、Q44）：建档入组当天同步创建—— 窗口在入组日前已结束的次数按节点
 *       passed_window_action 处理（BACKFILL 补建为「补录」任务、SKIP 或未配置跳过， 医院 C 的
 *       Q14）；窗口已开启未结束（窗口结束日不早于入组日且开启日不晚于入组日）的次数当天正常创建（NORMAL）； 其余次数不预生成，只写缓存（A-FU-001「未来节点不预先创建」）。
 *   <li>{@link #planForResume}（恢复补偿，E14-T06 / A 的 Q32、A-FU-008）：患者暂停恢复当天同步创建——
 *       暂停区间内本应开启且窗口已结束的次数补建为「暂停期间未执行」（PAUSED_SKIPPED，由医生选择补做或关闭，与平台停用规则一致）；窗口未结束的次数当天正常创建（NORMAL）；其余只写缓存。
 * </ul>
 *
 * <p>其余口径（两入口共用）：
 *
 * <ul>
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

    /** 创建方式：NORMAL 正常 / BACKFILL 补录（窗口在入组前已结束，fu_task.create_type，tech-plan §7）。 */
    public static final String CREATE_NORMAL = "NORMAL";

    public static final String CREATE_BACKFILL = "BACKFILL";

    /**
     * 暂停期间未执行（E14-T06 / A 的 Q32、A-FU-008）：恢复时，暂停区间内本应开启且窗口已结束的次数补建为
     * 「暂停期间未执行」，由医生选择补做或关闭（与平台停用规则一致）。状态侧建为 SKIPPED_SUSPENDED。
     */
    public static final String CREATE_PAUSED_SKIPPED = "PAUSED_SKIPPED";

    /**
     * 停用期间未执行（E14-T06 / PLT-TEN-017、tech-plan §8 停用补偿）：租户停用（含作业未执行）期间本应开启且窗口
     * 已结束的次数，恢复后补建为「停用期间未执行」，由人工选择补做或关闭。由服务层 （FollowupTaskCreationService）在每日调度补跑时按「窗口已结束 +
     * 不属该入组暂停区间」判定， 排期纯函数不感知作业执行历史。
     */
    public static final String CREATE_DISABLED_SKIPPED = "DISABLED_SKIPPED";

    /**
     * 临时任务（E15-T06 / A-FU-013「在子计划任务列表中临时添加任务，不占用年度任务」）：用户在任务列表或时间轴 手工添加（如再住院后加访），occurrence
     * 用负数计数器（-1、-2、…）避开常规 1..N 序列—— 排期纯函数不参与，由服务层（FollowupTaskTempService）创建。
     */
    public static final String CREATE_TEMP = "TEMP";

    /** 入组前已过窗口策略（fu_plan_node.passed_window_action）：BACKFILL 补建补录任务（A、B，Q44）。 */
    public static final String PASSED_WINDOW_BACKFILL = "BACKFILL";

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
            String passedWindowAction,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBeforeValue,
            String windowBeforeUnit,
            int windowAfterValue,
            String windowAfterUnit) {}

    /** 一个排期结果：应创建（或已由唯一键判存在）的任务与其完整日期组（createType 见本类常量）。 */
    public record PlannedTask(
            long nodeId,
            int occurrence,
            LocalDate planDate,
            LocalDate remindStart,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate openDate,
            String createType) {}

    /** 一次入组的排期结果：due 按开启日、计划日期、节点 id 稳定排序；next 为空 = 该计划已排完（缓存清空）。 */
    public record PlanResult(List<PlannedTask> due, PlannedTask next) {}

    /**
     * 每日调度的排期（E14-T04）：只负责入组后的按需创建。
     *
     * @param nodes 入组固定计划版本的全部节点（SUBPLAN + NODE 混排，顺序不限）
     * @param enrollmentMode 入组模式（NORMAL / ERI；节点启用条件过滤用）
     * @param basisDate 起算日期（计划的起算字段解析值；LAST_VISIT 节点第 1 次同样回落它）
     * @param logicalDate 逻辑执行日（补跑日）
     * @param enrolledOn 入组日（窗口在此前已结束的次数留给 E14-T05 的节点策略）
     * @param planEnd 计划管理周期边界（cycle_type=YEARS = 起算 + N 年；ONGOING 传 null）
     */
    public static PlanResult plan(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd) {
        return schedule(
                nodes, enrollmentMode, basisDate, logicalDate, enrolledOn, planEnd, false, null);
    }

    /**
     * 入组当天的排期（E14-T05 / Q44，验收「已结束窗口照常建任务并标补录」）： logicalDate 即入组日——窗口已结束的次数按节点 passed_window_action
     * 补建（BACKFILL）或跳过（SKIP / 未配置）； 窗口已开启未结束的次数当天正常创建（NORMAL）；其余只进缓存。
     */
    public static PlanResult planForEnrollment(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate planEnd) {
        return schedule(nodes, enrollmentMode, basisDate, logicalDate, null, planEnd, true, null);
    }

    /**
     * 恢复当天的排期（E14-T06 / A 的 Q32、A-FU-008「恢复后从下一个窗口起继续按需创建任务」）： 暂停区间 [pausedFrom, logicalDate]
     * 内本应开启（开启日不晚于逻辑日且不早于暂停起始日）且窗口已结束的次数补建为 「暂停期间未执行」（PAUSED_SKIPPED，由医生选择补做或关闭）；
     * 窗口已开启未结束的次数当天正常创建（NORMAL，照常发任务开启事件）； 其余次数不预生成，只进缓存。
     * 开启日早于暂停起始日的次数不属暂停补偿（当时应已由每日调度创建，缺建由调度口径的停用补偿兜底）。
     */
    public static PlanResult planForResume(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate pausedFrom,
            LocalDate planEnd) {
        return schedule(
                nodes, enrollmentMode, basisDate, logicalDate, null, planEnd, false, pausedFrom);
    }

    /**
     * 单个已存在次数的重算排期（E14-T07 / A-FU-011 起算日期变更重算）：按节点参数与新起算日期， 算「该节点第 occurrence
     * 次」的完整日期组（计划日期、提醒开始日、窗口与开启日）， 与 {@link #plan} 同一套次数推进口径（FIXED 未存周期回落节点偏移，OQ-78）； 不做开启日 /
     * 窗口判定——重算侧自行决定重排还是撤回。
     *
     * @param nodes 入组固定计划版本的全部节点（SUBPLAN + NODE 混排）
     * @param nodeId 任务行的节点 id（fu_task.node_id）
     * @param occurrence 任务行的次数（fu_task.occurrence）
     * @param basisDate 新起算日期（fu_enrollment.start_date 的新值）
     * @return 该次日期组；节点缺失 / 不是 NODE / 缺子计划（配置防御）返回 null
     */
    public static PlanDateCalculator.NodeSchedule occurrenceScheduleOf(
            List<NodeDef> nodes, long nodeId, int occurrence, LocalDate basisDate) {
        NodeDef target = null;
        Map<Long, NodeDef> subplans = new LinkedHashMap<>();
        for (NodeDef node : nodes) {
            if ("SUBPLAN".equals(node.nodeType())) {
                subplans.put(node.id(), node);
            }
            if (node.id() == nodeId) {
                target = node;
            }
        }
        if (target == null || !"NODE".equals(target.nodeType())) {
            return null;
        }
        NodeDef subplan = subplans.get(target.parentId());
        if (subplan == null) {
            return null;
        }
        // FIXED 固定次数排完即无该次（E23-T17 B 验收发现）：次数外的 occurrence 不存在——否则周期回落
        // 节点偏移会凭空算出「下一次」开启日，把 FIXED 子计划（如医院 B 术后早期随访 ×1）的补录任务
        // 提交误拦在直接录入期之外（§14「可直接录入」；CYCLE 持续循环不设界，不受影响）
        if ("FIXED".equals(subplan.repeatMode())
                && subplan.repeatCount() != null
                && occurrence > subplan.repeatCount()) {
            return null;
        }
        int cycleValue =
                subplan.cycleValue() != null
                        ? subplan.cycleValue()
                        : Math.abs(target.offsetValue());
        String cycleUnit =
                subplan.cycleUnit() != null
                        ? subplan.cycleUnit().toUpperCase()
                        : target.offsetUnit().toUpperCase();
        PlanDateCalculator.NodeTiming timing = timingOf(target);
        LocalDate planDate =
                PlanDateCalculator.occurrencePlanDate(
                        basisDate, timing, cycleValue, cycleUnit, occurrence);
        return PlanDateCalculator.scheduleFromPlanDate(timing, planDate);
    }

    /** 节点行的日期参数（fu_plan_node 偏移 / 提醒 / 窗口列 → NodeTiming，单位归一大写）。 */
    private static PlanDateCalculator.NodeTiming timingOf(NodeDef node) {
        return new PlanDateCalculator.NodeTiming(
                node.offsetValue(),
                node.offsetUnit().toUpperCase(),
                node.remindValue(),
                node.remindUnit().toUpperCase(),
                node.windowBeforeValue(),
                node.windowBeforeUnit().toUpperCase(),
                node.windowAfterValue(),
                node.windowAfterUnit().toUpperCase());
    }

    /** 入口共用的排期主干（atEnrollment = true 按入组即建口径；pausedFrom 非空按恢复补偿口径）。 */
    private static PlanResult schedule(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd,
            boolean atEnrollment,
            LocalDate pausedFrom) {
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
                    planNode(
                            node,
                            subplan,
                            basisDate,
                            logicalDate,
                            enrolledOn,
                            planEnd,
                            atEnrollment,
                            pausedFrom,
                            due);
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
            boolean atEnrollment,
            LocalDate pausedFrom,
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
        PlanDateCalculator.NodeTiming timing = timingOf(node);
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
                return task(node.id(), occurrence, schedule, CREATE_NORMAL);
            }
            String createType =
                    classify(node, schedule, logicalDate, enrolledOn, atEnrollment, pausedFrom);
            if (createType != null) {
                due.add(task(node.id(), occurrence, schedule, createType));
            }
            if (rolling) {
                return null; // LAST_VISIT 只排第 1 次：后续按实际随访日期滚动（fu_record 随 E15，OQ-78）
            }
        }
        return null;
    }

    /**
     * 一次已开启（开启日不晚于逻辑日）的次数是否应建、以何种创建方式：
     *
     * <ul>
     *   <li>入组即建（E14-T05）：窗口在入组日前已结束（windowEnd 早于逻辑日）→ 节点策略 BACKFILL 则补建为补录任务、 SKIP / 未配置跳过（医院 C 的
     *       Q14）；窗口未结束 → 当期任务正常创建（NORMAL）；
     *   <li>恢复补偿（E14-T06）：开启日在暂停区间内且窗口已结束 → 补建为「暂停期间未执行」（PAUSED_SKIPPED）； 窗口未结束 → 当期任务正常创建
     *       （NORMAL）；开启日早于暂停起始日的次数不属暂停补偿（当时应已由每日调度创建）；
     *   <li>每日调度（E14-T04）：窗口在入组日前已结束的一律不建（OQ-78③）；其余 NORMAL。
     * </ul>
     */
    private static String classify(
            NodeDef node,
            PlanDateCalculator.NodeSchedule schedule,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            boolean atEnrollment,
            LocalDate pausedFrom) {
        if (pausedFrom != null) {
            // 恢复补偿口径（E14-T06）：只接管暂停区间内本应开启的次数
            if (!schedule.openDate().isBefore(pausedFrom)) {
                return schedule.windowEnd().isBefore(logicalDate)
                        ? CREATE_PAUSED_SKIPPED
                        : CREATE_NORMAL;
            }
            return CREATE_NORMAL; // 早于暂停起始：按调度常规口径（窗口已结束时由服务层停用补偿兜底）
        }
        boolean endedBeforeEnrollmentDay =
                atEnrollment
                        ? schedule.windowEnd().isBefore(logicalDate)
                        : schedule.windowEnd().isBefore(enrolledOn);
        if (!endedBeforeEnrollmentDay) {
            return CREATE_NORMAL; // 窗口未结束：当天正常创建（入组即建）/ 每日调度常规口径
        }
        if (atEnrollment && PASSED_WINDOW_BACKFILL.equalsIgnoreCase(node.passedWindowAction())) {
            return CREATE_BACKFILL; // 已结束窗口照常建任务并标补录（Q44；SKIP / 未配置 → 不建）
        }
        return null;
    }

    private static PlannedTask task(
            long nodeId,
            int occurrence,
            PlanDateCalculator.NodeSchedule schedule,
            String createType) {
        return new PlannedTask(
                nodeId,
                occurrence,
                schedule.planDate(),
                schedule.remindStart(),
                schedule.windowStart(),
                schedule.windowEnd(),
                schedule.openDate(),
                createType);
    }
}
