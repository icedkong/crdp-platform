package com.crdp.followup.task.app;

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
import com.crdp.followup.record.app.FollowupRecordService;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E15-T02 / A-FU-005：开始录入（POST /tasks/{id}/start）的业务层口径。
 *
 * <p>锁定：患者数据范围前置校验（越范围 40400 不暴露任务存在）；REMINDER 无表单 40901； 已关联草稿幂等返回（不重复创建 / 不重复流转）；首次开始创建 DRAFT 草稿
 * + 回填 record_id + 任务进入进行中 + TASK_START 审计；并发回填报错时返回已胜出的记录。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FollowupTaskStartServiceTest {

    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final FollowupTaskStatusService statusService = mock(FollowupTaskStatusService.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final FollowupRecordService recordService = mock(FollowupRecordService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final FollowupTaskStartService service =
            new FollowupTaskStartService(
                    taskMapper, statusService, patientScope, recordService, audit);

    private static FollowupTaskMapper.StartTaskRow formTask(Long recordId) {
        return new FollowupTaskMapper.StartTaskRow(
                9001L, 301L, "FORM", "A_ANNUAL_FU", "PENDING", recordId);
    }

    @Test
    void startCreatesDraftLinksAndMovesTaskToInProgress() {
        when(taskMapper.findStartTaskById(9001L)).thenReturn(formTask(null));
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                701L,
                                301L,
                                9001L,
                                "TASK",
                                null,
                                "A_ANNUAL_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                "2026-11-05T09:00:00+08:00"));
        when(taskMapper.linkRecord(9001L, 701L)).thenReturn(1);

        FollowupTaskStartService.StartView view = service.start(9001L);

        assertEquals("9001", view.taskId());
        assertEquals("701", view.recordId(), "统一用 recordId 路由（pages.md §5）");
        assertEquals(true, view.recordCreated());
        verify(statusService).markInProgress(eq(9001L), any(), eq("开始录入"));
        verify(audit).append(any());
    }

    @Test
    void startIsIdempotentWhenRecordAlreadyLinked() {
        when(taskMapper.findStartTaskById(9001L)).thenReturn(formTask(701L));
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);

        FollowupTaskStartService.StartView view = service.start(9001L);

        assertEquals("701", view.recordId());
        assertEquals(false, view.recordCreated());
        verify(recordService, never()).createDraft(any());
        verify(statusService, never()).markInProgress(anyLong(), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void reminderTaskWithoutFormIsRejected() {
        when(taskMapper.findStartTaskById(9002L))
                .thenReturn(
                        new FollowupTaskMapper.StartTaskRow(
                                9002L, 301L, "REMINDER", null, "PENDING", null));
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);

        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.start(9002L)).code(),
                "站内提醒类任务无需填表（A-FU-026）");
        verify(recordService, never()).createDraft(any());
    }

    @Test
    void outOfScopePatientHidesTaskExistence() {
        when(taskMapper.findStartTaskById(9001L)).thenReturn(formTask(null));
        when(patientScope.patientIdInScope(301L)).thenReturn(null);

        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.start(9001L)).code(),
                "越范围患者不暴露任务存在（AGENTS §5.2）");
        verify(recordService, never()).createDraft(any());
    }

    @Test
    void missingTaskIs40400() {
        when(taskMapper.findStartTaskById(4040L)).thenReturn(null);

        assertEquals(40400, assertThrows(ApiException.class, () -> service.start(4040L)).code());
    }

    @Test
    void concurrentLinkMissReturnsWinnerRecord() {
        when(taskMapper.findStartTaskById(9001L)).thenReturn(formTask(null), formTask(701L));
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(recordService.createDraft(any()))
                .thenReturn(
                        new FollowupRecordService.RecordView(
                                702L,
                                301L,
                                9001L,
                                "TASK",
                                null,
                                "A_ANNUAL_FU",
                                1,
                                "DRAFT",
                                0,
                                Map.of(),
                                Map.of(),
                                null,
                                "2026-11-05T09:00:00+08:00"));
        when(taskMapper.linkRecord(9001L, 702L)).thenReturn(0);

        FollowupTaskStartService.StartView view = service.start(9001L);

        assertEquals("701", view.recordId(), "并发重复开始：已关联的记录胜出（uk_fu_task_record 兜底）");
        assertEquals(false, view.recordCreated());
        verify(statusService, never()).markInProgress(anyLong(), any(), any());
    }
}
