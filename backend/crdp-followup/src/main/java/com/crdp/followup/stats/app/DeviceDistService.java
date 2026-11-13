package com.crdp.followup.stats.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.stats.infra.mapper.DeviceDistMapper;
import com.crdp.followup.stats.infra.mapper.DeviceDistMapper.DistCountRow;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 器械类型分布卡片（E21-T19 / C-DASH-002；GET /api/hospital/followup/dashboard/cards/device_dist，
 * docs/api/stats.md §2、§3）：按起搏器类型统计在随访患者数，可按品牌拆分。
 *
 * <p>口径：
 *
 * <ul>
 *   <li><b>分母</b> = 范围内在随访患者数（followup_status = 'ACTIVE'、在册，与指标卡「在随访患者数」同一条件树， 「一个口径一个查询」）；
 *   <li><b>分布键</b> = 在用植入记录（pt_implant.status = 'ACTIVE'，一位患者至多一条）的起搏器类型 → 品牌 两级；行序与品牌序按业务字典
 *       sortNo（含停用项，C-FLD-009），无在用器械的患者归「未登记在用器械」行（合计 ≤ 分母）；
 *   <li>pct = 类型人数 ÷ 在随访患者数的百分整数（分母 0 为 0）；
 *   <li>功能开关 {@code implant} 关闭 / 无行 → 40301、前端隐藏（AGENTS §5.7；A / B 无植入数据，卡片无意义）； 目录行随 followup V96
 *       落库；数据范围由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行。
 * </ul>
 */
@Service
public class DeviceDistService {

    /** 器械类型分布卡片的功能开关（dash_card 目录 followup V96 行 feature_code 同值）。 */
    static final String IMPLANT_FEATURE = "implant";

    /** 无在用器械行的类型占位编码（deviceType 为 null 的聚合行）。 */
    static final String NO_DEVICE_CODE = "__none__";

    /** 无在用器械行的展示占位。 */
    static final String NO_DEVICE_LABEL = "未登记在用器械";

    /** 无品牌值的展示占位（在用器械未录品牌）。 */
    static final String NO_BRAND_LABEL = "未填品牌";

    private final DeviceDistMapper mapper;
    private final DashboardFeatureMapper features;
    private final ImplantDictPort dicts;

    public DeviceDistService(
            DeviceDistMapper mapper, DashboardFeatureMapper features, ImplantDictPort dicts) {
        this.mapper = mapper;
        this.features = features;
        this.dicts = dicts;
    }

    // ---------------------------------------------------------------- DTO

    /** 品牌一级（brand 为编码，label 为字典显示名）。 */
    public record BrandDistView(String brand, String label, long count) {}

    /** 类型一行：人数、占比（百分整数）与品牌拆分（可按品牌拆分，C-DASH-002）。 */
    public record TypeDistView(
            String deviceType, String label, long count, int pct, List<BrandDistView> brands) {}

    /** 卡片数据：followedCount = 范围内在随访患者数（分母），rows 按字典序。 */
    public record DeviceDistView(long followedCount, List<TypeDistView> rows) {}

    // ---------------------------------------------------------------- 取数

    /** 卡片取数（wardId 页面级病区筛选；implant 开关关闭 40301）。 */
    public DeviceDistView deviceDist(Long wardId) {
        if (!Boolean.TRUE.equals(features.findEnabled(IMPLANT_FEATURE))) {
            throw new ApiException(403, 40301, "器械类型分布卡片未开通（功能开关 implant 关闭）");
        }
        Long ward = sanitizeWard(wardId);
        List<DistCountRow> counts = mapper.countByTypeAndBrand(ward);
        long followedCount = counts.stream().mapToLong(DistCountRow::total).sum();
        // 行序 = 字典 sortNo（含停用项；未知编码按编码排序接在字典项之后）→「未登记在用器械」行最后
        List<String> typeOrder = dictOrder(ImplantDictPort.DICT_DEVICE_TYPE);
        List<String> brandOrder = dictOrder(ImplantDictPort.DICT_BRAND);
        Map<String, String> typeLabels = labelTable(ImplantDictPort.DICT_DEVICE_TYPE);
        Map<String, String> brandLabels = labelTable(ImplantDictPort.DICT_BRAND);
        Map<String, Map<String, Long>> byType = new LinkedHashMap<>();
        for (DistCountRow row : counts) {
            String type = row.deviceType() == null ? NO_DEVICE_CODE : row.deviceType();
            String brand = row.brand() == null ? NO_DEVICE_CODE : row.brand();
            byType.computeIfAbsent(type, key -> new LinkedHashMap<>())
                    .merge(brand, row.total(), Long::sum);
        }
        List<TypeDistView> rows = new ArrayList<>();
        for (String type : sortedCodes(byType.keySet(), typeOrder)) {
            Map<String, Long> brands = byType.get(type);
            long count = brands.values().stream().mapToLong(Long::longValue).sum();
            List<BrandDistView> brandRows = new ArrayList<>();
            for (String brand : sortedCodes(brands.keySet(), brandOrder)) {
                brandRows.add(
                        new BrandDistView(
                                NO_DEVICE_CODE.equals(brand) ? null : brand,
                                brandLabel(brandLabels, brand),
                                brands.get(brand)));
            }
            rows.add(
                    new TypeDistView(
                            NO_DEVICE_CODE.equals(type) ? null : type,
                            typeLabel(typeLabels, type),
                            count,
                            pct(count, followedCount),
                            List.copyOf(brandRows)));
        }
        return new DeviceDistView(followedCount, List.copyOf(rows));
    }

    // ---------------------------------------------------------------- 内部

    /** 占比（百分整数；分母 0 为 0）。 */
    static int pct(long count, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round(count * 100.0 / total);
    }

    private static String typeLabel(Map<String, String> labels, String code) {
        return NO_DEVICE_CODE.equals(code) ? NO_DEVICE_LABEL : labels.getOrDefault(code, code);
    }

    private static String brandLabel(Map<String, String> labels, String code) {
        return NO_DEVICE_CODE.equals(code) ? NO_BRAND_LABEL : labels.getOrDefault(code, code);
    }

    /** 字典项编码顺序（sortNo、itemCode 升序，ImplantDictPort 口径）。 */
    private List<String> dictOrder(String dictCode) {
        return dicts.items(dictCode).stream().map(ImplantDictPort.DictItem::itemCode).toList();
    }

    private Map<String, String> labelTable(String dictCode) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (ImplantDictPort.DictItem item : dicts.items(dictCode)) {
            labels.put(item.itemCode(), item.label());
        }
        return labels;
    }

    /** 聚合键排序：先按字典序index，字典外编码（含占位）按编码排后。 */
    private static List<String> sortedCodes(java.util.Set<String> codes, List<String> dictOrder) {
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String code : codes) {
            (dictOrder.contains(code) ? known : unknown).add(code);
        }
        known.sort(java.util.Comparator.comparingInt(dictOrder::indexOf));
        unknown.sort(String::compareTo);
        List<String> sorted = new ArrayList<>(known);
        sorted.addAll(unknown);
        // 「未登记在用器械」恒排最后（无在用器械行是兜底口径，不与真实类型争序）
        if (sorted.remove(NO_DEVICE_CODE)) {
            sorted.add(NO_DEVICE_CODE);
        }
        return sorted;
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
