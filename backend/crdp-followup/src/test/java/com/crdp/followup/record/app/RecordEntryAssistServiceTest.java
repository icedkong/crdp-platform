package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper.PatientRecordMetaRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper.TaskPlanDateRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E21-T11 / C-FU-024、C-FU-008：重复记录提示与随访序号派生的业务层口径。
 *
 * <p>锁定：同一患者同一<b>有效随访日期</b>（visit_date ?? 表单随访日期字段 ?? 任务计划日期）已有记录时 duplicate =
 * true（含草稿；excludeRecordId 排除自身）；越范围患者 40400（不暴露存在）；随访序号 = 1 + 早于本次的已提交条数（同日 id 小者为先）、 无法定本次日期为
 * null；随访编号直接取 pt_patient.visit_no。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class RecordEntryAssistServiceTest {

    private static final long PATIENT = 301L;

    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final FollowupTaskQueryMapper taskQueryMapper = mock(FollowupTaskQueryMapper.class);
    private final FormService formService = mock(FormService.class);

    private final RecordEntryAssistService service =
            new RecordEntryAssistService(patientScope, recordMapper, taskQueryMapper, formService);

    private static final LocalDate JUNE_2025 = LocalDate.of(2025, 6, 1);
    private static final LocalDate JULY_2025 = LocalDate.of(2025, 7, 1);

    @BeforeEach
    void patientInRange() {
        lenient().when(patientScope.patientIdInScope(PATIENT)).thenReturn(PATIENT);
    }

    private static PatientRecordMetaRow row(
            long id, Long taskId, String source, LocalDate visitDate, String data) {
        return new PatientRecordMetaRow(
                id, taskId, source, visitDate, "C_PROGRAM_FU", 1, "SUBMITTED", data);
    }

    /** 模板带随访日期字段（编码 followup_date → 稳定键 f214，与 death 定位同法）。 */
    private void stubFollowupDateField() {
        when(formService.schema("C_PROGRAM_FU", 1))
                .thenReturn(
                        new FormService.SchemaView(
                                "C_PROGRAM_FU",
                                "程控随访（示例）",
                                "FOLLOWUP",
                                1,
                                List.of(
                                        new FormService.CategoryView(
                                                "fu_contact",
                                                "随访概况",
                                                1,
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
    }

    // ---------------------------------------------------------------- 重复记录提示（C-FU-024）

    @Test
    void duplicateHitsSameVisitDateColumn() {
        when(recordMapper.findPatientRecordMeta(PATIENT))
                .thenReturn(List.of(row(701L, null, "BACKFILL", JUNE_2025, "{}")));

        RecordEntryAssistService.DuplicateCheckView view =
                service.checkDuplicate(PATIENT, JUNE_2025, null);

        assertTrue(view.duplicate());
        assertEquals(1, view.records().size());
        assertEquals("701", view.records().get(0).recordId());
        assertEquals("BACKFILL", view.records().get(0).source());
    }

    /** 任务录入记录随访日期在表单字段（visit_date 列为 NULL）：按模板字段编码定位稳定键取值匹配。 */
    @Test
    void duplicateHitsTaskRecordByFormFieldDate() {
        stubFollowupDateField();
        when(recordMapper.findPatientRecordMeta(PATIENT))
                .thenReturn(List.of(row(702L, 9001L, "TASK", null, "{\"f214\":\"2025-06-01\"}")));
        when(taskQueryMapper.findPlanDatesByIds(List.of(9001L)))
                .thenReturn(List.of(new TaskPlanDateRow(9001L, JULY_2025)));

        RecordEntryAssistService.DuplicateCheckView view =
                service.checkDuplicate(PATIENT, JUNE_2025, null);

        assertTrue(view.duplicate(), "表单随访日期同日应命中");
        assertEquals("702", view.records().get(0).recordId());
    }

    /** visit_date 列与表单字段都为空：回落任务计划日期（口径与对比时点一致）。 */
    @Test
    void duplicateFallsBackToTaskPlanDate() {
        when(recordMapper.findPatientRecordMeta(PATIENT))
                .thenReturn(List.of(row(703L, 9002L, "TASK", null, "{}")));
        when(taskQueryMapper.findPlanDatesByIds(List.of(9002L)))
                .thenReturn(List.of(new TaskPlanDateRow(9002L, JUNE_2025)));

        assertTrue(service.checkDuplicate(PATIENT, JUNE_2025, null).duplicate());
    }

    @Test
    void differentDateIsNotDuplicate() {
        when(recordMapper.findPatientRecordMeta(PATIENT))
                .thenReturn(List.of(row(701L, null, "BACKFILL", JULY_2025, "{}")));

        RecordEntryAssistService.DuplicateCheckView view =
                service.checkDuplicate(PATIENT, JUNE_2025, null);

        assertFalse(view.duplicate());
        assertTrue(view.records().isEmpty());
    }

    /** excludeRecordId 排除自身（同日已有一条但就是本记录时不再提示）。 */
    @Test
    void excludeRecordIdSkipsSelf() {
        when(recordMapper.findPatientRecordMeta(PATIENT))
                .thenReturn(List.of(row(701L, null, "BACKFILL", JUNE_2025, "{}")));

        RecordEntryAssistService.DuplicateCheckView view =
                service.checkDuplicate(PATIENT, JUNE_2025, 701L);

        assertFalse(view.duplicate());
    }

    /** 越范围 / 不存在患者 40400（与补录创建同口径，不暴露存在）。 */
    @Test
    void outOfScopePatientThrows40400() {
        when(patientScope.patientIdInScope(999L)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class, () -> service.checkDuplicate(999L, JUNE_2025, null));
        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 随访序号（C-FU-008 派生）

    /** 序号 = 1 + 早于本次的已提交条数；同日按 id 小者为先。 */
    @Test
    void sequenceCountsSubmittedBeforeCurrent() {
        when(recordMapper.findPatientRecordMeta(PATIENT))
                .thenReturn(
                        List.of(
                                row(701L, null, "BACKFILL", LocalDate.of(2025, 1, 10), "{}"),
                                row(702L, null, "BACKFILL", JUNE_2025, "{}"),
                                row(703L, null, "BACKFILL", LocalDate.of(2025, 2, 1), "{}"),
                                row(704L, null, "BACKFILL", LocalDate.of(2025, 6, 1), "{}"),
                                // DRAFT 不计数（同日已提交 702 / 704 之后）
                                new PatientRecordMetaRow(
                                        705L,
                                        null,
                                        "BACKFILL",
                                        JULY_2025,
                                        "C_PROGRAM_FU",
                                        1,
                                        "DRAFT",
                                        "{}")));

        assertEquals(1, service.sequenceNoOf(PATIENT, 701L, LocalDate.of(2025, 1, 10)));
        assertEquals(
                4,
                service.sequenceNoOf(PATIENT, 704L, JUNE_2025),
                "同日按 id 小者为先：701、703 更早，同日 702 在 704 之前 → 704 第 4");
        assertEquals(5, service.sequenceNoOf(PATIENT, 705L, JULY_2025), "草稿序号按落点预演（不含自身与未提交）");
    }

    /** 无法定本次有效日期（无 visit_date、无任务计划日期）→ null（前端不显示）。 */
    @Test
    void sequenceWithoutEffectiveDateIsNull() {
        when(recordMapper.findPatientRecordMeta(PATIENT)).thenReturn(List.of());

        assertNull(service.sequenceNoOf(PATIENT, 701L, null));
    }

    // ---------------------------------------------------------------- 随访编号（C-FU-008 自动带入）

    @Test
    void visitNoComesFromPatientRow() {
        when(recordMapper.findPatientVisitNo(PATIENT)).thenReturn("105-2026-001");

        assertEquals("105-2026-001", service.visitNoOf(PATIENT));
    }

    @Test
    void visitNoNullWhenNotGenerated() {
        when(recordMapper.findPatientVisitNo(PATIENT)).thenReturn(null);

        assertNull(service.visitNoOf(PATIENT));
    }
}
