package com.crdp.followup.stats.app;

import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.stats.domain.CompletenessAggregator;
import com.crdp.followup.stats.infra.mapper.CompletenessStatsMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FormProgressEngine.FieldProgress;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 数据完整度统计（E19-T05 / A-DASH-010 基线资料完整度、A-DASH-011 随访资料完整度、A-PAT-019、C-DASH-009； 口径 Q39 /
 * Q40，hospital-a-spec §9）：首页两张完整度卡片的唯一取数口（「一个口径一个查询」，docs/api/stats.md）， 并向患者检索提供 「缺该字段的患者」钻取 ID
 * 集（卡片最常缺失字段 → 患者列表，数字与列表一致由同一计算保证）。
 *
 * <p>口径：
 *
 * <ul>
 *   <li><b>字段级求值</b>：FormProgressEngine 字段级进度（与分类条 / 患者列表完整度列同一应填集合——显示中且有效必填且 storage=json
 *       且非派生，条件不显示不计分母）；基线按当前发布版对全部基线分节合并值求值（与 BaselineSectionService.refreshCompleteness
 *       同版本口径），随访记录按各自保存时固化的模板版本求值；
 *   <li><b>基线</b>（A-DASH-010）：单位 = 范围内患者（未保存过分节的按 0 计入）；汇总 = 各患者平均值 + 中位数；分布五段； 分类明细（8
 *       分类）按分类内同算法平均；最常缺失字段前 10（缺失率 = 缺失人数 ÷ 范围患者数）；
 *   <li><b>随访</b>（A-DASH-011 / Q40）：单位 = 已提交的年度随访记录（窗口内完成、逾期完成、补录、历史导入、随访中确认死亡都计入；
 *       计划外与无锚点手工补录不计，OQ-110③）；卡片汇总按患者平均（患者值 = 其记录平均值）；按随访年度一行（记录数 / 平均 / 中位数 / 记录级分布）；最常缺失字段前
 *       10（缺失率 = 缺失记录数 ÷ 已统计记录数）；
 *   <li><b>筛选</b>：病区（页面级）、患者类型、时间范围（基线默认出院日期可切登记日期；随访默认实际随访日期可切出院日期—— 任务录入记录的实际随访日期在表单字段
 *       followup_date，按记录模板版本解析）、随访年度（随访卡）。
 * </ul>
 *
 * <p>数据范围由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（范围内无患者返回空卡，不报错）。 统计时点 =
 * 按当前数据实时计算（A 规格 §9「统计时点」；60s 缓存随 E19-T08 / OQ-107① 落地，OQ-110①）。
 */
@Service
public class CompletenessStatsService {

    /** 基线表单编码（A_BASELINE，分类 1–8；与 BaselineSectionService 同源）。 */
    static final String BASELINE_FORM_CODE = BaselineSectionService.BASELINE_FORM_CODE;

    /** 随访实际随访日期的字段编码（A 规格 f214 followup_date；按记录模板版本解析稳定键，不在 SQL 硬编码）。 */
    static final String VISIT_DATE_FIELD_CODE = "followup_date";

    /** 基线时间范围维度：DISCHARGE 出院日期（默认）/ REGISTERED 登记日期（pt_patient.created_at）。 */
    private static final Map<String, String> BASELINE_DATE_COLUMNS =
            Map.of("DISCHARGE", "p.discharge_date", "REGISTERED", "p.created_at");

    /** 随访时间范围维度：VISIT 实际随访日期（默认）/ DISCHARGE 出院日期。 */
    private static final String TIME_FIELD_VISIT = "VISIT";

    private static final String TIME_FIELD_DISCHARGE = "DISCHARGE";

    private final CompletenessStatsMapper mapper;
    private final FormService formService;
    private final ObjectMapper json = new ObjectMapper();

    public CompletenessStatsService(CompletenessStatsMapper mapper, FormService formService) {
        this.mapper = mapper;
        this.formService = formService;
    }

    // ---------------------------------------------------------------- 查询参数与视图

    /** 完整度筛选（两卡共用形态；timeField / year 语义随卡片，见类注释）。 */
    public record Query(
            Long wardId,
            String patientType,
            LocalDate from,
            LocalDate to,
            String timeField,
            Integer year) {}

    /** 患者类型候选（范围内 DISTINCT 值；label = 编码，OQ-110⑤）。 */
    public record PatientTypeOptionView(String code, String label) {}

    /** 随访年度候选（值 = 年度 N，label 带「第 N 年」）。 */
    public record YearOptionView(int year, String label) {}

    /** 最常缺失字段行（A 规格 §9：字段、缺失人数 / 记录数、缺失率；点击进入缺该字段的患者列表）。 */
    public record MissingFieldView(
            String fieldKey,
            String label,
            String categoryKey,
            long missingCount,
            double missingRatePct) {}

    /** 分类明细行（基线卡「可切换为按 8 个分类的完整度表」）。 */
    public record CategoryRowView(
            String categoryKey, String label, long patientCount, double averagePct) {}

    /** 基线完整度卡（A-DASH-010：平均值与中位数；分布柱图；分类明细；最常缺失字段前 10）。 */
    public record BaselineCardView(
            Long wardId,
            String patientType,
            String timeField,
            LocalDate from,
            LocalDate to,
            long patientCount,
            double averagePct,
            double medianPct,
            List<CompletenessAggregator.BandView> distribution,
            List<CategoryRowView> categories,
            List<MissingFieldView> topMissing,
            List<PatientTypeOptionView> patientTypes) {}

    /** 按随访年度一行（Q40：已完成记录数、平均完整度、中位数、分布）。 */
    public record YearRowView(
            int year,
            String label,
            long recordCount,
            double averagePct,
            double medianPct,
            List<CompletenessAggregator.BandView> distribution) {}

    /** 随访完整度卡（A-DASH-011：平均值与已统计记录数；按年度表格；最常缺失字段前 10）。 */
    public record FollowupCardView(
            Long wardId,
            String patientType,
            String timeField,
            LocalDate from,
            LocalDate to,
            Integer year,
            long patientCount,
            long recordCount,
            double averagePct,
            double medianPct,
            List<CompletenessAggregator.BandView> distribution,
            List<YearRowView> years,
            List<MissingFieldView> topMissing,
            List<PatientTypeOptionView> patientTypes,
            List<YearOptionView> yearOptions) {}

    /** 单位字段进度 + 单位标识（钻取按字段找缺该字段的患者）。 */
    private record UnitFields(String unitKey, List<FieldProgress> fields) {}

    /** 单位列表 → 字段进度列表（聚合器输入形态）。 */
    private static List<List<FieldProgress>> unitFields(List<UnitFields> units) {
        return units.stream().map(UnitFields::fields).toList();
    }

    // ---------------------------------------------------------------- 基线资料完整度（A-DASH-010）

    /** 基线卡（分母 = 范围内全部在册患者；未保存过分节的按 0 计入）。 */
    public BaselineCardView baselineCard(Query query) {
        Query safe = sanitizeBaseline(query);
        String dateColumn = BASELINE_DATE_COLUMNS.get(safe.timeField());
        List<Long> patients =
                mapper.selectBaselinePatientIds(
                        safe.wardId(), safe.patientType(), dateColumn, safe.from(), safe.to());
        List<UnitFields> units = baselineUnits(patients, safe);
        CompletenessAggregator.StatsView stats =
                CompletenessAggregator.statsOf(
                        CompletenessAggregator.patientPcts(unitFields(units)));
        List<CategoryRowView> categories =
                CompletenessAggregator.categoryStats(unitFields(units), baselineCategoryLabels())
                        .stream()
                        .map(
                                row ->
                                        new CategoryRowView(
                                                row.categoryKey(),
                                                row.label(),
                                                row.unitCount(),
                                                row.averagePct()))
                        .toList();
        return new BaselineCardView(
                safe.wardId(),
                safe.patientType(),
                safe.timeField(),
                safe.from(),
                safe.to(),
                stats.unitCount(),
                stats.averagePct(),
                stats.medianPct(),
                stats.distribution(),
                categories,
                toMissingViews(
                        CompletenessAggregator.topMissing(
                                unitFields(units),
                                stats.unitCount(),
                                CompletenessAggregator.TOP_MISSING_LIMIT)),
                patientTypeOptions());
    }

    /**
     * 缺某基线字段的患者 ID 集（钻取：卡片最常缺失字段 → 患者列表；与卡片同一计算，数字一致）。 字段不在任何患者的完整度口径内（不存在 / 非应填 / 对所有人都条件不显示）→ 空集。
     */
    public List<Long> patientsMissingBaselineField(String fieldKey, Query query) {
        Query safe = sanitizeBaseline(query);
        String dateColumn = BASELINE_DATE_COLUMNS.get(safe.timeField());
        List<Long> patients =
                mapper.selectBaselinePatientIds(
                        safe.wardId(), safe.patientType(), dateColumn, safe.from(), safe.to());
        return missingUnitKeys(baselineUnits(patients, safe), fieldKey).stream()
                .map(Long::valueOf)
                .toList();
    }

    /** 基线各单位字段进度（患者全集与分节数据同一棵条件树加载、按键合并；未保存过分节 = 空表单 → 全缺失）。 */
    private List<UnitFields> baselineUnits(List<Long> patients, Query query) {
        if (patients.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Object>> mergedByPatient = new LinkedHashMap<>();
        for (Long patient : patients) {
            mergedByPatient.put(patient, new LinkedHashMap<>());
        }
        String dateColumn = BASELINE_DATE_COLUMNS.get(query.timeField());
        for (CompletenessStatsMapper.SectionRow row :
                mapper.selectBaselineSections(
                        query.wardId(),
                        query.patientType(),
                        dateColumn,
                        query.from(),
                        query.to())) {
            Map<String, Object> merged = mergedByPatient.get(row.patientId());
            if (merged != null) { // 分节行必然属于全集（同一条件树）；防御跳过脏数据
                merged.putAll(readMap(row.data()));
            }
        }
        int version = formService.publishedVersion(BASELINE_FORM_CODE);
        List<Map<String, Object>> unitDatas = new ArrayList<>(mergedByPatient.values());
        List<List<FieldProgress>> evaluated =
                formService.fieldProgressBatch(BASELINE_FORM_CODE, version, unitDatas, Map.of());
        List<UnitFields> units = new ArrayList<>(patients.size());
        for (int i = 0; i < patients.size(); i++) {
            units.add(new UnitFields(String.valueOf(patients.get(i)), evaluated.get(i)));
        }
        return units;
    }

    /** 基线分类声明显示名（basic → 基本信息……，A 规格 §9 分类明细）。 */
    private Map<String, String> baselineCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (FormService.CategoryView category :
                formService.schema(BASELINE_FORM_CODE, null).categories()) {
            labels.put(category.key(), category.label());
        }
        return labels;
    }

    // ---------------------------------------------------------------- 随访资料完整度（A-DASH-011）

    /** 随访卡（分母单位 = 已提交年度记录；卡片汇总按患者平均，Q40）。 */
    public FollowupCardView followupCard(Query query) {
        Query safe = sanitizeFollowup(query);
        List<CompletenessStatsMapper.AnnualRecordRow> records = loadAnnualRecords(safe);
        List<PatientTypeOptionView> patientTypes = patientTypeOptions();
        List<YearOptionView> yearOptions = yearOptions(records);
        if (safe.year() != null) { // 随访年度筛选在年度解析后收窄（yearOptions 仍为全量候选）
            records =
                    records.stream()
                            .filter(row -> Objects.equals(row.followupYear(), safe.year()))
                            .toList();
        }
        List<UnitFields> units = recordUnits(records);
        List<Integer> recordPcts = CompletenessAggregator.patientPcts(unitFields(units));
        List<CompletenessAggregator.RecordProgress> progress = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            progress.add(
                    new CompletenessAggregator.RecordProgress(
                            records.get(i).patientId(),
                            records.get(i).followupYear(),
                            recordPcts.get(i)));
        }
        CompletenessAggregator.FollowupCardStats stats =
                CompletenessAggregator.followupCard(progress);
        List<YearRowView> years =
                stats.years().stream()
                        .map(
                                row ->
                                        new YearRowView(
                                                row.year(),
                                                "第" + row.year() + "年",
                                                row.recordCount(),
                                                row.averagePct(),
                                                row.medianPct(),
                                                row.distribution()))
                        .toList();
        return new FollowupCardView(
                safe.wardId(),
                safe.patientType(),
                safe.timeField(),
                safe.from(),
                safe.to(),
                safe.year(),
                stats.patientCount(),
                stats.recordCount(),
                stats.averagePct(),
                stats.medianPct(),
                stats.distribution(),
                years,
                toMissingViews(
                        CompletenessAggregator.topMissing(
                                unitFields(units),
                                stats.recordCount(),
                                CompletenessAggregator.TOP_MISSING_LIMIT)),
                patientTypes,
                yearOptions);
    }

    /**
     * 随访资料缺某字段的患者 ID 集（钻取，患者级：该患者任一计入记录缺该字段即入选；「对应随访年度」列随 E19-T08 口径核对， OQ-110④）。字段不在任何记录的口径内 → 空集。
     */
    public List<Long> patientsMissingFollowupField(String fieldKey, Query query) {
        Query safe = sanitizeFollowup(query);
        List<CompletenessStatsMapper.AnnualRecordRow> records = loadAnnualRecords(safe);
        if (safe.year() != null) {
            records =
                    records.stream()
                            .filter(row -> Objects.equals(row.followupYear(), safe.year()))
                            .toList();
        }
        LinkedHashSet<Long> patients = new LinkedHashSet<>();
        for (String unitKey : missingUnitKeys(recordUnits(records), fieldKey)) {
            patients.add(Long.valueOf(unitKey));
        }
        return List.copyOf(patients);
    }

    /** 随访记录单位（按 (formCode, templateVersion) 分组批量求值——版本字段每版本只查一次）。 */
    private List<UnitFields> recordUnits(List<CompletenessStatsMapper.AnnualRecordRow> records) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>(); // formCode#version → 行下标
        for (int i = 0; i < records.size(); i++) {
            CompletenessStatsMapper.AnnualRecordRow row = records.get(i);
            groups.computeIfAbsent(
                            row.formCode() + "#" + row.templateVersion(), key -> new ArrayList<>())
                    .add(i);
        }
        Map<Integer, List<FieldProgress>> evaluated = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            List<Integer> indexes = entry.getValue();
            String[] parts = entry.getKey().split("#", 2);
            List<Map<String, Object>> unitDatas = new ArrayList<>(indexes.size());
            for (int index : indexes) {
                unitDatas.add(readMap(records.get(index).data()));
            }
            List<List<FieldProgress>> results =
                    formService.fieldProgressBatch(
                            parts[0], Integer.valueOf(parts[1]), unitDatas, Map.of());
            for (int i = 0; i < indexes.size(); i++) {
                evaluated.put(indexes.get(i), results.get(i));
            }
        }
        List<UnitFields> units = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            // 单位键 = 患者ID（钻取患者级）；年度在记录行上（聚合用）
            units.add(new UnitFields(String.valueOf(records.get(i).patientId()), evaluated.get(i)));
        }
        return units;
    }

    /** 年度记录加载 + 时间范围过滤（VISIT = 实际随访日期：visit_date 列或表单字段 followup_date 按模板版本解析）。 */
    private List<CompletenessStatsMapper.AnnualRecordRow> loadAnnualRecords(Query query) {
        List<CompletenessStatsMapper.AnnualRecordRow> rows =
                mapper.selectAnnualRecords(query.wardId(), query.patientType());
        if (query.from() == null && query.to() == null) {
            return rows;
        }
        boolean byVisit = TIME_FIELD_VISIT.equals(query.timeField());
        Map<String, String> visitDateKeys = new HashMap<>(); // formCode#version → 随访日期字段稳定键
        List<CompletenessStatsMapper.AnnualRecordRow> filtered = new ArrayList<>(rows.size());
        for (CompletenessStatsMapper.AnnualRecordRow row : rows) {
            LocalDate date = byVisit ? resolveVisitDate(row, visitDateKeys) : row.dischargeDate();
            if (date == null) {
                continue; // 该维度日期未知的记录不计入时间筛选结果
            }
            if (query.from() != null && date.isBefore(query.from())) {
                continue;
            }
            if (query.to() != null && date.isAfter(query.to())) {
                continue;
            }
            filtered.add(row);
        }
        return filtered;
    }

    /**
     * 实际随访日期：visit_date 列（补录 / 导入）→ 表单字段 followup_date（任务录入，A 规格 f214）按模板版本解析；
     * 每版本的随访日期字段键只解析一次（visitDateKeys 调用内缓存）。
     */
    private LocalDate resolveVisitDate(
            CompletenessStatsMapper.AnnualRecordRow row, Map<String, String> visitDateKeys) {
        if (row.visitDate() != null) {
            return row.visitDate();
        }
        String cacheKey = row.formCode() + "#" + row.templateVersion();
        if (!visitDateKeys.containsKey(cacheKey)) {
            String key = null;
            outer:
            for (FormService.CategoryView category :
                    formService.schema(row.formCode(), row.templateVersion()).categories()) {
                for (FormService.FieldView field : category.fields()) {
                    if (VISIT_DATE_FIELD_CODE.equals(field.code())) {
                        key = field.key();
                        break outer;
                    }
                }
            }
            visitDateKeys.put(cacheKey, key);
        }
        String dateKey = visitDateKeys.get(cacheKey);
        if (dateKey == null) {
            return null;
        }
        Object value = readMap(row.data()).get(dateKey);
        if (value instanceof String text && !text.isBlank()) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException invalid) {
                return null; // 脏数据不计入时间筛选
            }
        }
        return null;
    }

    /** 随访年度候选（时间筛选后的全量记录取 DISTINCT 年度，升序；「表中只列有记录的年度」）。 */
    private static List<YearOptionView> yearOptions(
            List<CompletenessStatsMapper.AnnualRecordRow> records) {
        TreeSet<Integer> years = new TreeSet<>();
        for (CompletenessStatsMapper.AnnualRecordRow row : records) {
            if (row.followupYear() != null) {
                years.add(row.followupYear());
            }
        }
        return years.stream().map(year -> new YearOptionView(year, "第" + year + "年")).toList();
    }

    // ---------------------------------------------------------------- 钻取共用

    /** 缺某字段的单位键（字段在口径内且未填；范围外单位天然不在结果里）。 */
    private static List<String> missingUnitKeys(List<UnitFields> units, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            throw new ApiException(400, 40001, "fieldKey 不合法");
        }
        List<String> keys = new ArrayList<>();
        for (UnitFields unit : units) {
            for (FieldProgress field : unit.fields()) {
                if (fieldKey.equals(field.fieldKey()) && !field.filled()) {
                    keys.add(unit.unitKey());
                    break;
                }
            }
        }
        return keys;
    }

    private static List<MissingFieldView> toMissingViews(
            List<CompletenessAggregator.MissingFieldView> rows) {
        return rows.stream()
                .map(
                        row ->
                                new MissingFieldView(
                                        row.fieldKey(),
                                        row.label(),
                                        row.categoryKey(),
                                        row.missingCount(),
                                        row.missingRatePct()))
                .toList();
    }

    private List<PatientTypeOptionView> patientTypeOptions() {
        return mapper.selectPatientTypes().stream()
                .map(row -> new PatientTypeOptionView(row.patientType(), row.patientType()))
                .toList();
    }

    // ---------------------------------------------------------------- 参数校验（白名单，防注入口径同
    // DashboardStatsMapper）

    private Query sanitizeBaseline(Query query) {
        Query safe = requireNonNull(query);
        String timeField = safe.timeField() == null ? TIME_FIELD_DISCHARGE : safe.timeField();
        if (!BASELINE_DATE_COLUMNS.containsKey(timeField)) {
            throw new ApiException(400, 40001, "timeField（DISCHARGE / REGISTERED）不合法");
        }
        Query normalized =
                new Query(
                        sanitizeWard(safe.wardId()),
                        sanitizePatientType(safe.patientType()),
                        safe.from(),
                        safe.to(),
                        timeField,
                        null);
        validateRange(normalized);
        return normalized;
    }

    private Query sanitizeFollowup(Query query) {
        Query safe = requireNonNull(query);
        String timeField = safe.timeField() == null ? TIME_FIELD_VISIT : safe.timeField();
        if (!TIME_FIELD_VISIT.equals(timeField) && !TIME_FIELD_DISCHARGE.equals(timeField)) {
            throw new ApiException(400, 40001, "timeField（VISIT / DISCHARGE）不合法");
        }
        if (safe.year() != null && safe.year() < 1) {
            throw new ApiException(400, 40001, "year（随访年度，从 1 起）不合法");
        }
        Query normalized =
                new Query(
                        sanitizeWard(safe.wardId()),
                        sanitizePatientType(safe.patientType()),
                        safe.from(),
                        safe.to(),
                        timeField,
                        safe.year());
        validateRange(normalized);
        return normalized;
    }

    private static Query requireNonNull(Query query) {
        Query safe = query == null ? new Query(null, null, null, null, null, null) : query;
        if (safe.from() == null && safe.to() != null || safe.from() != null && safe.to() == null) {
            throw new ApiException(400, 40001, "from / to 须成对提供（闭区间）");
        }
        return safe;
    }

    private static void validateRange(Query query) {
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw new ApiException(400, 40001, "from 不能晚于 to");
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

    private static String sanitizePatientType(String patientType) {
        if (patientType == null || patientType.isBlank()) {
            return null;
        }
        String trimmed = patientType.trim();
        if (trimmed.length() > 64) {
            throw new ApiException(400, 40001, "patientType 过长");
        }
        return trimmed;
    }

    private Map<String, Object> readMap(String data) {
        if (data == null || data.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(data, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException invalid) {
            return new LinkedHashMap<>(); // 脏数据按空值处理（该单位完整度按未填计），不阻断整卡
        }
    }
}
