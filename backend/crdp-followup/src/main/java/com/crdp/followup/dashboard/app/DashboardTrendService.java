package com.crdp.followup.dashboard.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 首页趋势图（E19-T02 / A-DASH-002；docs/api/stats.md §3 cardCode = trend）：按时间维度（手术日期默认 / 出院日期 / 入院日期）×
 * 粒度（月默认 / 季度 / 年）× 分组维度（不分组默认 / 病区 / 性别 / 出院状态 / 患者类型 / 年龄段）统计患者数， 时间范围默认最近 24 个月（A 规格
 * §9「趋势图可选项」）。
 *
 * <p>验收「各周期合计与按同一日期筛选的患者列表一致」（A 规格 §9）：分桶查询与患者列表检索共用 {@code deleted = 0} +
 * 日期列闭区间条件，同一数据范围（{@code @DataScope(resource = "patient")}）下各桶 total 之和 = 患者列表按相同日期区间筛选的总数。
 *
 * <p>指标暂只有「患者数」（默认指标）；「死亡人数 / 随访完成数」与「术者 / 手术方式 / 急诊手术」分组需基线 JSON 解析与 记录表联查，随 E19 后续任务与 E21
 * 落地（OQ-107）。分桶 / 分组表达式从本类枚举白名单拼装（见 DashboardStatsMapper 类注释防注入口径）。
 */
@Service
public class DashboardTrendService {

    /** 时间范围默认跨度（月）：A 规格 §9「默认最近 24 个月」。 */
    static final int DEFAULT_RANGE_MONTHS = 24;

    /** 分桶数上限（防超大区间拉爆分组行数；120 个季度 = 30 年）。 */
    private static final int MAX_BUCKETS = 240;

    private static final Set<String> TIME_DIMENSIONS = Set.of("SURGERY", "DISCHARGE", "ADMISSION");
    private static final Set<String> GRANULARITIES = Set.of("MONTH", "QUARTER", "YEAR");
    private static final Set<String> GROUP_BYS =
            Set.of("NONE", "WARD", "GENDER", "DISCHARGE_STATUS", "PATIENT_TYPE", "AGE_BAND");

    private static final String UNKNOWN = "__UNKNOWN__";

    /** 时间维度 → 患者表日期列（白名单；手术日期默认，A 规格 §9）。 */
    private static final Map<String, String> DIMENSION_COLUMNS =
            Map.of(
                    "SURGERY", "p.surgery_date",
                    "DISCHARGE", "p.discharge_date",
                    "ADMISSION", "p.admission_date");

    private final DashboardStatsMapper stats;

    public DashboardTrendService(DashboardStatsMapper stats) {
        this.stats = stats;
    }

    // ---------------------------------------------------------------- DTO

    /** 一个分组系列在图例中的取值（label 为中文展示；key 为 SQL 分组键）。 */
    public record TrendGroupView(String key, String label) {}

    /** 一个周期：total 为该周期患者数合计，groups 为各分组取值计数（含 0，图表面直接可用）。 */
    public record TrendBucketView(String bucket, long total, List<TrendGroupCountView> groups) {}

    /** 周期内一个分组取值的计数。 */
    public record TrendGroupCountView(String key, String label, long count) {}

    /** 趋势图数据：回显生效参数与周期序列（周期连续、空周期补 0）。 */
    public record TrendCardView(
            String timeDimension,
            String granularity,
            String groupBy,
            LocalDate from,
            LocalDate to,
            List<TrendBucketView> buckets,
            List<TrendGroupView> legend) {}

    // ---------------------------------------------------------------- 取数

    /** 趋势图（from / to 闭区间；默认最近 24 个月，止于当月月末）。 */
    public TrendCardView trend(
            Long wardId,
            LocalDate from,
            LocalDate to,
            String timeDimension,
            String granularity,
            String groupBy) {
        Long ward = sanitizeWard(wardId);
        String dimension =
                enumOrDefault(timeDimension, TIME_DIMENSIONS, "SURGERY", "timeDimension");
        String grain = enumOrDefault(granularity, GRANULARITIES, "MONTH", "granularity");
        String group = enumOrDefault(groupBy, GROUP_BYS, "NONE", "groupBy");
        LocalDate today = PlanDateCalculator.businessToday(java.time.Instant.now());
        LocalDate effectiveFrom =
                from != null
                        ? from
                        : today.withDayOfMonth(1).minusMonths(DEFAULT_RANGE_MONTHS - 1L);
        LocalDate effectiveTo = to != null ? to : today.withDayOfMonth(today.lengthOfMonth());
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ApiException(400, 40001, "时间范围（from 不能晚于 to）不合法");
        }

        String dimColumn = DIMENSION_COLUMNS.get(dimension);
        String bucketExpr = bucketExpression(grain, dimColumn);
        String groupExpr = groupExpression(group);
        String joinClause = "WARD".equals(group) ? "LEFT JOIN ward w ON w.id = p.ward_id" : "";
        List<DashboardStatsMapper.TrendCountRow> rows =
                stats.countTrend(
                        bucketExpr,
                        groupExpr,
                        dimColumn,
                        joinClause,
                        effectiveFrom,
                        effectiveTo,
                        ward);

        // 计数归并：bucket → groupKey → count（不分组只有一个 null 键）
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (DashboardStatsMapper.TrendCountRow row : rows) {
            counts.computeIfAbsent(row.bucket(), key -> new LinkedHashMap<>())
                    .put(row.groupKey() == null ? "" : row.groupKey(), row.total());
        }
        // 图例取值：固定顺序维度按口径排序，其余按取值字母序；未知归最后
        List<String> groupKeys = new ArrayList<>(collectGroupKeys(counts));
        sortGroupKeys(group, groupKeys);
        List<TrendGroupView> legend =
                groupKeys.stream()
                        .map(key -> new TrendGroupView(key, groupLabel(group, key)))
                        .toList();

        List<TrendBucketView> buckets = new ArrayList<>();
        for (String bucket : bucketKeys(grain, effectiveFrom, effectiveTo)) {
            Map<String, Long> bucketCounts = counts.getOrDefault(bucket, Map.of());
            long total = bucketCounts.values().stream().mapToLong(Long::longValue).sum();
            List<TrendGroupCountView> groupCounts = new ArrayList<>();
            for (String key : groupKeys) {
                groupCounts.add(
                        new TrendGroupCountView(
                                key, groupLabel(group, key), bucketCounts.getOrDefault(key, 0L)));
            }
            buckets.add(new TrendBucketView(bucket, total, groupCounts));
        }
        return new TrendCardView(
                dimension, grain, group, effectiveFrom, effectiveTo, buckets, legend);
    }

    // ---------------------------------------------------------------- 白名单拼装（防注入：表达式只出自本类，参数走 #{}）

    /** 粒度 → 分桶 SQL 表达式（与 {@link #bucketKeys} 的 Java 周期键一一对应）。 */
    private static String bucketExpression(String granularity, String dimColumn) {
        return switch (granularity) {
            case "QUARTER" -> "CONCAT(YEAR(" + dimColumn + "), '-Q', QUARTER(" + dimColumn + "))";
            case "YEAR" -> "DATE_FORMAT(" + dimColumn + ", '%Y')";
            default -> "DATE_FORMAT(" + dimColumn + ", '%Y-%m')";
        };
    }

    /** 分组维度 → 分组 SQL 表达式（null = 不分组）。 */
    private static String groupExpression(String groupBy) {
        return switch (groupBy) {
            case "WARD" -> "COALESCE(w.name, '" + UNKNOWN + "')";
            case "GENDER" -> "COALESCE(NULLIF(p.gender, ''), '" + UNKNOWN + "')";
            case "DISCHARGE_STATUS" ->
                    "COALESCE(CAST(p.discharge_status AS CHAR), '" + UNKNOWN + "')";
            case "PATIENT_TYPE" -> "COALESCE(NULLIF(p.patient_type, ''), '" + UNKNOWN + "')";
            // 年龄段按手术年龄（A 规格 §9「分组维度的取值来源」：< 50、50–59、60–69、70–79、≥ 80；分段可配置随后续任务）
            case "AGE_BAND" ->
                    "CASE WHEN p.surgery_date IS NULL OR p.birth_date IS NULL THEN '"
                            + UNKNOWN
                            + "'"
                            + " WHEN TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date) < 50 THEN 'LT50'"
                            + " WHEN TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date) < 60 THEN 'A50_59'"
                            + " WHEN TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date) < 70 THEN 'A60_69'"
                            + " WHEN TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date) < 80 THEN 'A70_79'"
                            + " ELSE 'GE80' END";
            default -> null;
        };
    }

    /** 分组键 → 中文标签（患者类型为字典编码原样展示，字典翻译由前端按字段字典渲染）。 */
    private static String groupLabel(String groupBy, String key) {
        if (UNKNOWN.equals(key)) {
            return "未知";
        }
        return switch (groupBy) {
            case "GENDER" -> "M".equals(key) ? "男" : "F".equals(key) ? "女" : key;
            case "DISCHARGE_STATUS" ->
                    switch (key) {
                        case "1" -> "存活";
                        case "2" -> "病重自动出院";
                        case "3" -> "死亡";
                        default -> key;
                    };
            case "AGE_BAND" ->
                    switch (key) {
                        case "LT50" -> "< 50 岁";
                        case "A50_59" -> "50–59 岁";
                        case "A60_69" -> "60–69 岁";
                        case "A70_79" -> "70–79 岁";
                        case "GE80" -> "≥ 80 岁";
                        default -> key;
                    };
            default -> key;
        };
    }

    /** 固定顺序维度的键序（其余维度按字母序，未知最后）。 */
    private static void sortGroupKeys(String groupBy, List<String> keys) {
        Comparator<String> comparator =
                switch (groupBy) {
                    case "GENDER" -> Comparator.comparing(DashboardTrendService::genderRank);
                    case "DISCHARGE_STATUS" ->
                            Comparator.comparing(DashboardTrendService::dischargeStatusRank);
                    case "AGE_BAND" -> Comparator.comparing(DashboardTrendService::ageBandRank);
                    default -> Comparator.naturalOrder();
                };
        keys.sort(Comparator.<String, Boolean>comparing(UNKNOWN::equals).thenComparing(comparator));
    }

    private static int genderRank(String key) {
        return "M".equals(key) ? 0 : "F".equals(key) ? 1 : 2;
    }

    private static int dischargeStatusRank(String key) {
        return switch (key) {
            case "1" -> 0;
            case "2" -> 1;
            case "3" -> 2;
            default -> 3;
        };
    }

    private static int ageBandRank(String key) {
        return switch (key) {
            case "LT50" -> 0;
            case "A50_59" -> 1;
            case "A60_69" -> 2;
            case "A70_79" -> 3;
            case "GE80" -> 4;
            default -> 5;
        };
    }

    /** 全部分组键（按出现顺序去重，排序由 {@link #sortGroupKeys} 收口）。 */
    private static Set<String> collectGroupKeys(Map<String, Map<String, Long>> counts) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Long> bucketCounts : counts.values()) {
            keys.addAll(bucketCounts.keySet());
        }
        return keys;
    }

    // ---------------------------------------------------------------- 周期序列（连续补零）

    /** [from, to] 覆盖的周期键（与 bucketExpression 的 SQL 键同格式）；超上限抛 40001。 */
    private List<String> bucketKeys(String granularity, LocalDate from, LocalDate to) {
        List<String> keys = new ArrayList<>();
        LocalDate cursor = periodStart(granularity, from);
        LocalDate end = periodStart(granularity, to);
        while (!cursor.isAfter(end)) {
            keys.add(periodKey(granularity, cursor));
            if (keys.size() > MAX_BUCKETS) {
                throw new ApiException(400, 40001, "时间范围（跨度过长，最多 " + MAX_BUCKETS + " 个周期）不合法");
            }
            cursor = nextPeriod(granularity, cursor);
        }
        return keys;
    }

    /** 周期起点（月 / 季度 / 年的第一天）。 */
    private static LocalDate periodStart(String granularity, LocalDate date) {
        return switch (granularity) {
            case "YEAR" -> date.withDayOfYear(1);
            case "QUARTER" ->
                    date.withDayOfMonth(1).withMonth((date.getMonthValue() - 1) / 3 * 3 + 1);
            default -> date.withDayOfMonth(1);
        };
    }

    /** 周期键（与 SQL 表达式一致：YYYY-MM / YYYY-Qn / YYYY）。 */
    private static String periodKey(String granularity, LocalDate periodStart) {
        return switch (granularity) {
            case "YEAR" -> String.valueOf(periodStart.getYear());
            case "QUARTER" -> periodStart.getYear() + "-Q" + (periodStart.getMonthValue() / 3 + 1);
            default ->
                    periodStart.getYear()
                            + "-"
                            + String.format("%02d", periodStart.getMonthValue());
        };
    }

    /** 下一周期起点。 */
    private static LocalDate nextPeriod(String granularity, LocalDate periodStart) {
        return switch (granularity) {
            case "YEAR" -> periodStart.plusYears(1);
            case "QUARTER" -> periodStart.plusMonths(3);
            default -> periodStart.plusMonths(1);
        };
    }

    // ---------------------------------------------------------------- 参数清洗

    /** 枚举参数清洗：大小写与分隔符不敏感（ageBand / AGE_BAND 等价），未知值 40001；缺省回落默认值。 */
    private static String enumOrDefault(
            String value, Set<String> allowed, String fallback, String field) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase().replace("-", "_");
        for (String candidate : allowed) {
            if (candidate.replace("_", "").equals(normalized.replace("_", ""))) {
                return candidate;
            }
        }
        throw new ApiException(400, 40001, field + "（" + String.join(" / ", allowed) + "）不合法");
    }

    private static Long sanitizeWard(Long wardId) {
        if (wardId == null) {
            return null;
        }
        if (wardId <= 0) {
            throw new ApiException(400, 40001, "wardId（须为正整数）不合法");
        }
        return wardId;
    }
}
