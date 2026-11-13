package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E15-T07 / A-FU-014、C-FU-013：历史补录业务口径；E15-T06 起 UNPLANNED 计划外随访（A-FU-013、C-FU-022）； E21-T14 /
 * C-FU-014 补录建议随访时间点带入与植入日期下界。
 *
 * <p>锁定：越范围患者 40400（不暴露存在）；补录只收过去日期（计划外允许当天）；计划外须患者在随访中（ACTIVE）、 不创建任务、不动后续节点（C-FU-022）；E21-T12
 * 起远程监测（REMOTE / C-FU-025）随功能开关 remote_monitor_record 开放：关闭 40301、开启当天可录且须在随访中、
 * 同一表单人工录入、不做时间点建议；表单缺省取在组计划 FORM 节点、显式指定时校验存在； 创建 source=BACKFILL / UNPLANNED / REMOTE 的 DRAFT
 * 并写各自审计。E21-T14 起：BACKFILL 创建把建议随访时间点 种进草稿（可改选）、随访日期不得早于最早植入日期（40001）；UNPLANNED
 * 不建议（C-FU-022「随访时间点选其他」）。 全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class RecordBackfillServiceTest {

    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final FollowupRecordService recordService = mock(FollowupRecordService.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final FormService formService = mock(FormService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final TimepointSuggestionService suggestions = mock(TimepointSuggestionService.class);
    private final com.crdp.followup.record.infra.mapper.RecordFeatureMapper features =
            mock(com.crdp.followup.record.infra.mapper.RecordFeatureMapper.class);

    private final RecordBackfillService service =
            new RecordBackfillService(
                    patientScope,
                    recordService,
                    recordMapper,
                    formService,
                    suggestions,
                    audit,
                    features);

    /** 业务日期口径：服务端按北京时间（Asia/Shanghai）判定「今天 / 未来」，测试同口径才不受运行时区影响。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static final LocalDate PAST = LocalDate.now(BEIJING).minusDays(30);

    @Test
    void createsBackfillDraftWithDerivedFormAndAudit() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn("A_ANNUAL_FU");
        stubSuggestion("A_ANNUAL_FU", null, null);
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                701L,
                                301L,
                                null,
                                "BACKFILL",
                                PAST,
                                null,
                                "A_ANNUAL_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                null));

        RecordBackfillService.BackfillView view =
                service.create(
                        301L, new RecordBackfillService.BackfillCommand("BACKFILL", PAST, null));

        assertEquals("701", view.recordId(), "创建后统一用 recordId 进入表单（pages.md §5）");
        assertEquals(PAST, view.visitDate());
        assertEquals("A_ANNUAL_FU", view.formCode(), "缺省表单取在组计划 FORM 节点");
        ArgumentCaptor<FollowupRecordService.CreateCommand> captor =
                ArgumentCaptor.forClass(FollowupRecordService.CreateCommand.class);
        verify(recordService).createDraft(captor.capture());
        assertEquals("BACKFILL", captor.getValue().source(), "标记为补录（A-FU-014）");
        assertEquals(PAST, captor.getValue().visitDate(), "填写实际随访日期");
        assertEquals(null, captor.getValue().taskId(), "不需要任务、不受窗口限制（C-FU-013）");
        verify(audit).append(any());
    }

    @Test
    void explicitFormCodeIsChecked() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(formService.publishedVersion("A_ANNUAL_FU")).thenReturn(2);
        stubSuggestion("A_ANNUAL_FU", null, null);
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                702L,
                                301L,
                                null,
                                "BACKFILL",
                                PAST,
                                null,
                                "A_ANNUAL_FU",
                                2,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                null));

        service.create(
                301L, new RecordBackfillService.BackfillCommand("BACKFILL", PAST, "A_ANNUAL_FU"));

        verify(recordMapper, never()).findEnrollmentFormCode(301L);
    }

    // ---------------------------------------------------------------- E21-T14 / C-FU-014（建议时间点带入）

    @Test
    void backfillSeedsSuggestedTimepointIntoDraft() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(formService.publishedVersion("C_PROGRAM_FU")).thenReturn(1);
        stubSuggestion("C_PROGRAM_FU", null, 2);
        when(suggestions.initialData("C_PROGRAM_FU", suggestionView(null, 2)))
                .thenReturn(Map.of("f304", 2));
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                703L,
                                301L,
                                null,
                                "BACKFILL",
                                PAST,
                                null,
                                "C_PROGRAM_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                null));

        service.create(
                301L, new RecordBackfillService.BackfillCommand("BACKFILL", PAST, "C_PROGRAM_FU"));

        ArgumentCaptor<FollowupRecordService.CreateCommand> captor =
                ArgumentCaptor.forClass(FollowupRecordService.CreateCommand.class);
        verify(recordService).createDraft(captor.capture());
        assertEquals(Map.of("f304", 2), captor.getValue().data(), "建议随访时间点种进草稿（可改选，C-FU-014）");
    }

    @Test
    void backfillDateBeforeEarliestImplantIsRejected() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(formService.publishedVersion("C_PROGRAM_FU")).thenReturn(1);
        // 最早植入 2021-06-14，补录日期早于它（C 规格 §5「补录一份纸质随访记录」：不早于植入日期）
        stubSuggestion("C_PROGRAM_FU", LocalDate.of(2021, 6, 14), null);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "BACKFILL",
                                                        LocalDate.of(2021, 6, 1),
                                                        "C_PROGRAM_FU")))
                        .code(),
                "补录随访日期不能早于植入日期");
        verify(recordService, never()).createDraft(any());
    }

    @Test
    void unplannedDoesNotConsultTimepointSuggestion() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordMapper.findPatientFollowupStatus(301L)).thenReturn("ACTIVE");
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn("A_ANNUAL_FU");
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                802L,
                                301L,
                                null,
                                "UNPLANNED",
                                LocalDate.now(BEIJING),
                                null,
                                "A_ANNUAL_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                null));

        service.create(
                301L,
                new RecordBackfillService.BackfillCommand(
                        "UNPLANNED", LocalDate.now(BEIJING), null));

        verify(suggestions, never()).suggest(anyLong(), any(), any());
        verify(suggestions, never()).initialData(any(), any());
    }

    /** 建议服务桩：earliest / code 控制「下界校验」与「种入值」两个分支（null = 无）。 */
    private void stubSuggestion(String formCode, LocalDate earliest, Integer code) {
        when(suggestions.suggest(eq(301L), any(), eq(formCode)))
                .thenReturn(suggestionView(earliest, code));
    }

    private static TimepointSuggestionService.SuggestionView suggestionView(
            LocalDate earliest, Integer code) {
        return new TimepointSuggestionService.SuggestionView(
                "301",
                PAST,
                null,
                earliest,
                code == null ? null : 31,
                false,
                code,
                code == null ? null : "术后1个月");
    }

    @Test
    void outOfScopePatientIs40400() {
        when(patientScope.patientIdInScope(301L)).thenReturn(null);

        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "BACKFILL", PAST, null)))
                        .code(),
                "越范围患者不暴露存在");
        verify(recordService, never()).createDraft(any());
    }

    @Test
    void remoteAndUnknownSourcesAreRejected() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        // 开关关闭（无行 / 0 = A / B 默认）：40301，前端入口随 GET /auth/features 隐藏（AGENTS §5.7）
        assertEquals(
                40301,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "REMOTE", PAST, null)))
                        .code(),
                "远程监测记录未开通（remote_monitor_record 关闭）");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "TASK", PAST, null)))
                        .code(),
                "任务录入走 POST /tasks/{id}/start");
        verify(recordService, never()).createDraft(any());
    }

    // ---------------------------------------------------------------- 远程监测记录（E21-T12 / C-FU-025）

    @Test
    void createsRemoteDraftTodayForActivePatientWhenFeatureOn() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(features.findEnabled("remote_monitor_record")).thenReturn(true);
        when(recordMapper.findPatientFollowupStatus(301L)).thenReturn("ACTIVE");
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn("C_PROGRAM_FU");
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                821L,
                                301L,
                                null,
                                "REMOTE",
                                LocalDate.now(BEIJING),
                                null,
                                "C_PROGRAM_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                null));

        RecordBackfillService.BackfillView view =
                service.create(
                        301L,
                        new RecordBackfillService.BackfillCommand(
                                "REMOTE", LocalDate.now(BEIJING), null));

        assertEquals("821", view.recordId(), "创建后统一用 recordId 进入表单（同一表单人工录入）");
        ArgumentCaptor<FollowupRecordService.CreateCommand> captor =
                ArgumentCaptor.forClass(FollowupRecordService.CreateCommand.class);
        verify(recordService).createDraft(captor.capture());
        assertEquals("REMOTE", captor.getValue().source(), "source = REMOTE，与门诊程控记录区分（C-FU-025）");
        assertEquals(null, captor.getValue().taskId(), "不创建任务");
        assertEquals(LocalDate.now(BEIJING), captor.getValue().visitDate(), "远程传输收到当天即可录（与计划外同口径）");
        verify(audit).append(any());
        // 远程传输不对应术后节点：不做时间点建议（与补录的 C-FU-014 分开）
        verify(suggestions, never()).suggest(anyLong(), any(), any());
        verify(suggestions, never()).initialData(any(), any());
    }

    @Test
    void remoteValidatesFeatureDateAndPatientStatus() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(features.findEnabled("remote_monitor_record")).thenReturn(true);
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "REMOTE", null, null)))
                        .code(),
                "随访日期必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "REMOTE",
                                                        LocalDate.now(BEIJING).plusDays(1),
                                                        null)))
                        .code(),
                "未来日期拒绝");
        for (String status : java.util.List.of("DEAD", "TERMINATED", "LOST")) {
            when(recordMapper.findPatientFollowupStatus(301L)).thenReturn(status);
            assertEquals(
                    40901,
                    assertThrows(
                                    ApiException.class,
                                    () ->
                                            service.create(
                                                    301L,
                                                    new RecordBackfillService.BackfillCommand(
                                                            "REMOTE",
                                                            LocalDate.now(BEIJING),
                                                            null)))
                            .code(),
                    "患者不在随访中（" + status + "）不能录入远程监测记录");
        }
        verify(recordService, never()).createDraft(any());
    }

    // ---------------------------------------------------------------- 计划外随访（E15-T06 / C-FU-022）

    @Test
    void createsUnplannedDraftTodayForActivePatient() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordMapper.findPatientFollowupStatus(301L)).thenReturn("ACTIVE");
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn("A_ANNUAL_FU");
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                801L,
                                301L,
                                null,
                                "UNPLANNED",
                                LocalDate.now(BEIJING),
                                null,
                                "A_ANNUAL_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                null));

        RecordBackfillService.BackfillView view =
                service.create(
                        301L,
                        new RecordBackfillService.BackfillCommand(
                                "UNPLANNED", LocalDate.now(BEIJING), null));

        assertEquals("801", view.recordId());
        ArgumentCaptor<FollowupRecordService.CreateCommand> captor =
                ArgumentCaptor.forClass(FollowupRecordService.CreateCommand.class);
        verify(recordService).createDraft(captor.capture());
        assertEquals("UNPLANNED", captor.getValue().source(), "计划外随访标记来源（C-FU-022）");
        assertEquals(null, captor.getValue().taskId(), "不创建任务、不影响后续节点");
        verify(audit).append(any());
    }

    @Test
    void unplannedRequiresActivePatientAndNonFutureDate() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "UNPLANNED", null, null)))
                        .code(),
                "随访日期必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "UNPLANNED",
                                                        LocalDate.now(BEIJING).plusDays(1),
                                                        null)))
                        .code(),
                "未来日期拒绝（未来随访由计划排期）");
        for (String status : java.util.List.of("DEAD", "TERMINATED", "LOST")) {
            when(recordMapper.findPatientFollowupStatus(301L)).thenReturn(status);
            assertEquals(
                    40901,
                    assertThrows(
                                    ApiException.class,
                                    () ->
                                            service.create(
                                                    301L,
                                                    new RecordBackfillService.BackfillCommand(
                                                            "UNPLANNED",
                                                            LocalDate.now(BEIJING),
                                                            null)))
                            .code(),
                    "患者不在随访中（" + status + "）不能新建计划外随访");
        }
        verify(recordService, never()).createDraft(any());
    }

    @Test
    void visitDateMustBeProvidedAndInThePast() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "BACKFILL", null, null)))
                        .code(),
                "实际随访日期必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "BACKFILL", LocalDate.now(BEIJING), null)))
                        .code(),
                "补录随访日期必须是过去的日期（当天走任务 / 工作台）");
    }

    @Test
    void noResolvableFormIs40001() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn(null);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "BACKFILL", PAST, null)))
                        .code(),
                "在组计划没有可用表单时提示显式指定");
    }
}
