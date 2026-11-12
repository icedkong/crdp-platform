package com.crdp.followup.compare.app;

import com.crdp.followup.compare.infra.mapper.CompareFeatureMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper.BaselineSectionRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper.CandidateRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper.TaskPlanRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 上一次记录解析（E17-T02 / A-FU-027；docs/api/followup.md §3 {@code GET /records/{id}} 的 {@code previous}，
 * 「由后端按规则参数选取，前端不自己找」）。
 *
 * <p>解析口径（hospital-a-spec 第 5 章「录入时对比提示规则」与 tech-plan §11）：
 *
 * <ul>
 *   <li><b>有上一次随访</b>：取该患者「已提交」的同表单随访记录（含逾期完成、补录、历史导入）中，对比时点 （{@code visit_date ?? 关联任务
 *       plan_date}）早于本次、最近的一条；同对比时点按 id 小者为上一次（补录同日 多次的确定序）。计划外随访与草稿不作对比对象（A-FU-027）；
 *   <li><b>无上一次随访</b>（第 1 年或此前无随访）：回落基线分节字段（出院前超声，Q41），按启用规则的 baseline_form_code / baseline_field
 *       独立列取值；基线无提交语义，分节当前值即回落值；
 *   <li>取值白名单 = 启用规则的 target_field ∪ params.dateField（新发事件「日期晚于上一次随访」判定）， 不整份下发；
 *       「上一次该字段为空时跳过该条规则」由求值侧（E17-T03）按缺失键跳过；
 *   <li>功能开关 compare_highlight 关闭或该表单无启用规则 → previous 为 null（记录页本身正常返回， AGENTS §5.7）。
 * </ul>
 *
 * <p>求值已随 E17-T03 落地（{@link com.crdp.followup.compare.domain.CompareEvaluator} 与前端 packages/rules
 * 共享用例锁定一致）；结果持久化与补录后重算已随 E17-T04 落地（{@link CompareHighlightService} 批量重算经 {@link #previousOf}
 * 复用本类同一份链定序与基线回落）；E21-T11 起候选按「同一器械」过滤（fu_record.implant_id，OQ-130③——C
 * 规格电池类参数与同一器械比；电极级沿用跨器械口径随后续电极维度细化）。
 */
@Service
public class CompareSourceService {

    /** previous.kind：上一次已提交随访记录 / 基线回落（无上一次随访时）。 */
    public static final String KIND_FOLLOWUP = "FOLLOWUP";

    public static final String KIND_BASELINE = "BASELINE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CompareFeatureMapper features;
    private final CompareRuleMapper rules;
    private final CompareSourceMapper source;

    public CompareSourceService(
            CompareFeatureMapper features, CompareRuleMapper rules, CompareSourceMapper source) {
        this.features = features;
        this.rules = rules;
        this.source = source;
    }

    /** 上一次记录视图（followup.md §3）：FOLLOWUP 带记录定位与对比时点，BASELINE 只带回落值； 无对比来源返回 null。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviousView(
            String kind,
            String recordId,
            LocalDate visitDate,
            String source,
            Map<String, Object> values) {

        /** 上一次已提交随访（recordId / visitDate / source 定位展示，values 为白名单键值）。 */
        public static PreviousView followup(
                long recordId, LocalDate effectiveDate, String source, Map<String, Object> values) {
            return new PreviousView(
                    KIND_FOLLOWUP, String.valueOf(recordId), effectiveDate, source, values);
        }

        /** 基线回落（第 1 年与基线出院前超声对比，Q41）。 */
        public static PreviousView baseline(Map<String, Object> values) {
            return new PreviousView(KIND_BASELINE, null, null, null, values);
        }
    }

    /**
     * 解析当前记录的对比来源（E17-T02）：功能开关 → 启用规则（无则 null）→ 上一次已提交随访 → 基线回落。
     *
     * @param recordId 当前记录（排除自身，避免已提交详情把自己当上一次）
     * @param patientId 当前患者
     * @param formCode 当前表单（对比字段同表，跨表单不作对象）
     * @param visitDate 当前记录的实际随访日期（TASK 记录为 null，时点回落任务计划日期）
     */
    public PreviousView resolve(
            long recordId, long patientId, String formCode, LocalDate visitDate) {
        if (Boolean.FALSE.equals(features.findEnabled(CompareRuleService.FEATURE_CODE))) {
            return null;
        }
        List<CompareRuleRow> enabled = enabledRulesOf(formCode);
        if (enabled.isEmpty()) {
            return null;
        }
        LocalDate effectiveDate = visitDate != null ? visitDate : source.findTaskPlanDate(recordId);
        // E21-T11 / C-FU-010（OQ-130③）：当前记录带器械维度时候选按「同一器械」过滤（电池类参数与同一器械比；
        // 阈值 / 感知 / 阻抗的电极级「沿用跨器械」口径随后续在电极维度细化，先同一器械口径）；无维度（implant
        // 开关关闭的 A / B 租户、植入前 / 取出后）不过滤，与 E17 既有行为一致。
        Long currentImplant = source.findRecordImplant(recordId);
        List<CandidateRow> candidates =
                source.findSubmittedCandidates(patientId, recordId, formCode);
        return previousOf(
                enabled,
                candidates,
                planDatesOf(candidates),
                recordId,
                effectiveDate,
                patientId,
                currentImplant);
    }

    /**
     * 同一器械过滤（E21-T11 / OQ-130③）：当前记录器械维度为 null → 不过滤；有维度 → 只留关联同一器械的候选
     * （器械维度缺失的候选一并滤除——关联不上器械的记录不作「同一器械的上一次」）。
     */
    static List<CandidateRow> sameImplantOnly(List<CandidateRow> candidates, Long implantId) {
        if (implantId == null) {
            return candidates;
        }
        List<CandidateRow> filtered = new ArrayList<>();
        for (CandidateRow row : candidates) {
            if (implantId.equals(row.implantId())) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /**
     * 链上取「当前记录的上一次」（E17-T04 批量重算与 {@link #resolve} 共用同一份定序与回落逻辑， 不再各写一份）： 候选里取对比时点早于本次、最近的一条（同点按 id
     * 小者）；无候选回落基线分节。 不做功能开关与规则存在性检查（调用方先判）。 E21-T11：currentImplantId 非空时候选先按同一器械过滤（OQ-130③）。
     *
     * @param enabled 启用规则（白名单键与基线回落列都来自它）
     * @param candidates 对比候选（已排除计划外；含当前记录自身时由 id 排除）
     * @param planDates 候选的任务计划日期（visit_date 为空的 TASK 候选的对比时点）
     * @param recordId 当前记录（链定序排除自身）
     * @param currentEffective 当前记录对比时点（visit_date ?? 任务计划日期）
     * @param patientId 当前患者（基线回落查询）
     * @param currentImplantId 当前记录器械维度（fu_record.implant_id；null 不过滤——A / B 与植入前 / 取出后）
     */
    PreviousView previousOf(
            List<CompareRuleRow> enabled,
            List<CandidateRow> candidates,
            Map<Long, LocalDate> planDates,
            long recordId,
            LocalDate currentEffective,
            long patientId,
            Long currentImplantId) {
        Set<String> referencedKeys = referencedKeys(enabled);
        CandidateRow previous =
                nearestEarlier(
                        sameImplantOnly(candidates, currentImplantId),
                        recordId,
                        currentEffective,
                        planDates);
        if (previous != null) {
            Map<String, Object> values = pickKeys(readMap(previous.data()), referencedKeys);
            return PreviousView.followup(
                    previous.id(), effectiveOf(previous, planDates), previous.source(), values);
        }
        Map<String, Object> baseline = baselineValues(patientId, enabled);
        return baseline.isEmpty() ? null : PreviousView.baseline(baseline);
    }

    // ---------------------------------------------------------------- 内部

    /** 启用规则（status = ENABLED，按 form_code 过滤）。 */
    private List<CompareRuleRow> enabledRulesOf(String formCode) {
        List<CompareRuleRow> enabled = new ArrayList<>();
        for (CompareRuleRow row : rules.findAll(formCode)) {
            if ("ENABLED".equals(row.status())) {
                enabled.add(row);
            }
        }
        return enabled;
    }

    /**
     * 白名单键：启用规则的对比字段 + 新发事件规则的日期字段（params.dateField）+ 变化率规则的参考注字段（params.noteField，E21-T20 脉宽参考）。
     */
    private static Set<String> referencedKeys(List<CompareRuleRow> enabled) {
        Set<String> keys = new LinkedHashSet<>();
        for (CompareRuleRow row : enabled) {
            keys.add(row.targetField());
            Object dateField = readParams(row.params()).get("dateField");
            if (dateField instanceof String key && !key.isBlank()) {
                keys.add(key);
            }
            Object noteField = readParams(row.params()).get("noteField");
            if (noteField instanceof String key && !key.isBlank()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** visit_date 为空的候选批量补查任务计划日期（无任务候选不查库）。 */
    private Map<Long, LocalDate> planDatesOf(List<CandidateRow> candidates) {
        List<Long> taskIds = new ArrayList<>();
        for (CandidateRow row : candidates) {
            if (row.visitDate() == null && row.taskId() != null) {
                taskIds.add(row.taskId());
            }
        }
        Map<Long, LocalDate> planDates = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            for (TaskPlanRow row : source.findTaskPlanDates(taskIds)) {
                planDates.put(row.taskId(), row.planDate());
            }
        }
        return planDates;
    }

    /** 候选里取「对比时点早于本次、最近的一条」（同日按 id 小者；本次时点缺失时取最近一条兜底）。 */
    private static CandidateRow nearestEarlier(
            List<CandidateRow> candidates,
            long recordId,
            LocalDate currentEffective,
            Map<Long, LocalDate> planDates) {
        CandidateRow best = null;
        for (CandidateRow row : candidates) {
            if (isEarlier(row, recordId, currentEffective, planDates)
                    && (best == null || after(row, best, planDates))) {
                best = row;
            }
        }
        return best;
    }

    /** 候选是否早于本次：时点更早，或同时点且 id 更小。 候选无时点（TASK 无计划日期，不应出现）不算早于；本次无时点（不应出现）退化为取最近一条。 */
    private static boolean isEarlier(
            CandidateRow row,
            long recordId,
            LocalDate currentEffective,
            Map<Long, LocalDate> planDates) {
        if (currentEffective == null) {
            return true;
        }
        LocalDate effective = effectiveOf(row, planDates);
        if (effective == null) {
            return false;
        }
        return effective.isBefore(currentEffective)
                || (effective.isEqual(currentEffective) && row.id() < recordId);
    }

    /** 候选的对比时点（visit_date ?? 任务计划日期）。 */
    static LocalDate effectiveOf(CandidateRow row, Map<Long, LocalDate> planDates) {
        return row.visitDate() != null ? row.visitDate() : planDates.get(row.taskId());
    }

    /** 定序：对比时点大者靠后（无时点视为最早）；同时点按 id 大者靠后。 */
    private static boolean after(
            CandidateRow left, CandidateRow right, Map<Long, LocalDate> planDates) {
        LocalDate a = effectiveOf(left, planDates);
        LocalDate b = effectiveOf(right, planDates);
        if (a == null || b == null) {
            return b == null && a != null;
        }
        return a.isAfter(b) || (a.isEqual(b) && left.id() > right.id());
    }

    /** 基线回落值：按规则的 baseline_form_code 分组读分节，取回落字段在分节值里的键。 */
    private Map<String, Object> baselineValues(long patientId, List<CompareRuleRow> enabled) {
        Map<String, List<String>> fieldsByForm = new LinkedHashMap<>();
        for (CompareRuleRow row : enabled) {
            if (row.baselineFormCode() != null && row.baselineField() != null) {
                fieldsByForm
                        .computeIfAbsent(row.baselineFormCode(), key -> new ArrayList<>())
                        .add(row.baselineField());
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fieldsByForm.entrySet()) {
            Set<String> keys = new LinkedHashSet<>(entry.getValue());
            for (BaselineSectionRow section :
                    source.findBaselineSections(patientId, entry.getKey())) {
                pickKeysInto(readMap(section.data()), keys, values);
            }
        }
        return values;
    }

    /** 从值表里挑白名单键（保持键序；不存在的键跳过——求值侧按缺失跳过规则）。 */
    private static Map<String, Object> pickKeys(Map<String, Object> data, Set<String> keys) {
        Map<String, Object> picked = new LinkedHashMap<>();
        pickKeysInto(data, keys, picked);
        return picked;
    }

    private static void pickKeysInto(
            Map<String, Object> data, Set<String> keys, Map<String, Object> into) {
        for (String key : keys) {
            if (data.containsKey(key)) {
                into.put(key, data.get(key));
            }
        }
    }

    private static Map<String, Object> readParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return readMap(json);
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("对比来源 JSON 解析失败（id 脏数据）", broken);
        }
    }
}
