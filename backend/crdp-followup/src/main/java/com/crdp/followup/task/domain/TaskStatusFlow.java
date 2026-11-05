package com.crdp.followup.task.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态流转纯函数（E15-T01 / A-FU-003「任务状态流转」、A-FU-010「逾期与逾期完成」）。不碰库。
 *
 * <p>状态全集（A 规格「关键字段 · 任务状态」表；fu_task.status，V48 枚举、V51 加宽）：待随访 PENDING（创建初始，E14-T04）→ 进行中
 * IN_PROGRESS（第一次联系或暂存草稿）/ 逾期 OVERDUE（窗口结束日次日，本任务每日刷新）/ 已完成 COMPLETED（提交随访记录）； 失访 LOST（连续未联系到 +
 * 医生确认，A-FU-012 随 E15-T05）、已终止 TERMINATED（死亡 / 人工停止，E14-T06 已落）、 停用或暂停期间未执行
 * SKIPPED_SUSPENDED（PLT-TEN-017、A 的 Q32，E14-T06 已落，本任务补「补做 / 关闭」流转）。
 * 「待开始（计划中）」只是时间轴上的计算显示态，不落库、不属状态机（A-FU-003）。
 *
 * <p>完成方式（A 规格任务状态表「已完成」行：窗口开启前提交另标「窗口前完成」、窗口后提交另标「逾期完成」； A-FU-010 验收「逾期完成单独标记」，落
 * fu_task.complete_kind，followup V53）： ON_TIME 窗口内 / BEFORE_WINDOW 窗口前 / OVERDUE
 * 逾期完成（含「停用期间未执行」补做后完成——窗口已结束，同标逾期完成）。
 */
public final class TaskStatusFlow {

    /** 待随访：任务已开启、尚未联系（A-FU-003 初始态，创建即此值）。 */
    public static final String PENDING = "PENDING";

    /** 进行中：已有联系尝试或暂存草稿（A 规格任务状态表「记录第一次联系或暂存」）。 */
    public static final String IN_PROGRESS = "IN_PROGRESS";

    /** 已完成：随访记录已提交（complete_kind 另标窗口前 / 窗口内 / 逾期完成）。 */
    public static final String COMPLETED = "COMPLETED";

    /** 逾期：窗口结束日次日仍未完成（A-FU-010，每日 00:10 调度刷新；进入后不再退回进行中，直至完成 / 失访 / 终止）。 */
    public static final String OVERDUE = "OVERDUE";

    /**
     * 失访候选（A-FU-012，E15-T05）：窗口内不同日期连续 N 次「未联系到 / 号码错误」且窗口已结束，
     * 每日刷新判定（「拒访」不计入）；不是终态——仍是待办（进「我的待办」、可改派）， 医生确认后 → 失访，或在直接录入期内联系上患者照常完成。
     */
    public static final String LOST_CANDIDATE = "LOST_CANDIDATE";

    /** 失访：多次联系不上、医生自失访候选确认（A-FU-012，E15-T05）。 */
    public static final String LOST = "LOST";

    /** 已终止：患者死亡或人工停止（E14-T06 已落写入口）。 */
    public static final String TERMINATED = "TERMINATED";

    /** 停用或暂停期间未执行（PLT-TEN-017、A 的 Q32；create_type 区分来源，E14-T06 落，本任务接补做 / 关闭）。 */
    public static final String SKIPPED_SUSPENDED = "SKIPPED_SUSPENDED";

    /** 完成方式：窗口内完成（windowStart ≤ 完成日 ≤ windowEnd）。 */
    public static final String KIND_ON_TIME = "ON_TIME";

    /** 完成方式：窗口前完成（开启日早于窗口开始日时，窗口开启前提交，A 规格任务状态表）。 */
    public static final String KIND_BEFORE_WINDOW = "BEFORE_WINDOW";

    /** 完成方式：逾期完成（窗口结束后提交，A-FU-010「逾期完成单独标记」）。 */
    public static final String KIND_OVERDUE = "OVERDUE";

    /**
     * 合法流转表（A-FU-003 + 关键字段表「进入条件」逐行；E15-T05 起含失访候选）：
     *
     * <ul>
     *   <li>PENDING → IN_PROGRESS（第一次联系 /
     *       暂存草稿）、OVERDUE（窗口结束日次日）、COMPLETED（提交）、LOST_CANDIDATE（E15-T05 每日刷新）、LOST （E15-T05
     *       确认）、TERMINATED（E14-T06）；
     *   <li>IN_PROGRESS → OVERDUE、COMPLETED、LOST_CANDIDATE、LOST、TERMINATED（逾期刷新与终止不排除已联系的任务）；
     *   <li>OVERDUE → COMPLETED（逾期完成）、LOST_CANDIDATE、LOST、TERMINATED——<b>不退回</b>
     *       IN_PROGRESS（逾期是窗口事实，联系 / 录入不改写， OQ-82①）；
     *   <li>LOST_CANDIDATE → LOST（医生确认，A-FU-012）、COMPLETED（直接录入期内联系上患者照常完成，A-FU-010）、 TERMINATED（死亡
     *       / 移出计划终止不排除待确认候选）——<b>不退回</b> PENDING / OVERDUE（判定是窗口事实）；
     *   <li>SKIPPED_SUSPENDED → PENDING / OVERDUE（补做：按窗口是否已结束定，OQ-82②）、TERMINATED（关闭，Q32 /
     *       PLT-TEN-017）；
     *   <li>COMPLETED / LOST / TERMINATED 终态，不再流转（历史事实，同 E14-T06 终止保留口径）。
     * </ul>
     */
    private static final Map<String, Set<String>> TRANSITIONS =
            Map.of(
                    PENDING,
                    Set.of(IN_PROGRESS, OVERDUE, COMPLETED, LOST_CANDIDATE, LOST, TERMINATED),
                    IN_PROGRESS,
                    Set.of(OVERDUE, COMPLETED, LOST_CANDIDATE, LOST, TERMINATED),
                    OVERDUE,
                    Set.of(COMPLETED, LOST_CANDIDATE, LOST, TERMINATED),
                    LOST_CANDIDATE,
                    Set.of(LOST, COMPLETED, TERMINATED),
                    SKIPPED_SUSPENDED,
                    Set.of(PENDING, OVERDUE, TERMINATED),
                    COMPLETED,
                    Set.of(),
                    LOST,
                    Set.of(),
                    TERMINATED,
                    Set.of());

    private TaskStatusFlow() {}

    /** 状态是否合法（未知值防御：直接判非法，调用方按数据缺陷处理）。 */
    public static boolean known(String status) {
        return TRANSITIONS.containsKey(status);
    }

    /** from → to 是否规格允许（终态与未列出的方向一律 false）。 */
    public static boolean canTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** 是否终态（已完成 / 失访 / 已终止；终态任务不再参与任何流转与逾期刷新）。 */
    public static boolean isTerminal(String status) {
        Set<String> allowed = TRANSITIONS.get(status);
        return allowed != null && allowed.isEmpty();
    }

    /**
     * 完成方式（A 规格任务状态表「已完成」行）：完成日早于窗口开始日 → 窗口前完成； 在窗口内（含首尾两天）→ 窗口内；晚于窗口结束日 →
     * 逾期完成。完成日按北京时间业务日（LocalDate）。
     */
    public static String completeKindOf(
            LocalDate windowStart, LocalDate windowEnd, LocalDate completedOn) {
        if (completedOn.isBefore(windowStart)) {
            return KIND_BEFORE_WINDOW;
        }
        return completedOn.isAfter(windowEnd) ? KIND_OVERDUE : KIND_ON_TIME;
    }
}
