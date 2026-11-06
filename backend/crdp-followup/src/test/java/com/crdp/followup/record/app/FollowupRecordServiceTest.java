package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.crdp.followup.record.infra.mapper.FollowupRecordInsert;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * E15-T07 / A-FU-014、C-FU-013：fu_record 载体（OQ-83① 迁移点）上的记录生命周期。
 *
 * <p>锁定：创建固化发布版并走写入闸门；草稿增量合并（null 清空键）、冲突详情（currentVersion / 更新人 / diffFields 只列 两边不同的键）、已提交
 * 40901；提交完整校验 → 隐藏字段清空 → SUBMITTED。HTTP 契约与 fe_record 载体一致。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FollowupRecordServiceTest {

    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final FormService formService = mock(FormService.class);

    private final FollowupRecordService service =
            new FollowupRecordService(recordMapper, formService);

    private static final FollowupRecordMapper.RecordRowRaw ROW =
            new FollowupRecordMapper.RecordRowRaw(
                    701L,
                    301L,
                    9001L,
                    "TASK",
                    null,
                    "A_ANNUAL_FU",
                    1,
                    "DRAFT",
                    2,
                    "{\"f214\":\"2026-11-05\",\"f213\":1}",
                    "{}",
                    7L,
                    LocalDateTime.of(2026, 11, 1, 9, 0),
                    8L,
                    LocalDateTime.of(2026, 11, 5, 10, 0));

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDraftFixatesPublishedVersionAndRunsWriteGate() {
        when(formService.publishedVersion("A_ANNUAL_FU")).thenReturn(3);
        when(formService.writeGateData(eq("A_ANNUAL_FU"), eq(3), any()))
                .thenReturn(Map.of("f214", "2026-01-10"));
        when(recordMapper.insertRecord(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<FollowupRecordInsert>getArgument(0).setId(888L);
                            return 1;
                        });
        when(recordMapper.findRecord(888L)).thenReturn(rowWithStatus("DRAFT"));

        FollowupRecordService.RecordView created =
                service.createDraft(
                        new FollowupRecordService.CreateCommand(
                                301L,
                                null,
                                FollowupRecordService.SOURCE_BACKFILL,
                                LocalDate.of(2025, 6, 1),
                                "A_ANNUAL_FU",
                                Map.of(),
                                Map.of()));
        assertEquals(888L, created.id());

        assertEquals("BACKFILL", created.source(), "补录记录标记来源（A-FU-014 标记为补录）");
        assertEquals(LocalDate.of(2025, 6, 1), created.visitDate(), "补录带实际随访日期");
        assertEquals(301L, created.patientId());
        ArgumentCaptor<FollowupRecordInsert> captor =
                ArgumentCaptor.forClass(FollowupRecordInsert.class);
        verify(recordMapper).insertRecord(captor.capture());
        assertEquals(3, captor.getValue().getTemplateVersion(), "创建时固化发布版（OQ-63②）");
        assertEquals(LocalDate.of(2025, 6, 1), captor.getValue().getVisitDate());
        assertNull(captor.getValue().getTaskId(), "补录不需要任务（C-FU-013）");
    }

    @Test
    void saveDraftMergesChangesAndClearsNullKeys() {
        when(recordMapper.findRecord(701L)).thenReturn(ROW);
        when(formService.writeGateData(eq("A_ANNUAL_FU"), eq(1), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, Map.class));
        when(recordMapper.updateDraft(eq(701L), eq(2), any(), eq(7L))).thenReturn(1);

        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        changes.put("f213", null); // null = 清空该键（Map.of 不收 null，用可空 Map）
        changes.put("f215", 0);
        FollowupRecordService.RecordView saved = service.saveDraft(701L, 2, changes);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> gate = ArgumentCaptor.forClass((Class) Map.class);
        verify(formService).writeGateData(eq("A_ANNUAL_FU"), eq(1), gate.capture());
        assertFalse(gate.getValue().containsKey("f213"), "null 值清空该键（E25 口径）");
        assertEquals(0, gate.getValue().get("f215"), "非空值合并进既有 data");
        assertEquals(2, saved.version());
    }

    @Test
    void saveDraftConflictCarriesCurrentVersionUpdaterAndDiff() {
        FollowupRecordMapper.RecordRowRaw bumped =
                new FollowupRecordMapper.RecordRowRaw(
                        701L,
                        301L,
                        9001L,
                        "TASK",
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "DRAFT",
                        5,
                        "{\"f214\":\"2026-11-05\",\"f213\":1}",
                        "{}",
                        7L,
                        LocalDateTime.of(2026, 11, 1, 9, 0),
                        8L,
                        LocalDateTime.of(2026, 11, 5, 10, 0));
        when(recordMapper.findRecord(701L)).thenReturn(bumped);
        when(formService.fieldLabels("A_ANNUAL_FU", 1)).thenReturn(Map.of("f214", "随访日期"));
        when(recordMapper.findDisplayName(8L)).thenReturn("李医生（示例）");

        ApiException thrown =
                assertThrows(
                        ApiException.class,
                        () -> service.saveDraft(701L, 2, Map.of("f214", "2026-12-01")));

        assertEquals(40900, thrown.code());
        FollowupRecordService.DraftConflictView detail =
                (FollowupRecordService.DraftConflictView) thrown.data();
        assertEquals(5, detail.currentVersion());
        assertEquals("李医生（示例）", detail.updatedBy());
        assertEquals(1, detail.diffFields().size(), "只列两边不同的键（OQ-77③）");
        assertEquals("随访日期", detail.diffFields().get(0).label(), "label 取模板版本快照显示名");
        verify(recordMapper, never())
                .updateDraft(
                        anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void saveDraftRejectsSubmittedAndMissingBaseVersion() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(701L, null, Map.of("f214", "2026-11-05")))
                        .code(),
                "baseVersion 必填");
        when(recordMapper.findRecord(702L)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.saveDraft(702L, 1, Map.of())).code(),
                "记录不存在（不暴露存在）");
        FollowupRecordMapper.RecordRowRaw submitted =
                new FollowupRecordMapper.RecordRowRaw(
                        703L,
                        301L,
                        9001L,
                        "TASK",
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "SUBMITTED",
                        3,
                        "{}",
                        "{}",
                        7L,
                        LocalDateTime.of(2026, 11, 1, 9, 0),
                        8L,
                        LocalDateTime.of(2026, 11, 5, 10, 0));
        when(recordMapper.findRecord(703L)).thenReturn(submitted);
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(703L, 3, Map.of("f214", "2026-11-05")))
                        .code(),
                "已提交不再自动保存");
    }

    @Test
    void submitValidatesStripsAndPersists() {
        when(recordMapper.findRecord(701L)).thenReturn(ROW, submittedRow());
        when(formService.validateForSubmit("A_ANNUAL_FU", 1, Map.of("f214", "2026-11-05")))
                .thenReturn(List.of());
        when(formService.stripHiddenFields(eq("A_ANNUAL_FU"), eq(1), any(), any()))
                .thenReturn(
                        new FormService.StrippedData(
                                Map.of("f214", "2026-11-05"), List.of("f219")));
        when(formService.writeGateData(eq("A_ANNUAL_FU"), eq(1), any()))
                .thenReturn(Map.of("f214", "2026-11-05"));
        when(recordMapper.submitRecord(eq(701L), eq(2), any(), any(), eq(7L))).thenReturn(1);

        FollowupRecordService.SubmitOutcome outcome =
                service.submit(701L, 2, Map.of("f214", "2026-11-05"), null);

        assertEquals("SUBMITTED", outcome.view().status());
        assertEquals(3, outcome.view().version(), "提交后 version + 1");
        assertTrue(outcome.before().containsKey("f213"), "before = 提交前 data（字段历史 diff 用）");
        assertEquals(List.of("f219"), outcome.clearedKeys(), "隐藏字段清空键随提交流程带回");
        verify(formService).validateForSubmit("A_ANNUAL_FU", 1, Map.of("f214", "2026-11-05"));
    }

    @Test
    void submitRejectsMissingBaseVersionAndDuplicateSubmit() {
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.submit(701L, null, Map.of(), null))
                        .code(),
                "baseVersion 必填");
        FollowupRecordMapper.RecordRowRaw submitted =
                new FollowupRecordMapper.RecordRowRaw(
                        703L,
                        301L,
                        9001L,
                        "TASK",
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "SUBMITTED",
                        3,
                        "{}",
                        "{}",
                        7L,
                        LocalDateTime.of(2026, 11, 1, 9, 0),
                        8L,
                        LocalDateTime.of(2026, 11, 5, 10, 0));
        when(recordMapper.findRecord(703L)).thenReturn(submitted);
        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.submit(703L, 3, Map.of(), null))
                        .code(),
                "不能重复提交");
    }

    private static FollowupRecordMapper.RecordRowRaw submittedRow() {
        return new FollowupRecordMapper.RecordRowRaw(
                701L,
                301L,
                9001L,
                "TASK",
                null,
                "A_ANNUAL_FU",
                1,
                "SUBMITTED",
                3,
                "{\"f214\":\"2026-11-05\"}",
                "{}",
                7L,
                LocalDateTime.of(2026, 11, 1, 9, 0),
                7L,
                LocalDateTime.of(2026, 11, 5, 10, 0));
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                7L,
                "doctor.demo",
                "张医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                0L, // 绝对时长用部署配置的全局值,
                null);
    }

    private static FollowupRecordMapper.RecordRowRaw rowWithStatus(String status) {
        return new FollowupRecordMapper.RecordRowRaw(
                888L,
                301L,
                null,
                "BACKFILL",
                LocalDate.of(2025, 6, 1),
                "A_ANNUAL_FU",
                3,
                status,
                0,
                "{}",
                "{}",
                7L,
                LocalDateTime.of(2026, 11, 5, 9, 0),
                7L,
                LocalDateTime.of(2026, 11, 5, 9, 0));
    }
}
