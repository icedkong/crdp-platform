package com.crdp.followup.compare.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crdp.followup.compare.infra.mapper.CompareFeatureMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleRow;
import com.crdp.followup.compare.infra.mapper.CompareSourceMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E17-T02 / A-FU-027：上一次记录解析单元测试。
 *
 * <p>验收「第 1 年与基线对比」：此前无已提交随访时回落基线分节字段（出院前超声 f102 / f103）； 有上一次已提交随访时取「对比时点早于本次、最近的一条」，
 * 值只取启用规则引用的键；功能开关关闭或无启用规则不解析。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class CompareSourceServiceTest {

    private final CompareFeatureMapper features = mock(CompareFeatureMapper.class);
    private final CompareRuleMapper rules = mock(CompareRuleMapper.class);
    private final CompareSourceMapper source = mock(CompareSourceMapper.class);

    private final CompareSourceService service = new CompareSourceService(features, rules, source);

    private static final long PATIENT = 301L;
    private static final long RECORD = 801L;
    private static final String FORM = "A_ANNUAL_FU";

    @BeforeEach
    void featureDefaultsOn() {
        lenient().when(features.findEnabled("compare_highlight")).thenReturn(null);
    }

    /** 种子规则行（迁移 V67 同形）：EF 下降（回落基线 f102）/ LVDd 增加（回落基线 f103）。 */
    private static CompareRuleRow rule(String ruleCode, String targetField, boolean enabled) {
        return new CompareRuleRow(
                11L,
                FORM,
                "年度随访记录",
                ruleCode,
                ruleCode,
                "fu_echo",
                targetField,
                "示例字段",
                "%",
                "DIFF",
                "{\"direction\":\"DROP\",\"threshold\":10}",
                "A_BASELINE",
                baselineFieldOf(ruleCode),
                "出院前示例字段",
                "示例提示",
                10,
                enabled ? "ENABLED" : "DISABLED",
                7L,
                LocalDateTime.of(2027, 5, 17, 10, 0));
    }

    private static String baselineFieldOf(String ruleCode) {
        return "ef_drop".equals(ruleCode) ? "f102" : "f103";
    }

    /** 已提交随访候选行（TASK 候选带 taskId，计划日期走批量补查）。 */
    private static CompareSourceMapper.CandidateRow candidate(
            long id, String source, LocalDate visitDate, Long taskId, String data) {
        return new CompareSourceMapper.CandidateRow(id, source, visitDate, taskId, data);
    }

    /** 基线分节行（出院前超声分类 echo，键 = 稳定键）。 */
    private static CompareSourceMapper.BaselineSectionRow section(String data) {
        return new CompareSourceMapper.BaselineSectionRow("echo", data);
    }

    /** 验收位：第 1 年（无已提交随访）回落基线出院前超声字段。 */
    @Test
    void firstYearFallsBackToBaselineEcho() {
        when(rules.findAll(FORM)).thenReturn(List.of(rule("ef_drop", "f248", true)));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM)).thenReturn(List.of());
        when(source.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(section("{\"f102\":55,\"f103\":52,\"f250\":3}")));

        CompareSourceService.PreviousView previous =
                service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5));

        assertEquals("BASELINE", previous.kind(), "第 1 年与基线对比（A-FU-027）");
        assertNull(previous.recordId());
        assertEquals(Map.of("f102", 55), previous.values(), "只取规则回落基线字段的值");
    }

    /** 多条基线回落规则合并取值（EF f102 + LVDd f103，各自独立成列）。 */
    @Test
    void baselineFallbackMergesAllRuleFields() {
        when(rules.findAll(FORM))
                .thenReturn(
                        List.of(rule("ef_drop", "f248", true), rule("lvdd_rise", "f249", true)));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM)).thenReturn(List.of());
        when(source.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(section("{\"f102\":55,\"f103\":52}")));

        CompareSourceService.PreviousView previous =
                service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5));

        assertEquals("BASELINE", previous.kind());
        assertEquals(Map.of("f102", 55, "f103", 52), previous.values());
    }

    /** 有上一次已提交随访：取该记录，值只带启用规则引用的键（白名单，不整份下发）。 */
    @Test
    void previousFollowupWinsOverBaseline() {
        when(rules.findAll(FORM)).thenReturn(List.of(rule("ef_drop", "f248", true)));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM))
                .thenReturn(
                        List.of(
                                candidate(
                                        700L,
                                        "TASK",
                                        null,
                                        900L,
                                        "{\"f248\":44,\"f249\":58,\"f222\":\"无关值\"}")));
        when(source.findTaskPlanDates(List.of(900L)))
                .thenReturn(
                        List.of(
                                new CompareSourceMapper.TaskPlanRow(
                                        900L, LocalDate.of(2025, 11, 5))));
        when(source.findTaskPlanDate(RECORD)).thenReturn(LocalDate.of(2026, 11, 5));

        CompareSourceService.PreviousView previous = service.resolve(RECORD, PATIENT, FORM, null);

        assertEquals("FOLLOWUP", previous.kind());
        assertEquals("700", previous.recordId());
        assertEquals("TASK", previous.source());
        assertEquals(LocalDate.of(2025, 11, 5), previous.visitDate(), "TASK 记录对比时点 = 计划日期");
        assertEquals(Map.of("f248", 44), previous.values(), "只取规则引用键");
        verify(source, never()).findBaselineSections(PATIENT, "A_BASELINE");
    }

    /** 多条候选取「早于本次、最近的一条」；更早的不取、更晚的不取。 */
    @Test
    void picksNearestEarlierRecord() {
        when(rules.findAll(FORM)).thenReturn(List.of(rule("ef_drop", "f248", true)));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM))
                .thenReturn(
                        List.of(
                                candidate(
                                        700L,
                                        "BACKFILL",
                                        LocalDate.of(2024, 6, 1),
                                        null,
                                        "{\"f248\":50}"),
                                candidate(710L, "TASK", null, 910L, "{\"f248\":41}"),
                                candidate(
                                        720L,
                                        "BACKFILL",
                                        LocalDate.of(2027, 6, 1),
                                        null,
                                        "{\"f248\":30}")));
        when(source.findTaskPlanDates(List.of(910L)))
                .thenReturn(
                        List.of(
                                new CompareSourceMapper.TaskPlanRow(
                                        910L, LocalDate.of(2026, 6, 1))));
        when(source.findTaskPlanDate(RECORD)).thenReturn(LocalDate.of(2026, 11, 5));

        CompareSourceService.PreviousView previous = service.resolve(RECORD, PATIENT, FORM, null);

        assertEquals("FOLLOWUP", previous.kind());
        assertEquals("710", previous.recordId(), "取对比时点早于本次且最近的一条");
        assertEquals(LocalDate.of(2026, 6, 1), previous.visitDate());
        assertEquals(Map.of("f248", 41), previous.values());
    }

    /** 同对比时点按 id 定序：id 更小的算上一次，id 更大的不算（补录同日多次的确定口径）。 */
    @Test
    void sameDateBreaksTieById() {
        when(rules.findAll(FORM)).thenReturn(List.of(rule("ef_drop", "f248", true)));
        LocalDate same = LocalDate.of(2026, 11, 5);
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM))
                .thenReturn(
                        List.of(
                                candidate(790L, "BACKFILL", same, null, "{\"f248\":40}"),
                                candidate(810L, "BACKFILL", same, null, "{\"f248\":31}")));
        when(source.findTaskPlanDate(RECORD)).thenReturn(same);

        CompareSourceService.PreviousView previous = service.resolve(RECORD, PATIENT, FORM, null);

        assertEquals("790", previous.recordId(), "同日按 id 小者为上一次");
    }

    /** 新发事件规则引用的日期字段也在取值白名单内（「日期晚于上一次随访」判定用）。 */
    @Test
    void newEventDateFieldIncludedInValues() {
        CompareRuleRow event =
                new CompareRuleRow(
                        12L,
                        FORM,
                        "年度随访记录",
                        "rehospital",
                        "再住院",
                        "fu_event",
                        "f223",
                        "再住院类型",
                        null,
                        "NEW_EVENT",
                        "{\"dateField\":\"f224\"}",
                        null,
                        null,
                        null,
                        "示例提示",
                        20,
                        "ENABLED",
                        7L,
                        LocalDateTime.of(2027, 5, 17, 10, 0));
        when(rules.findAll(FORM)).thenReturn(List.of(event));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM))
                .thenReturn(
                        List.of(
                                candidate(
                                        700L,
                                        "BACKFILL",
                                        LocalDate.of(2025, 6, 1),
                                        null,
                                        "{\"f223\":2,\"f224\":\"2025-05-01\",\"f248\":44}")));

        CompareSourceService.PreviousView previous =
                service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5));

        assertEquals("FOLLOWUP", previous.kind());
        assertEquals(Map.of("f223", 2, "f224", "2025-05-01"), previous.values());
    }

    /** 无已提交随访且基线分节缺回落字段（或无分节）→ previous 为空（规则求值侧逐条跳过）。 */
    @Test
    void missingBaselineValuesYieldsNull() {
        when(rules.findAll(FORM)).thenReturn(List.of(rule("ef_drop", "f248", true)));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM)).thenReturn(List.of());
        when(source.findBaselineSections(PATIENT, "A_BASELINE"))
                .thenReturn(List.of(section("{\"f250\":3}")));

        assertNull(service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5)));

        when(source.findBaselineSections(PATIENT, "A_BASELINE")).thenReturn(List.of());
        assertNull(service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5)));
    }

    /** 规则全部停用 → 不解析（previous 为空，规则开关即时生效）。 */
    @Test
    void disabledRulesSkipResolution() {
        when(rules.findAll(FORM)).thenReturn(List.of(rule("ef_drop", "f248", false)));

        assertNull(service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5)));
    }

    /** 功能开关 compare_highlight 关闭 → 不解析（记录页正常返回，previous 缺省）。 */
    @Test
    void featureOffSkipsResolution() {
        when(features.findEnabled("compare_highlight")).thenReturn(false);

        assertNull(service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5)));
        verifyNoInteractions(rules);
        verifyNoInteractions(source);
    }

    /** 无回落基线列的规则不参与基线取值（新发事件只对比上一次随访）。 */
    @Test
    void rulesWithoutBaselineColumnsDoNotSeedBaseline() {
        CompareRuleRow death =
                new CompareRuleRow(
                        13L,
                        FORM,
                        "年度随访记录",
                        "death_yes",
                        "新发死亡",
                        "fu_contact",
                        "f215",
                        "是否死亡",
                        null,
                        "FLIP_TO_YES",
                        "{}",
                        null,
                        null,
                        null,
                        "示例提示",
                        30,
                        "ENABLED",
                        7L,
                        LocalDateTime.of(2027, 5, 17, 10, 0));
        when(rules.findAll(FORM)).thenReturn(List.of(death));
        when(source.findSubmittedCandidates(PATIENT, RECORD, FORM)).thenReturn(List.of());

        assertNull(
                service.resolve(RECORD, PATIENT, FORM, LocalDate.of(2026, 11, 5)),
                "无上一次随访且规则无基线回落 → previous 为空");
    }
}
