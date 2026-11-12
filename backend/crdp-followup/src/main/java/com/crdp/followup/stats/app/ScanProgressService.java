package com.crdp.followup.stats.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.stats.infra.mapper.ScanProgressMapper;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 补录进度卡片（E21-T15 / C-DASH-007；GET /api/hospital/followup/dashboard/cards/scan_progress，
 * docs/api/stats.md §2、§3）：待录入与已电子化扫描件的数量与比例，可按科室病区（页面级 wardId）、上传人、 文档年份查看；另显示补录随访记录条数。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>计数条件与全院报告单检索（E16-T05）同一条件树（NORMAL 在册 + 病区 / 上传人 / 报告年份）， 「一个口径一个查询」（stats.md
 *       §1）；只统计电子化状态非空的报告单（A 租户行恒 NULL，A-ATT-018）。
 *   <li>比例 = 已电子化 ÷（待录入 + 已电子化）的百分数（整数）——「无需录入」不参与补录进度分母 （无需转录，C-SCAN-011）；分母为 0 时 0。
 *   <li>补录随访记录条数 = 已提交的历史补录记录（fu_record.source = BACKFILL 且 SUBMITTED， A-FU-014 /
 *       C-FU-013）；只收病区筛选，上传人 / 文档年份是扫描件侧筛选、记录无对应语义。
 *   <li>功能开关 {@code scan_progress}（种子 V100 默认 0，医院 C 上线随 E23 打开）关闭 / 无行 → 40301、 前端隐藏（AGENTS
 *       §5.7）；数据范围由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行。
 * </ul>
 */
@Service
public class ScanProgressService {

    /** 补录进度卡片的功能开关（dash_card 目录 followup V85 行 feature_code 同值）。 */
    static final String SCAN_PROGRESS_FEATURE = "scan_progress";

    /** 文档年份合法区间：早于 1900 / 晚于 2999 视为参数错误。 */
    private static final int YEAR_MIN = 1900;

    private static final int YEAR_MAX = 2999;

    private final ScanProgressMapper mapper;
    private final DashboardFeatureMapper features;

    public ScanProgressService(ScanProgressMapper mapper, DashboardFeatureMapper features) {
        this.mapper = mapper;
        this.features = features;
    }

    // ---------------------------------------------------------------- DTO

    /** 上传人候选（id 序列化为字符串——超过 2^53 的 ID 不进 JSON number）。 */
    public record UserOptionView(String id, String name) {}

    /**
     * 补录进度卡片数据：pending 待录入 / done 已电子化 / noEntry 无需录入份数，donePct = 已电子化 ÷（待录入 +
     * 已电子化）百分数；backfillRecordCount 补录随访记录条数；uploaders / years 筛选候选。
     */
    public record ScanProgressView(
            long pendingCount,
            long doneCount,
            long noEntryCount,
            int donePct,
            long backfillRecordCount,
            List<UserOptionView> uploaders,
            List<Integer> years) {}

    // ---------------------------------------------------------------- 取数

    /** 卡片取数（wardId 页面级病区筛选；uploaderId 上传人；year 文档年份）。 */
    public ScanProgressView scanProgress(Long wardId, Long uploaderId, Integer year) {
        if (!Boolean.TRUE.equals(features.findEnabled(SCAN_PROGRESS_FEATURE))) {
            throw new ApiException(403, 40301, "补录进度卡片未开通（功能开关 scan_progress 关闭）");
        }
        Long ward = sanitizeWard(wardId);
        Long uploader = sanitizeUploader(uploaderId);
        Integer sanitizedYear = sanitizeYear(year);
        long pending = 0;
        long done = 0;
        long noEntry = 0;
        for (ScanProgressMapper.StatusCountRow row :
                mapper.countByStatus(ward, uploader, sanitizedYear)) {
            switch (row.digitizeStatus()) {
                case "PENDING" -> pending = row.total();
                case "DONE" -> done = row.total();
                case "NO_ENTRY" -> noEntry = row.total();
                default -> {
                    // 未知取值不进卡片（防御：状态列取值由创建 / 手工修改口约束）
                }
            }
        }
        long backfill = mapper.countBackfillRecords(ward);
        return new ScanProgressView(
                pending,
                done,
                noEntry,
                donePct(pending, done),
                backfill,
                mapper.uploaderOptions(ward).stream()
                        .map(row -> new UserOptionView(String.valueOf(row.id()), row.name()))
                        .toList(),
                List.copyOf(mapper.yearOptions(ward)));
    }

    /** 比例（百分数整数）：已电子化 ÷（待录入 + 已电子化）；「无需录入」不参与分母，分母 0 → 0。 */
    static int donePct(long pending, long done) {
        long denominator = pending + done;
        if (denominator <= 0) {
            return 0;
        }
        return (int) Math.round(done * 100.0 / denominator);
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

    private static Long sanitizeUploader(Long uploaderId) {
        if (uploaderId == null) {
            return null;
        }
        if (uploaderId <= 0) {
            throw new ApiException(400, 40001, "uploaderId（须为正整数）不合法");
        }
        return uploaderId;
    }

    private static Integer sanitizeYear(Integer year) {
        if (year == null) {
            return null;
        }
        if (year < YEAR_MIN || year > YEAR_MAX) {
            throw new ApiException(400, 40001, "year（1900–2999）不合法");
        }
        return year;
    }
}
