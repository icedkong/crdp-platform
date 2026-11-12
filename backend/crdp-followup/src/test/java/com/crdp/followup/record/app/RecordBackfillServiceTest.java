package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
 * E15-T07 / A-FU-014、C-FU-013：历史补录业务口径；E15-T06 起 UNPLANNED 计划外随访（A-FU-013、C-FU-022）。
 *
 * <p>锁定：越范围患者 40400（不暴露存在）；补录只收过去日期（计划外允许当天）；计划外须患者在随访中（ACTIVE）、 不创建任务、不动后续节点（C-FU-022）；远程监测随 E21 仍
 * 40001；表单缺省取在组计划 FORM 节点、显式指定时校验存在； 创建 source=BACKFILL / UNPLANNED 的 DRAFT 并写各自审计。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class RecordBackfillServiceTest {

    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final FollowupRecordService recordService = mock(FollowupRecordService.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final FormService formService = mock(FormService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final RecordBackfillService service =
            new RecordBackfillService(
                    patientScope, recordService, recordMapper, formService, audit);

    /** 业务日期口径：服务端按北京时间（Asia/Shanghai）判定「今天 / 未来」，测试同口径才不受运行时区影响。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static final LocalDate PAST = LocalDate.now(BEIJING).minusDays(30);

    @Test
    void createsBackfillDraftWithDerivedFormAndAudit() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn("A_ANNUAL_FU");
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
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "REMOTE", PAST, null)))
                        .code(),
                "远程监测随 E21");
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
