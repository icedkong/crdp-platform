package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.app.FollowupTerminationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private final FieldHistoryService fieldHistory = mock(FieldHistoryService.class);
    private final FollowupTaskStatusService statusService = mock(FollowupTaskStatusService.class);
    private final FollowupTerminationService terminationService =
            mock(FollowupTerminationService.class);
    private final FollowupTaskQueryMapper taskQueryMapper = mock(FollowupTaskQueryMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final RecordEntryService service =
            new RecordEntryService(
                    formService,
                    fieldHistory,
                    statusService,
                    terminationService,
                    taskQueryMapper,
                    crypto,
                    audit);

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

    private static FormService.RecordView record(String status, Map<String, Object> data) {
        return new FormService.RecordView(
                701L,
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
        when(formService.record(701L)).thenReturn(record("DRAFT", Map.of("f214", "2026-11-05")));
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

    @Test
    void detailWithoutTaskContextIsNull() {
        when(formService.record(702L)).thenReturn(record("DRAFT", Map.of()));
        when(taskQueryMapper.taskContextByRecordId(702L)).thenReturn(null);

        RecordEntryService.RecordDetailView view = service.detail(702L);

        assertNull(view.context(), "未关联任务 / 越范围的记录无任务上下文");
    }

    @Test
    void submitCompletesTaskAndWritesFieldHistoryAndAudit() {
        Map<String, Object> data = Map.of("f214", "2026-11-05", "f215", 0);
        when(formService.submitRecord(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FormService.SubmitOutcome(
                                record("SUBMITTED", data),
                                Map.of("f214", "2026-11-05"),
                                List.of(),
                                List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(taskRow("IN_PROGRESS"));
        when(fieldHistory.logChanges(
                        eq(FieldHistoryService.FORM_RECORD),
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
                service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null));

        assertEquals("COMPLETED", view.taskStatus(), "提交后任务完成（A-FU-005 验收位）");
        assertEquals("ON_TIME", view.completeKind());
        assertEquals(1, view.fieldChanges());
        assertEquals("9001", view.taskId());
        verify(terminationService, never()).terminateOnDeath(anyLong(), any(), any());
        verify(audit).append(any());
    }

    @Test
    void submitWithDeathRecordedTerminatesPatientTasks() {
        Map<String, Object> data = Map.of("f214", "2026-11-05", "f215", 1);
        when(formService.submitRecord(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FormService.SubmitOutcome(
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

        service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null));

        // OQ-79②：随访记录「是否死亡=是」→ 死亡登记（未开始任务全部终止，幂等入口）
        verify(terminationService).terminateOnDeath(eq(301L), eq("随访记录提交：是否死亡 = 是"), any());
    }

    @Test
    void submitWithoutLinkedTaskSkipsTaskCompletion() {
        Map<String, Object> data = Map.of("f214", "2026-11-05");
        when(formService.submitRecord(eq(701L), eq(2), eq(data), any()))
                .thenReturn(
                        new FormService.SubmitOutcome(
                                record("SUBMITTED", data), Map.of(), List.of(), List.of()));
        when(taskQueryMapper.taskContextByRecordId(701L)).thenReturn(null);
        when(fieldHistory.logChanges(any(), anyLong(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(List.of());

        RecordEntryService.SubmitResultView view =
                service.submit(701L, new RecordEntryService.SubmitCommand(2, data, null));

        assertNull(view.taskId());
        verify(statusService, never()).complete(anyLong(), any());
        verify(audit).append(any());
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
                                                        null, Map.of(), null)))
                        .code(),
                "baseVersion 必填（乐观锁）");
        verify(formService, never()).submitRecord(anyLong(), any(), any(), any());
    }
}
