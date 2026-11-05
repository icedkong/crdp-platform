package com.crdp.followup.contact.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.contact.infra.mapper.ContactLogMapper;
import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.plan.app.FollowupTerminationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E15-T04 / A-FU-006、A-FU-025：沟通记录写入口径（单测锁定，全部虚构数据）。
 *
 * <p>覆盖：患者数据范围前置校验（越范围 40400 不暴露存在）；方式 / 结果枚举校验（40001 提示完整字典）； 所用联系方式归属校验；关联任务归属校验；录音只收 AUDIO 类别且
 * PENDING（已挂他处 40001）； 第一次联系任务进入进行中；结果「告知已故」触发死亡终止；修改乐观锁 40900；删除连带软删录音（A-ATT-017）。
 */
@ExtendWith(MockitoExtension.class)
class ContactLogServiceTest {

    private static final long PATIENT = 301L;
    private static final long TASK = 9001L;
    private static final long OPERATOR = 7L;

    private final ContactLogMapper mapper = mock(ContactLogMapper.class);
    private final SysFileMapper files = mock(SysFileMapper.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final FollowupTaskStatusService taskStatus = mock(FollowupTaskStatusService.class);
    private final FollowupTerminationService termination = mock(FollowupTerminationService.class);
    private final FilePolicyService filePolicy = mock(FilePolicyService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ContactLogService service =
            new ContactLogService(
                    mapper,
                    files,
                    patientScope,
                    taskMapper,
                    taskStatus,
                    termination,
                    filePolicy,
                    audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient()
                .when(filePolicy.policy())
                .thenReturn(new FilePolicyService.UploadPolicy(List.of(), 20, 50, 10));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 创建

    @Test
    void createRecordsContactMovesTaskToInProgressAndAttachesAudio() {
        inScope();
        taskOfPatient();
        when(mapper.insert(any(ContactLogMapper.ContactLogInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation
                                    .getArgument(0, ContactLogMapper.ContactLogInsert.class)
                                    .setId(5001L);
                            return 1;
                        });
        SysFileRow mp3 = fileRow(6001L, "mp3", "PENDING");
        when(files.find(6001L)).thenReturn(mp3);
        when(files.markAttached(6001L)).thenReturn(1);
        stubRowView(5001L);

        ContactLogService.CreateResult result =
                service.create(
                        PATIENT,
                        new ContactLogService.SaveBody(
                                TASK,
                                "PHONE",
                                "REACHED",
                                null,
                                null,
                                LocalDateTime.of(2026, 11, 5, 9, 0).plusDays(1),
                                "患者诉近期胸闷，建议门诊复查（示例）",
                                List.of(6001L)));

        assertEquals("5001", result.log().id());
        assertEquals("REACHED", result.log().result());
        assertEquals(null, result.terminatedTasks(), "非「告知已故」不触发死亡终止");
        verify(files).markAttached(6001L);
        verify(mapper).insertAudio(5001L, 6001L, 0);
        verify(taskStatus).markInProgress(TASK, OPERATOR, "记录沟通");
        verify(termination, never()).terminateOnDeath(anyLong(), any(), any());
        verify(audit).append(any());
    }

    @Test
    void createWithDeceasedResultTerminatesPatientOnDeath() {
        inScope();
        when(mapper.insert(any(ContactLogMapper.ContactLogInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation
                                    .getArgument(0, ContactLogMapper.ContactLogInsert.class)
                                    .setId(5002L);
                            return 1;
                        });
        stubRowView(5002L);
        when(termination.terminateOnDeath(eq(PATIENT), any(), eq(OPERATOR)))
                .thenReturn(
                        new FollowupTerminationService.DeathTerminationView(
                                String.valueOf(PATIENT), "DEAD", 3));

        ContactLogService.CreateResult result =
                service.create(
                        PATIENT,
                        new ContactLogService.SaveBody(
                                null, "PHONE", "DECEASED", null, null, null, "家属告知已故（示例）", null));

        assertEquals(3, result.terminatedTasks(), "「告知已故」同事务终止未完成任务");
        verify(termination).terminateOnDeath(eq(PATIENT), eq("沟通记录「告知已故」"), eq(OPERATOR));
        verify(taskStatus, never()).markInProgress(anyLong(), any(), any());
    }

    @Test
    void createRejectsUnknownResultAndMethodWithFullDictionaryHint() {
        inScope();

        ApiException result =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ContactLogService.SaveBody(
                                                null, "PHONE", "GAVE_UP", null, null, null, null,
                                                null)));
        assertEquals(40001, result.code());
        assertEquals(
                true, result.getMessage().contains("告知已故"), "错误提示含完整六值字典：" + result.getMessage());

        ApiException method =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ContactLogService.SaveBody(
                                                null, "SMS", "REACHED", null, null, null, null,
                                                null)));
        assertEquals(40001, method.code());
    }

    @Test
    void createHidesExistenceForOutOfScopePatient() {
        when(patientScope.patientIdInScope(PATIENT)).thenReturn(null);

        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                PATIENT,
                                                new ContactLogService.SaveBody(
                                                        null, "PHONE", "REACHED", null, null, null,
                                                        null, null)))
                        .code(),
                "越范围患者不暴露存在（AGENTS §5.2）");
        verify(mapper, never()).insert(any(ContactLogMapper.ContactLogInsert.class));
    }

    @Test
    void createRejectsTaskOfAnotherPatientAndMissingTask() {
        inScope();
        when(taskMapper.findTaskById(TASK))
                .thenReturn(
                        new FollowupTaskMapper.TaskRow(
                                TASK,
                                81L,
                                999L,
                                1L,
                                11L,
                                1,
                                "NORMAL",
                                "PENDING",
                                java.time.LocalDate.of(2026, 11, 5),
                                java.time.LocalDate.of(2026, 10, 29)));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                PATIENT,
                                                new ContactLogService.SaveBody(
                                                        TASK, "PHONE", "REACHED", null, null, null,
                                                        null, null)))
                        .code(),
                "任务与患者不一致 40001");
        when(taskMapper.findTaskById(4040L)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                PATIENT,
                                                new ContactLogService.SaveBody(
                                                        4040L, "PHONE", "REACHED", null, null, null,
                                                        null, null)))
                        .code());
    }

    @Test
    void createRejectsNonAudioOrAlreadyAttachedFile() {
        inScope();
        when(mapper.insert(any(ContactLogMapper.ContactLogInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation
                                    .getArgument(0, ContactLogMapper.ContactLogInsert.class)
                                    .setId(5003L);
                            return 1;
                        });
        when(files.find(6002L)).thenReturn(fileRow(6002L, "pdf", "PENDING"));

        ApiException notAudio =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ContactLogService.SaveBody(
                                                null,
                                                "WECHAT",
                                                "NOT_REACHED",
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of(6002L))));
        assertEquals(40001, notAudio.code(), "只接受录音文件（MP3 / M4A / WAV / AMR）");

        when(files.find(6003L)).thenReturn(fileRow(6003L, "amr", "PENDING"));
        when(files.markAttached(6003L)).thenReturn(0);
        ApiException attached =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ContactLogService.SaveBody(
                                                null,
                                                "PHONE",
                                                "REACHED",
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of(6003L))));
        assertEquals(40001, attached.code(), "已挂他处的录音拒绝重复关联");

        when(files.find(4040L)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                PATIENT,
                                                new ContactLogService.SaveBody(
                                                        null,
                                                        "PHONE",
                                                        "REACHED",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        List.of(4040L))))
                        .code(),
                "录音文件不存在 40400");
    }

    @Test
    void createRejectsContactVersionOfAnotherPatient() {
        inScope();
        when(patientScope.versionsOf(PATIENT)).thenReturn(List.of());

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                PATIENT,
                                                new ContactLogService.SaveBody(
                                                        null, "PHONE", "REACHED", null, 777L, null,
                                                        null, null)))
                        .code(),
                "所用联系方式必须属于该患者");
    }

    @Test
    void createRejectsNextContactTimeNotAfterContactTime() {
        inScope();
        LocalDateTime time = LocalDateTime.of(2026, 11, 5, 9, 0);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                PATIENT,
                                                new ContactLogService.SaveBody(
                                                        null, "PHONE", "REACHED", time, null, time,
                                                        null, null)))
                        .code(),
                "下次联系时间必须晚于本次沟通时间");
    }

    // ---------------------------------------------------------------- 修改与删除

    @Test
    void updateUsesOptimisticLockAndReturns40900OnConflict() {
        inScope();
        stubRowView(5001L);
        when(mapper.update(eq(5001L), eq(0), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertEquals(
                40900,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.update(
                                                5001L,
                                                new ContactLogService.UpdateBody(
                                                        "PHONE",
                                                        "NOT_REACHED",
                                                        null,
                                                        null,
                                                        null,
                                                        "再次拨打电话（示例）",
                                                        0)))
                        .code(),
                "乐观锁冲突 40900（AGENTS §5.10）");
    }

    @Test
    void updateToDeceasedTriggersDeathTerminationOnlyWhenChanged() {
        inScope();
        stubRowView(5001L);
        when(mapper.update(eq(5001L), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        stubRowView(5001L);

        service.update(
                5001L,
                new ContactLogService.UpdateBody(
                        "PHONE", "DECEASED", null, null, null, "补充告知已故（示例）", 0));
        verify(termination).terminateOnDeath(eq(PATIENT), any(), eq(OPERATOR));
    }

    @Test
    void deleteRemovesRowAndSoftDeletesAudios() {
        inScope();
        stubRowView(5001L);
        when(mapper.audiosOf(List.of(5001L)))
                .thenReturn(
                        List.of(
                                new ContactLogMapper.AudioRow(
                                        5001L,
                                        6001L,
                                        "mp3",
                                        "通话录音（示例）.mp3",
                                        "audio/mpeg",
                                        2048L,
                                        0)));
        when(mapper.delete(5001L)).thenReturn(1);

        service.delete(5001L);

        verify(mapper).deleteAudiosOf(5001L);
        verify(files).markDeleted(6001L);
        verify(audit).append(any());
    }

    @Test
    void deleteHidesExistenceForOutOfScopePatient() {
        when(mapper.find(5001L)).thenReturn(row(5001L, "REACHED"));
        when(patientScope.patientIdInScope(PATIENT)).thenReturn(null);

        assertEquals(40400, assertThrows(ApiException.class, () -> service.delete(5001L)).code());
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    void latestResultByPatientsPicksNewestRowPerPatient() {
        when(mapper.resultsOfPatients(List.of(PATIENT, 302L)))
                .thenReturn(
                        List.of(
                                new ContactLogMapper.LatestResultRow(PATIENT, 10L, "NOT_REACHED"),
                                new ContactLogMapper.LatestResultRow(PATIENT, 22L, "REACHED"),
                                new ContactLogMapper.LatestResultRow(302L, 15L, "REFUSED")));

        assertEquals(
                java.util.Map.of(PATIENT, "REACHED", 302L, "REFUSED"),
                service.latestResultByPatients(List.of(PATIENT, 302L)),
                "任务列表「最近联系」取每患者最新一条");
        assertEquals(java.util.Map.of(), service.latestResultByPatients(List.of()));
    }

    // ---------------------------------------------------------------- 造数

    private void inScope() {
        lenient().when(patientScope.patientIdInScope(PATIENT)).thenReturn(PATIENT);
        lenient().when(patientScope.versionsOf(PATIENT)).thenReturn(List.of());
    }

    private void taskOfPatient() {
        lenient()
                .when(taskMapper.findTaskById(TASK))
                .thenReturn(
                        new FollowupTaskMapper.TaskRow(
                                TASK,
                                81L,
                                PATIENT,
                                1L,
                                11L,
                                1,
                                "NORMAL",
                                "PENDING",
                                java.time.LocalDate.of(2026, 11, 5),
                                java.time.LocalDate.of(2026, 10, 29)));
    }

    private static ContactLogMapper.ContactLogRow row(long id, String result) {
        return new ContactLogMapper.ContactLogRow(
                id,
                PATIENT,
                null,
                "PHONE",
                result,
                LocalDateTime.of(2026, 11, 5, 9, 0),
                null,
                null,
                null,
                0,
                OPERATOR,
                "王医生（示例）",
                LocalDateTime.of(2026, 11, 5, 9, 0),
                LocalDateTime.of(2026, 11, 5, 9, 0));
    }

    private void stubRowView(long id) {
        lenient().when(mapper.find(id)).thenReturn(row(id, "REACHED"));
        lenient().when(mapper.audiosOf(anyList())).thenReturn(List.of());
    }

    private static SysFileRow fileRow(long id, String format, String status) {
        SysFileRow row = new SysFileRow();
        row.setId(id);
        row.setObjectKey("t/demoa/files/202611/x." + format);
        row.setOriginalName("通话录音（示例）." + format);
        row.setFormatCode(format);
        row.setMimeType("audio/mpeg");
        row.setSizeBytes(2048L);
        row.setSha256("0123abcd".repeat(8));
        row.setStatus(status);
        row.setUploaderId(OPERATOR);
        return row;
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "doctor.demo",
                "王医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                null);
    }
}
