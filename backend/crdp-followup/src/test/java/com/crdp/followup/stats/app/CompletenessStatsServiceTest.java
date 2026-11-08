package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.stats.infra.mapper.CompletenessStatsMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FormProgressEngine.FieldProgress;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * E19-T05 / A-DASH-010、A-DASH-011 服务层单测（口径 Q39 / Q40）：聚合口径由 {@link
 * com.crdp.followup.stats.domain.CompletenessAggregatorTest} 锁定，本测试锁定装配——
 * 患者全集与分节同条件树、多分节按键合并、未保存分节按 0 计入；随访记录按 (formCode, templateVersion) 分组批量求值、 时间范围按实际随访日期（表单字段
 * followup_date 稳定键）过滤；钻取 ID 集与卡片同一计算；参数校验 40001。全部虚构数据。
 */
class CompletenessStatsServiceTest {

    private final CompletenessStatsMapper mapper = mock(CompletenessStatsMapper.class);
    private final FormService formService = mock(FormService.class);

    private final CompletenessStatsService service =
            new CompletenessStatsService(mapper, formService);

    private static FormService.FieldView fieldView(String key, String code) {
        return new FormService.FieldView(
                key,
                code,
                key + "（示例）",
                "text",
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                false,
                "json",
                null);
    }

    private static FormService.SchemaView schema(FormService.CategoryView... categories) {
        return new FormService.SchemaView(
                "A_BASELINE", "基线档案（示例）", "BASELINE", 1, List.of(categories), List.of());
    }

    /** 字段级求值桩：按单位 data 的键是否非空出 f1 / f2 两个应填字段（顺序 = 患者全集序）。 */
    private void stubBaselineBatch() {
        Mockito.lenient().when(formService.publishedVersion("A_BASELINE")).thenReturn(1);
        Mockito.lenient()
                .when(formService.fieldProgressBatch(eq("A_BASELINE"), eq(1), anyList(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> datas = invocation.getArgument(2);
                            return datas.stream()
                                    .map(
                                            data ->
                                                    List.of(
                                                            new FieldProgress(
                                                                    "f1",
                                                                    "字段一（示例）",
                                                                    "basic",
                                                                    filled(data, "f1")),
                                                            new FieldProgress(
                                                                    "f2",
                                                                    "字段二（示例）",
                                                                    "basic",
                                                                    filled(data, "f2"))))
                                    .toList();
                        });
        Mockito.lenient()
                .when(formService.schema(eq("A_BASELINE"), any()))
                .thenReturn(schema(new FormService.CategoryView("basic", "基本信息", 10, List.of())));
    }

    private static boolean filled(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null && !"".equals(value);
    }

    // ---------------------------------------------------------------- 基线卡（A-DASH-010）

    @Test
    void baselineCardMergesSectionsAndCountsPatientsWithoutSectionsAsZero() {
        stubBaselineBatch();
        when(mapper.selectBaselinePatientIds(null, null, "p.discharge_date", null, null))
                .thenReturn(List.of(1L, 2L, 3L));
        when(mapper.selectBaselineSections(null, null, "p.discharge_date", null, null))
                .thenReturn(
                        List.of(
                                // 患者一分两个分类分节保存（多行按键合并），患者二从未保存（0 计入），患者三只填 f1
                                new CompletenessStatsMapper.SectionRow(
                                        1L, "{\"f1\":\"已答\",\"f2\":\"已答\"}"),
                                new CompletenessStatsMapper.SectionRow(3L, "{\"f1\":\"已答\"}")));
        when(mapper.selectPatientTypes())
                .thenReturn(
                        List.of(
                                new CompletenessStatsMapper.PatientTypeRow("ELECTIVE"),
                                new CompletenessStatsMapper.PatientTypeRow("URGENT")));

        CompletenessStatsService.BaselineCardView view =
                service.baselineCard(
                        new CompletenessStatsService.Query(null, null, null, null, null, null));

        assertEquals(3, view.patientCount(), "分母 = 范围内全部患者（未保存分节的按 0 计入）");
        assertEquals(50.0, view.averagePct(), "患者值 100 / 0 / 50 → 平均 50.0");
        assertEquals(50.0, view.medianPct());
        assertEquals(
                List.of(1L, 0L, 0L, 1L, 1L),
                view.distribution().stream().map(item -> item.count()).toList(),
                "五段分布：100 ×1、50–69 ×1、<50 ×1");
        assertEquals(1, view.categories().size());
        assertEquals("基本信息", view.categories().get(0).label());
        assertEquals(50.0, view.categories().get(0).averagePct());
        assertEquals(
                List.of("f2", "f1"),
                view.topMissing().stream().map(item -> item.fieldKey()).toList(),
                "最常缺失字段按缺失人数降序");
        assertEquals(2, view.topMissing().get(0).missingCount());
        assertEquals(66.7, view.topMissing().get(0).missingRatePct());
        assertEquals(
                List.of("ELECTIVE", "URGENT"),
                view.patientTypes().stream().map(item -> item.code()).toList());
        assertEquals("DISCHARGE", view.timeField(), "基线时间维度缺省 = 出院日期");
    }

    @Test
    void baselineDrillReturnsPatientsMissingTheField() {
        stubBaselineBatch();
        when(mapper.selectBaselinePatientIds(null, null, "p.discharge_date", null, null))
                .thenReturn(List.of(1L, 2L, 3L));
        when(mapper.selectBaselineSections(null, null, "p.discharge_date", null, null))
                .thenReturn(
                        List.of(
                                new CompletenessStatsMapper.SectionRow(1L, "{\"f1\":\"已答\"}"),
                                new CompletenessStatsMapper.SectionRow(3L, "{\"f1\":\"已答\"}")));

        List<Long> missing =
                service.patientsMissingBaselineField(
                        "f1",
                        new CompletenessStatsService.Query(null, null, null, null, null, null));

        assertEquals(List.of(2L), missing, "只有患者二缺 f1（与卡片 topMissing 的缺失人数同一计算）");
    }

    @Test
    void baselineCardEmptyScopeReturnsZeroView() {
        stubBaselineBatch();
        when(mapper.selectBaselinePatientIds(null, null, "p.discharge_date", null, null))
                .thenReturn(List.of());
        when(mapper.selectBaselineSections(null, null, "p.discharge_date", null, null))
                .thenReturn(List.of());

        CompletenessStatsService.BaselineCardView view =
                service.baselineCard(
                        new CompletenessStatsService.Query(null, null, null, null, null, null));

        assertEquals(0, view.patientCount());
        assertEquals(0.0, view.averagePct());
        assertEquals(0, view.topMissing().size());
        assertEquals(0, view.categories().size());
    }

    // ---------------------------------------------------------------- 随访卡（A-DASH-011 / Q40）

    private static CompletenessStatsMapper.AnnualRecordRow record(
            long recordId, long patientId, Integer anchorYear, LocalDate visitDate, String data) {
        return new CompletenessStatsMapper.AnnualRecordRow(
                recordId,
                patientId,
                "A_ANNUAL_FU",
                1,
                data,
                visitDate,
                LocalDate.of(2026, 3, 8),
                anchorYear);
    }

    /** 随访字段级求值桩：g1 / g2 两个应填字段。 */
    private void stubFollowupBatch() {
        Mockito.lenient()
                .when(formService.fieldProgressBatch(eq("A_ANNUAL_FU"), eq(1), anyList(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> datas = invocation.getArgument(2);
                            return datas.stream()
                                    .map(
                                            data ->
                                                    List.of(
                                                            new FieldProgress(
                                                                    "g1",
                                                                    "随访一（示例）",
                                                                    "fu_contact",
                                                                    filled(data, "g1")),
                                                            new FieldProgress(
                                                                    "g2",
                                                                    "随访二（示例）",
                                                                    "fu_contact",
                                                                    filled(data, "g2"))))
                                    .toList();
                        });
        Mockito.lenient()
                .when(formService.schema(eq("A_ANNUAL_FU"), eq(1)))
                .thenReturn(
                        schema(
                                new FormService.CategoryView(
                                        "fu_contact",
                                        "联系与生存",
                                        10,
                                        List.of(fieldView("f214", "followup_date")))));
    }

    @Test
    void followupCardAggregatesByPatientAndYear() {
        stubFollowupBatch();
        when(mapper.selectAnnualRecords(null, null))
                .thenReturn(
                        List.of(
                                // 患者一第 1、2 年（100 / 50 → 患者 75.0），患者二第 1 年（100）→ 卡片平均 87.5
                                record(
                                        11L,
                                        1L,
                                        1,
                                        null,
                                        "{\"g1\":\"a\",\"g2\":\"b\",\"f214\":\"2026-03-10\"}"),
                                record(12L, 1L, 2, null, "{\"g1\":\"a\",\"f214\":\"2027-03-10\"}"),
                                record(
                                        13L,
                                        2L,
                                        1,
                                        LocalDate.of(2026, 4, 1),
                                        "{\"g1\":\"a\",\"g2\":\"b\"}")));
        when(mapper.selectPatientTypes()).thenReturn(List.of());

        CompletenessStatsService.FollowupCardView view =
                service.followupCard(
                        new CompletenessStatsService.Query(null, null, null, null, null, null));

        assertEquals(3, view.recordCount(), "已统计记录数 = 计入的已提交年度记录");
        assertEquals(2, view.patientCount());
        assertEquals(87.5, view.averagePct(), "卡片汇总按患者平均（(100+50)/2 与 100 的平均，Q40）");
        assertEquals(2, view.years().size(), "只列有记录的年度");
        assertEquals("第1年", view.years().get(0).label());
        assertEquals(2, view.years().get(0).recordCount());
        assertEquals(100.0, view.years().get(0).averagePct(), "年度行 = 记录级平均（100 + 100）/ 2");
        assertEquals(50.0, view.years().get(1).averagePct(), "第 2 年 = 50（患者一第 2 条记录）");
        assertEquals(
                List.of("g2"),
                view.topMissing().stream().map(item -> item.fieldKey()).toList(),
                "最常缺失字段按缺失记录数降序（g2 缺 1、g1 无缺失不出现）");
        assertEquals(33.3, view.topMissing().get(0).missingRatePct(), "缺失率 = 缺失记录数 ÷ 已统计记录数");
        assertEquals(2, view.yearOptions().size(), "随访年度候选 = 全量记录的年度");
    }

    @Test
    void followupCardFiltersByVisitDateResolvedFromFormField() {
        stubFollowupBatch();
        when(mapper.selectAnnualRecords(null, null))
                .thenReturn(
                        List.of(
                                // 任务录入记录的实际随访日期在表单字段 f214（followup_date）；导入 / 补录在 visit_date 列
                                record(
                                        11L,
                                        1L,
                                        1,
                                        null,
                                        "{\"g1\":\"a\",\"g2\":\"b\",\"f214\":\"2026-03-10\"}"),
                                record(
                                        12L,
                                        2L,
                                        1,
                                        LocalDate.of(2025, 6, 1),
                                        "{\"g1\":\"a\",\"g2\":\"b\"}")));
        when(mapper.selectPatientTypes()).thenReturn(List.of());

        CompletenessStatsService.FollowupCardView view =
                service.followupCard(
                        new CompletenessStatsService.Query(
                                null,
                                null,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 12, 31),
                                null,
                                null));

        assertEquals(1, view.recordCount(), "时间范围默认按实际随访日期（f214 / visit_date），2025 的记录不计入");
        assertEquals(1, view.patientCount());
        assertEquals(List.of(1), view.yearOptions().stream().map(item -> item.year()).toList());
    }

    @Test
    void followupDrillReturnsPatientLevelIds() {
        stubFollowupBatch();
        when(mapper.selectAnnualRecords(null, null))
                .thenReturn(
                        List.of(
                                record(11L, 1L, 1, null, "{\"g1\":\"a\",\"f214\":\"2026-03-10\"}"),
                                record(12L, 2L, 1, null, "{\"f214\":\"2026-03-11\"}"),
                                record(
                                        13L,
                                        3L,
                                        2,
                                        null,
                                        "{\"g1\":\"a\",\"g2\":\"b\",\"f214\":\"2026-03-12\"}")));

        List<Long> missing =
                service.patientsMissingFollowupField(
                        "g2",
                        new CompletenessStatsService.Query(null, null, null, null, null, null));

        assertEquals(List.of(1L, 2L), missing, "患者级：任一计入记录缺该字段即入选（去重、保序）");
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    void rejectsInvalidParameters() {
        ApiException timeField =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.baselineCard(
                                        new CompletenessStatsService.Query(
                                                null, null, null, null, "SIDEWAYS", null)));
        assertEquals(40001, timeField.code());

        ApiException followupTimeField =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.followupCard(
                                        new CompletenessStatsService.Query(
                                                null, null, null, null, "REGISTERED", null)));
        assertEquals(40001, followupTimeField.code(), "随访卡时间维度只有 VISIT / DISCHARGE");

        ApiException range =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.baselineCard(
                                        new CompletenessStatsService.Query(
                                                null,
                                                null,
                                                LocalDate.of(2026, 6, 30),
                                                LocalDate.of(2026, 1, 1),
                                                null,
                                                null)));
        assertEquals(40001, range.code(), "from 晚于 to 40001");

        ApiException ward =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.followupCard(
                                        new CompletenessStatsService.Query(
                                                0L, null, null, null, null, null)));
        assertEquals(40001, ward.code());

        ApiException year =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.followupCard(
                                        new CompletenessStatsService.Query(
                                                null, null, null, null, null, 0)));
        assertEquals(40001, year.code(), "随访年度从 1 起");
    }

    @Test
    void rejectsHalfRange() {
        ApiException half =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.baselineCard(
                                        new CompletenessStatsService.Query(
                                                null,
                                                null,
                                                LocalDate.of(2026, 1, 1),
                                                null,
                                                null,
                                                null)));
        assertEquals(40001, half.code(), "from / to 须成对提供");
        assertThrows(
                ApiException.class,
                () ->
                        service.patientsMissingBaselineField(
                                " ",
                                new CompletenessStatsService.Query(
                                        null, null, null, null, null, null)),
                "空 fieldKey 40001");
    }
}
