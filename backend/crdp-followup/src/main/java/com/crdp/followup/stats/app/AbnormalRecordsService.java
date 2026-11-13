package com.crdp.followup.stats.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.stats.infra.mapper.AbnormalRecordsMapper;
import com.crdp.followup.stats.infra.mapper.AbnormalRecordsMapper.HighlightRecordRow;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 异常标红记录卡片（E21-T19 / C-DASH-006；GET /api/hospital/followup/dashboard/cards/abnormal_records，
 * docs/api/stats.md §2、§3）：近 N 天（默认 30）提交的含标红参数的随访记录，显示标红项目与变化率， 点击进入记录；只做查看，不需处理。
 *
 * <p>口径：
 *
 * <ul>
 *   <li><b>窗口</b> = 近 N 天提交，N 缺省 30（参数 days，1–365）；「提交」以 updated_at 近似（提交 / 标红重算 / 修改已提交都会触碰该列，
 *       fu_record 无独立提交时间列，OQ-107④）；窗口起点 = 北京业务日 today −（N − 1）的 0 点，与指标卡「近 30 天含标红参数的记录数」
 *       （C-DASH-001）同一棵条件树——days = 30 时两数一致（「一个口径一个查询」）；
 *   <li><b>标红来源</b> = fu_record.highlight（E17-T04 对比重算写入的命中数组，规则求值与前端实时标红同源）：每条记录列出命中项（字段 /
 *       规则文案）与变化率（change / changeText）；只读展示，不触发任何处理流程；
 *   <li><b>软删行不在册</b>（deleted = 0，V87 读取侧口径）；姓名脱敏（字段策略 + {@code TenantViewFieldGuard}，AGENTS §5.3）；
 *   <li>功能开关 {@code implant} 关闭 / 无行 → 40301、前端隐藏（C 卡片，A / B 默认不显示——首页卡片目录按开关出卡， 目录行随 followup V96
 *       落库）；数据范围由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行。
 * </ul>
 */
@Service
public class AbnormalRecordsService {

    /** 异常标红记录卡片的功能开关（dash_card 目录 followup V96 行 feature_code 同值）。 */
    static final String IMPLANT_FEATURE = "implant";

    /** 窗口缺省天数（C-DASH-006「近 N 天（默认 30 天）」；指标卡固定 30 天同值，OQ-107④）。 */
    static final int DEFAULT_DAYS = 30;

    /** 窗口天数上下界（防止无界窗口拖垮查询）。 */
    private static final int DAYS_MIN = 1;

    private static final int DAYS_MAX = 365;

    /** 列表行上限（卡片型列表；超出部分 total 体现）。 */
    static final int ITEM_LIMIT = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AbnormalRecordsMapper mapper;
    private final DashboardStatsMapper stats;
    private final DashboardFeatureMapper features;
    private final CryptoService crypto;
    private final TenantViewFieldGuard viewGuard;

    public AbnormalRecordsService(
            AbnormalRecordsMapper mapper,
            DashboardStatsMapper stats,
            DashboardFeatureMapper features,
            CryptoService crypto,
            TenantViewFieldGuard viewGuard) {
        this.mapper = mapper;
        this.stats = stats;
        this.features = features;
        this.crypto = crypto;
        this.viewGuard = viewGuard;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * 一条标红命中（E17-T04 求值器写入的命中数组元素同形）：key 为标红字段稳定键，message 为规则文案， change / changeText 为变化量与文案（如「下降
     * 11 个百分点」），previousValue 为上一次值（可空）。
     */
    public record HighlightHitView(
            String key, String message, Object previousValue, Object change, String changeText) {}

    /** 一行 = 一条含标红参数的已提交记录（姓名脱敏；recordId 用于点击进入记录）。 */
    public record AbnormalItemView(
            String recordId,
            String patientId,
            String name,
            String visitNo,
            LocalDate visitDate,
            LocalDateTime submittedAt,
            List<HighlightHitView> hits) {}

    /** 卡片数据：days / since 回显窗口，total = 同条件树计数（days = 30 时与指标卡一致），items 前 {@link #ITEM_LIMIT} 行。 */
    public record AbnormalRecordsView(
            int days, LocalDate since, long total, int limit, List<AbnormalItemView> items) {}

    // ---------------------------------------------------------------- 取数

    /** 卡片取数（wardId 页面级病区筛选；days 近 N 天，缺省 30；implant 开关关闭 40301）。 */
    public AbnormalRecordsView abnormalRecords(Long wardId, Integer days) {
        if (!Boolean.TRUE.equals(features.findEnabled(IMPLANT_FEATURE))) {
            throw new ApiException(403, 40301, "异常标红记录卡片未开通（功能开关 implant 关闭）");
        }
        Long ward = sanitizeWard(wardId);
        int windowDays = sanitizeDays(days);
        LocalDate today = PlanDateCalculator.businessToday(java.time.Instant.now());
        LocalDate since = today.minusDays(windowDays - 1L);
        long total = stats.countRecentHighlightRecords(since.atStartOfDay(), ward);
        List<AbnormalItemView> items = new ArrayList<>();
        for (HighlightRecordRow row :
                mapper.findRecentHighlights(since.atStartOfDay(), ward, ITEM_LIMIT)) {
            items.add(
                    new AbnormalItemView(
                            String.valueOf(row.id()),
                            String.valueOf(row.patientId()),
                            maskedName(row.nameCipher()),
                            row.visitNo(),
                            row.visitDate(),
                            row.updatedAt(),
                            hitsOf(row.highlight())));
        }
        return new AbnormalRecordsView(windowDays, since, total, ITEM_LIMIT, List.copyOf(items));
    }

    // ---------------------------------------------------------------- 内部

    /** 命中数组解析（脏数据容错为空列表——展示卡片不因个别脏行失败）。 */
    private static List<HighlightHitView> hitsOf(String highlight) {
        if (highlight == null || highlight.isBlank()) {
            return List.of();
        }
        try {
            List<LinkedHashMap<String, Object>> raw =
                    JSON.readValue(
                            highlight, new TypeReference<List<LinkedHashMap<String, Object>>>() {});
            List<HighlightHitView> hits = new ArrayList<>();
            for (Map<String, Object> hit : raw) {
                hits.add(
                        new HighlightHitView(
                                textOf(hit.get("key")),
                                textOf(hit.get("message")),
                                hit.get("previousValue"),
                                hit.get("change"),
                                textOf(hit.get("changeText"))));
            }
            return List.copyOf(hits);
        } catch (Exception invalid) {
            return List.of();
        }
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String maskedName(String nameCipher) {
        if (nameCipher == null) {
            return "";
        }
        String name = crypto.decrypt(nameCipher);
        return viewGuard.apply(
                "patient", "name", name == null ? "" : SensitiveValueMasker.mask("name", name));
    }

    private static int sanitizeDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        if (days < DAYS_MIN || days > DAYS_MAX) {
            throw new ApiException(400, 40001, "days（1–365）不合法");
        }
        return days;
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
