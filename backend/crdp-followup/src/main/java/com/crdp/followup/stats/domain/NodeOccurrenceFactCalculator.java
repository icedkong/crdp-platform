package com.crdp.followup.stats.domain;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节点随访事实表计算纯函数（E19-T01 / A-DASH-012；tech-plan §13「写入时维护的统计结构」）：对一条入组， 按其固定计划版本算出「每个节点的第 N
 * 次」事实行（st_node_occurrence），口径 = hospital-a-spec 「节点随访率口径（与 Q38 相同）」。不碰库。
 *
 * <p><b>事实行来源</b>（并集，uk = 入组 + 节点 + 第 N 次，与 fu_task / fu_record 锚点同粒度）：
 *
 * <ul>
 *   <li>计划推算：窗口已结束（窗口结束日早于统计日）且不早于入组日的次数（与每日调度下界一致，OQ-78③）—— 暂停中 /
 *       作业缺失导致没有任务的窗口也照常出行（应随访分母不因缺任务而漏计）；
 *   <li>任务行：fu_task 常规任务（跟进 followup_seq &gt; 0 与临时 occurrence &lt; 0 由加载侧过滤）；
 *   <li>记录行：E18 历史导入 / 历史未随访的锚点记录（fu_record.enrollment_id + node_id + occurrence，V73）。
 *   <li>入组前已过窗口且无任务无记录（医院 C 的 SKIP / Q14）不出行——不计入应随访。
 * </ul>
 *
 * <p><b>应随访</b>：窗口已结束，且在条件判定基准日（窗口开始日；入组更晚取入组日）患者仍符合条件——按 pt_status_history 回看（最新 effective_date ≤
 * 基准日；无行视为 ACTIVE）状态为 存活（ACTIVE）或暂停 （PAUSED，暂停仍计入，Q38）；已死亡 / 已终止 / 失访 → 不计。窗口未结束的行保留（dueReason =
 * WINDOW_OPEN）， 窗口结束日过了统计日的下一次刷新自然转应随访。
 *
 * <p><b>已随访</b>：该次随访记录已提交——任务已完成（fu_task.status = COMPLETED）或锚点记录已提交 （HIST_IMPORT；HIST_MISSED
 * 草稿补填提交按补录计，Q06）；窗口内 / 逾期 / 补录 / 历史导入 / 窗口外日期都算， 随访中确认死亡并提交的记录算已随访。无锚点的手工补录（source =
 * BACKFILL，A-FU-014）按与各次计划日期 最近的一次归属（暂行口径，见 OQ-106）。
 *
 * <p>次数推进、月末口径与周期回落（FIXED 未存周期 → 周期回落节点偏移，OQ-78①）复用 {@link TaskCreationPlanner} 的同一套口径；
 * 管理周期边界（cycle_type = YEARS）与 LAST_VISIT 只排第 1 次同 E14-T04。
 */
public final class NodeOccurrenceFactCalculator {

    /** 单节点防御上限（与 TaskCreationPlanner 一致：防退化配置拖死刷新）。 */
    private static final int MAX_OCCURRENCES = 100_000;

    /** dueReason：应随访。 */
    public static final String REASON_DUE = "DUE";

    /** dueReason：窗口未结束（统计日不晚于窗口结束日）。 */
    public static final String REASON_WINDOW_OPEN = "WINDOW_OPEN";

    /** dueReason：条件判定基准日患者已死亡 / 已终止 / 失访，不符合应随访。 */
    public static final String REASON_NOT_QUALIFIED = "NOT_QUALIFIED";

    /** 完成方式：窗口内完成（对齐 fu_task.complete_kind，E15-T01 V53）。 */
    public static final String KIND_ON_TIME = "ON_TIME";

    /** 完成方式：补录（补录任务 / 手工补录 / 历史未随访年度补填提交）。 */
    public static final String KIND_BACKFILL = "BACKFILL";

    /** 完成方式：历史导入（E18）。 */
    public static final String KIND_HIST_IMPORT = "HIST_IMPORT";

    /** fu_record.source：历史导入（E18-T05，有数据年度）。 */
    public static final String SOURCE_HIST_IMPORT = "HIST_IMPORT";

    /** fu_record.source：历史未随访（E18-T05，窗口已结束且无数据，Q06）。 */
    public static final String SOURCE_HIST_MISSED = "HIST_MISSED";

    private NodeOccurrenceFactCalculator() {}

    /** 计划版本标识（事实行的 plan_code / plan_version 冗余，供按计划族筛选）。 */
    public record PlanVersion(long planId, String planCode, int version) {}

    /** 入组输入（startDate = 起算日期快照；enrolledOn = 入组日，条件判定与下界口径用）。 */
    public record EnrollmentInput(
            long id,
            long patientId,
            long planId,
            LocalDate startDate,
            String mode,
            LocalDate enrolledOn) {}

    /**
     * 任务输入（fu_task 常规任务行；completeKind = fu_task.complete_kind 窗口前 / 窗口内 / 逾期完成， E15-T01
     * V53；recordId = fu_task.record_id）。
     */
    public record TaskInput(
            long taskId,
            Long recordId,
            long nodeId,
            int occurrence,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            String createType,
            String status,
            String completeKind) {}

    /** 锚点记录输入（fu_record 历史导入 / 历史未随访行，V73 锚点列）。 */
    public record RecordInput(
            long recordId,
            Long nodeId,
            Integer occurrence,
            String source,
            String status,
            LocalDate visitDate) {}

    /** 无锚点的已提交补录记录（source = BACKFILL、无入组锚点，A-FU-014；按计划日期最近归属）。 */
    public record FloatingRecord(long recordId, LocalDate visitDate) {}

    /** 患者状态时间线一点（pt_status_history 行；统计按窗口开始日回看，V39）。 */
    public record StatusPoint(String status, LocalDate effectiveDate) {}

    /** 一行事实（st_node_occurrence；taskId / recordId / completeKind / inWindow 仅已随访时有意义）。 */
    public record FactRow(
            long enrollmentId,
            long patientId,
            long planId,
            String planCode,
            int planVersion,
            long nodeId,
            int occurrence,
            Integer followupYear,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            Integer cohortYear,
            boolean due,
            String dueReason,
            Long taskId,
            Long recordId,
            boolean followed,
            String completeKind,
            boolean inWindow,
            boolean lost,
            boolean deathConfirmed) {}

    /**
     * 计算一条入组的全部事实行。
     *
     * @param nodes 入组固定计划版本的全部节点（SUBPLAN + NODE 混排）
     * @param plan 计划版本标识
     * @param enrollment 入组（起算日期不可解析返回空，与任务调度同口径）
     * @param tasks 该入组的常规任务行
     * @param anchoredRecords 该入组的锚点记录行（历史导入 / 历史未随访）
     * @param floatingBackfills 该患者无锚点的已提交补录记录
     * @param statusHistory 该患者的状态时间线（顺序不限）
     * @param planEnd 计划管理周期边界（cycle_type = YEARS = 起算 + N 年；ONGOING 传 null）
     * @param asOf 统计日（每日刷新的逻辑日；窗口结束日早于它即「已结束」）
     */
    public static List<FactRow> compute(
            List<NodeDef> nodes,
            PlanVersion plan,
            EnrollmentInput enrollment,
            List<TaskInput> tasks,
            List<RecordInput> anchoredRecords,
            List<FloatingRecord> floatingBackfills,
            List<StatusPoint> statusHistory,
            LocalDate planEnd,
            LocalDate asOf) {
        if (enrollment.startDate() == null) {
            return List.of(); // 起算日期不可解析：与任务调度一致跳过（IMPLANT_DATE 随 E21）
        }
        Map<Long, NodeDef> subplans = new LinkedHashMap<>();
        for (NodeDef node : nodes) {
            if ("SUBPLAN".equals(node.nodeType())) {
                subplans.put(node.id(), node);
            }
        }
        Map<String, TaskInput> taskIndex = new LinkedHashMap<>();
        for (TaskInput task : tasks) {
            taskIndex.put(task.nodeId() + "#" + task.occurrence(), task);
        }
        Map<String, RecordInput> recordIndex = new LinkedHashMap<>();
        for (RecordInput record : anchoredRecords) {
            if (record.nodeId() != null && record.occurrence() != null) {
                recordIndex.put(record.nodeId() + "#" + record.occurrence(), record);
            }
        }
        List<FactRow> rows = new ArrayList<>();
        for (NodeDef node : nodes) {
            if (!"NODE".equals(node.nodeType())) {
                continue;
            }
            if (node.enrollMode() != null
                    && !node.enrollMode().equalsIgnoreCase(enrollment.mode())) {
                continue; // C：ERI 期循环节点只在 ERI 模式生效（与调度同口径）
            }
            NodeDef subplan = subplans.get(node.parentId());
            if (subplan == null) {
                continue; // 防御：已发布计划必有父行
            }
            computeNode(
                    node,
                    subplan,
                    plan,
                    enrollment,
                    taskIndex,
                    recordIndex,
                    statusHistory,
                    planEnd,
                    asOf,
                    rows);
        }
        attributeFloatingBackfills(floatingBackfills, rows);
        rows.sort(Comparator.comparingLong(FactRow::nodeId).thenComparingInt(FactRow::occurrence));
        return List.copyOf(rows);
    }

    /** 单节点逐次推进（口径见类注释；期间任何「窗口未结束且无任务无记录」的次数即止）。 */
    private static void computeNode(
            NodeDef node,
            NodeDef subplan,
            PlanVersion plan,
            EnrollmentInput enrollment,
            Map<String, TaskInput> taskIndex,
            Map<String, RecordInput> recordIndex,
            List<StatusPoint> statusHistory,
            LocalDate planEnd,
            LocalDate asOf,
            List<FactRow> rows) {
        boolean rolling = "LAST_VISIT".equals(node.basis());
        String repeatMode = Objects.requireNonNullElse(subplan.repeatMode(), "CYCLE").toUpperCase();
        boolean fixed = "FIXED".equals(repeatMode);
        int repeatCount = fixed && subplan.repeatCount() != null ? subplan.repeatCount() : 0;
        int cycleValue =
                subplan.cycleValue() != null ? subplan.cycleValue() : Math.abs(node.offsetValue());
        String cycleUnit =
                subplan.cycleUnit() != null
                        ? subplan.cycleUnit().toUpperCase()
                        : node.offsetUnit().toUpperCase();
        // 年度节点 = 周期单位为年的子计划（A 持续循环或固定次数的年度计划都算；「随访年度」列供每年随访率
        // A-DASH-006 与节点随访率共用同一查询，固定次数年度计划同样适用）
        boolean yearly = "YEAR".equals(cycleUnit);
        PlanDateCalculator.NodeTiming timing = timingOf(node);
        for (int occurrence = 1; occurrence <= MAX_OCCURRENCES; occurrence++) {
            if (fixed && occurrence > repeatCount) {
                return; // 固定次数排完
            }
            LocalDate planDate =
                    PlanDateCalculator.occurrencePlanDate(
                            enrollment.startDate(), timing, cycleValue, cycleUnit, occurrence);
            if (planEnd != null && planDate.isAfter(planEnd)) {
                return; // 管理周期边界外不排
            }
            PlanDateCalculator.NodeSchedule schedule =
                    PlanDateCalculator.scheduleFromPlanDate(timing, planDate);
            TaskInput task = taskIndex.get(node.id() + "#" + occurrence);
            RecordInput record = recordIndex.get(node.id() + "#" + occurrence);
            boolean windowEnded = schedule.windowEnd().isBefore(asOf);
            boolean preEnrollment = schedule.windowEnd().isBefore(enrollment.enrolledOn());
            if (!windowEnded && task == null && record == null) {
                return; // 后续次数窗口更晚、任务按开启日顺序创建：不会再有事实来源
            }
            // 入组前已过窗口：只有任务（A、B 的补录，Q44）或锚点记录（E18 历史导入）才出行（C 的 SKIP 不计，Q14）
            boolean include = task != null || record != null || (windowEnded && !preEnrollment);
            if (!include) {
                if (rolling) {
                    return; // LAST_VISIT 只排第 1 次（第 2 次起按实际随访滚动，OQ-78②）
                }
                continue;
            }
            rows.add(
                    rowOf(
                            node,
                            yearly,
                            plan,
                            enrollment,
                            task,
                            record,
                            statusHistory,
                            schedule,
                            occurrence,
                            windowEnded));
            if (rolling) {
                return; // LAST_VISIT 只排第 1 次
            }
        }
    }

    /** 组装一行的应随访 / 已随访 / 失访 / 死亡判定（口径见类注释）。 */
    private static FactRow rowOf(
            NodeDef node,
            boolean yearly,
            PlanVersion plan,
            EnrollmentInput enrollment,
            TaskInput task,
            RecordInput record,
            List<StatusPoint> statusHistory,
            PlanDateCalculator.NodeSchedule schedule,
            int occurrence,
            boolean windowEnded) {
        boolean due;
        String dueReason;
        if (!windowEnded) {
            due = false;
            dueReason = REASON_WINDOW_OPEN;
        } else {
            // 条件判定基准日：窗口开始日；入组更晚取入组日（建档时窗口已过按入组日状态判，Q44）
            LocalDate refDate =
                    schedule.windowStart().isAfter(enrollment.enrolledOn())
                            ? schedule.windowStart()
                            : enrollment.enrolledOn();
            String statusAtRef = statusAt(statusHistory, refDate);
            due = "ACTIVE".equals(statusAtRef) || "PAUSED".equals(statusAtRef);
            dueReason = due ? REASON_DUE : REASON_NOT_QUALIFIED;
        }
        boolean taskCompleted = task != null && "COMPLETED".equals(task.status());
        boolean recordSubmitted = record != null && "SUBMITTED".equals(record.status());
        boolean followed = taskCompleted || recordSubmitted;
        String completeKind = null;
        Long recordId = record != null ? record.recordId() : null;
        if (taskCompleted) {
            completeKind =
                    TaskCreationPlanner.CREATE_BACKFILL.equals(task.createType())
                            ? KIND_BACKFILL
                            : task.completeKind();
            if (recordId == null) {
                recordId = task.recordId();
            }
        } else if (recordSubmitted) {
            completeKind =
                    SOURCE_HIST_MISSED.equals(record.source()) ? KIND_BACKFILL : KIND_HIST_IMPORT;
        }
        boolean inWindow = false;
        if (followed) {
            if (task != null && taskCompleted) {
                inWindow = KIND_ON_TIME.equals(completeKind); // 窗口内完成（E15-T01 完成方式判定）
            } else {
                LocalDate visitDate = record != null && recordSubmitted ? record.visitDate() : null;
                inWindow =
                        visitDate != null
                                && !visitDate.isBefore(schedule.windowStart())
                                && !visitDate.isAfter(schedule.windowEnd());
            }
        }
        boolean lost = task != null && "LOST".equals(task.status());
        boolean deathConfirmed =
                statusHistory.stream()
                        .anyMatch(
                                point ->
                                        "DEAD".equals(point.status())
                                                && !point.effectiveDate()
                                                        .isBefore(schedule.windowStart())
                                                && !point.effectiveDate()
                                                        .isAfter(schedule.windowEnd()));
        return new FactRow(
                enrollment.id(),
                enrollment.patientId(),
                plan.planId(),
                plan.planCode(),
                plan.version(),
                node.id(),
                occurrence,
                yearly ? occurrence : null,
                schedule.planDate(),
                schedule.windowStart(),
                schedule.windowEnd(),
                enrollment.startDate().getYear(),
                due,
                dueReason,
                task != null ? task.taskId() : null,
                recordId,
                followed,
                completeKind,
                inWindow,
                lost,
                deathConfirmed);
    }

    /** 无锚点补录归属：与各事实行计划日期最近的一次（同近取更早一次；暂行口径 OQ-106）。 */
    private static void attributeFloatingBackfills(
            List<FloatingRecord> floatingBackfills, List<FactRow> rows) {
        for (FloatingRecord floating : floatingBackfills) {
            int targetIndex = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                long distance =
                        Math.abs(
                                rows.get(i).planDate().toEpochDay()
                                        - floating.visitDate().toEpochDay());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    targetIndex = i;
                }
            }
            if (targetIndex < 0) {
                continue; // 该患者尚无事实行（如 C 入组前已过窗口不计）：无处归属，忽略
            }
            FactRow target = rows.get(targetIndex);
            if (target.followed()) {
                continue; // 该次已有提交记录（任务 / 锚点）：补录不重复计
            }
            rows.set(
                    targetIndex,
                    new FactRow(
                            target.enrollmentId(),
                            target.patientId(),
                            target.planId(),
                            target.planCode(),
                            target.planVersion(),
                            target.nodeId(),
                            target.occurrence(),
                            target.followupYear(),
                            target.planDate(),
                            target.windowStart(),
                            target.windowEnd(),
                            target.cohortYear(),
                            target.due(),
                            target.dueReason(),
                            target.taskId(),
                            floating.recordId(),
                            true,
                            KIND_BACKFILL,
                            !floating.visitDate().isBefore(target.windowStart())
                                    && !floating.visitDate().isAfter(target.windowEnd()),
                            target.lost(),
                            target.deathConfirmed()));
        }
    }

    /** 基准日状态回看（最新 effective_date ≤ refDate；无行视为 ACTIVE——登记即写初始行，防御口径）。 */
    private static String statusAt(List<StatusPoint> statusHistory, LocalDate refDate) {
        StatusPoint latest = null;
        for (StatusPoint point : statusHistory) {
            if (point.effectiveDate().isAfter(refDate)) {
                continue;
            }
            if (latest == null || !point.effectiveDate().isBefore(latest.effectiveDate())) {
                // 同日多行（极端并发）取列表靠后一行：与「最新一行生效」的直觉一致
                latest = point;
            }
        }
        return latest != null ? latest.status() : "ACTIVE";
    }

    /** 节点行的日期参数（与 TaskCreationPlanner.timingOf 同构；单位归一大写）。 */
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
}
