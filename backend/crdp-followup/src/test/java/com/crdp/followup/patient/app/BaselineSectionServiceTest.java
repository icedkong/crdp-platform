package com.crdp.followup.patient.app;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.BaselineSectionInsert;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper.SectionRowRaw;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FormProgressEngine.CategoryProgress;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T02 单元测试（tasks.csv 验收「锁定后只读，解锁需权限并留痕」；A-PAT-003 可分次补录、A-PAT-005 字段级留痕）。
 *
 * <p>分节口径：读取按模板分类列全部分节（无行 = EMPTY / version 0）并算合并进度； 暂存草稿只过写入闸门（不校验必填、不写字段历史），首次暂存建行；
 * 正式保存完整校验（42200 透传）→ 字段级历史（PT_BASELINE_SECTION）→ 完整度重算（A-PAT-019）→ BASELINE_SECTION_SAVE 审计；
 * baseVersion 过期 40900 附冲突详情；锁定后保存与暂存一律 40901（锁定后只读，A-PAT-006）； 越范围患者 40400。全部虚构数据。
 */
class BaselineSectionServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;
    private static final String FORM = "A_BASELINE";

    private final BaselineGateway gateway = mock(BaselineGateway.class);
    private final BaselineSectionMapper mapper = mock(BaselineSectionMapper.class);
    private final BaselineLockService lockService = mock(BaselineLockService.class);
    private final FormService formService = mock(FormService.class);
    private final FieldHistoryService fieldHistory = mock(FieldHistoryService.class);
    private final PatientSummaryService summary = mock(PatientSummaryService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final BaselineSectionService service =
            new BaselineSectionService(
                    gateway, mapper, lockService, formService, fieldHistory, summary, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        lenient().when(gateway.sections(PATIENT_ID)).thenReturn(List.of());
        lenient().when(formService.publishedVersion(FORM)).thenReturn(3);
        lenient()
                .when(formService.schema(eq(FORM), anyInt()))
                .thenReturn(
                        new FormService.SchemaView(
                                FORM,
                                "基线档案",
                                "BASELINE",
                                3,
                                List.of(
                                        new FormService.CategoryView(
                                                "case", "病例基本信息", 10, List.of()),
                                        new FormService.CategoryView(
                                                "lab", "实验室检查", 20, List.of())),
                                List.of()));
        lenient()
                .when(formService.progress(eq(FORM), anyInt(), any(), any()))
                .thenReturn(
                        new FormService.ProgressView(
                                FORM, 3, List.of(new CategoryProgress("case", "病例基本信息", 1, 2))));
        lenient()
                .when(formService.writeGateData(eq(FORM), anyInt(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient()
                .when(formService.stripHiddenFields(eq(FORM), anyInt(), any(), any()))
                .thenAnswer(
                        invocation ->
                                new FormService.StrippedData(invocation.getArgument(2), List.of()));
        lenient().when(formService.fieldLabels(eq(FORM), anyInt())).thenReturn(Map.of());
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
        lenient().when(mapper.findDisplayName(anyLong())).thenReturn("演示医生（示例）");
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 读取（全部分节 + 进度 + 锁定态）

    @Test
    void baselineListsAllTemplateCategoriesWithEmptyDefaults() {
        BaselineSectionService.BaselineView view = service.baseline(PATIENT_ID);

        assertEquals(FORM, view.formCode());
        assertEquals(3, view.formVersion());
        assertEquals(2, view.sections().size());
        BaselineSectionService.SectionView empty = view.sections().get(0);
        assertEquals("case", empty.code());
        assertEquals(BaselineSectionService.STATUS_EMPTY, empty.status());
        assertEquals(0, empty.version());
        assertNull(empty.id());
        assertFalse(empty.hasDraft());
        assertEquals(1, view.progress().size());
        assertEquals(1, view.progress().get(0).filled());
        assertEquals(2, view.progress().get(0).total());
        verify(lockService).lockView(PATIENT_ID);
    }

    @Test
    void baselineOutOfScopeIs40400() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.baseline(PATIENT_ID));
        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 暂存草稿（tech-plan §6 草稿语义）

    @Test
    void draftCreatesSectionOnFirstSave() {
        when(gateway.section(PATIENT_ID, "case")).thenReturn(null, sectionRow("case", 1, true));

        BaselineSectionService.DraftSavedView saved =
                service.saveDraft(PATIENT_ID, "case", draftCommand(0, Map.of("f010", 55), null));

        assertEquals(1, saved.version());
        ArgumentCaptor<BaselineSectionInsert> insert =
                ArgumentCaptor.forClass(BaselineSectionInsert.class);
        verify(mapper).insertSection(insert.capture());
        assertEquals("case", insert.getValue().getCategoryKey());
        assertTrue(insert.getValue().isHasDraft());
        verify(fieldHistory, never())
                .logChanges(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        anyString());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    @Test
    void draftMergesChangesAndBumpsVersion() {
        SectionRowRaw row = sectionRow("case", 2, true, "{\"f010\":55}");
        when(gateway.section(PATIENT_ID, "case")).thenReturn(row, sectionRow("case", 3, true));
        when(mapper.updateDraft(eq(row.id()), eq(2), anyString(), eq(OPERATOR_ID))).thenReturn(1);

        BaselineSectionService.DraftSavedView saved =
                service.saveDraft(PATIENT_ID, "case", draftCommand(2, Map.of("f011", 170), null));

        assertEquals(3, saved.version());
        verify(mapper).updateDraft(eq(row.id()), eq(2), anyString(), eq(OPERATOR_ID));
        verify(summary).updateBaselineCompleteness(eq(PATIENT_ID), eq(50), eq(null));
    }

    @Test
    void draftWithoutChangesIs40001() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveDraft(
                                        PATIENT_ID, "case", draftCommand(0, Map.of(), null)));
        assertEquals(40001, error.code());
    }

    @Test
    void draftWithStaleBaseVersionIs40900WithConflictDetail() {
        SectionRowRaw row = sectionRow("case", 5, true, "{\"f010\":55,\"f011\":170}");
        when(gateway.section(PATIENT_ID, "case")).thenReturn(row);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveDraft(
                                        PATIENT_ID,
                                        "case",
                                        draftCommand(2, Map.of("f010", 60), null)));

        assertEquals(40900, error.code());
        BaselineSectionService.DraftConflictView detail =
                (BaselineSectionService.DraftConflictView) error.data();
        assertEquals(5, detail.currentVersion());
        assertEquals(1, detail.diffFields().size());
        assertEquals("f010", detail.diffFields().get(0).key());
        assertEquals(60, detail.diffFields().get(0).mine());
        assertEquals(55, detail.diffFields().get(0).server());
    }

    @Test
    void draftOverwriteWritesAudit() {
        SectionRowRaw row = sectionRow("case", 2, true, "{\"f010\":55}");
        when(gateway.section(PATIENT_ID, "case")).thenReturn(row, sectionRow("case", 3, true));
        when(mapper.updateDraft(eq(row.id()), eq(2), anyString(), eq(OPERATOR_ID))).thenReturn(1);

        service.saveDraft(PATIENT_ID, "case", draftCommand(2, Map.of("f010", 60), true));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("BASELINE_DRAFT_OVERWRITE", entry.getValue().action());
    }

    // ---------------------------------------------------------------- 正式保存（A-PAT-005 留痕 +
    // A-PAT-019 完整度）

    @Test
    void saveWritesFieldHistoryAuditAndCompleteness() {
        SectionRowRaw before = sectionRow("case", 2, true, "{\"f010\":55}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("f010", 60);
        data.put("f011", 170);
        when(gateway.section(PATIENT_ID, "case"))
                .thenReturn(before, sectionRow("case", 3, false, "{\"f010\":60,\"f011\":170}"));
        when(mapper.updateSection(
                        eq(before.id()), eq(2), anyString(), anyString(), eq(OPERATOR_ID)))
                .thenReturn(1);

        BaselineSectionService.SaveResultView result =
                service.saveSection(
                        PATIENT_ID, "case", new BaselineSectionService.SaveCommand(2, data, null));

        assertEquals(3, result.section().version());
        assertFalse(result.section().hasDraft());
        verify(fieldHistory)
                .logChanges(
                        eq(FieldHistoryService.PT_BASELINE_SECTION),
                        eq(before.id()),
                        eq(FORM),
                        eq(2),
                        any(),
                        any(),
                        any(),
                        eq("基线分节保存"));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("BASELINE_SECTION_SAVE", entry.getValue().action());
        verify(summary).updateBaselineCompleteness(eq(PATIENT_ID), eq(50), eq(null));
    }

    // ---------------------------------------------------------------- 报告单映射写入（E16-T07 / A-ATT-013）

    @Test
    void applyMappingWriteMergesUpdatesAndRefreshesCompleteness() {
        SectionRowRaw before = sectionRow("case", 2, false, "{\"f010\":55}");
        when(gateway.section(PATIENT_ID, "case"))
                .thenReturn(before, sectionRow("case", 3, false, "{\"f010\":60}"));
        when(mapper.updateSection(eq(before.id()), eq(2), any(), any(), eq(OPERATOR_ID)))
                .thenReturn(1);

        BaselineSectionService.MappingWriteOutcome outcome =
                service.applyMappingWrite(PATIENT_ID, "case", 2, Map.of("f010", 60));

        assertEquals(3, outcome.after().version(), "写入后 version + 1");
        assertEquals(Map.of("f010", 55), outcome.before(), "返回写入前值（字段历史 before 用）");
        assertEquals(60, outcome.after().data().get("f010"));
        assertFalse(outcome.after().hasDraft(), "映射写入不置草稿标记");
        verify(summary).updateBaselineCompleteness(eq(PATIENT_ID), anyInt(), eq(null));
    }

    @Test
    void applyMappingWriteCreatesSectionWhenMissing() {
        when(gateway.section(PATIENT_ID, "case"))
                .thenReturn(null, sectionRow("case", 1, false, "{\"f010\":60}"));
        when(mapper.insertSection(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<BaselineSectionInsert>getArgument(0).setId(90L);
                            return 1;
                        });

        BaselineSectionService.MappingWriteOutcome outcome =
                service.applyMappingWrite(PATIENT_ID, "case", 0, Map.of("f010", 60));

        assertEquals(1, outcome.after().version(), "首次写入建行 v1（baseVersion 0）");
        ArgumentCaptor<BaselineSectionInsert> captor =
                ArgumentCaptor.forClass(BaselineSectionInsert.class);
        verify(mapper).insertSection(captor.capture());
        assertEquals(3, captor.getValue().getTemplateVersion(), "建行固化当前发布版");
        assertFalse(captor.getValue().isHasDraft());
        verify(summary).updateBaselineCompleteness(eq(PATIENT_ID), anyInt(), eq(null));
    }

    @Test
    void applyMappingWriteRejectsLockedBaselineAndStaleVersion() {
        doThrow(new ApiException(409, 40901, "基线已锁定，不能写入映射字段（需先解锁）"))
                .when(lockService)
                .requireUnlocked(eq(PATIENT_ID), anyString());
        ApiException locked =
                assertThrows(
                        ApiException.class,
                        () -> service.applyMappingWrite(PATIENT_ID, "case", 2, Map.of("f010", 60)));
        assertEquals(40901, locked.code(), "锁定后只读（A-PAT-006）");

        org.mockito.Mockito.doNothing().when(lockService).requireUnlocked(anyLong(), anyString());
        when(gateway.section(PATIENT_ID, "case")).thenReturn(sectionRow("case", 5, false, "{}"));
        ApiException stale =
                assertThrows(
                        ApiException.class,
                        () -> service.applyMappingWrite(PATIENT_ID, "case", 2, Map.of("f010", 60)));
        assertEquals(40900, stale.code(), "乐观锁冲突 409");
        verify(mapper, never())
                .updateSection(anyLong(), anyInt(), anyString(), anyString(), anyLong());
    }

    @Test
    void savePropagatesValidationError() {
        SectionRowRaw row = sectionRow("case", 2, true, "{}");
        when(gateway.section(PATIENT_ID, "case")).thenReturn(row);
        when(formService.validateForSubmit(eq(FORM), eq(2), any()))
                .thenThrow(new ApiException(422, 42200, "表单校验未通过"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveSection(
                                        PATIENT_ID,
                                        "case",
                                        new BaselineSectionService.SaveCommand(
                                                2, Map.of("f010", 60), null)));

        assertEquals(42200, error.code());
        verify(mapper, never())
                .updateSection(anyLong(), anyInt(), anyString(), anyString(), anyLong());
    }

    @Test
    void saveWithStaleBaseVersionIs40900() {
        when(gateway.section(PATIENT_ID, "case")).thenReturn(sectionRow("case", 4, false, "{}"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveSection(
                                        PATIENT_ID,
                                        "case",
                                        new BaselineSectionService.SaveCommand(1, Map.of(), null)));

        assertEquals(40900, error.code());
    }

    @Test
    void saveOnMissingRowWithNonZeroBaseVersionIs40900() {
        when(gateway.section(PATIENT_ID, "case")).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveSection(
                                        PATIENT_ID,
                                        "case",
                                        new BaselineSectionService.SaveCommand(3, Map.of(), null)));

        assertEquals(40900, error.code());
    }

    // ---------------------------------------------------------------- 锁定后只读（A-PAT-006）

    @Test
    void lockedBaselineRejectsDraftWith40901() {
        org.mockito.Mockito.doThrow(new ApiException(409, 40901, "基线已锁定，不能暂存草稿（需先解锁）"))
                .when(lockService)
                .requireUnlocked(eq(PATIENT_ID), anyString());

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveDraft(
                                        PATIENT_ID,
                                        "case",
                                        draftCommand(0, Map.of("f010", 55), null)));

        assertEquals(40901, error.code());
    }

    @Test
    void lockedBaselineRejectsSaveWith40901() {
        org.mockito.Mockito.doThrow(new ApiException(409, 40901, "基线已锁定，不能保存（需先解锁）"))
                .when(lockService)
                .requireUnlocked(eq(PATIENT_ID), anyString());

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveSection(
                                        PATIENT_ID,
                                        "case",
                                        new BaselineSectionService.SaveCommand(0, Map.of(), null)));

        assertEquals(40901, error.code());
        verify(mapper, never()).insertSection(any());
    }

    // ---------------------------------------------------------------- 字段历史查询（A-PAT-005 可查）

    @Test
    void historyOfUnsavedSectionIs40400() {
        when(gateway.section(PATIENT_ID, "case")).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class, () -> service.sectionHistory(PATIENT_ID, "case", null));
        assertEquals(40400, error.code());
    }

    @Test
    void historyDelegatesToFieldHistory() {
        SectionRowRaw row = sectionRow("case", 2, false, "{}");
        when(gateway.section(PATIENT_ID, "case")).thenReturn(row);
        when(fieldHistory.history(FieldHistoryService.PT_BASELINE_SECTION, row.id(), "f010"))
                .thenReturn(List.of());

        service.sectionHistory(PATIENT_ID, "case", "f010");

        verify(fieldHistory).history(FieldHistoryService.PT_BASELINE_SECTION, row.id(), "f010");
    }

    // ---------------------------------------------------------------- 内部

    private static BaselineSectionService.DraftCommand draftCommand(
            int baseVersion, Map<String, Object> changes, Boolean overwrite) {
        return new BaselineSectionService.DraftCommand(baseVersion, changes, overwrite);
    }

    private static SectionRowRaw sectionRow(String categoryKey, int version, boolean hasDraft) {
        return sectionRow(categoryKey, version, hasDraft, "{}");
    }

    private static SectionRowRaw sectionRow(
            String categoryKey, int version, boolean hasDraft, String data) {
        return new SectionRowRaw(
                77L,
                PATIENT_ID,
                FORM,
                categoryKey,
                2,
                data,
                null,
                version,
                hasDraft,
                OPERATOR_ID,
                LocalDateTime.of(2026, 11, 5, 9, 0),
                OPERATOR_ID,
                LocalDateTime.of(2026, 11, 6, 10, 0));
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:patient:view", "followup:patient:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                0L, // 绝对时长用部署配置的全局值,
                null);
    }
}
