package com.crdp.followup.stats.domain;

import com.crdp.form.rule.app.FormProgressEngine.FieldProgress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 完整度聚合纯函数（E19-T05 / A-DASH-010 基线资料完整度、A-DASH-011 随访资料完整度、A-PAT-019；口径 Q39 / Q40， hospital-a-spec
 * §9「基线资料完整度口径」「随访资料完整度口径」）。 输入 = 每个统计单位的<b>字段级进度</b>（{@link
 * com.crdp.form.rule.app.FormProgressEngine#fieldProgress}——与分类条 / 列表完整度列同一应填集合），不碰库。
 *
 * <p>口径（医院已确认 Q39 / Q40）：
 *
 * <ul>
 *   <li><b>单位完整度</b> = round(已填 × 100 ÷ 应填)；应填 0 视为 100（与 {@code
 *       BaselineSectionService.refreshCompleteness} 患者列同口径——无可填项即完整）；
 *   <li><b>基线汇总</b> = 各患者完整度的平均值 + 中位数（每名患者权重相同）；分布五段 100 / 90–99 / 70–89 / 50–69 / &lt;50；
 *       分类明细按分类内同算法先取单位值再平均，该分类无应填字段的单位不计入该分类分母；
 *   <li><b>随访</b>：单条记录完整度同上取整；患者值 = 其记录平均值（每条记录权重相同，Q40）；卡片汇总按患者平均 （有已完成记录的患者）；按随访年度一行 = 该年记录平均值 /
 *       记录数 / 中位数 / 记录级分布；
 *   <li><b>最常缺失字段</b>：按缺失单位数降序（同数按字段稳定键序）取前 10；缺失率 = 缺失单位数 ÷ 范围单位数（基线 = 患者、随访 = 记录），一位小数。
 * </ul>
 */
public final class CompletenessAggregator {

    /** 最常缺失字段条数上限（A 规格 §9「最常缺失字段前 10」）。 */
    public static final int TOP_MISSING_LIMIT = 10;

    private CompletenessAggregator() {}

    /** 分布分段（Q39「分段可配置」首期固定五段；顺序即展示顺序）。 */
    public record BandView(String band, String label, long count, double pct) {}

    /** 汇总统计（unitCount = 分母单位数：基线 = 范围患者、随访卡片 = 有已完成记录的患者）。 */
    public record StatsView(
            long unitCount, double averagePct, double medianPct, List<BandView> distribution) {}

    /** 分类明细行（label = 分类声明显示名；unitCount = 该分类有应填字段的单位数）。 */
    public record CategoryStatView(
            String categoryKey, String label, long unitCount, double averagePct) {}

    /** 最常缺失字段行（missingCount / missingRatePct 基于范围单位数）。 */
    public record MissingFieldView(
            String fieldKey,
            String label,
            String categoryKey,
            long missingCount,
            double missingRatePct) {}

    /** 一条计入的已完成年度记录（patientId + followupYear + 记录完整度）。 */
    public record RecordProgress(long patientId, int followupYear, int recordPct) {}

    /** 随访卡片聚合（分布为患者级；years 为记录级）。 */
    public record FollowupCardStats(
            long recordCount,
            long patientCount,
            double averagePct,
            double medianPct,
            List<BandView> distribution,
            List<YearStatView> years) {}

    /** 按随访年度一行（Q40：平均值 / 记录数 / 中位数 / 分布——记录级）。 */
    public record YearStatView(
            int year,
            long recordCount,
            double averagePct,
            double medianPct,
            List<BandView> distribution) {}

    /** 五段分段（固定口径；顺序 = FULL、90–99、70–89、50–69、&lt;50）。 */
    private static final String[] BAND_KEYS = {"FULL", "90_99", "70_89", "50_69", "BELOW_50"};

    private static final String[] BAND_LABELS = {"100%", "90–99%", "70–89%", "50–69%", "低于 50%"};

    // ---------------------------------------------------------------- 单位完整度（基线 = 患者、随访 = 记录）

    /** 各单位的完整度（int，与患者列表列同口径；应填 0 视为 100）。 */
    public static List<Integer> patientPcts(List<List<FieldProgress>> units) {
        List<Integer> pcts = new ArrayList<>(units.size());
        for (List<FieldProgress> fields : units) {
            int filled = 0;
            int total = 0;
            for (FieldProgress field : fields) {
                total++;
                if (field.filled()) {
                    filled++;
                }
            }
            pcts.add(total == 0 ? 100 : (int) Math.round(filled * 100.0 / total));
        }
        return pcts;
    }

    /** 汇总统计（平均值 / 中位数 / 五段分布；空集 → unitCount 0、比率 0、分布全 0 段仍输出）。 */
    public static StatsView statsOf(List<Integer> unitPcts) {
        long count = unitPcts.size();
        double average =
                count == 0
                        ? 0d
                        : round1(
                                unitPcts.stream().mapToInt(Integer::intValue).average().orElse(0d));
        return new StatsView(count, average, round1(median(unitPcts)), bands(unitPcts, count));
    }

    /** 五段分布（count + 占比一位小数；固定五段全输出，空段计 0）。 */
    public static List<BandView> bands(List<Integer> unitPcts, long unitCount) {
        long[] counts = new long[BAND_KEYS.length];
        for (int pct : unitPcts) {
            counts[bandIndex(pct)]++;
        }
        List<BandView> views = new ArrayList<>(BAND_KEYS.length);
        for (int i = 0; i < BAND_KEYS.length; i++) {
            views.add(
                    new BandView(
                            BAND_KEYS[i],
                            BAND_LABELS[i],
                            counts[i],
                            unitCount == 0 ? 0d : round1(counts[i] * 100.0 / unitCount)));
        }
        return List.copyOf(views);
    }

    private static int bandIndex(int pct) {
        if (pct >= 100) {
            return 0;
        }
        if (pct >= 90) {
            return 1;
        }
        if (pct >= 70) {
            return 2;
        }
        if (pct >= 50) {
            return 3;
        }
        return 4;
    }

    // ---------------------------------------------------------------- 分类明细（A-DASH-010「可按 8
    // 个分类拆分显示」）

    /**
     * 分类明细：每分类先算各单位在<b>该分类内</b>的完整度（同算法），再对有应填字段的单位取平均； categoryLabels 缺声明的分类回落字段首现序、label = key（与
     * FormProgressEngine 的分类兜底一致）。
     */
    public static List<CategoryStatView> categoryStats(
            List<List<FieldProgress>> units, Map<String, String> categoryLabels) {
        Map<String, List<Integer>> byCategory = new LinkedHashMap<>();
        for (List<FieldProgress> fields : units) {
            Map<String, int[]> unitCounts = new LinkedHashMap<>();
            for (FieldProgress field : fields) {
                int[] count = unitCounts.computeIfAbsent(field.categoryKey(), key -> new int[2]);
                count[1]++;
                if (field.filled()) {
                    count[0]++;
                }
            }
            for (Map.Entry<String, int[]> entry : unitCounts.entrySet()) {
                int[] count = entry.getValue();
                byCategory
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(count[1] == 0 ? 100 : (int) Math.round(count[0] * 100.0 / count[1]));
            }
        }
        List<CategoryStatView> views = new ArrayList<>(byCategory.size());
        for (Map.Entry<String, List<Integer>> entry : byCategory.entrySet()) {
            List<Integer> pcts = entry.getValue();
            String label = categoryLabels.getOrDefault(entry.getKey(), entry.getKey());
            views.add(
                    new CategoryStatView(
                            entry.getKey(),
                            label,
                            pcts.size(),
                            round1(
                                    pcts.stream()
                                            .mapToInt(Integer::intValue)
                                            .average()
                                            .orElse(0d))));
        }
        return List.copyOf(views);
    }

    // ---------------------------------------------------------------- 最常缺失字段（可钻取到缺失字段）

    /**
     * 最常缺失字段：按缺失单位数降序（同数按字段稳定键升序——与 fe_form_version_field 输出序对应的稳定次序）， 只列有缺失的字段，limit 截断（卡片传 {@link
     * #TOP_MISSING_LIMIT}）。缺失率 = 缺失单位数 ÷ unitCount。
     */
    public static List<MissingFieldView> topMissing(
            List<List<FieldProgress>> units, long unitCount, int limit) {
        Map<String, long[]> missing =
                new LinkedHashMap<>(); // fieldKey → [missingCount, firstSeenOrder]
        Map<String, FieldProgress> firstSeen = new LinkedHashMap<>();
        long order = 0;
        for (List<FieldProgress> fields : units) {
            for (FieldProgress field : fields) {
                firstSeen.putIfAbsent(field.fieldKey(), field);
                long[] count = missing.putIfAbsent(field.fieldKey(), new long[] {0, order});
                if (count == null) { // 首次出现（putIfAbsent 返回 null = 新建）
                    count = missing.get(field.fieldKey());
                }
                if (!field.filled()) {
                    count[0]++;
                }
            }
            order++;
        }
        record Entry(String fieldKey, long missingCount, long firstSeenOrder) {}

        List<Entry> sorted =
                missing.entrySet().stream()
                        .map(
                                entry ->
                                        new Entry(
                                                entry.getKey(),
                                                entry.getValue()[0],
                                                entry.getValue()[1]))
                        .filter(entry -> entry.missingCount() > 0)
                        .sorted(
                                Comparator.comparingLong(Entry::missingCount)
                                        .reversed()
                                        .thenComparingLong(Entry::firstSeenOrder))
                        .limit(Math.max(0, limit))
                        .toList();
        List<MissingFieldView> views = new ArrayList<>(sorted.size());
        for (Entry entry : sorted) {
            FieldProgress field = firstSeen.get(entry.fieldKey());
            views.add(
                    new MissingFieldView(
                            field.fieldKey(),
                            field.label(),
                            field.categoryKey(),
                            entry.missingCount(),
                            unitCount == 0
                                    ? 0d
                                    : round1(entry.missingCount() * 100.0 / unitCount)));
        }
        return List.copyOf(views);
    }

    // ---------------------------------------------------------------- 随访卡片（A-DASH-011 / Q40）

    /**
     * 随访卡片聚合：卡片汇总按<b>患者</b>平均（每名患者 = 其记录平均值，权重相同；无已完成记录的患者不参与—— 由调用侧只装计入记录保证）； years
     * 按年度升序只列有记录的年度（记录级平均 / 记录数 / 中位数 / 分布）。
     */
    public static FollowupCardStats followupCard(List<RecordProgress> records) {
        Map<Long, List<Integer>> byPatient = new LinkedHashMap<>();
        TreeMap<Integer, List<Integer>> byYear = new TreeMap<>();
        for (RecordProgress record : records) {
            byPatient
                    .computeIfAbsent(record.patientId(), key -> new ArrayList<>())
                    .add(record.recordPct());
            byYear.computeIfAbsent(record.followupYear(), key -> new ArrayList<>())
                    .add(record.recordPct());
        }
        // 患者值 = 其记录平均值（每条记录权重相同，Q40）；卡片汇总 = 患者值平均
        List<Double> patientValues = new ArrayList<>(byPatient.size());
        for (List<Integer> pcts : byPatient.values()) {
            patientValues.add(pcts.stream().mapToInt(Integer::intValue).average().orElse(0d));
        }
        List<Integer> rounded =
                patientValues.stream().map(value -> (int) Math.round(value)).toList();
        StatsView stats = statsOf(rounded);
        // 汇总比率用未取整患者值再平均（显示口径），中位数 / 分布沿用取整值
        double average =
                patientValues.isEmpty()
                        ? 0d
                        : round1(
                                patientValues.stream()
                                        .mapToDouble(Double::doubleValue)
                                        .average()
                                        .orElse(0d));

        List<YearStatView> years = new ArrayList<>(byYear.size());
        for (Map.Entry<Integer, List<Integer>> entry : byYear.entrySet()) {
            StatsView yearStats = statsOf(entry.getValue());
            years.add(
                    new YearStatView(
                            entry.getKey(),
                            yearStats.unitCount(),
                            yearStats.averagePct(),
                            yearStats.medianPct(),
                            yearStats.distribution()));
        }
        return new FollowupCardStats(
                records.size(),
                byPatient.size(),
                average,
                stats.medianPct(),
                stats.distribution(),
                List.copyOf(years));
    }

    // ---------------------------------------------------------------- 内部

    /** 中位数：偶数个取中间两数平均；空集 0。 */
    private static double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    /** 一位小数（四舍五入）。 */
    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
