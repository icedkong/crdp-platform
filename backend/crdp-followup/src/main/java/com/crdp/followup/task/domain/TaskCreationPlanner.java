package com.crdp.followup.task.domain;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 *   <li><b>滚动循环节点（basis = LAST_VISIT，E21-T07 / C-FU-006）</b>：一次只排「下一次」——第 N 次计划日期 = max（上次实际随访日期
 *       {@link LoopBasis#lastVisitDate()}，已排最新一次的计划日期）+ 节点偏移（C 规格 §5 节点表「上次实际随访日期 + 1 / 6 / 12 个月」；
 *       错过未执行的次数同样按周期前移，循环不因缺访停摆，下次次数 = 已排最大次数 + 1）； 无任何已提交随访（快照与已排均空）时首次回落起算日期，
 *       且该日期与固定节点计划日重合时抑制首次（OQ-139②「首次抑制」——普通 / ICD 计划常规随访首次与「术后 1 年 / 6 个月」重合，由固定节点承担）；
 *   <li>FIXED 子计划未存周期（配置面只收次数）：周期回落节点偏移，第 N 次 = 基准 + N × 偏移（OQ-78 暂行）； 存了周期 （cycle_value /
 *       cycle_unit 非空）则按 第 N 次 = 基准 + 偏移 +（N − 1）× 周期；
 *   <li>启用条件（C / V91）：ERI 模式下仅 enroll_mode = ERI 的循环节点接管排期（C-FU-006「后续节点切换为 ERI
 *       期随访」、OQ-138⑦——常规与固定节点不再创建新任务）；常规（NORMAL）模式排非 ERI 节点（含未配置，A / B 计划不受影响）。
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
     * 滚动循环（basis = LAST_VISIT）节点的排期输入（E21-T07 / C-FU-006）：{@code lastVisitDate} = 入组的上次实际随访日期快照
     * （fu_enrollment.last_visit_date，记录提交时单调推进）， {@code nodeStates} = 各滚动节点已排任务的推进状态（下次次数 = 已排最大次数
     * + 1、最新一次计划日期）。 无滚动节点的计划（A / B）传 {@link #none()}——滚动分支退回「首次回落起算日期」的旧行为。
     */
    public record LoopBasis(LocalDate lastVisitDate, Map<Long, NodeLoopState> nodeStates) {

        /** 单个滚动节点的推进状态（occurrence 从 1 起；latestPlanDate 为已排最新一次的计划日期，未排过为 null）。 */
        public record NodeLoopState(int nextOccurrence, LocalDate latestPlanDate) {}

        /** 无滚动输入（A / B 计划；或调用方尚未接入循环排期）。 */
        public static LoopBasis none() {
            return new LoopBasis(null, Map.of());
        }
    }

    /** 滚动循环的排期基准：max（上次实际随访日期，已排最新一次计划日期）——错过未执行的次数同样按周期前移，循环不停摆（OQ-140①）。 */
    static LocalDate rollingBase(LocalDate lastVisitDate, LocalDate latestPlanDate) {
        if (latestPlanDate == null) {
            return lastVisitDate;
        }
        if (lastVisitDate == null || latestPlanDate.isAfter(lastVisitDate)) {
            return latestPlanDate;
        }
        return lastVisitDate;
    }

    /**
     * 滚动（LAST_VISIT）节点的下一次排期（E21-T07）：基准 = max（上次实际随访日期，已排最新一次计划日期）+ 节点偏移。
     * 供直接录入期判定（FollowupTaskStatusService）等单点消费；节点缺失 / 非滚动节点返回 null。
     */
    public static PlanDateCalculator.NodeSchedule rollingNextSchedule(
            List<NodeDef> nodes, long nodeId, LocalDate lastVisitDate, LocalDate latestPlanDate) {
        NodeDef target = null;
        for (NodeDef node : nodes) {
            if (node.id() == nodeId) {
                target = node;
            }
        }
        if (target == null
                || !"NODE".equals(target.nodeType())
                || !"LAST_VISIT".equalsIgnoreCase(target.basis())) {
            return null;
        }
        LocalDate base = rollingBase(lastVisitDate, latestPlanDate);
        if (base == null) {
            return null; // 无随访快照也无已排次数：回落起算日期属完整排期口径，单点消费不适用
        }
        return PlanDateCalculator.computeNodeSchedule(timingOf(target), base);
    }

    /**
     * 每日调度的排期（E14-T04）：只负责入组后的按需创建。滚动循环输入取 {@link LoopBasis#none()}（无滚动节点的计划； 滚动节点退回「首次回落起算日期」）。
     *
     * @param nodes 入组固定计划版本的全部节点（SUBPLAN + NODE 混排，顺序不限）
     * @param enrollmentMode 入组模式（NORMAL / ERI；节点启用条件过滤用）
     * @param basisDate 起算日期（计划的起算字段解析值；LAST_VISIT 节点无随访快照时回落它）
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
        return plan(
                nodes,
                enrollmentMode,
                basisDate,
                logicalDate,
                enrolledOn,
                planEnd,
                LoopBasis.none());
    }

    /** 每日调度的排期（E21-T07 起带滚动循环输入；其余参数同上）。 */
    public static PlanResult plan(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd,
            LoopBasis loopBasis) {
        return schedule(
                nodes,
                enrollmentMode,
                basisDate,
                logicalDate,
                enrolledOn,
                planEnd,
                false,
                null,
                loopBasis);
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
        return planForEnrollment(
                nodes, enrollmentMode, basisDate, logicalDate, planEnd, LoopBasis.none());
    }

    /** 入组当天的排期（E21-T07 起带滚动循环输入；其余参数同上）。 */
    public static PlanResult planForEnrollment(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate planEnd,
            LoopBasis loopBasis) {
        return schedule(
                nodes,
                enrollmentMode,
                basisDate,
                logicalDate,
                null,
                planEnd,
                true,
                null,
                loopBasis);
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
        return planForResume(
                nodes,
                enrollmentMode,
                basisDate,
                logicalDate,
                pausedFrom,
                planEnd,
                LoopBasis.none());
    }

    /** 恢复当天的排期（E21-T07 起带滚动循环输入；其余参数同上）。 */
    public static PlanResult planForResume(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate pausedFrom,
            LocalDate planEnd,
            LoopBasis loopBasis) {
        return schedule(
                nodes,
                enrollmentMode,
                basisDate,
                logicalDate,
                null,
                planEnd,
                false,
                pausedFrom,
                loopBasis);
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
     * @return 该次日期组；节点缺失 / 不是 NODE / 缺子计划（配置防御）/ 滚动节点（E21-T07：次数日期由随访滚动派生，
     *     不随起算日期重算——起算变更重算对滚动任务不动，日期保持派生时的值）返回 null
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
        if (target == null
                || !"NODE".equals(target.nodeType())
                || "LAST_VISIT".equalsIgnoreCase(target.basis())) {
            return null; // 滚动节点：次数日期派生自随访（E21-T07），静态次数公式不适用
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

    /** 入口共用的排期主干（atEnrollment = true 按入组即建口径；pausedFrom 非空按恢复补偿口径；loopBasis = 滚动循环输入，E21-T07）。 */
    private static PlanResult schedule(
            List<NodeDef> nodes,
            String enrollmentMode,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd,
            boolean atEnrollment,
            LocalDate pausedFrom,
            LoopBasis loopBasis) {
        Map<Long, NodeDef> subplans = new LinkedHashMap<>();
        for (NodeDef node : nodes) {
            if ("SUBPLAN".equals(node.nodeType())) {
                subplans.put(node.id(), node);
            }
        }
        // 首次抑制的比对集（OQ-139②）：当前模式下生效的非滚动节点的计划日期（FIXED 取全部次数，其余取第 1 次——A / B 年度 CYCLE 节点无穷次，只比对首次已覆盖
        // C 预置形态）
        Set<LocalDate> fixedPlanDates = new HashSet<>();
        for (NodeDef node : nodes) {
            if (!"NODE".equals(node.nodeType())
                    || !activeInMode(node.enrollMode(), enrollmentMode)
                    || "LAST_VISIT".equalsIgnoreCase(node.basis())) {
                continue;
            }
            NodeDef subplan = subplans.get(node.parentId());
            if (subplan == null) {
                continue;
            }
            String repeatMode =
                    Objects.requireNonNullElse(subplan.repeatMode(), "CYCLE").toUpperCase();
            int count =
                    "FIXED".equals(repeatMode) && subplan.repeatCount() != null
                            ? subplan.repeatCount()
                            : 1;
            int cycleValue =
                    subplan.cycleValue() != null
                            ? subplan.cycleValue()
                            : Math.abs(node.offsetValue());
            String cycleUnit =
                    subplan.cycleUnit() != null
                            ? subplan.cycleUnit().toUpperCase()
                            : node.offsetUnit().toUpperCase();
            PlanDateCalculator.NodeTiming timing = timingOf(node);
            for (int occurrence = 1; occurrence <= count; occurrence++) {
                fixedPlanDates.add(
                        PlanDateCalculator.occurrencePlanDate(
                                basisDate, timing, cycleValue, cycleUnit, occurrence));
            }
        }
        List<PlannedTask> due = new ArrayList<>();
        PlannedTask next = null;
        for (NodeDef node : nodes) {
            if (!"NODE".equals(node.nodeType())
                    || !activeInMode(node.enrollMode(), enrollmentMode)) {
                continue; // C / V91：ERI 模式下仅 ERI 循环节点接管；常规模式排非 ERI 节点（含未配置）
            }
            NodeDef subplan = subplans.get(node.parentId());
            if (subplan == null) {
                continue; // 已发布计划经 PlanNodeRule 前置不会缺父行，防御跳过
            }
            PlannedTask nodeNext =
                    "LAST_VISIT".equalsIgnoreCase(node.basis())
                            ? planRollingNode(
                                    node,
                                    basisDate,
                                    logicalDate,
                                    enrolledOn,
                                    planEnd,
                                    atEnrollment,
                                    pausedFrom,
                                    loopBasis,
                                    fixedPlanDates,
                                    due)
                            : planNode(
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

    /**
     * 节点启用条件与入组模式的匹配（C / V91，E21-T07 起）：ERI 模式下仅 enroll_mode = ERI 的循环节点接管排期 （C-FU-006「后续节点切换为 ERI
     * 期随访循环」、OQ-138⑦——固定与常规节点不再创建新任务，未完成的原任务由切换终止）； 常规（NORMAL / 未配置）模式排非 ERI 节点（含 enroll_mode 为空的 A
     * / B 计划节点）。
     */
    static boolean activeInMode(String nodeEnrollMode, String enrollmentMode) {
        if ("ERI".equalsIgnoreCase(enrollmentMode)) {
            return "ERI".equalsIgnoreCase(nodeEnrollMode);
        }
        return nodeEnrollMode == null || !"ERI".equalsIgnoreCase(nodeEnrollMode);
    }

    /**
     * 排一个滚动（LAST_VISIT）循环节点（E21-T07 / C-FU-006）：一次只排「下一次」。
     *
     * <ul>
     *   <li>基准 = max（上次实际随访日期快照，已排最新一次计划日期）+ 节点偏移（C 节点表「上次实际随访日期 + 1 / 6 / 12 个月」）——
     *       错过未执行的次数同样按周期前移（不因缺访停摆），早于计划日期的随访不把下次排到已排次数之前（不重复同日期）；
     *   <li>次数 = 已排最大次数 + 1（uk 幂等）；开启日未到作为 next 缓存，已到按 classify 归类（NORMAL / 补录 / 期间未执行）；
     *   <li>无随访快照且未排过（首次）：回落起算日期 + 偏移，该日期与固定节点计划日重合时抑制（OQ-139②——由固定节点承担，如 普通计划常规首次 = 植入 + 12
     *       个月与「术后1年」重合）。
     * </ul>
     */
    private static PlannedTask planRollingNode(
            NodeDef node,
            LocalDate basisDate,
            LocalDate logicalDate,
            LocalDate enrolledOn,
            LocalDate planEnd,
            boolean atEnrollment,
            LocalDate pausedFrom,
            LoopBasis loopBasis,
            Set<LocalDate> fixedPlanDates,
            List<PlannedTask> due) {
        LoopBasis.NodeLoopState state = loopBasis.nodeStates().get(node.id());
        int occurrence = state != null ? state.nextOccurrence() : 1;
        LocalDate base =
                rollingBase(
                        loopBasis.lastVisitDate(), state != null ? state.latestPlanDate() : null);
        boolean fallbackToBasis = base == null;
        if (fallbackToBasis) {
            base = basisDate;
        }
        PlanDateCalculator.NodeSchedule schedule =
                PlanDateCalculator.computeNodeSchedule(timingOf(node), base);
        if (fallbackToBasis && fixedPlanDates.contains(schedule.planDate())) {
            return null; // 首次抑制（OQ-139②）：回落起算日的首次与固定节点计划日重合，由固定节点承担
        }
        if (planEnd != null && schedule.planDate().isAfter(planEnd)) {
            return null; // 管理周期（cycle_type=YEARS）边界外不排
        }
        if (schedule.openDate().isAfter(logicalDate)) {
            return task(node.id(), occurrence, schedule, CREATE_NORMAL);
        }
        String createType =
                classify(node, schedule, logicalDate, enrolledOn, atEnrollment, pausedFrom);
        if (createType != null) {
            due.add(task(node.id(), occurrence, schedule, createType));
        }
        return null; // 滚动一次只排下一次：再下一次的日期待随访 / 缺访推进后才知道
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
