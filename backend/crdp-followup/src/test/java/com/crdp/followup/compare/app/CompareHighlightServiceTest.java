package com.crdp.followup.compare.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crdp.followup.compare.infra.mapper.CompareFeatureMapper;
import com.crdp.followup.compare.infra.mapper.CompareHighlightMapper;
import com.crdp.followup.compare.infra.mapper.CompareHighlightMapper.SubmittedRow;
import com.crdp.followup.compare.infra.mapper.CompareRuleMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper;
import com.crdp.form.record.app.FormService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E17-T04 / A-FU-027：对比标红结果持久化与重算单元测试。
 *
 * <p>验收「改规则后可重算已提交记录」：fu_record.highlight 保存复算（提交时与前端实时标红同源）， 规则变更后按新阈值整表单重算； tech-plan
 * §11「补录一条更早的记录后，排在它之后的记录『上一次』会变化，重算这些记录」。 计划外随访不作对比对象（候选）但自身照常重算； 上一次为空跳过对比类规则（E17-T03
 * 求值口径）。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class CompareHighlightServiceTest {

    private final CompareFeatureMapper features = mock(CompareFeatureMapper.class);
    private final CompareRuleMapper rules = mock(CompareRuleMapper.class);
    private final CompareSourceMapper sourceMapper = mock(CompareSourceMapper.class);
    private final CompareHighlightMapper highlights = mock(CompareHighlightMapper.class);
    private final FormService formService = mock(FormService.class);

    /** 链定序与基线回落复用 E17-T02 的真实实现（只 mock 其 Mapper）。 */
    private final CompareSourceService sourceService =
            new CompareSourceService(features, rules, sourceMapper);

    private final CompareHighlightService service =
            new CompareHighlightService(features, rules, sourceService, highlights, formService);

    private static final long PATIENT = 301L;
    private static final String FORM = "A_ANNUAL_FU";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void featureDefaultsOn() {
        lenient().when(features.findEnabled("compare_highlight")).thenReturn(null);
    }

    // ---------------------------------------------------------------- 数据

    /** 种子规则行（迁移 V67 同形）：EF 下降 ≥ 10 个百分点，回落基线 f102。 */
    private static CompareRuleRow efDropRow(boolean enabled) {
        return new CompareRuleRow(
                11L,
                FORM,
                "年度随访记录",
                "ef_drop",
                "射血分数下降",
                "fu_echo",
                "f248",
                "EF 射血分数",
                "%",
                "DIFF",
                "{\"direction\":\"DROP\",\"threshold\":10}",
                "A_BASELINE",
                "f102",
                "出院前最后一次EF",
                "EF 比上次（或出院前）下降 ≥ 10 个百分点",
                10,
                enabled ? "ENABLED" : "DISABLED",
                7L,
                LocalDateTime.of(2027, 5, 17, 10, 0));
    }

    /** 已提交记录行（TASK 行 visitDate 空由任务计划日期定序）。 */
    private static SubmittedRow row(
            long id, String source, LocalDate visitDate, Long taskId, String data) {
        return new SubmittedRow(id, PATIENT, source, visitDate, taskId, 1, data);
    }

    private static SubmittedRow backfill(long id, LocalDate visitDate, String data) {
        return row(id, "BACKFILL", visitDate, null, data);
    }

    /** 基线分节行（出院前超声，f102 = 出院前最后一次 EF）。 */
    private static CompareSourceMapper.BaselineSectionRow baseline(String data) {
        return new CompareSourceMapper.BaselineSectionRow("echo", data);
    }

    /** 序数规则行（返流加重 ≥ 2 级，无基线回落）。 */
    private static CompareRuleRow ordinalRow() {
        return new CompareRuleRow(
                12L,
                FORM,
                "年度随访记录",
                "mr_rise",
                "瓣膜返流加重",
                "fu_echo",
                "f239",
                "二尖瓣返流程度",
                null,
                "ORDINAL_RISE",
                "{\"grades\":2}",
                null,
                null,
                null,
                "返流程度升高 ≥ 2 级",
                20,
                "ENABLED",
                7L,
                LocalDateTime.of(2027, 5, 17, 10, 0));
    }

    /** 模板 schema（f239 选项 1=轻度序数 1、4=重度序数 3，fe_option.ordinal 口径）。 */
    private static FormService.SchemaView schemaWithOrdinals() {
        FormService.OptionItemView mild =
                new FormService.OptionItemView(1, "轻度（示例）", 1, false, false, false);
        FormService.OptionItemView severe =
                new FormService.OptionItemView(4, "重度（示例）", 3, false, false, false);
        FormService.FieldView field =
                new FormService.FieldView(
                        "f239",
                        "mr",
                        "二尖瓣返流程度",
                        "SINGLE",
                        null,
                        null,
                        null,
                        null,
                        List.of(mild, severe),
                        false,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null);
        FormService.CategoryView category =
                new FormService.CategoryView("fu_echo", "心脏超声", 1, List.of(field));
        return new FormService.SchemaView(
                FORM, "年度随访记录（示例）", "FOLLOWUP", 1, List.of(category), List.of());
    }

    // ---------------------------------------------------------------- 提交重算（持久化）

    /** 第 1 年（无更早记录）回落基线：EF 55 → 44 命中 ef_drop，highlight 存命中数组（同前端 CompareHit 形）。 */
    @Test
    void submitPersistsHitAgainstBaselineFallback() throws Exception {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(List.of(row(801L, "TASK", null, 900L, "{\"f248\":44}")));
        when(highlights.findTaskPlanDates(List.of(900L)))
                .thenReturn(
                        List.of(
                                new CompareSourceMapper.TaskPlanRow(
                                        900L, LocalDate.of(2026, 11, 5))));
        when(sourceMapper.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));

        int recalculated = service.refreshSubmittedRecords(PATIENT, FORM);

        assertEquals(1, recalculated);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(highlights).updateHighlight(eq(801L), stored.capture());
        JsonNode hit = JSON.readTree(stored.getValue()).get(0);
        assertEquals("ef_drop", hit.path("ruleId").asText());
        assertEquals("f248", hit.path("key").asText());
        assertEquals(55, hit.path("previousValue").asDouble());
        assertEquals(-11.0, hit.path("change").asDouble());
        assertEquals("下降 11 个百分点", hit.path("changeText").asText());
    }

    /** 不命中（下降 5 < 10）→ highlight 置 NULL（不存空数组）。 */
    @Test
    void noHitStoresNullHighlight() {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(List.of(backfill(801L, LocalDate.of(2026, 11, 5), "{\"f248\":50}")));
        when(sourceMapper.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));

        assertEquals(1, service.refreshSubmittedRecords(PATIENT, FORM));
        verify(highlights).updateHighlight(801L, null);
    }

    /** 无已提交记录（防御）→ 不写库。 */
    @Test
    void noSubmittedRecordsIsNoop() {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM)).thenReturn(List.of());

        assertEquals(0, service.refreshSubmittedRecords(PATIENT, FORM));
        verify(highlights, never()).updateHighlight(anyLong(), any());
    }

    // ---------------------------------------------------------------- 补录更早记录 →
    // 排在后面的记录换上一次（tech-plan §11）

    /**
     * 补录一条更早的记录后，晚于它的记录「上一次」从基线变为该补录记录：EF 基线 55 / 补录 60 / 本次 44， 本次命中 previousValue = 60（下降
     * 16）；补录记录自身回落基线（60 对 55 上升，不命中 → NULL）。
     */
    @Test
    void backfilledEarlierRecordShiftsPreviousOfLaterOnes() throws Exception {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(
                        List.of(
                                backfill(790L, LocalDate.of(2025, 6, 1), "{\"f248\":60}"),
                                row(801L, "TASK", null, 900L, "{\"f248\":44}")));
        when(highlights.findTaskPlanDates(List.of(900L)))
                .thenReturn(
                        List.of(
                                new CompareSourceMapper.TaskPlanRow(
                                        900L, LocalDate.of(2026, 11, 5))));
        when(sourceMapper.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));

        assertEquals(2, service.refreshSubmittedRecords(PATIENT, FORM));

        verify(highlights).updateHighlight(eq(790L), isNull());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(highlights).updateHighlight(eq(801L), stored.capture());
        assertEquals(60, JSON.readTree(stored.getValue()).get(0).path("previousValue").asDouble());
    }

    /** 计划外随访不作对比对象（不进候选链），但自身照常重算（回落基线 / 更早的年度记录）。 */
    @Test
    void unplannedRecordsRecomputedButNeverUsedAsPrevious() throws Exception {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(
                        List.of(
                                row(
                                        800L,
                                        "UNPLANNED",
                                        LocalDate.of(2026, 6, 1),
                                        null,
                                        "{\"f248\":50}"),
                                row(801L, "TASK", null, 900L, "{\"f248\":44}")));
        when(highlights.findTaskPlanDates(List.of(900L)))
                .thenReturn(
                        List.of(
                                new CompareSourceMapper.TaskPlanRow(
                                        900L, LocalDate.of(2026, 11, 5))));
        when(sourceMapper.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));

        assertEquals(2, service.refreshSubmittedRecords(PATIENT, FORM));

        // 801 的上一次 = 基线 55（计划外 800 不作对象），下降 11 命中；800 自身对基线上升 5 不命中
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(highlights).updateHighlight(eq(801L), stored.capture());
        assertEquals(55, JSON.readTree(stored.getValue()).get(0).path("previousValue").asDouble());
        verify(highlights).updateHighlight(eq(800L), isNull());
    }

    /** 序数规则按模板选项序数求值（fe_option.ordinal：1=序数 1 → 4=序数 3，升高 2 级）。 */
    @Test
    void ordinalRiseRuleUsesSchemaOrdinals() throws Exception {
        when(rules.findAll(FORM)).thenReturn(List.of(ordinalRow()));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(
                        List.of(
                                backfill(701L, LocalDate.of(2025, 6, 1), "{\"f239\":1}"),
                                backfill(801L, LocalDate.of(2026, 11, 5), "{\"f239\":4}")));
        when(formService.schema(FORM, 1)).thenReturn(schemaWithOrdinals());

        assertEquals(2, service.refreshSubmittedRecords(PATIENT, FORM));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(highlights).updateHighlight(eq(801L), stored.capture());
        JsonNode hit = JSON.readTree(stored.getValue()).get(0);
        assertEquals("mr_rise", hit.path("ruleId").asText());
        assertEquals(2.0, hit.path("change").asDouble());
        assertEquals("升高 2 级", hit.path("changeText").asText());
        // 最早一条无上一次且序数规则无基线回落 → 跳过（NULL）
        verify(highlights).updateHighlight(eq(701L), isNull());
    }

    // ---------------------------------------------------------------- 规则变更重算（验收位）

    /** 规则全部停用后重算 → 该表单已提交记录的 highlight 全部清除（NULL）。 */
    @Test
    void disabledRulesClearPersistedHighlights() {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(false)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(List.of(backfill(801L, LocalDate.of(2026, 11, 5), "{\"f248\":44}")));

        assertEquals(1, service.refreshSubmittedRecords(PATIENT, FORM));
        verify(highlights).updateHighlight(801L, null);
    }

    /** recalculateForm 按患者游标分页遍历（内存有界），逐患者重算。 */
    @Test
    void recalculateFormWalksPatientsByCursor() {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedPatients(FORM, 0L, 200)).thenReturn(List.of(301L));
        when(highlights.findSubmittedPatients(FORM, 301L, 200)).thenReturn(List.of(302L));
        when(highlights.findSubmittedPatients(FORM, 302L, 200)).thenReturn(List.of());
        when(highlights.findSubmittedByPatient(301L, FORM))
                .thenReturn(List.of(backfill(801L, LocalDate.of(2026, 11, 5), "{\"f248\":44}")));
        when(highlights.findSubmittedByPatient(302L, FORM))
                .thenReturn(List.of(backfill(811L, LocalDate.of(2026, 10, 1), "{\"f248\":50}")));
        when(sourceMapper.findBaselineSections(301L, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));
        when(sourceMapper.findBaselineSections(302L, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));

        int recalculated = service.recalculateForm(FORM);

        assertEquals(2, recalculated);
        verify(highlights).updateHighlight(eq(801L), notNull());
        verify(highlights).updateHighlight(eq(811L), isNull());
        verify(highlights).findSubmittedPatients(FORM, 302L, 200);
    }

    /** 规则变更重算覆盖全量已提交记录：阈值收紧后原命中记录不再命中（NULL）。 */
    @Test
    void recalculateFormAppliesNewThreshold() {
        when(rules.findAll(FORM))
                .thenReturn(
                        List.of(
                                new CompareRuleRow(
                                        11L,
                                        FORM,
                                        "年度随访记录",
                                        "ef_drop",
                                        "射血分数下降",
                                        "fu_echo",
                                        "f248",
                                        "EF 射血分数",
                                        "%",
                                        "DIFF",
                                        "{\"direction\":\"DROP\",\"threshold\":20}",
                                        "A_BASELINE",
                                        "f102",
                                        "出院前最后一次EF",
                                        "EF 比上次（或出院前）下降 ≥ 20 个百分点",
                                        10,
                                        "ENABLED",
                                        7L,
                                        LocalDateTime.of(2027, 5, 24, 9, 0))));
        when(highlights.findSubmittedPatients(FORM, 0L, 200)).thenReturn(List.of(301L));
        when(highlights.findSubmittedPatients(FORM, 301L, 200)).thenReturn(List.of());
        when(highlights.findSubmittedByPatient(301L, FORM))
                .thenReturn(List.of(backfill(801L, LocalDate.of(2026, 11, 5), "{\"f248\":44}")));
        when(sourceMapper.findBaselineSections(301L, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f102\":55}")));

        service.recalculateForm(FORM);

        // 下降 11 < 新阈值 20 → 不再命中，清除旧值
        verify(highlights).updateHighlight(801L, null);
    }

    // ---------------------------------------------------------------- 基线写入后的依赖重算

    /** 报告单写入基线分节后：只重算「启用规则回落该基线表单」的随访表单（停用规则不参与）。 */
    @Test
    void baselineDependentsRecomputeOnlyFormsReferencingTheBaselineForm() {
        CompareRuleRow disabledOtherForm =
                new CompareRuleRow(
                        21L,
                        "C_PROGRAM_FU",
                        "程控随访（示例）",
                        "c_threshold",
                        "阈值升高（示例）",
                        "fu_measure",
                        "f301",
                        "起搏阈值（示例）",
                        "V",
                        "CHANGE_PCT",
                        "{\"direction\":\"RISE\",\"percent\":50}",
                        "A_BASELINE",
                        "f199",
                        "示例回落字段",
                        "示例提示",
                        10,
                        "DISABLED",
                        7L,
                        LocalDateTime.of(2027, 5, 17, 10, 0));
        when(rules.findAll(null)).thenReturn(List.of(efDropRow(true), disabledOtherForm));
        when(highlights.findSubmittedByPatient(PATIENT, FORM)).thenReturn(List.of());

        assertEquals(0, service.refreshBaselineDependents(PATIENT, "A_BASELINE"));

        verify(highlights).findSubmittedByPatient(PATIENT, FORM);
        verify(highlights, never()).findSubmittedByPatient(PATIENT, "C_PROGRAM_FU");
    }

    /** 功能开关关闭 → 三个入口都不重算（highlight 本应为 NULL，不做无谓扫描）。 */
    @Test
    void featureOffSkipsAllRecalculation() {
        when(features.findEnabled("compare_highlight")).thenReturn(false);

        assertEquals(0, service.refreshSubmittedRecords(PATIENT, FORM));
        assertEquals(0, service.recalculateForm(FORM));
        assertEquals(0, service.refreshBaselineDependents(PATIENT, "A_BASELINE"));

        verifyNoInteractions(highlights);
        verifyNoInteractions(sourceMapper);
    }

    /** 上一次该字段为空跳过（E17-T03 口径）：基线缺 f102 → 不命中 → NULL。 */
    @Test
    void missingBaselineValueSkipsRule() {
        when(rules.findAll(FORM)).thenReturn(List.of(efDropRow(true)));
        when(highlights.findSubmittedByPatient(PATIENT, FORM))
                .thenReturn(List.of(backfill(801L, LocalDate.of(2026, 11, 5), "{\"f248\":44}")));
        when(sourceMapper.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(baseline("{\"f103\":52}")));

        service.refreshSubmittedRecords(PATIENT, FORM);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(highlights).updateHighlight(eq(801L), stored.capture());
        assertNull(stored.getValue(), "无命中不存数组");
    }
}
