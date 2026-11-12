package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.compare.app.CompareHighlightService;
import com.crdp.followup.compare.app.CompareRuleService;
import com.crdp.followup.compare.app.CompareSourceService;
import com.crdp.followup.plan.app.FollowupTerminationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E15-T02 / A-FU-005：随访工作台记录详情与提交的业务层口径。
 *
 * <p>锁定：详情脱敏（姓名 / 病案号 SensitiveValueMasker）与任务上下文；提交联动任务完成（「提交后任务完成」验收位）+ DRAFT→SUBMITTED 字段历史 +
 * RECORD_SUBMIT 审计；「是否死亡 = 是」按模板字段编码 death 定位并触发死亡终止（OQ-79②）；无关联任务的提交不联动任务； baseVersion 缺失
 * 40001。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class RecordEntryServiceTest {

    private final FormService formService = mock(FormService.class);
    private final FollowupRecordService recordService = mock(FollowupRecordService.class);
    private final FieldHistoryService fieldHistory = mock(FieldHistoryService.class);
    private final FollowupTaskStatusService statusService = mock(FollowupTaskStatusService.class);
    private final FollowupTerminationService terminationService =
            mock(FollowupTerminationService.class);
    private final FollowupTaskQueryMapper taskQueryMapper = mock(FollowupTaskQueryMapper.class);
    private final CompareSourceService compareSource = mock(CompareSourceService.class);
    private final CompareRuleService compareRules = mock(CompareRuleService.class);
    private final CompareHighlightService compareHighlights = mock(CompareHighlightService.class);
    private final com.crdp.followup.implant.app.DeviceContextService deviceContext =
            mock(com.crdp.followup.implant.app.DeviceContextService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final RecordEntryAssistService entryAssist = mock(RecordEntryAssistService.class);
    private final com.crdp.followup.record.infra.mapper.FollowupRecordMapper recordMapper =
            mock(com.crdp.followup.record.infra.mapper.FollowupRecordMapper.class);
    private final com.crdp.followup.record.infra.mapper.RecordFeatureMapper features =
            mock(com.crdp.followup.record.infra.mapper.RecordFeatureMapper.class);
    private final com.crdp.followup.plan.app.PlanSwitchService planSwitch =
            mock(com.crdp.followup.plan.app.PlanSwitchService.class);
    private final FollowupSequenceService sequences = mock(FollowupSequenceService.class);
    private final com.crdp.followup.report.app.ReportTranscribeService reportTranscribe =
            mock(com.crdp.followup.report.app.ReportTranscribeService.class);

    private final RecordEntryService service =
            new RecordEntryService(
                    recordService,
                    formService,
                    fieldHistory,
                    statusService,
                    terminationService,
                    taskQueryMapper,
                    compareSource,
                    compareRules,
                    compareHighlights,
                    deviceContext,
                    crypto,
                    audit,
                    entryAssist,
                    recordMapper,
                    features,
                    planSwitch,
                    sequences,
                    reportTranscribe);

    private static FollowupTaskQueryMapper.TaskRowRaw taskRow(String status) {
        return new FollowupTaskQueryMapper.TaskRowRaw(
                9001L,
                301L,
                "cipher-name",
                "cipher-mrn",
                "LG-001",
                "示例病区一",
                "第4年随访",
                4,
                "FORM",
                "A_ANNUAL_FU",
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 10, 29),
                LocalDate.of(2026, 11, 12),
                LocalDate.of(2026, 10, 29),
                status,
                null,
                "NORMAL",
                701L,
                7L,
                "张医生（示例）",
                LocalDate.of(2027, 11, 5));
    }

    private static FollowupRecordService.RecordView record(
            String status, Map<String, Object> data) {
        return record(status, data, 301L);
    }

    private static FollowupRecordService.RecordView record(
            String status, Map<String, Object> data, long patientId) {
        return new FollowupRecordService.RecordView(
                701L,
                patientId,
                null,
                "TASK",
                null,
                null,
                "A_ANNUAL_FU",
                1,
                status,
                2,
                data,
                Map.of(),
                null,
                "2026-11-05T09:00:00+08:00");
    }

    @Test
    void detailMasksPatientAndCarriesTaskContext() {
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of("f214", "2026-11-05")));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        when(crypto.decrypt("cipher-name")).thenReturn("周随访（示例）");
        when(crypto.decrypt("cipher-mrn")).thenReturn("MRN-0001");

        RecordEntryService.RecordDetailView view = service.detail(701L);

        assertEquals("701", view.id());
        assertEquals("DRAFT", view.status());
        assertEquals("9001", view.context().taskId());
        assertTrue(
                !view.context().patientName().contains("周随访"),
                "姓名脱敏：" + view.context().patientName());
        assertTrue(view.context().mrn().contains("*"), "病案号脱敏：" + view.context().mrn());
        assertEquals("第4年随访", view.context().nodeName());
        assertEquals(LocalDate.of(2027, 11, 5), view.context().nextPlanDate());
    }

    /**
     * E21-T03 / C-FU-009：详情随记录下发器械上下文 device——按记录随访日期解析（visit_date 为空的任务录入回落当前 器械，由
     * DeviceContextService 判定），页面以 {@code { device: … }}} 传入 FormRenderer 的 context。
     */
    @Test
    void detailCarriesDeviceContextResolvedByVisitDate() {
        FollowupRecordService.RecordView backfill =
                new FollowupRecordService.RecordView(
                        702L,
                        301L,
                        null,
                        "BACKFILL",
                        LocalDate.of(2025, 6, 1),
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "DRAFT",
                        2,
                        Map.of(),
                        Map.of(),
                        null,
                        "2026-11-05T09:00:00+08:00");
        when(recordService.record(702L)).thenReturn(backfill);
        when(deviceContext.resolve(301L, LocalDate.of(2025, 6, 1)))
                .thenReturn(
                        new com.crdp.followup.implant.app.DeviceContextService.DeviceView(
                                "501",
                                "DDD",
                                "双腔起搏器(DDD)",
                                LocalDate.of(2024, 6, 1),
                                true,
                                2,
                                false));

        RecordEntryService.RecordDetailView view = service.detail(702L);

        assertEquals("501", view.device().implantId());
        assertEquals("DDD", view.device().type());
        assertTrue(view.device().hasRaLead());
        assertEquals(2, view.device().chambers());
        assertFalse(view.device().current(), "按随访日期匹配到的是历史器械");
    }

    /** 开关关闭 / 无匹配器械（A 租户、取出后）→ device 为 null，记录页照常返回。 */
    @Test
    void detailWithoutDeviceStillReturns() {
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(null);
        when(taskQueryMapper.patientBrief(301L)).thenReturn(null);

        RecordEntryService.RecordDetailView view = service.detail(701L);

        assertNull(view.device(), "无器械上下文时 device 为 null");
    }

    /**
     * E21-T09 / C-FU-018：详情 sequenceNo 改经 FollowupSequenceService（开关开启的已提交记录优先读重排落库值），
     * 有效日期口径不变（visit_date ?? 任务计划日期）。
     */
    @Test
    void detailResolvesSequenceNoViaSequenceService() {
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of("f214", "2026-11-05")));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        when(sequences.sequenceNoOfDetail(any(), eq(LocalDate.of(2026, 11, 5)))).thenReturn(4);

        assertEquals(4, service.detail(701L).sequenceNo());
    }

    @Test
    void detailWithoutTaskContextCarriesPatientBrief() {
        FollowupRecordService.RecordView backfill =
                new FollowupRecordService.RecordView(
                        702L,
                        301L,
                        null,
                        "BACKFILL",
                        LocalDate.of(2025, 6, 1),
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "DRAFT",
                        2,
                        Map.of(),
                        Map.of(),
                        null,
                        "2026-11-05T09:00:00+08:00");
        when(recordService.record(702L)).thenReturn(backfill);
        when(taskQueryMapper.taskContextByRecordId(702L)).thenReturn(null);
        when(taskQueryMapper.patientBrief(301L))
                .thenReturn(
                        new FollowupTaskQueryMapper.PatientBriefRow(
                                301L, "cipher-name", "cipher-mrn", "示例病区一"));
        when(crypto.decrypt("cipher-name")).thenReturn("周随访（示例）");
        when(crypto.decrypt("cipher-mrn")).thenReturn("MRN-0001");

        RecordEntryService.RecordDetailView view = service.detail(702L);

        assertNull(view.context(), "未关联任务 / 越范围的记录无任务上下文");
        assertEquals("BACKFILL", view.source(), "补录记录带来源标识（A-FU-014 标记为补录）");
        assertEquals(LocalDate.of(2025, 6, 1), view.visitDate(), "补录带实际随访日期");
        assertEquals("301", view.patient().patientId());
        assertTrue(view.patient().patientName().contains("*"), "摘要姓名脱敏");
    }

    /** 详情默认无启用规则（各 detail 用例按需覆盖；E17-T03 compareRules 缺省 null）。 */
    @BeforeEach
    void noCompareRulesByDefault() {
        lenient().when(compareRules.evaluationRules(any())).thenReturn(List.of());
        lenient().when(compareSource.resolve(anyLong(), anyLong(), any(), any())).thenReturn(null);
    }

    /** E17-T02：详情装配 previous（A-FU-027 上一次记录解析，求值随 E17-T03）。 */
    @Test
    void detailCarriesComparePrevious() {
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        CompareSourceService.PreviousView baseline =
                CompareSourceService.PreviousView.baseline(Map.of("f102", 55));
        when(compareSource.resolve(701L, 301L, "A_ANNUAL_FU", null)).thenReturn(baseline);

        assertEquals(baseline, service.detail(701L).previous(), "第 1 年回落基线出院前超声");
    }

    /** E17-T03：详情附 compareRules（启用规则下发，前端实时标红与后端复算共用，tech-plan §11）。 */
    @Test
    void detailCarriesEnabledCompareRules() {
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        CompareRuleService.RuleView efDrop =
                new CompareRuleService.RuleView(
                        11L,
                        "A_ANNUAL_FU",
                        "年度随访记录",
                        "ef_drop",
                        "射血分数下降",
                        "fu_echo",
                        "f248",
                        "EF 射血分数",
                        "%",
                        "DIFF",
                        "差值下降 / 上升",
                        Map.of("direction", "DROP", "threshold", 10),
                        "A_BASELINE",
                        "f102",
                        "出院前最后一次EF",
                        "EF 比上次（或出院前）下降 ≥ 10 个百分点",
                        true,
                        "2027-05-17T10:00");
        when(compareRules.evaluationRules("A_ANNUAL_FU")).thenReturn(List.of(efDrop));

        RecordEntryService.RecordDetailView view = service.detail(701L);

        assertEquals(List.of(efDrop), view.compareRules(), "启用规则随记录详情下发（只含启用行）");
        assertNull(view.previous(), "无上一次且基线回落为空 → previous 为 null（规则仍可固定阈值求值）");
    }

    /** E17-T03：功能开关关闭 / 无启用规则 → compareRules 为 null（记录页正常返回、前端不标红）。 */
    @Test
    void detailWithoutEnabledRulesOmitsCompareRules() {
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));

        assertNull(service.detail(701L).compareRules(), "无启用规则不下发");
    }

    @Test
    void submitCompletesTaskAndWritesFieldHistoryAndAudit() {
        Map<String, Object> data = Map.of("f214", "2026-11-05", "f215", 0);
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                record("SUBMITTED", data),
                                Map.of("f214", "2026-11-05"),
                                List.of(),
                                List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        when(fieldHistory.logChanges(
                        eq(FieldHistoryService.FU_RECORD),
                        eq(701L),
                        eq("A_ANNUAL_FU"),
                        eq(1),
                        any(),
                        any(),
                        eq(com.crdp.form.history.domain.ChangeSource.USER),
                        any()))
                .thenReturn(List.of(new FieldHistoryService.FieldChange("f215", null, 0)));
        when(statusService.complete(eq(9001L), any()))
                .thenReturn(
                        new FollowupTaskStatusService.CompletionView(
                                "9001", "COMPLETED", "ON_TIME"));
        // 模板 schema：death 字段编码定位（不硬编码键名），提交值 0 = 否
        when(formService.schema("A_ANNUAL_FU", 1))
                .thenReturn(
                        new FormService.SchemaView(
                                "A_ANNUAL_FU",
                                "年度随访记录",
                                "FOLLOWUP",
                                1,
                                List.of(
                                        new FormService.CategoryView(
                                                "fu_contact",
                                                "联系与生存",
                                                10,
                                                List.of(
                                                        new FormService.FieldView(
                                                                "f215", "death", "是否死亡", "radio",
                                                                null, null, null, null, List.of(),
                                                                true, null, null, null, false,
                                                                "json", null)))),
                                List.of()));

        RecordEntryService.SubmitResultView view =
                service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, null));

        assertEquals("COMPLETED", view.taskStatus(), "提交后任务完成（A-FU-005 验收位）");
        assertEquals("ON_TIME", view.completeKind());
        assertEquals(1, view.fieldChanges());
        assertEquals("9001", view.taskId());
        verify(terminationService, never()).terminateOnDeath(anyLong(), any(), any());
        verify(audit).append(any());
        // E17-T04：提交后同一事务重算该患者该表单已提交记录的 fu_record.highlight（保存复算落库）
        verify(compareHighlights).refreshSubmittedRecords(301L, "A_ANNUAL_FU");
        // E21-T09 / C-FU-018：提交后同一事务重排该患者随访序号（任务录入来源的非补录措辞）
        verify(sequences).resequence(301L, "提交随访记录，随访序号自动重排（C-FU-018）");
    }

    /**
     * E21-T17 / C-SCAN-012、C-FU-017：转录态提交（transcribeReportId 随提交带入）在同一事务完成转录——
     * 报告单自动加入该记录位置并置「已电子化」；结果回带 transcribedReportId（前端提示口径）。
     */
    @Test
    void submitWithTranscribeReportIdCompletesTranscription() {
        Map<String, Object> data = Map.of("f214", "2026-11-05", "f215", 0);
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                record("SUBMITTED", data), Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(null);
        when(fieldHistory.logChanges(any(), eq(701L), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(formService.schema("A_ANNUAL_FU", 1)).thenReturn(null);

        RecordEntryService.SubmitResultView view =
                service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, 900L));

        verify(reportTranscribe).completeTranscription(900L, 701L, 301L, 1);
        assertEquals("900", view.transcribedReportId(), "转录完成回带报告单 id");
    }

    /** 非转录态提交（普通任务 / 补录，不带 transcribeReportId）不触发转录联动。 */
    @Test
    void submitWithoutTranscribeReportIdSkipsTranscription() {
        Map<String, Object> data = Map.of("f214", "2026-11-05");
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                record("SUBMITTED", data), Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(null);
        when(fieldHistory.logChanges(any(), eq(701L), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(formService.schema("A_ANNUAL_FU", 1)).thenReturn(null);

        RecordEntryService.SubmitResultView view =
                service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, null));

        verify(reportTranscribe, never())
                .completeTranscription(anyLong(), anyLong(), anyLong(), anyInt());
        assertNull(view.transcribedReportId());
    }

    @Test
    void submitWithDeathRecordedTerminatesPatientTasks() {
        Map<String, Object> data = Map.of("f214", "2026-11-05", "f215", 1);
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                record("SUBMITTED", data), Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        when(fieldHistory.logChanges(any(), anyLong(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(statusService.complete(eq(9001L), any()))
                .thenReturn(
                        new FollowupTaskStatusService.CompletionView(
                                "9001", "COMPLETED", "OVERDUE"));
        when(formService.schema("A_ANNUAL_FU", 1))
                .thenReturn(
                        new FormService.SchemaView(
                                "A_ANNUAL_FU",
                                "年度随访记录",
                                "FOLLOWUP",
                                1,
                                List.of(
                                        new FormService.CategoryView(
                                                "fu_contact",
                                                "联系与生存",
                                                10,
                                                List.of(
                                                        new FormService.FieldView(
                                                                "f215", "death", "是否死亡", "radio",
                                                                null, null, null, null, List.of(),
                                                                true, null, null, null, false,
                                                                "json", null)))),
                                List.of()));

        service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, null));

        // OQ-79②：随访记录「是否死亡=是」→ 死亡登记（未开始任务全部终止，幂等入口）
        verify(terminationService).terminateOnDeath(eq(301L), eq("随访记录提交：是否死亡 = 是"), any());
    }

    @Test
    void submitWithoutLinkedTaskSkipsTaskCompletion() {
        Map<String, Object> data = Map.of("f214", "2026-11-05");
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                record("SUBMITTED", data), Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(null);
        when(fieldHistory.logChanges(any(), anyLong(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(List.of());

        RecordEntryService.SubmitResultView view =
                service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, null));

        assertNull(view.taskId());
        verify(statusService, never()).complete(anyLong(), any());
        verify(audit).append(any());
    }

    @Test
    void submitHooksPlanSwitchWithEffectiveVisitDate() {
        Map<String, Object> data = Map.of("f214", "2026-11-05");
        // 补录记录：visit_date 落列（fu_record.visit_date），ERI 起算基准取它
        FollowupRecordService.RecordView view =
                new FollowupRecordService.RecordView(
                        701L,
                        301L,
                        null,
                        "BACKFILL",
                        LocalDate.of(2026, 11, 5),
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "SUBMITTED",
                        3,
                        data,
                        Map.of(),
                        null,
                        null);
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                view, Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(null);
        when(fieldHistory.logChanges(any(), anyLong(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, null));

        // E21-T06 / C-FU-007：提交后同事务触发计划切换判定（ERI 标记 / 医生指定），随访日期取 visit_date
        verify(planSwitch).onRecordSubmitted(301L, 701L, LocalDate.of(2026, 11, 5), data);
        // E21-T09 / C-FU-018：补录插入触发的重排用补录措辞（验收「补录插入后序号自动重排」）
        verify(sequences).resequence(301L, "补录插入随访记录，随访序号自动重排（C-FU-018）");
    }

    @Test
    void submitResolvesVisitDateFromFormFollowupDateFieldWhenColumnEmpty() {
        Map<String, Object> data = Map.of("f214", "2026-11-06");
        // 任务录入记录：fu_record.visit_date 为 NULL，随访日期在表单字段（编码 followup_date → 稳定键 f214）
        FollowupRecordService.RecordView view =
                new FollowupRecordService.RecordView(
                        701L,
                        301L,
                        9001L,
                        "TASK",
                        null,
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "SUBMITTED",
                        3,
                        data,
                        Map.of(),
                        null,
                        null);
        when(recordService.submit(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                view, Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        when(fieldHistory.logChanges(any(), anyLong(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(statusService.complete(eq(9001L), any()))
                .thenReturn(
                        new FollowupTaskStatusService.CompletionView(
                                "9001", "COMPLETED", "ON_TIME"));
        when(formService.schema("C_PROGRAM_FU", 1))
                .thenReturn(
                        new FormService.SchemaView(
                                "C_PROGRAM_FU",
                                "程控随访（示例）",
                                "FOLLOWUP",
                                1,
                                List.of(
                                        new FormService.CategoryView(
                                                "fu_overview",
                                                "随访概况",
                                                10,
                                                List.of(
                                                        new FormService.FieldView(
                                                                "f214",
                                                                "followup_date",
                                                                "随访日期",
                                                                "date",
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                List.of(),
                                                                true,
                                                                null,
                                                                null,
                                                                null,
                                                                false,
                                                                "json",
                                                                null)))),
                                List.of()));

        service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null, null));

        // 有效随访日期回落表单字段值（与重复提示口径一致，OQ-136⑤）
        verify(planSwitch).onRecordSubmitted(301L, 701L, LocalDate.of(2026, 11, 6), data);
    }

    @Test
    void submitRequiresBaseVersion() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.submit(
                                                701L,
                                                new RecordEntryService.SubmitCommand(
                                                        null, Map.of(), null, null)))
                        .code(),
                "baseVersion 必填（乐观锁）");
        verify(recordService, never()).submit(anyLong(), any(), any(), any());
    }

    // ------------------------------------------------------- 修改 / 删除已提交（E21-T11 / C-FU-026、Q17）

    /** 登录会话（权限码可注入；E21-T11 权限闸门用）。 */
    private static void loginAs(List<String> permissionCodes) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new SessionPrincipal(
                                        "token",
                                        "HOSPITAL",
                                        1L,
                                        "democ",
                                        "虚构示例医院",
                                        7L,
                                        "doctor.demo",
                                        "张医生（示例）",
                                        List.of("FOLLOWUP_DOCTOR"),
                                        List.of(),
                                        permissionCodes,
                                        List.of("followup"),
                                        1L,
                                        ScopeSnapshot.allowAll(),
                                        false,
                                        null),
                                null,
                                List.of()));
    }

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    /** 验收位「修改已提交记录需单独权限」：开关开启（医院 C）时仅有录入码 record:edit → 40300。 */
    @Test
    void editSessionRequiresSeparatePermissionWhenFeatureOn() {
        loginAs(List.of("followup:record:edit"));
        when(features.findEnabled("record_edit_submitted")).thenReturn(true);

        assertEquals(
                40300,
                assertThrows(ApiException.class, () -> service.editSession(701L, "抄录错误")).code(),
                "C-FU-026：未分配「修改已提交记录」权限只能查看");
        verify(audit, never()).append(any());
    }

    /** 开关开启 + edit-submitted → 放行并返回当前 version（PUT 的 baseVersion）。 */
    @Test
    void editSessionPassesWithEditSubmittedWhenFeatureOn() {
        loginAs(List.of("followup:record:edit-submitted"));
        when(features.findEnabled("record_edit_submitted")).thenReturn(true);
        when(recordService.record(701L)).thenReturn(record("SUBMITTED", Map.of()));

        RecordEntryService.EditSessionView view = service.editSession(701L, "抄录错误");

        assertEquals("701", view.recordId());
        assertEquals(2, view.version());
        assertEquals("SUBMITTED", view.status());
        verify(audit).append(any());
    }

    /** 开关关闭（A / B，无行默认）→ record:edit 即可（followup.md §6「A 用户有 record:edit 即可」）。 */
    @Test
    void editSessionPassesWithPlainEditWhenFeatureOff() {
        loginAs(List.of("followup:record:edit"));
        when(features.findEnabled("record_edit_submitted")).thenReturn(null);
        when(recordService.record(701L)).thenReturn(record("SUBMITTED", Map.of()));

        assertEquals(2, service.editSession(701L, null).version());
    }

    /** 只有已提交记录走修改流程：DRAFT 直接编辑（40901）。 */
    @Test
    void editSessionRejectsDraft() {
        loginAs(List.of("followup:record:edit-submitted"));
        when(features.findEnabled("record_edit_submitted")).thenReturn(true);
        when(recordService.record(701L)).thenReturn(record("DRAFT", Map.of()));

        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.editSession(701L, null)).code());
    }

    /** 保存修改：reason 必填（OQ-14 暂定 A / C 都必填）。 */
    @Test
    void updateSubmittedRequiresReason() {
        loginAs(List.of("followup:record:edit-submitted"));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.updateSubmitted(
                                                701L,
                                                new RecordEntryService.UpdateCommand(
                                                        2, Map.of(), null, " ", null)))
                        .code(),
                "修改原因必填（ReasonModal）");
        verify(recordService, never()).updateSubmitted(anyLong(), any(), any(), any());
    }

    /** 保存修改：字段级历史带原因、对比标红复算、RECORD_UPDATE 审计（C-FU-026「修改留字段级历史」）。 */
    @Test
    void updateSubmittedWritesFieldHistoryWithReasonAndRecalculates() {
        loginAs(List.of("followup:record:edit-submitted"));
        when(features.findEnabled("record_edit_submitted")).thenReturn(true);
        FollowupRecordService.RecordView updated =
                new FollowupRecordService.RecordView(
                        701L,
                        301L,
                        9001L,
                        "TASK",
                        null,
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "SUBMITTED",
                        3,
                        Map.of("f308", 1.2),
                        Map.of(),
                        null,
                        "2026-11-05T09:00:00+08:00");
        when(recordService.updateSubmitted(eq(701L), eq(2), any(), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                updated, Map.of("f308", 0.7), List.of(), List.of()));
        when(fieldHistory.logChanges(any(), eq(701L), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of());

        RecordEntryService.UpdateResultView view =
                service.updateSubmitted(
                        701L,
                        new RecordEntryService.UpdateCommand(
                                2, Map.of("f308", 1.2), null, "抄录错误", null));

        assertEquals("701", view.recordId());
        assertEquals(3, view.version(), "保存后生成 v2（version + 1）");
        verify(fieldHistory)
                .logChanges(
                        eq(FieldHistoryService.FU_RECORD),
                        eq(701L),
                        eq("A_ANNUAL_FU"),
                        eq(1),
                        any(),
                        any(),
                        eq(ChangeSource.USER),
                        eq("修改已提交随访记录：抄录错误"));
        verify(compareHighlights).refreshSubmittedRecords(301L, "A_ANNUAL_FU");
        // E21-T09 / C-FU-018：修改已提交记录后同一事务重排（表单随访日期字段可能改早 / 改晚）
        verify(sequences).resequence(301L, FollowupSequenceService.REASON_UPDATE_SUBMITTED);
        verify(audit).append(any());
    }

    /** E21-T17 / C-SCAN-012：转录态保存修改（位置中已有已提交记录的对照录入）同样完成转录联动。 */
    @Test
    void updateSubmittedWithTranscribeReportIdCompletesTranscription() {
        loginAs(List.of("followup:record:edit-submitted"));
        when(features.findEnabled("record_edit_submitted")).thenReturn(true);
        FollowupRecordService.RecordView updated =
                new FollowupRecordService.RecordView(
                        701L,
                        301L,
                        null,
                        "BACKFILL",
                        LocalDate.of(2025, 6, 1),
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "SUBMITTED",
                        3,
                        Map.of(),
                        Map.of(),
                        null,
                        "2026-11-05T09:00:00+08:00");
        when(recordService.updateSubmitted(eq(701L), eq(2), any(), any()))
                .thenReturn(
                        new FollowupRecordService.SubmitOutcome(
                                updated, Map.of(), List.of(), List.of()));
        when(fieldHistory.logChanges(any(), eq(701L), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of());

        RecordEntryService.UpdateResultView view =
                service.updateSubmitted(
                        701L,
                        new RecordEntryService.UpdateCommand(2, Map.of(), null, "按扫描件补全参数", 900L));

        verify(reportTranscribe).completeTranscription(900L, 701L, 301L, 1);
        assertEquals("900", view.transcribedReportId());
    }

    /** 删除：reason 必填、软删除 + 断开任务链接 + RECORD_DELETE 审计（C-FU-026 / PLT-AUD-001）。 */
    @Test
    void deleteSoftDeletesAndUnlinksTaskWithAudit() {
        loginAs(List.of("followup:record:delete"));
        when(recordService.record(701L)).thenReturn(record("SUBMITTED", Map.of()));
        when(recordMapper.softDelete(eq(701L), eq("重复录入"), eq(7L))).thenReturn(1);

        service.deleteRecord(701L, "重复录入");

        verify(recordMapper).softDelete(701L, "重复录入", 7L);
        verify(recordMapper).clearTaskRecordLink(701L);
        // E21-T09 / C-FU-018：软删出册后同一事务重排（后面记录序号回退）
        verify(sequences).resequence(301L, FollowupSequenceService.REASON_DELETE);
        verify(audit).append(any());
    }

    /** 删除：原因缺失 40001；软删除 0 行（已被删 / 不存在）40400。 */
    @Test
    void deleteRequiresReasonAndMissingRowIs40400() {
        loginAs(List.of("followup:record:delete"));

        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.deleteRecord(701L, null)).code());

        when(recordService.record(701L)).thenReturn(record("SUBMITTED", Map.of()));
        when(recordMapper.softDelete(anyLong(), any(), anyLong())).thenReturn(0);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.deleteRecord(701L, "重复录入")).code());
    }
}
