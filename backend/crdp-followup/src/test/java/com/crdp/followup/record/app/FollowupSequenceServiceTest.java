package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper.PatientRecordMetaRow;
import com.crdp.followup.record.infra.mapper.RecordFeatureMapper;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E21-T09 / C-FU-018：随访序号自动编号与重排的业务层口径。
 *
 * <p>验收（tasks.csv「补录插入后序号自动重排」；hospital-c-spec §5「补录 2021-06 的记录后，该患者原第 3 次及之后的随访序号自动加
 * 1，变更历史中有重排记录」「内部记录号不变」）：同一患者已提交记录按有效随访日期升序 1..N 编号（同日按 id 小者为先，与 E21-T11 派生预演同口径）；
 * 补录插入较早日期后，排在后面的记录序号整体后移，逐行写 fe_field_history（source = SYSTEM_RECALC、虚拟稳定键 sequence_no）； 功能开关
 * record_sequence_no 关闭（无行 / 0，A / B 默认）跳过。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FollowupSequenceServiceTest {

    private static final long PATIENT = 301L;

    private final RecordEntryAssistService assist = mock(RecordEntryAssistService.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final RecordFeatureMapper features = mock(RecordFeatureMapper.class);
    private final FieldHistoryService fieldHistory = mock(FieldHistoryService.class);

    private final FollowupSequenceService service =
            new FollowupSequenceService(assist, recordMapper, features, fieldHistory);

    private static final LocalDate JAN = LocalDate.of(2025, 1, 10);
    private static final LocalDate MAR = LocalDate.of(2025, 3, 15);
    private static final LocalDate JUN = LocalDate.of(2025, 6, 1);
    private static final LocalDate SEP = LocalDate.of(2025, 9, 20);

    @BeforeEach
    void featureOn() {
        lenient().when(features.findEnabled("record_sequence_no")).thenReturn(true);
    }

    private static PatientRecordMetaRow row(
            long id, LocalDate visitDate, String status, Integer sequenceNo) {
        return new PatientRecordMetaRow(
                id, null, "BACKFILL", visitDate, "C_PROGRAM_FU", 1, status, "{}", sequenceNo);
    }

    // ---------------------------------------------------------------- 自动编号（升序 1..N）

    /** 按有效随访日期升序编号 1..N（列表乱序输入、同日按 id 小者为先）；首编旧值 null 一并留痕。 */
    @Test
    void numbersSubmittedRowsByEffectiveDateAscending() {
        when(assist.submittedRowsOrderedByEffectiveDate(PATIENT))
                .thenReturn(
                        List.of(
                                row(701L, JAN, "SUBMITTED", null),
                                row(703L, JUN, "SUBMITTED", null), // 同日 id 更小者在前
                                row(704L, JUN, "SUBMITTED", null),
                                row(705L, SEP, "SUBMITTED", null)));
        when(assist.submittedRows(PATIENT))
                .thenReturn(
                        List.of(
                                row(705L, SEP, "SUBMITTED", null),
                                row(703L, JUN, "SUBMITTED", null),
                                row(704L, JUN, "SUBMITTED", null),
                                row(701L, JAN, "SUBMITTED", null)));

        int changed = service.resequence(PATIENT, "提交随访记录，随访序号自动重排（C-FU-018）");

        assertEquals(4, changed);
        verify(recordMapper).updateSequenceNo(701L, 1);
        verify(recordMapper).updateSequenceNo(703L, 2);
        verify(recordMapper).updateSequenceNo(704L, 3);
        verify(recordMapper).updateSequenceNo(705L, 4);
        verify(fieldHistory)
                .logChange(
                        FieldHistoryService.FU_RECORD,
                        705L,
                        "C_PROGRAM_FU",
                        1,
                        "sequence_no",
                        null,
                        4,
                        ChangeSource.SYSTEM_RECALC,
                        "提交随访记录，随访序号自动重排（C-FU-018）",
                        null);
    }

    /** 序号已正确的行不重写、不留痕（幂等：无变化零写入）。 */
    @Test
    void unchangedRowsAreNotRewritten() {
        when(assist.submittedRowsOrderedByEffectiveDate(PATIENT))
                .thenReturn(
                        List.of(
                                row(701L, JAN, "SUBMITTED", 1),
                                row(702L, MAR, "SUBMITTED", 2),
                                row(703L, JUN, "SUBMITTED", 3)));
        when(assist.submittedRows(PATIENT))
                .thenReturn(
                        List.of(
                                row(701L, JAN, "SUBMITTED", 1),
                                row(702L, MAR, "SUBMITTED", 2),
                                row(703L, JUN, "SUBMITTED", 3)));

        assertEquals(0, service.resequence(PATIENT, "提交随访记录，随访序号自动重排（C-FU-018）"));

        verify(recordMapper, never())
                .updateSequenceNo(
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(fieldHistory);
    }

    // ---------------------------------------------------------------- 验收位：补录插入后重排

    /**
     * 验收原文（hospital-c-spec §5）：补录 2021-06 的记录后，原第 3 次及之后的随访序号自动加 1，变更历史中有重排记录；
     * 内部记录号（fu_record.id）不变——只改 sequence_no 列。
     */
    @Test
    void backfillInsertShiftsLaterSequenceNumbersAndWritesHistory() {
        // 存量：1（1 月）、2（6 月）、3（9 月）；补录插入 3 月 → 新记录第 2，原 2 / 3 顺延 3 / 4，1 不动
        PatientRecordMetaRow inserted = row(710L, MAR, "SUBMITTED", null);
        when(assist.submittedRowsOrderedByEffectiveDate(PATIENT))
                .thenReturn(
                        List.of(
                                row(701L, JAN, "SUBMITTED", 1),
                                inserted,
                                row(703L, JUN, "SUBMITTED", 2),
                                row(705L, SEP, "SUBMITTED", 3)));
        when(assist.submittedRows(PATIENT))
                .thenReturn(
                        List.of(
                                row(701L, JAN, "SUBMITTED", 1),
                                row(703L, JUN, "SUBMITTED", 2),
                                row(705L, SEP, "SUBMITTED", 3),
                                inserted));

        int changed = service.resequence(PATIENT, "补录插入随访记录，随访序号自动重排（C-FU-018）");

        assertEquals(3, changed, "新记录首编 + 两行顺延");
        verify(recordMapper).updateSequenceNo(710L, 2);
        verify(recordMapper).updateSequenceNo(703L, 3);
        verify(recordMapper).updateSequenceNo(705L, 4);
        verify(recordMapper, never()).updateSequenceNo(701L, 1);
        // 变更历史：顺延行旧值 → 新值（补录插入触发）
        verify(fieldHistory)
                .logChange(
                        FieldHistoryService.FU_RECORD,
                        703L,
                        "C_PROGRAM_FU",
                        1,
                        "sequence_no",
                        2,
                        3,
                        ChangeSource.SYSTEM_RECALC,
                        "补录插入随访记录，随访序号自动重排（C-FU-018）",
                        null);
        verify(fieldHistory)
                .logChange(
                        FieldHistoryService.FU_RECORD,
                        705L,
                        "C_PROGRAM_FU",
                        1,
                        "sequence_no",
                        3,
                        4,
                        ChangeSource.SYSTEM_RECALC,
                        "补录插入随访记录，随访序号自动重排（C-FU-018）",
                        null);
    }

    /** 删除（软删）后重排：行从定序列表消失，后面行序号回退（3 → 2），旧值留痕。 */
    @Test
    void deleteShiftsLaterNumbersDown() {
        when(assist.submittedRowsOrderedByEffectiveDate(PATIENT))
                .thenReturn(
                        List.of(row(701L, JAN, "SUBMITTED", 1), row(705L, SEP, "SUBMITTED", 3)));
        when(assist.submittedRows(PATIENT))
                .thenReturn(
                        List.of(row(701L, JAN, "SUBMITTED", 1), row(705L, SEP, "SUBMITTED", 3)));

        int changed = service.resequence(PATIENT, "删除随访记录，随访序号自动重排（C-FU-018）");

        assertEquals(1, changed);
        verify(recordMapper).updateSequenceNo(705L, 2);
        verify(fieldHistory)
                .logChange(
                        FieldHistoryService.FU_RECORD,
                        705L,
                        "C_PROGRAM_FU",
                        1,
                        "sequence_no",
                        3,
                        2,
                        ChangeSource.SYSTEM_RECALC,
                        "删除随访记录，随访序号自动重排（C-FU-018）",
                        null);
    }

    /** 草稿不占号（定序只含已提交行；草稿保持 null，读侧预演落点由 sequenceNoOf 出）。 */
    @Test
    void draftRowsAreNotNumbered() {
        when(assist.submittedRowsOrderedByEffectiveDate(PATIENT))
                .thenReturn(List.of(row(701L, JAN, "SUBMITTED", 1)));
        when(assist.submittedRows(PATIENT)).thenReturn(List.of(row(701L, JAN, "SUBMITTED", 1)));

        assertEquals(0, service.resequence(PATIENT, "提交随访记录，随访序号自动重排（C-FU-018）"));
    }

    /** 无法定有效日期的已提交行不占号；残留旧序号清空并留痕（防脏号占位）。 */
    @Test
    void staleNumberOnUndateableRowIsCleared() {
        when(assist.submittedRowsOrderedByEffectiveDate(PATIENT))
                .thenReturn(List.of(row(701L, JAN, "SUBMITTED", 1)));
        when(assist.submittedRows(PATIENT))
                .thenReturn(
                        List.of(row(701L, JAN, "SUBMITTED", 1), row(709L, null, "SUBMITTED", 2)));

        int changed = service.resequence(PATIENT, "修改已提交随访记录，随访序号自动重排（C-FU-018）");

        assertEquals(1, changed);
        verify(recordMapper).updateSequenceNo(709L, null);
        verify(fieldHistory)
                .logChange(
                        FieldHistoryService.FU_RECORD,
                        709L,
                        "C_PROGRAM_FU",
                        1,
                        "sequence_no",
                        2,
                        null,
                        ChangeSource.SYSTEM_RECALC,
                        "修改已提交随访记录，随访序号自动重排（C-FU-018）",
                        null);
    }

    /** 功能开关关闭（无行 / 0 = A / B 默认，V95 只落关闭行）→ 跳过重排，零读写。 */
    @Test
    void featureOffSkipsResequence() {
        when(features.findEnabled("record_sequence_no")).thenReturn(false);

        assertEquals(0, service.resequence(PATIENT, "提交随访记录，随访序号自动重排（C-FU-018）"));

        verifyNoInteractions(assist, recordMapper, fieldHistory);
    }

    // ---------------------------------------------------------------- 详情读侧（E21-T11 派生 → 落库值优先）

    /** 已提交 + 开关开启：落库序号优先（重排后的口径；存量未编行回落派生预演）。 */
    @Test
    void detailPrefersPersistedNumberForSubmittedWhenFeatureOn() {
        FollowupRecordService.RecordView submitted =
                new FollowupRecordService.RecordView(
                        701L,
                        PATIENT,
                        null,
                        "BACKFILL",
                        JUN,
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "SUBMITTED",
                        2,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null);
        when(recordMapper.findRecordSequenceNo(701L)).thenReturn(4);

        assertEquals(4, service.sequenceNoOfDetail(submitted, JUN));
        verify(assist, never()).sequenceNoOf(PATIENT, 701L, JUN);
    }

    /** 落库值缺失（存量行未触发过重排）→ 回落 E21-T11 派生预演。 */
    @Test
    void detailFallsBackToDerivedWhenPersistedMissing() {
        FollowupRecordService.RecordView submitted =
                new FollowupRecordService.RecordView(
                        701L,
                        PATIENT,
                        null,
                        "BACKFILL",
                        JUN,
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "SUBMITTED",
                        2,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null);
        when(recordMapper.findRecordSequenceNo(701L)).thenReturn(null);
        when(assist.sequenceNoOf(PATIENT, 701L, JUN)).thenReturn(3);

        assertEquals(3, service.sequenceNoOfDetail(submitted, JUN));
    }

    /** 开关关闭（A / B）与草稿：读侧维持 E21-T11 派生预演，不查落库列。 */
    @Test
    void detailUsesDerivedPreviewWhenFeatureOffOrDraft() {
        FollowupRecordService.RecordView draft =
                new FollowupRecordService.RecordView(
                        701L,
                        PATIENT,
                        null,
                        "TASK",
                        null,
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "DRAFT",
                        0,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null);
        when(assist.sequenceNoOf(PATIENT, 701L, JUN)).thenReturn(5);
        assertEquals(5, service.sequenceNoOfDetail(draft, JUN), "草稿走派生预演（落点）");

        when(features.findEnabled("record_sequence_no")).thenReturn(false);
        FollowupRecordService.RecordView submitted =
                new FollowupRecordService.RecordView(
                        701L,
                        PATIENT,
                        null,
                        "BACKFILL",
                        JUN,
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "SUBMITTED",
                        2,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null);
        when(assist.sequenceNoOf(PATIENT, 701L, JUN)).thenReturn(4);
        assertEquals(4, service.sequenceNoOfDetail(submitted, JUN), "开关关闭走派生（A / B 不变）");
        verify(recordMapper, never()).findRecordSequenceNo(701L);
    }

    /** 无可定日期（既无落库也无派生落点）→ null（前端不显示，E21-T11 口径）。 */
    @Test
    void detailWithoutEffectiveDateIsNull() {
        FollowupRecordService.RecordView draft =
                new FollowupRecordService.RecordView(
                        701L,
                        PATIENT,
                        null,
                        "TASK",
                        null,
                        null,
                        "C_PROGRAM_FU",
                        1,
                        "DRAFT",
                        0,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null);
        when(assist.sequenceNoOf(PATIENT, 701L, null)).thenReturn(null);

        assertNull(service.sequenceNoOfDetail(draft, null));
    }
}
