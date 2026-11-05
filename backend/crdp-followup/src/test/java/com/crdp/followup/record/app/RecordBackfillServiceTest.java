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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E15-T07 / A-FU-014、C-FU-013：历史补录业务口径。
 *
 * <p>锁定：越范围患者 40400（不暴露存在）；来源只收 BACKFILL（计划外随 E15-T06、远程监测随 E21）； 实际随访日期必填且必须是过去的日期；表单缺省取在组计划 FORM
 * 节点、显式指定时校验存在；创建 source=BACKFILL 的 DRAFT 并写 RECORD_BACKFILL_CREATE 审计。全部虚构数据。
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

    private static final LocalDate PAST = LocalDate.now().minusDays(30);

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
    void unplannedAndRemoteSourcesAreRejected() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                301L,
                                                new RecordBackfillService.BackfillCommand(
                                                        "UNPLANNED", PAST, null)))
                        .code(),
                "计划外随访随 E15-T06");
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
                                                        "BACKFILL", LocalDate.now(), null)))
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
