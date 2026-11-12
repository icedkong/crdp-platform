package com.crdp.followup.compare.app;

import com.crdp.followup.compare.domain.CompareEvaluator;
import com.crdp.followup.compare.domain.CompareEvaluator.EvalPrevious;
import com.crdp.followup.compare.domain.CompareEvaluator.EvalRule;
import com.crdp.followup.compare.domain.CompareEvaluator.Hit;
import com.crdp.followup.compare.infra.mapper.CompareFeatureMapper;
import com.crdp.followup.compare.infra.mapper.CompareHighlightMapper;
import com.crdp.followup.compare.infra.mapper.CompareHighlightMapper.SubmittedRow;
import com.crdp.followup.compare.infra.mapper.CompareRuleMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper.CandidateRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper.TaskPlanRow;
import com.crdp.form.record.app.FormService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对比标红结果持久化与重算（E17-T04 / A-FU-027；tech-plan §11「后端保存时用同一组规则再算一次，结果存 fu_record.highlight」；followup
 * V69）。
 *
 * <p>求值与前端实时标红同源：启用规则（fu_compare_rule）+ 上一次解析（{@link CompareSourceService#previousOf}，E17-T02
 * 同一份链定序与基线回落）+ {@link CompareEvaluator}（E17-T03 共享用例锁定的七类规则），命中数组与前端 CompareHit 同形 （ruleId / key /
 * categoryKey / message / previousValue / change / changeText）；无命中 / 无启用规则 / 功能关闭 → NULL。
 *
 * <p>重算触发（调用方事务内，各入口写各自审计）：
 *
 * <ul>
 *   <li><b>记录提交</b>（{@link #refreshSubmittedRecords}，RecordEntryService 接线）：重算该患者该表单全部已提交记录——
 *       含本次（保存复算落库） 与补录一条更早记录后「上一次」变化、排在它后面的记录（tech-plan §11）；
 *   <li><b>规则变更</b>（{@link #recalculateForm}，CompareRuleService 接线）：该表单全部已提交记录按新开关与阈值重算——
 *       停用即清除、调阈值即按新值命中（验收「改规则后可重算已提交记录」）；
 *   <li><b>报告单映射写入</b>（E16-T07「录入时对比标红同样生效」）：写入随访记录走 {@link #refreshSubmittedRecords}
 *       （目标记录值变了，自身与后续记录的重算）；写入基线分节走 {@link #refreshBaselineDependents}（回落该基线表单的 启用规则所在表单）。
 * </ul>
 *
 * <p>规模口径：单患者重算逐行 UPDATE（一名患者记录数有限）；整表单重算按患者 keyset 游标分页（{@link #PATIENT_PAGE_SIZE}），内存有界。功能开关
 * compare_highlight 关闭 → 直接跳过（highlight 本应为 NULL，不做无谓扫描）。
 */
@Service
public class CompareHighlightService {

    /** 规则变更重算的患者分页大小（keyset 游标；整表单已提交记录可达数万行，按患者分批装载）。 */
    static final int PATIENT_PAGE_SIZE = 200;

    /** 命中数组序列化：空值键省略（previousValue / change / changeText 可为 null）。 */
    private static final ObjectMapper JSON =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final CompareFeatureMapper features;
    private final CompareRuleMapper rules;
    private final CompareSourceService sourceService;
    private final CompareHighlightMapper highlights;
    private final FormService formService;

    public CompareHighlightService(
            CompareFeatureMapper features,
            CompareRuleMapper rules,
            CompareSourceService sourceService,
            CompareHighlightMapper highlights,
            FormService formService) {
        this.features = features;
        this.rules = rules;
        this.sourceService = sourceService;
        this.highlights = highlights;
        this.formService = formService;
    }

    /** 记录提交 / 随访记录映射写入后：重算该患者该表单全部已提交记录（本次落库 + 同患者其余记录——补录更早记录后 排在后面的记录「上一次」变化）。返回重算条数。 */
    @Transactional
    public int refreshSubmittedRecords(long patientId, String formCode) {
        if (featureOff()) {
            return 0;
        }
        return recomputePatient(patientId, formCode, enabledRulesOf(formCode));
    }

    /**
     * 基线分节被写入（报告单映射写入）后：重算「启用规则的回落基线表单 = 该基线表单」的随访表单，该患者的已提交记录 （第 1
     * 年记录回落基线值，值变则命中变化）。停用规则不参与（其表单不重算）。返回重算条数。
     */
    @Transactional
    public int refreshBaselineDependents(long patientId, String baselineFormCode) {
        if (featureOff()) {
            return 0;
        }
        Map<String, List<CompareRuleRow>> referencing = new LinkedHashMap<>();
        for (CompareRuleRow row : rules.findAll(null)) {
            if ("ENABLED".equals(row.status())
                    && baselineFormCode != null
                    && baselineFormCode.equals(row.baselineFormCode())) {
                referencing.computeIfAbsent(row.formCode(), key -> new ArrayList<>()).add(row);
            }
        }
        int count = 0;
        for (Map.Entry<String, List<CompareRuleRow>> entry : referencing.entrySet()) {
            count += recomputePatient(patientId, entry.getKey(), entry.getValue());
        }
        return count;
    }

    /**
     * 规则变更后：该表单<b>全部</b>已提交记录按当前启用规则重算（开关与阈值取库中最新值——同一事务内即本次修改后的值）； 全部停用 → 整表单清除。按患者 keyset
     * 分页遍历（内存有界）。返回重算条数。
     */
    @Transactional
    public int recalculateForm(String formCode) {
        if (featureOff()) {
            return 0;
        }
        List<CompareRuleRow> enabled = enabledRulesOf(formCode);
        int total = 0;
        long cursor = 0L;
        while (true) {
            List<Long> patients =
                    highlights.findSubmittedPatients(formCode, cursor, PATIENT_PAGE_SIZE);
            if (patients.isEmpty()) {
                return total;
            }
            for (Long patientId : patients) {
                total += recomputePatient(patientId, formCode, enabled);
                cursor = patientId;
            }
        }
    }

    // ---------------------------------------------------------------- 单患者重算

    /** 重算一名患者一个表单的全部已提交记录：链定序（previousOf）→ 求值 → 逐行落 highlight（无命中 NULL）。 */
    private int recomputePatient(long patientId, String formCode, List<CompareRuleRow> enabled) {
        List<SubmittedRow> rows = highlights.findSubmittedByPatient(patientId, formCode);
        if (rows.isEmpty()) {
            return 0;
        }
        Map<Long, LocalDate> planDates = planDatesOf(rows);
        List<CandidateRow> candidates = candidatesOf(rows);
        List<EvalRule> evalRules = toEvalRules(enabled);
        Map<Integer, Map<String, Map<Integer, Integer>>> ordinalsByTemplateVersion =
                new HashMap<>();
        for (SubmittedRow row : rows) {
            highlights.updateHighlight(
                    row.id(),
                    highlightOf(
                            row,
                            patientId,
                            formCode,
                            enabled,
                            evalRules,
                            candidates,
                            planDates,
                            ordinalsByTemplateVersion));
        }
        return rows.size();
    }

    /** 一条记录的命中 JSON：启用规则 + 上一次 + 模板序数 → 求值；无启用规则 / 无命中 → null（不存空数组）。 */
    private String highlightOf(
            SubmittedRow row,
            long patientId,
            String formCode,
            List<CompareRuleRow> enabled,
            List<EvalRule> evalRules,
            List<CandidateRow> candidates,
            Map<Long, LocalDate> planDates,
            Map<Integer, Map<String, Map<Integer, Integer>>> ordinalsByTemplateVersion) {
        if (enabled.isEmpty()) {
            return null;
        }
        LocalDate currentEffective =
                row.visitDate() != null ? row.visitDate() : planDates.get(row.taskId());
        CompareSourceService.PreviousView previous =
                sourceService.previousOf(
                        enabled,
                        candidates,
                        planDates,
                        row.id(),
                        currentEffective,
                        patientId,
                        row.implantId());
        EvalPrevious evalPrevious =
                previous == null
                        ? EvalPrevious.none()
                        : new EvalPrevious(
                                previous.kind(),
                                previous.visitDate() == null
                                        ? null
                                        : previous.visitDate().toString(),
                                previous.values());
        List<Hit> hits =
                CompareEvaluator.evaluate(
                        evalRules,
                        readMap(row.data()),
                        evalPrevious,
                        ordinalsOf(formCode, row.templateVersion(), ordinalsByTemplateVersion));
        return hits.isEmpty() ? null : writeJson(hits);
    }

    // ---------------------------------------------------------------- 装配

    /** 该表单启用规则（findAll 已按 form_code, status, sort_no, id 序，过滤后保持规则顺序）。 */
    private List<CompareRuleRow> enabledRulesOf(String formCode) {
        List<CompareRuleRow> enabled = new ArrayList<>();
        for (CompareRuleRow row : rules.findAll(formCode)) {
            if ("ENABLED".equals(row.status())) {
                enabled.add(row);
            }
        }
        return enabled;
    }

    /** 计划外随访不作对比对象（A-FU-027 候选口径）；其余已提交记录全部进链（implantId 随行，E21-T11 同一器械过滤）。 */
    private static List<CandidateRow> candidatesOf(List<SubmittedRow> rows) {
        List<CandidateRow> candidates = new ArrayList<>();
        for (SubmittedRow row : rows) {
            if (!"UNPLANNED".equals(row.source())) {
                candidates.add(
                        new CandidateRow(
                                row.id(),
                                row.source(),
                                row.visitDate(),
                                row.taskId(),
                                row.implantId(),
                                row.data()));
            }
        }
        return candidates;
    }

    /** visit_date 为空的 TASK 行批量补查任务计划日期（对比时点）；无任务行不查库。 */
    private Map<Long, LocalDate> planDatesOf(List<SubmittedRow> rows) {
        List<Long> taskIds = new ArrayList<>();
        for (SubmittedRow row : rows) {
            if (row.visitDate() == null && row.taskId() != null) {
                taskIds.add(row.taskId());
            }
        }
        Map<Long, LocalDate> planDates = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            for (TaskPlanRow row : highlights.findTaskPlanDates(taskIds)) {
                planDates.put(row.taskId(), row.planDate());
            }
        }
        return planDates;
    }

    /**
     * 序数表（ORDINAL_RISE 用）：按记录固化的模板版本取 schema，字段稳定键 → 选项编码 → fe_option.ordinal； 按模板版本缓存（同版本只取一次）。
     */
    private Map<String, Map<Integer, Integer>> ordinalsOf(
            String formCode,
            int templateVersion,
            Map<Integer, Map<String, Map<Integer, Integer>>> cache) {
        return cache.computeIfAbsent(
                templateVersion,
                version -> {
                    FormService.SchemaView schema = formService.schema(formCode, version);
                    return schema == null ? Map.of() : ordinalsOf(schema);
                });
    }

    private static Map<String, Map<Integer, Integer>> ordinalsOf(FormService.SchemaView schema) {
        Map<String, Map<Integer, Integer>> ordinals = new LinkedHashMap<>();
        for (FormService.CategoryView category : schema.categories()) {
            for (FormService.FieldView field : category.fields()) {
                if (field.optionSet() == null) {
                    continue;
                }
                Map<Integer, Integer> codes = new LinkedHashMap<>();
                for (FormService.OptionItemView option : field.optionSet()) {
                    if (option.ordinal() != null) {
                        codes.put(option.code(), option.ordinal());
                    }
                }
                if (!codes.isEmpty()) {
                    ordinals.put(field.key(), codes);
                }
            }
        }
        return ordinals;
    }

    /** CompareRuleRow → 求值规则（params 解析为对象；显示名列不进求值）。 */
    private static List<EvalRule> toEvalRules(List<CompareRuleRow> rows) {
        List<EvalRule> evalRules = new ArrayList<>();
        for (CompareRuleRow row : rows) {
            evalRules.add(
                    new EvalRule(
                            row.ruleCode(),
                            row.categoryKey(),
                            row.targetField(),
                            row.ruleType(),
                            readParams(row.params()),
                            row.baselineField(),
                            row.fieldUnit(),
                            row.message()));
        }
        return evalRules;
    }

    private boolean featureOff() {
        return Boolean.FALSE.equals(features.findEnabled(CompareRuleService.FEATURE_CODE));
    }

    // ---------------------------------------------------------------- JSON

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
            throw new IllegalStateException("对比重算 JSON 解析失败（id 脏数据）", broken);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("对比重算结果序列化失败", broken);
        }
    }
}
