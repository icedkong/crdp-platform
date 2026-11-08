package com.crdp.followup.stats.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 节点随访统计查询（E19-T01 / A-DASH-012；「一个口径一个查询」，docs/api/stats.md §1）： 按节点 × 第 N
 * 次出应随访、已随访数，数据来自每日刷新的节点随访事实表（{@link NodeOccurrenceRefreshService}）。
 *
 * <p>E19-T04 的两张随访率卡片以本服务为唯一取数口（GET
 * /api/hospital/followup/dashboard/cards/{yearly_rate|node_rate}）： 每年随访率（A-DASH-006）= 年度节点按随访年度 N
 * 聚合（患者级去重——多计划并行时同一患者同一年度只计一次，OQ-109②）； 节点随访率（A-DASH-012、C-DASH-004）按计划 × 节点 × 第 N 次聚合（行 =
 * 入组）；两卡同一事实表、同一 due / followed 口径（Q38：逾期完成、补录、历史导入与窗口外日期都计入已随访， E19-T01
 * 刷新侧已保证），单计划下年度节点两处数字一致（A-DASH-012「与每年随访率的关系」）。
 *
 * <p>数据范围由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 按患者病区出行（范围内无患者返回空行集，不报错）； 病区 /
 * 队列为范围内再收窄。数据时点 = 最近一次每日刷新（asOf 回显 refreshed_date，当日完成 / 补录次日刷新前以最近刷新为准，OQ-106①）。
 */
@Service
public class NodeOccurrenceStatsService {

    /** 节点随访率卡片的功能开关（A-DASH-013 卡片目录：医院 A 默认不显示、B / C 显示；种子 V100）。 */
    static final String NODE_RATE_FEATURE = "node_follow_rate";

    /** 队列年份（起算日期年份）合法区间：早于 1900 / 晚于 2999 视为参数错误。 */
    private static final int COHORT_YEAR_MIN = 1900;

    private static final int COHORT_YEAR_MAX = 2999;

    private final NodeOccurrenceMapper occurrenceMapper;
    private final DashboardFeatureMapper features;

    public NodeOccurrenceStatsService(
            NodeOccurrenceMapper occurrenceMapper, DashboardFeatureMapper features) {
        this.occurrenceMapper = occurrenceMapper;
        this.features = features;
    }

    /**
     * 一行 = 一个节点的一个次数（固定节点各一行、循环节点按第 N 次分行，A-DASH-012「节点」口径）： 应随访、 已随访（含逾期完成 / 补录 /
     * 历史导入）、窗口内完成（参考）、失访、死亡人数；按计划内排序返回。 只列有应随访患者的次数（聚合天然满足）。
     */
    public record NodeCountView(
            long nodeId,
            String nodeName,
            int occurrence,
            long dueCount,
            long followedCount,
            long inWindowCount,
            long lostCount,
            long deathCount) {}

    /** 按计划版本聚合（planId = fu_plan.id；筛选条件随 E19-T04 卡片接口扩展病区 / 队列）。 */
    public List<NodeCountView> nodeCounts(long planId) {
        return occurrenceMapper.aggregateByNode(planId).stream()
                .map(
                        row ->
                                new NodeCountView(
                                        row.nodeId(),
                                        row.nodeName(),
                                        row.occurrence(),
                                        row.dueCount(),
                                        row.followedCount(),
                                        row.inWindowCount(),
                                        row.lostCount(),
                                        row.deathCount()))
                .toList();
    }

    // ---------------------------------------------------------------- E19-T04 卡片（Q38 口径）

    /** 随访计划候选（A-DASH-012「先选随访计划」；缺省 = 预置计划，id 最小的已发布版本）。 */
    public record PlanOptionView(long id, String name) {}

    /**
     * 一行随访率（year 与nodeName/occurrence 随卡片形态填）：随访率 = 已随访 ÷ 应随访为唯一主指标， 窗口内完成率仅作参考（Q38）；比率为百分数、一位小数。
     */
    public record RateRowView(
            String label,
            String nodeName,
            int occurrence,
            Integer year,
            long dueCount,
            long followedCount,
            long inWindowCount,
            long lostCount,
            long deathCount,
            double followRatePct,
            double inWindowRatePct,
            double lostRatePct) {}

    /** 每年随访率卡片（A-DASH-006）：rows 按随访年度 N 升序，只列至少有 1 名应随访患者的年度。 */
    public record YearlyRateCardView(
            LocalDate asOf,
            Integer cohortYear,
            List<Integer> cohortYears,
            List<RateRowView> rows) {}

    /** 节点随访率卡片（A-DASH-012 / C-DASH-004）：rows 按计划内节点顺序，plans 为候选。 */
    public record NodeRateCardView(
            LocalDate asOf,
            long planId,
            String planName,
            Integer cohortYear,
            List<Integer> cohortYears,
            List<PlanOptionView> plans,
            List<RateRowView> rows) {}

    /** 每年随访率（年度节点；病区 / 出院年份队列筛选，A-DASH-006）。 */
    public YearlyRateCardView yearlyRate(Long wardId, Integer cohortYear) {
        Long ward = sanitizeWard(wardId);
        Integer cohort = sanitizeCohortYear(cohortYear);
        List<NodeOccurrenceMapper.YearCountRow> aggregated =
                occurrenceMapper.aggregateByYear(ward, cohort);
        List<RateRowView> rows =
                aggregated.stream()
                        .map(
                                row ->
                                        new RateRowView(
                                                "第" + row.followupYear() + "年",
                                                null,
                                                row.followupYear(),
                                                row.followupYear(),
                                                row.dueCount(),
                                                row.followedCount(),
                                                row.inWindowCount(),
                                                row.lostCount(),
                                                row.deathCount(),
                                                pct(row.followedCount(), row.dueCount()),
                                                pct(row.inWindowCount(), row.dueCount()),
                                                pct(row.lostCount(), row.dueCount())))
                        .toList();
        LocalDate asOf = aggregated.isEmpty() ? null : aggregated.get(0).refreshedDate();
        return new YearlyRateCardView(asOf, cohort, occurrenceMapper.findCohortYears(null), rows);
    }

    /**
     * 节点随访率（A-DASH-012 / C-DASH-004；受 {@code node_follow_rate} 开关控制：关闭返回 40301、前端隐藏， AGENTS §5.7）。
     */
    public NodeRateCardView nodeRate(Long planId, Long wardId, Integer cohortYear) {
        if (!Boolean.TRUE.equals(features.findEnabled(NODE_RATE_FEATURE))) {
            throw new ApiException(403, 40301, "节点随访率卡片未开通（功能开关 node_follow_rate 关闭）");
        }
        Long plan = sanitizeId(planId, "planId");
        Long ward = sanitizeWard(wardId);
        Integer cohort = sanitizeCohortYear(cohortYear);
        List<PlanOptionView> plans =
                occurrenceMapper.findPublishedPlans().stream()
                        .map(row -> new PlanOptionView(row.id(), row.name()))
                        .toList();
        long effectivePlanId;
        String planName = null;
        if (plan != null) {
            effectivePlanId = plan;
            planName =
                    plans.stream()
                            .filter(option -> option.id() == plan)
                            .map(PlanOptionView::name)
                            .findFirst()
                            .orElse(null);
        } else if (!plans.isEmpty()) {
            // A-DASH-012「默认本租户的预置计划」：候选按 id 升序（预置计划在前），取第一个
            effectivePlanId = plans.get(0).id();
            planName = plans.get(0).name();
        } else {
            effectivePlanId = 0L;
        }
        List<NodeOccurrenceMapper.NodeRateRow> aggregated =
                effectivePlanId == 0L
                        ? List.of()
                        : occurrenceMapper.aggregateNodeRates(effectivePlanId, ward, cohort);
        List<RateRowView> rows =
                aggregated.stream().map(NodeOccurrenceStatsService::toRateRow).toList();
        LocalDate asOf = aggregated.isEmpty() ? null : aggregated.get(0).refreshedDate();
        return new NodeRateCardView(
                asOf,
                effectivePlanId,
                planName,
                cohort,
                occurrenceMapper.findCohortYears(effectivePlanId == 0L ? null : effectivePlanId),
                plans,
                rows);
    }

    /** 节点行 → 卡片行：行标签按节点形态（固定 = 节点名、年度循环 = 第 N 年、其余循环 = 第 N 次，A-DASH-012「节点」口径）。 */
    private static RateRowView toRateRow(NodeOccurrenceMapper.NodeRateRow row) {
        String label;
        if (row.followupYear() != null) {
            label = "第" + row.followupYear() + "年";
        } else if ("CYCLE".equals(row.repeatMode())) {
            label = "第" + row.occurrence() + "次";
        } else {
            label = row.nodeName();
        }
        return new RateRowView(
                label,
                row.nodeName(),
                row.occurrence(),
                row.followupYear(),
                row.dueCount(),
                row.followedCount(),
                row.inWindowCount(),
                row.lostCount(),
                row.deathCount(),
                pct(row.followedCount(), row.dueCount()),
                pct(row.inWindowCount(), row.dueCount()),
                pct(row.lostCount(), row.dueCount()));
    }

    /** 比率换算：百分数、一位小数；分母为 0 时为 0（聚合行分母 ≥ 1，防御兜底）。 */
    private static double pct(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return Math.round(numerator * 1000d / denominator) / 10d;
    }

    private static Long sanitizeWard(Long wardId) {
        return sanitizeId(wardId, "wardId");
    }

    private static Long sanitizeId(Long id, String name) {
        if (id == null) {
            return null;
        }
        if (id <= 0) {
            throw new ApiException(400, 40001, name + "（须为正整数）不合法");
        }
        return id;
    }

    private static Integer sanitizeCohortYear(Integer cohortYear) {
        if (cohortYear == null) {
            return null;
        }
        if (cohortYear < COHORT_YEAR_MIN || cohortYear > COHORT_YEAR_MAX) {
            throw new ApiException(400, 40001, "cohortYear（1900–2999）不合法");
        }
        return cohortYear;
    }
}
