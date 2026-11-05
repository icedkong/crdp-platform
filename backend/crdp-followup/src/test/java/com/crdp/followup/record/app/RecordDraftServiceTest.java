package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E25-T01 / NFR-SAVE-001、NFR-SAVE-003：随访记录服务器草稿接口的业务层口径。
 *
 * <p>锁定：自动保存不逐次写审计（tech-plan「范围与审计」）；「保留我的并覆盖」（overwrite=true， patient.md §4.1）由后端写
 * RECORD_DRAFT_OVERWRITE 审计且只记字段键不记值（敏感值只存摘要）； baseVersion 缺失 / changes 为空 40001；引擎的 409
 * 冲突原样透传给前端（ConflictBanner 暂停自动保存）。
 *
 * <p>E15-T02 追加（OQ-77② 接入点）：保存成功后关联任务（fu_task.record_id）仍在待随访时进入进行中（「第一次暂存草稿 →
 * 进行中」）；无关联任务不联动。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class RecordDraftServiceTest {

    private final FollowupRecordService recordService = mock(FollowupRecordService.class);
    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final FollowupTaskStatusService statusService = mock(FollowupTaskStatusService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final RecordDraftService service =
            new RecordDraftService(recordService, taskMapper, statusService, audit);

    private static FollowupRecordService.RecordView fuRecord(int version) {
        return new FollowupRecordService.RecordView(
                101L,
                301L,
                9001L,
                "TASK",
                null,
                "A_ANNUAL_FU",
                1,
                "DRAFT",
                version,
                Map.of("f214", "2026-03-10"),
                Map.of(),
                null,
                "2026-03-10T10:15:00+08:00");
    }

    @Test
    void savesDraftWithoutAuditByDefault() {
        when(recordService.saveDraft(101L, 2, Map.of("f214", "2026-03-10")))
                .thenReturn(fuRecord(3));

        RecordDraftService.DraftSavedView view =
                service.saveDraft(
                        101L,
                        new RecordDraftService.DraftCommand(2, Map.of("f214", "2026-03-10"), null));

        assertEquals(3, view.version());
        assertEquals("2026-03-10T10:15:00+08:00", view.savedAt());
        verify(audit, never()).append(any());
    }

    @Test
    void overwriteWritesAuditWithFieldKeysOnly() {
        when(recordService.saveDraft(101L, 7, Map.of("f214", "2026-03-10")))
                .thenReturn(fuRecord(8));

        service.saveDraft(
                101L, new RecordDraftService.DraftCommand(7, Map.of("f214", "2026-03-10"), true));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("followup", entry.module());
        assertEquals("RECORD_DRAFT_OVERWRITE", entry.action());
        assertEquals("FU_RECORD", entry.objectType());
        assertEquals("101", entry.objectId());
        assertTrue(entry.description().contains("覆盖"), "描述点明覆盖他人版本：" + entry.description());
        assertTrue(entry.afterJson().contains("f214"), "审计记变更字段键");
        assertTrue(
                !entry.afterJson().contains("2026-03-10"),
                "审计不落字段值（敏感值只存摘要，AGENTS.md §5.5）：" + entry.afterJson());
    }

    @Test
    void rejectsMissingBaseVersionAndEmptyChanges() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.saveDraft(
                                                101L,
                                                new RecordDraftService.DraftCommand(
                                                        null, Map.of("f214", "2026-03-10"), null)))
                        .code(),
                "baseVersion 必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.saveDraft(
                                                101L,
                                                new RecordDraftService.DraftCommand(2, null, null)))
                        .code(),
                "changes 必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.saveDraft(
                                                101L,
                                                new RecordDraftService.DraftCommand(
                                                        2, Map.of(), null)))
                        .code(),
                "只在有新改动时发送（空 changes 是客户端缺陷）");
        verify(recordService, never()).saveDraft(anyLong(), any(), any());
    }

    @Test
    void engineConflictPassesThrough() {
        ApiException conflict =
                new ApiException(
                        409,
                        40900,
                        "该记录已被他人更新",
                        new FollowupRecordService.DraftConflictView(
                                7, "张医生（示例）", "2026-10-31T10:12:00+08:00", List.of()));
        when(recordService.saveDraft(101L, 2, Map.of("f214", "2026-03-10"))).thenThrow(conflict);

        ApiException thrown =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveDraft(
                                        101L,
                                        new RecordDraftService.DraftCommand(
                                                2, Map.of("f214", "2026-03-10"), true)));

        assertSame(conflict, thrown, "409 冲突详情原样透传（前端暂停自动保存并弹 ConflictBanner）");
        verify(audit, never()).append(any());
    }

    @Test
    void linkedPendingTaskMovesToInProgressOnFirstDraftSave() {
        when(recordService.saveDraft(101L, 2, Map.of("f214", "2026-03-10")))
                .thenReturn(fuRecord(3));
        when(taskMapper.findTaskByRecordId(101L))
                .thenReturn(new FollowupTaskMapper.RecordLinkedTaskRow(9001L, "PENDING"));

        service.saveDraft(
                101L, new RecordDraftService.DraftCommand(2, Map.of("f214", "2026-03-10"), null));

        // OQ-77②：第一次暂存草稿 → 进行中（markInProgress 幂等守卫由状态服务承担，本层恒调）
        verify(statusService).markInProgress(9001L, null, "暂存草稿");
    }

    @Test
    void unlinkedRecordDoesNotTouchTasks() {
        when(recordService.saveDraft(101L, 2, Map.of("f214", "2026-03-10")))
                .thenReturn(fuRecord(3));
        when(taskMapper.findTaskByRecordId(101L)).thenReturn(null);

        service.saveDraft(
                101L, new RecordDraftService.DraftCommand(2, Map.of("f214", "2026-03-10"), null));

        verify(statusService, never()).markInProgress(anyLong(), any(), any());
    }
}
