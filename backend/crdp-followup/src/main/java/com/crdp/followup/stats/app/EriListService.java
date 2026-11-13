package com.crdp.followup.stats.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.plan.domain.EriEval;
import com.crdp.followup.stats.infra.mapper.EriListMapper;
import com.crdp.followup.stats.infra.mapper.EriListMapper.LatestEriRecordRow;
import com.crdp.followup.stats.infra.mapper.EriListMapper.PatientDeviceRow;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 接近 ERI 器械列表卡片（E21-T19 / C-DASH-005；GET /api/hospital/followup/dashboard/cards/eri_list，
 * docs/api/stats.md §2、§3）：列出最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命低于阈值的患者， 含器械、最近随访日期、下次计划日期。
 *
 * <p>口径：
 *
 * <ul>
 *   <li><b>最近一次 ERI 状态</b> = 每患者最近一条含 ERI 字段（f323 / f324）的已提交随访记录（按有效随访日期取最新， EriListMapper
 *       窗口函数），命中判定与计划切换 SW-7 / SW-8 共享 {@link EriEval}（同一谓词）；未提交过含 ERI 字段记录的患者不进列表；
 *   <li><b>患者范围</b> = 随访中（followup_status = 'ACTIVE'，在册）∩ 本人数据范围（@DataScope）∩ 病区筛选 —— 已死亡 / 已终止 /
 *       失访患者无随访动作，不出行；
 *   <li><b>器械</b> = 在用植入记录（uk 至多一条）的类型 / 品牌 / 型号；显示名随业务字典（含停用项，C-FLD-009）；无在用器械行显示「未登记在用器械」；
 *   <li><b>最近随访日期</b> = pt_patient_summary.latest_followup_date（E15 提交后重算；缺失回落该记录有效随访日期），
 *       <b>下次计划日期</b> = pt_patient_summary.next_plan_date（E14 调度写入；无在组计划为 null，前端显示「—」）；
 *   <li>姓名脱敏（字段策略 + {@code TenantViewFieldGuard}，与患者列表同口径；列表不提供逐行明文，AGENTS §5.3）； 指标卡「接近 ERI
 *       器械数」（C-DASH-001）与本卡 total 同一查询（E21-T19 起出数，仍受 eri_cycle 开关约束）；
 *   <li>功能开关 {@code eri_cycle}（stats.md §3）关闭 / 无行 → 40301、前端隐藏（AGENTS §5.7）；目录行随 followup V96 落库。
 * </ul>
 */
@Service
public class EriListService {

    /** 接近 ERI 卡片的功能开关（dash_card 目录 followup V96 行 feature_code 同值）。 */
    static final String ERI_CYCLE_FEATURE = "eri_cycle";

    /** 列表行上限（卡片型列表；超出部分 total 体现，与任务看板「每列前 20」同做法）。 */
    static final int ITEM_LIMIT = 20;

    /** 无在用器械行的展示占位。 */
    static final String NO_DEVICE_LABEL = "未登记在用器械";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EriListMapper mapper;
    private final DashboardFeatureMapper features;
    private final ImplantDictPort dicts;
    private final CryptoService crypto;
    private final TenantViewFieldGuard viewGuard;

    public EriListService(
            EriListMapper mapper,
            DashboardFeatureMapper features,
            ImplantDictPort dicts,
            CryptoService crypto,
            TenantViewFieldGuard viewGuard) {
        this.mapper = mapper;
        this.features = features;
        this.dicts = dicts;
        this.crypto = crypto;
        this.viewGuard = viewGuard;
    }

    // ---------------------------------------------------------------- DTO

    /** 一行：患者 + 在用器械 + 最近随访 / 下次计划（姓名脱敏；id 序列化为字符串——超过 2^53 不进 JSON number）。 */
    public record EriItemView(
            String patientId,
            String name,
            String visitNo,
            String deviceType,
            String deviceTypeLabel,
            String brand,
            String brandLabel,
            String model,
            LocalDate lastVisitDate,
            LocalDate nextPlanDate,
            String eriStatusCode,
            String eriStatusName,
            Double batteryLife) {}

    /** 卡片数据：total = 命中患者总数（与指标卡「接近 ERI 器械数」同查询），items 前 {@link #ITEM_LIMIT} 行。 */
    public record EriListView(
            long total, List<EriItemView> items, double batteryLifeThreshold, int limit) {}

    // ---------------------------------------------------------------- 取数

    /** 卡片取数（wardId 页面级病区筛选；eri_cycle 开关关闭 40301）。 */
    public EriListView eriList(Long wardId) {
        if (!Boolean.TRUE.equals(features.findEnabled(ERI_CYCLE_FEATURE))) {
            throw new ApiException(403, 40301, "接近 ERI 器械卡片未开通（功能开关 eri_cycle 关闭）");
        }
        Long ward = sanitizeWard(wardId);
        List<MarkedRecord> marked = markedRecords(ward);
        // 证据随访日期倒序（最新的接近 / 已达在前，null 最后），同日按记录 id 稳定排序
        marked.sort(
                Comparator.comparing(
                                MarkedRecord::visitDate,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparingLong(MarkedRecord::recordId));
        List<EriItemView> items = new ArrayList<>();
        if (!marked.isEmpty()) {
            List<Long> patientIds =
                    marked.stream().limit(ITEM_LIMIT).map(MarkedRecord::patientId).toList();
            Map<Long, PatientDeviceRow> byPatient = new LinkedHashMap<>();
            for (PatientDeviceRow row : mapper.findPatientDevices(patientIds)) {
                byPatient.put(row.id(), row);
            }
            Map<String, String> deviceTypeLabels = labelTable(ImplantDictPort.DICT_DEVICE_TYPE);
            Map<String, String> brandLabels = labelTable(ImplantDictPort.DICT_BRAND);
            for (MarkedRecord record : marked) {
                if (items.size() >= ITEM_LIMIT) {
                    break;
                }
                items.add(
                        itemOf(
                                record,
                                byPatient.get(record.patientId()),
                                deviceTypeLabels,
                                brandLabels));
            }
        }
        return new EriListView(
                marked.size(), List.copyOf(items), EriEval.BATTERY_LIFE_THRESHOLD, ITEM_LIMIT);
    }

    /** 命中患者数（指标卡「接近 ERI 器械数」C-DASH-001：与本卡 total 同一查询； 调用方自行判定 eri_cycle 开关，本方法不出 40301）。 */
    public long nearEriCount(Long wardId) {
        return markedRecords(sanitizeWard(wardId)).size();
    }

    // ---------------------------------------------------------------- 内部

    /** 命中行（每患者最近一条含 ERI 字段的已提交记录 → EriEval.marked 过滤）。 */
    private List<MarkedRecord> markedRecords(Long ward) {
        List<MarkedRecord> marked = new ArrayList<>();
        for (LatestEriRecordRow row : mapper.findLatestEriRecords(ward)) {
            Map<String, Object> data = readData(row.data());
            if (EriEval.marked(data)) {
                marked.add(new MarkedRecord(row, data));
            }
        }
        return marked;
    }

    private EriItemView itemOf(
            MarkedRecord record,
            PatientDeviceRow row,
            Map<String, String> deviceTypeLabels,
            Map<String, String> brandLabels) {
        String deviceType = row == null ? null : row.deviceType();
        String brand = row == null ? null : row.brand();
        String statusCode = EriEval.statusCode(record.data());
        return new EriItemView(
                String.valueOf(record.patientId()),
                maskedName(row),
                row == null ? null : row.visitNo(),
                deviceType,
                deviceType == null ? NO_DEVICE_LABEL : labelOf(deviceTypeLabels, deviceType),
                brand,
                brand == null ? null : labelOf(brandLabels, brand),
                row == null ? null : row.model(),
                row != null && row.latestFollowupDate() != null
                        ? row.latestFollowupDate()
                        : record.visitDate(),
                row == null ? null : row.nextPlanDate(),
                statusCode,
                eriStatusName(statusCode),
                EriEval.batteryLife(record.data()));
    }

    /** ERI 状态显示名（字典 c_eri_status 取值，components.md §4 device.eri）。 */
    static String eriStatusName(String statusCode) {
        if (EriEval.STATUS_NEAR.equals(statusCode)) {
            return "接近ERI";
        }
        if (EriEval.STATUS_EOI.equals(statusCode)) {
            return "已达ERI(EOI)";
        }
        return EriEval.STATUS_NORMAL.equals(statusCode) ? "正常" : null;
    }

    private String maskedName(PatientDeviceRow row) {
        if (row == null || row.nameCipher() == null) {
            return "";
        }
        String name = crypto.decrypt(row.nameCipher());
        return viewGuard.apply(
                "patient", "name", name == null ? "" : SensitiveValueMasker.mask("name", name));
    }

    /** 字典编码 → 显示名（含停用项——历史值照常显示，C-FLD-009）。 */
    private Map<String, String> labelTable(String dictCode) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (ImplantDictPort.DictItem item : dicts.items(dictCode)) {
            labels.put(item.itemCode(), item.label());
        }
        return labels;
    }

    private static String labelOf(Map<String, String> labels, String code) {
        return labels.getOrDefault(code, code);
    }

    private static Map<String, Object> readData(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception invalid) {
            return Map.of(); // 脏数据不阻断卡片（防御：字段值写入侧已约束）
        }
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

    /** 命中记录与其表单值（data 复用于状态 / 电池寿命列）。 */
    private record MarkedRecord(
            long patientId, long recordId, LocalDate visitDate, Map<String, Object> data) {

        MarkedRecord(LatestEriRecordRow row, Map<String, Object> data) {
            this(row.patientId(), row.recordId(), row.visitDate(), data);
        }
    }
}
