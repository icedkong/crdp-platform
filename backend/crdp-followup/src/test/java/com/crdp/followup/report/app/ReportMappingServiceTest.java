package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.BaselineLockService;
import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.record.app.FollowupRecordService;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T07 单测锁定（全部虚构数据，A-ATT-013、C-SCAN-005）：报告单映射写入的预览（候选、字段、原值 / 新值、冲突提示）与确认（逐项确认覆盖、留痕、审计）。
 *
 * <p>验收原文「映射写入留痕，冲突提示」：留痕 = fe_field_history（来源 REPORT）+ RPT_MAPPING_WRITE 审计； 冲突提示 = 预览 conflict
 * 标记（原值不同）+ 确认只写 fieldKeys（未确认的冲突字段跳过并回告 skippedConflicts）。
 */
@ExtendWith(MockitoExtension.class)
class ReportMappingServiceTest {

    private static final long OPERATOR = 7L;

    private static final long PATIENT = 301L;

    private static final long REPORT = 900L;

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 10, 20);

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final ReportConfigMapper config = mock(ReportConfigMapper.class);
    private final FormService formService = mock(FormService.class);
    private final FollowupRecordService recordService = mock(FollowupRecordService.class);
    private final BaselineSectionService baselineService = mock(BaselineSectionService.class);
    private final BaselineLockService lockService = mock(BaselineLockService.class);
    private final FieldHistoryService fieldHistory = mock(FieldHistoryService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ReportMappingService service =
            new ReportMappingService(
                    mapper,
                    config,
                    formService,
                    recordService,
                    baselineService,
                    lockService,
                    fieldHistory,
                    audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        stubEchoWorld();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 预览：候选与冲突提示（A-ATT-013 流程第 4
    // 步）

    @Test
    void previewListsApplicableMappingsWithConvertedWritesAndConflicts() {
        ReportMappingService.PreviewView preview = service.preview(REPORT);

        assertEquals("900", preview.reportId());
        assertEquals("心脏超声", preview.typeName());
        assertEquals(2, preview.mappings().size(), "位置含基线超声检查分类 + 随访记录 → 基线（术前）与随访两条映射");

        ReportMappingService.MappingOptionView baseline =
                preview.mappings().stream()
                        .filter(item -> "BASELINE".equals(item.targetType()))
                        .findFirst()
                        .orElseThrow();
        assertNull(baseline.blockedReason());
        assertEquals("PREOP_LAST", baseline.phase());
        assertEquals(1, baseline.candidates().size());
        ReportMappingService.CandidateView section = baseline.candidates().get(0);
        assertEquals("55", section.targetRecordId(), "基线目标 = 超声检查分节行");
        assertEquals(5, section.targetVersion());
        assertEquals("echo", section.categoryKey());
        assertEquals("超声检查", section.categoryLabel());
        assertEquals(1, section.conflictCount(), "EF 55 vs 原值 50 冲突");
        ReportMappingService.WriteView ef = writeOf(section, "f083").orElseThrow();
        assertEquals("ITEM", ef.source());
        assertEquals("EF", ef.itemCode());
        assertEquals(55, ef.newValue(), "数值结果转 JSON 数值");
        assertEquals(50, ef.oldValue());
        assertTrue(ef.conflict(), "原值不同 → conflict 提示");
        assertEquals("2026-10-20", writeOf(section, "f079").orElseThrow().newValue(), "报告时间行");
        ReportMappingService.WriteView mr = writeOf(section, "f097").orElseThrow();
        assertEquals(2, mr.newValue(), "选项名「少量」→ 编码 2");
        assertFalse(mr.conflict(), "原值相同不算冲突");

        ReportMappingService.MappingOptionView followup =
                preview.mappings().stream()
                        .filter(item -> "FOLLOWUP_RECORD".equals(item.targetType()))
                        .findFirst()
                        .orElseThrow();
        assertNull(followup.phase());
        assertEquals(1, followup.candidates().size(), "位置里 1 条随访记录 → 1 个候选");
        ReportMappingService.CandidateView record = followup.candidates().get(0);
        assertEquals("88", record.linkRecordId());
        assertEquals("88", record.targetRecordId());
        assertEquals(3, record.targetVersion());
        assertEquals("第2年随访 2026-06-10", record.recordLabel());
        assertTrue(writeOf(record, "f248").orElseThrow().conflict(), "f248 原值 50 vs 55");
        assertEquals(1, writeOf(record, "f236").orElseThrow().newValue(), "映射级附带写入：复查超声置为是");
        assertEquals("EXTRA", writeOf(record, "f236").orElseThrow().source());
        assertEquals(1, writeOf(record, "f238").orElseThrow().newValue(), "项目级附带写入：有无二尖瓣返流置为有");
        assertEquals("2026-10-20", writeOf(record, "f237").orElseThrow().newValue());
    }

    @Test
    void previewOmitsMappingsWhoseTargetIsNotAmongLocations() {
        // 位置只有基线超声检查分类 → 随访映射不适用（映射目标须是位置之一）
        when(mapper.linksOfReport(REPORT))
                .thenReturn(List.of(link("baseline", null, "category", "echo", 3)));
        List<ReportMappingService.MappingOptionView> baselineOnly =
                service.preview(REPORT).mappings();
        assertEquals(1, baselineOnly.size());
        assertEquals("BASELINE", baselineOnly.get(0).targetType(), "随访映射被省略");

        // 位置只有随访记录 → 基线映射不适用
        when(mapper.linksOfReport(REPORT))
                .thenReturn(List.of(link("followup_record", 88L, null, null, 2)));
        List<ReportMappingService.MappingOptionView> followupOnly =
                service.preview(REPORT).mappings();
        assertEquals(1, followupOnly.size());
        assertEquals("FOLLOWUP_RECORD", followupOnly.get(0).targetType(), "基线映射被省略");
    }

    @Test
    void previewBlocksBaselineMappingOnReportDateBounds() {
        when(mapper.patientDates(PATIENT))
                .thenReturn(
                        new ReportMapper.PatientDatesRow(
                                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5)));
        when(config.findMappings())
                .thenReturn(List.of(mapping(71L, "PREOP_LAST"), mapping(72L, "PREDISCHARGE_LAST")));

        List<ReportMappingService.MappingOptionView> mappings = service.preview(REPORT).mappings();

        assertEquals(2, mappings.size());
        assertTrue(
                mappings.get(0).blockedReason().contains("手术日期"),
                "术前最后一次：报告时间晚于手术日期 → 拦截提示：" + mappings.get(0).blockedReason());
        assertTrue(
                mappings.get(1).blockedReason().contains("出院日期"),
                "出院前最后一次：报告时间晚于出院日期 → 拦截提示：" + mappings.get(1).blockedReason());
    }

    @Test
    void previewMarksLockedBaselineAsBlocked() {
        when(baselineService.baseline(PATIENT))
                .thenReturn(
                        baselineView(
                                List.of(section("echo", "55", 5, Map.of("f083", 50, "f097", 2))),
                                lockedView()));

        ReportMappingService.MappingOptionView baseline =
                service.preview(REPORT).mappings().stream()
                        .filter(item -> "BASELINE".equals(item.targetType()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(baseline.blockedReason().contains("基线已锁定"), baseline.blockedReason());
    }

    @Test
    void previewFlagsUnconvertibleResults() {
        when(mapper.indicatorsOfReport(REPORT))
                .thenReturn(
                        List.of(
                                new ReportMapper.IndicatorRow("EF", "未见明显异常（示例）"),
                                new ReportMapper.IndicatorRow("MR_DEGREE", "重度（示例）")));

        ReportMappingService.MappingOptionView followup =
                service.preview(REPORT).mappings().stream()
                        .filter(item -> "FOLLOWUP_RECORD".equals(item.targetType()))
                        .findFirst()
                        .orElseThrow();
        ReportMappingService.WriteView ef =
                writeOf(followup.candidates().get(0), "f248").orElseThrow();
        assertNull(ef.newValue());
        assertTrue(ef.error().contains("数值"), ef.error());
        assertTrue(
                writeOf(followup.candidates().get(0), "f239")
                        .orElseThrow()
                        .error()
                        .contains("不在选项"),
                "选项名不在选项集内 → 错误提示");
    }

    @Test
    void previewReturnsNoMappingsForTypeWithoutMapping() {
        when(mapper.findReport(REPORT)).thenReturn(reportHead("LAB", "检验"));
        when(config.findMappings()).thenReturn(List.of(mapping(73L, null)));

        ReportMappingService.PreviewView preview = service.preview(REPORT);

        assertTrue(preview.mappings().isEmpty(), "类型无映射配置 → 空清单（前端隐藏映射步骤）");
    }

    @Test
    void previewRejectsMissingOrOutOfScopeReport() {
        when(mapper.findReport(REPORT)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.preview(REPORT));
        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 确认：逐项覆盖、留痕、审计（流程第 5 步）

    @Test
    void confirmWritesFollowupRecordAndLeavesHistoryAndAudit() {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("f248", 55);
        merged.put("f237", "2026-10-20");
        merged.put("f239", 2);
        merged.put("f236", 1);
        merged.put("f238", 1);
        when(recordService.applyMappingWrite(eq(88L), eq(3), any()))
                .thenReturn(
                        new FollowupRecordService.MappingWriteOutcome(
                                recordView(88L, 4, merged), Map.of("f248", 50)));
        when(fieldHistory.logChanges(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        anyString()))
                .thenReturn(
                        List.of(
                                new FieldHistoryService.FieldChange("f248", 50, 55),
                                new FieldHistoryService.FieldChange("f237", null, "2026-10-20")));

        ReportMappingService.ConfirmedView view =
                service.confirm(
                        REPORT,
                        new ReportMappingService.ConfirmCommand(
                                "73", "88", 3, List.of("f236", "f237", "f238", "f239", "f248")));

        assertEquals("88", view.targetRecordId());
        assertEquals(4, view.targetVersion(), "写入后 version + 1");
        assertEquals(
                List.of("f237", "f248", "f239", "f238", "f236"),
                view.writtenFields(),
                "按映射顺序写入（项目行在前、项目级与映射级附带写入随后）");
        assertTrue(view.skippedConflicts().isEmpty());
        assertEquals(2, view.fieldChanges(), "字段历史条数");

        ArgumentCaptor<Map<String, Object>> mergedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(recordService).applyMappingWrite(eq(88L), eq(3), mergedCaptor.capture());
        assertEquals(55, mergedCaptor.getValue().get("f248"), "确认的冲突字段按新值写入");

        verify(fieldHistory)
                .logChanges(
                        eq(FieldHistoryService.FU_RECORD),
                        eq(88L),
                        eq("A_ANNUAL_FU"),
                        eq(2),
                        eq(Map.of("f248", 50)),
                        eq(merged),
                        eq(ChangeSource.REPORT),
                        assertContains("心脏超声"));
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(auditCaptor.capture());
        assertEquals("RPT_MAPPING_WRITE", auditCaptor.getValue().action());
        assertTrue(
                auditCaptor.getValue().description().contains("900"),
                auditCaptor.getValue().description());
    }

    @Test
    void confirmSkipsConflictingFieldsNotConfirmed() {
        when(recordService.applyMappingWrite(eq(88L), eq(3), any()))
                .thenReturn(
                        new FollowupRecordService.MappingWriteOutcome(
                                recordView(88L, 4, Map.of("f248", 50)), Map.of("f248", 50)));
        when(fieldHistory.logChanges(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        anyString()))
                .thenReturn(List.of());

        ReportMappingService.ConfirmedView view =
                service.confirm(
                        REPORT,
                        new ReportMappingService.ConfirmCommand(
                                "73", "88", 3, List.of("f236", "f237", "f239")));

        assertEquals(List.of("f248"), view.skippedConflicts(), "未确认的冲突字段跳过并回告");
        ArgumentCaptor<Map<String, Object>> mergedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(recordService).applyMappingWrite(eq(88L), eq(3), mergedCaptor.capture());
        assertEquals(50, mergedCaptor.getValue().get("f248"), "未确认覆盖 → 保留原值（不做字段级合并，只跳过）");
    }

    @Test
    void confirmRejectsInvalidFieldKeys() {
        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "73", "88", 3, List.of())));
        assertEquals(40001, empty.code());

        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "73", "88", 3, List.of("f999"))));
        assertEquals(40001, unknown.code());
        assertTrue(unknown.getMessage().contains("f999"), unknown.getMessage());

        when(mapper.indicatorsOfReport(REPORT))
                .thenReturn(List.of(new ReportMapper.IndicatorRow("EF", "未见明显异常（示例）")));
        ApiException errored =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "73", "88", 3, List.of("f248"))));
        assertEquals(40001, errored.code());
        assertTrue(errored.getMessage().contains("数值"), errored.getMessage());
        verify(recordService, never()).applyMappingWrite(anyLong(), anyInt(), any());
    }

    @Test
    void confirmRejectsStaleTargetVersion() {
        when(recordService.applyMappingWrite(eq(88L), eq(3), any()))
                .thenThrow(new ApiException(409, 40900, "该记录已被他人更新"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "73", "88", 3, List.of("f248"))));
        assertEquals(40900, error.code(), "版本冲突 409（AGENTS §5.10 乐观锁）");
    }

    @Test
    void confirmWritesBaselineSectionCreatingWhenMissing() {
        when(baselineService.baseline(PATIENT)).thenReturn(baselineView(List.of(), unlockedView()));
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("f079", "2026-10-20");
        merged.put("f083", 55);
        merged.put("f097", 2);
        when(baselineService.applyMappingWrite(eq(PATIENT), eq("echo"), eq(0), any()))
                .thenReturn(
                        new BaselineSectionService.MappingWriteOutcome(
                                section("echo", "77", 1, merged), "A_BASELINE", 3, Map.of()));
        when(fieldHistory.logChanges(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        anyString()))
                .thenReturn(List.of(new FieldHistoryService.FieldChange("f083", null, 55)));

        ReportMappingService.ConfirmedView view =
                service.confirm(
                        REPORT,
                        new ReportMappingService.ConfirmCommand(
                                "71", null, 0, List.of("f079", "f083", "f097")));

        assertEquals("77", view.targetRecordId());
        assertEquals(1, view.targetVersion(), "分节不存在时按 baseVersion 0 新建");
        verify(lockService).requireUnlocked(eq(PATIENT), anyString());
        verify(fieldHistory)
                .logChanges(
                        eq(FieldHistoryService.PT_BASELINE_SECTION),
                        eq(77L),
                        eq("A_BASELINE"),
                        eq(3),
                        eq(Map.of()),
                        eq(merged),
                        eq(ChangeSource.REPORT),
                        assertContains("心脏超声"));
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void confirmRejectsBaselineGuards() {
        lenient()
                .doThrow(new ApiException(409, 40901, "基线已锁定，不能写入映射字段（需先解锁）"))
                .when(lockService)
                .requireUnlocked(eq(PATIENT), anyString());
        ApiException locked =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "71", null, 5, List.of("f083"))));
        assertEquals(40901, locked.code(), "基线锁定 40901（A-PAT-006 锁定后只读）");

        lenient().doNothing().when(lockService).requireUnlocked(anyLong(), anyString());
        when(mapper.patientDates(PATIENT))
                .thenReturn(new ReportMapper.PatientDatesRow(LocalDate.of(2026, 10, 1), null));
        ApiException late =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "71", null, 5, List.of("f083"))));
        assertEquals(40001, late.code());
        assertTrue(late.getMessage().contains("手术日期"), late.getMessage());
        verify(baselineService, never()).applyMappingWrite(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void confirmRejectsMappingGuards() {
        when(config.findMapping(99L)).thenReturn(null);
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "99", "88", 3, List.of("f248"))));
        assertEquals(40400, missing.code());

        ReportConfigMapper.MappingRow disabled = mapping(73L, null, "DISABLED");
        when(config.findMapping(73L)).thenReturn(disabled);
        ApiException stopped =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "73", "88", 3, List.of("f248"))));
        assertEquals(40001, stopped.code());
        assertTrue(stopped.getMessage().contains("停用"), stopped.getMessage());

        ReportConfigMapper.MappingRow foreignType =
                new ReportConfigMapper.MappingRow(
                        74L,
                        "LAB_MAPPING",
                        "检验映射",
                        "LAB",
                        "检验",
                        "FOLLOWUP_RECORD",
                        "A_ANNUAL_FU",
                        "年度随访",
                        null,
                        null,
                        null,
                        null,
                        10,
                        "ENABLED",
                        null);
        when(config.findMapping(74L)).thenReturn(foreignType);
        ApiException foreign =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "74", "88", 3, List.of("f248"))));
        assertEquals(40001, foreign.code());
        assertTrue(foreign.getMessage().contains("不属于"), foreign.getMessage());
    }

    @Test
    void confirmRejectsTargetRecordOutsideLocations() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirm(
                                        REPORT,
                                        new ReportMappingService.ConfirmCommand(
                                                "73", "99", 3, List.of("f248"))));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("位置"), error.getMessage());
        verify(recordService, never()).applyMappingWrite(anyLong(), anyInt(), any());
    }

    // ---------------------------------------------------------------- 世界桩（与 V64 预置同构，全部虚构数据）

    /** 造一个「心脏超声报告单挂在基线超声检查分类 + 第 2 年随访记录」的世界：两条映射、指标结果、目标记录与分节。 */
    private void stubEchoWorld() {
        lenient().when(mapper.findReport(REPORT)).thenReturn(reportHead("ECHO", "心脏超声"));
        lenient()
                .when(mapper.linksOfReport(REPORT))
                .thenReturn(
                        List.of(
                                link("baseline", null, "category", "echo", 3),
                                link("followup_record", 88L, null, null, 2)));
        lenient()
                .when(mapper.indicatorsOfReport(REPORT))
                .thenReturn(
                        List.of(
                                new ReportMapper.IndicatorRow("EF", "55"),
                                new ReportMapper.IndicatorRow("MR_DEGREE", "少量")));
        lenient()
                .when(mapper.patientDates(PATIENT))
                .thenReturn(new ReportMapper.PatientDatesRow(null, null));
        lenient()
                .when(mapper.recordsOfPatient(PATIENT))
                .thenReturn(
                        List.of(
                                new ReportMapper.RecordRow(
                                        88L,
                                        98L,
                                        "第2年随访",
                                        "TASK",
                                        null,
                                        LocalDate.of(2026, 6, 10),
                                        "A_ANNUAL_FU",
                                        2,
                                        "SUBMITTED")));
        lenient()
                .when(config.findMappings())
                .thenReturn(List.of(mapping(71L, "PREOP_LAST"), mapping(73L, null)));
        lenient().when(config.findMapping(71L)).thenReturn(mapping(71L, "PREOP_LAST"));
        lenient().when(config.findMapping(72L)).thenReturn(mapping(72L, "PREDISCHARGE_LAST"));
        lenient().when(config.findMapping(73L)).thenReturn(mapping(73L, null));
        lenient()
                .when(config.findMappingItems(71L))
                .thenReturn(
                        List.of(
                                item(71L, "REPORT_DATE", "f079", 10),
                                item(71L, "EF", "f083", 20),
                                item(71L, "MR_DEGREE", "f097", 30)));
        lenient()
                .when(config.findMappingItems(72L))
                .thenReturn(List.of(item(72L, "EF", "f083", 10)));
        lenient()
                .when(config.findMappingItems(73L))
                .thenReturn(
                        List.of(
                                item(73L, "REPORT_DATE", "f237", 10),
                                item(73L, "EF", "f248", 20),
                                new ReportConfigMapper.MappingItemRow(
                                        2L,
                                        73L,
                                        "MR_DEGREE",
                                        "f239",
                                        "二尖瓣返流程度",
                                        "[{\"fieldKey\":\"f238\",\"value\":\"1\"}]",
                                        30)));
        // 基线表单 v3：超声检查分类（f079 时间 / f083 EF / f097 二尖瓣返流）
        lenient().when(formService.publishedVersion("A_BASELINE")).thenReturn(3);
        lenient()
                .when(formService.schema("A_BASELINE", 3))
                .thenReturn(
                        schema(
                                3,
                                List.of(
                                        category(
                                                "basic",
                                                "基本信息",
                                                List.of(field("f001", "手术日期", "date", List.of()))),
                                        category(
                                                "echo",
                                                "超声检查",
                                                List.of(
                                                        field(
                                                                "f079",
                                                                "术前最后一次超声时间",
                                                                "date",
                                                                List.of()),
                                                        field(
                                                                "f083",
                                                                "术前最后一次超声EF值",
                                                                "number",
                                                                List.of()),
                                                        field(
                                                                "f097",
                                                                "术前最后一次超声二尖瓣反流",
                                                                "select",
                                                                reflux()))))));
        // 随访表单 v2：复查超声分组（f236 复查超声 / f237 时间 / f238 有无 / f239 程度 / f248 EF）
        lenient()
                .when(formService.schema("A_ANNUAL_FU", 2))
                .thenReturn(
                        schema(
                                2,
                                List.of(
                                        category(
                                                "overview",
                                                "随访概况",
                                                List.of(field("f214", "随访日期", "date", List.of()))),
                                        category(
                                                "echo",
                                                "复查超声",
                                                List.of(
                                                        field(
                                                                "f236",
                                                                "复查超声",
                                                                "radio",
                                                                List.of(
                                                                        option(0, "否"),
                                                                        option(1, "是"))),
                                                        field("f237", "超声检查时间", "date", List.of()),
                                                        field(
                                                                "f238",
                                                                "有无二尖瓣返流",
                                                                "radio",
                                                                List.of(
                                                                        option(0, "否"),
                                                                        option(1, "是"))),
                                                        field(
                                                                "f239", "二尖瓣返流程度", "select",
                                                                reflux()),
                                                        field(
                                                                "f248", "EF 射血分数", "number",
                                                                List.of()))))));
        // 目标记录：已提交的第 2 年随访，EF 原值 50（与报告 55 冲突）
        lenient()
                .when(recordService.record(88L))
                .thenReturn(recordView(88L, 3, Map.of("f248", 50)));
        // 基线：超声检查分节已建（v5，EF 50 / 返流 少量），未锁定
        lenient()
                .when(baselineService.baseline(PATIENT))
                .thenReturn(
                        baselineView(
                                List.of(section("echo", "55", 5, Map.of("f083", 50, "f097", 2))),
                                unlockedView()));
        lenient()
                .when(
                        fieldHistory.logChanges(
                                anyString(),
                                anyLong(),
                                anyString(),
                                anyInt(),
                                any(),
                                any(),
                                any(),
                                anyString()))
                .thenReturn(List.of());
    }

    private static ReportMapper.ReportHeadRow reportHead(String typeCode, String typeName) {
        return new ReportMapper.ReportHeadRow(
                REPORT, PATIENT, typeCode, typeName, "经胸超声心动图（示例）", REPORT_DATE);
    }

    /** V64 同构映射行（73 = 随访复查超声，带映射级附带写入 f236=1；71/72 = 基线术前 / 出院前）。 */
    private static ReportConfigMapper.MappingRow mapping(long id, String phase) {
        return mapping(id, phase, "ENABLED");
    }

    private static ReportConfigMapper.MappingRow mapping(long id, String phase, String status) {
        boolean baseline = phase != null;
        return new ReportConfigMapper.MappingRow(
                id,
                baseline ? "ECHO_PREOP_LAST" : "ECHO_FOLLOWUP",
                baseline ? "心脏超声 → 基线（术前最后一次）" : "心脏超声 → 随访记录（复查超声）",
                "ECHO",
                "心脏超声",
                baseline ? "BASELINE" : "FOLLOWUP_RECORD",
                baseline ? "A_BASELINE" : "A_ANNUAL_FU",
                baseline ? "基线档案" : "年度随访",
                phase,
                55L,
                "心脏超声指标",
                baseline ? null : "[{\"fieldKey\":\"f236\",\"value\":\"1\"}]",
                (int) id,
                status,
                null);
    }

    private static ReportConfigMapper.MappingItemRow item(
            long mappingId, String itemCode, String fieldKey, int sortNo) {
        return new ReportConfigMapper.MappingItemRow(
                (long) sortNo, mappingId, itemCode, fieldKey, "字段（示例）", null, sortNo);
    }

    private static ReportMapper.LinkRow link(
            String linkType, Long recordId, String anchorType, String anchorKey, int version) {
        return new ReportMapper.LinkRow(
                1L, REPORT, linkType, recordId, anchorType, anchorKey, version, 0);
    }

    private static FollowupRecordService.RecordView recordView(
            long id, int version, Map<String, Object> data) {
        return new FollowupRecordService.RecordView(
                id,
                PATIENT,
                98L,
                "TASK",
                null,
                "A_ANNUAL_FU",
                2,
                "SUBMITTED",
                version,
                new LinkedHashMap<>(data),
                Map.of(),
                "2026-06-01 09:00:00+08:00",
                "2026-06-01 09:00:00+08:00");
    }

    private static BaselineSectionService.BaselineView baselineView(
            List<BaselineSectionService.SectionView> sections, BaselineLockService.LockView lock) {
        return new BaselineSectionService.BaselineView("A_BASELINE", 3, lock, sections, List.of());
    }

    private static BaselineSectionService.SectionView section(
            String code, String id, int version, Map<String, Object> data) {
        return new BaselineSectionService.SectionView(
                code,
                id,
                version,
                "PARTIAL",
                new LinkedHashMap<>(data),
                Map.of(),
                "管理员（示例）",
                "2026-06-01 09:00:00+08:00",
                false);
    }

    private static BaselineLockService.LockView unlockedView() {
        return new BaselineLockService.LockView(
                false, null, null, null, "IT 解锁（示例）", "管理员（示例）", "2026-06-01 09:00:00+08:00");
    }

    private static BaselineLockService.LockView lockedView() {
        return new BaselineLockService.LockView(
                true, "归档（示例）", "管理员（示例）", "2026-06-01 09:00:00+08:00", null, null, null);
    }

    private static FormService.SchemaView schema(
            int version, List<FormService.CategoryView> categories) {
        return new FormService.SchemaView(
                "FORM", "表单（示例）", "FOLLOWUP", version, categories, List.of());
    }

    private static FormService.CategoryView category(
            String key, String label, List<FormService.FieldView> fields) {
        return new FormService.CategoryView(key, label, 10, fields);
    }

    private static FormService.FieldView field(
            String key, String label, String type, List<FormService.OptionItemView> options) {
        return new FormService.FieldView(
                key,
                "code_" + key,
                label,
                type,
                null,
                null,
                null,
                null,
                options,
                false,
                null,
                null,
                null,
                false,
                "json",
                null);
    }

    private static FormService.OptionItemView option(int code, String label) {
        return new FormService.OptionItemView(code, label, code * 10, false, false, false);
    }

    private static List<FormService.OptionItemView> reflux() {
        List<FormService.OptionItemView> options = new ArrayList<>();
        for (String[] pair :
                new String[][] {{"0", "无"}, {"1", "微量"}, {"2", "少量"}, {"3", "中量"}, {"4", "大量"}}) {
            options.add(option(Integer.parseInt(pair[0]), pair[1]));
        }
        return options;
    }

    private static java.util.Optional<ReportMappingService.WriteView> writeOf(
            ReportMappingService.CandidateView candidate, String fieldKey) {
        return candidate.writes().stream()
                .filter(write -> fieldKey.equals(write.fieldKey()))
                .findFirst();
    }

    /** Mockito eq() 变体：匹配包含关键字的 reason（日志断言可读性）。 */
    private static String assertContains(String keyword) {
        return org.mockito.ArgumentMatchers.argThat(
                reason -> reason != null && reason.contains(keyword));
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "admin.demo",
                "管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
