package com.crdp.followup.patient.app;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crdp.followup.patient.infra.mapper.PatientSummaryMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FormProgressEngine.CategoryProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatientFollowupSummaryServiceTest {
    private final PatientSummaryMapper mapper = mock(PatientSummaryMapper.class);
    private final FormService forms = mock(FormService.class);
    private final PatientFollowupSummaryService service =
            new PatientFollowupSummaryService(mapper, forms, new ObjectMapper());

    @Test
    void newestRecordDrivesIndicatorsAndAllRecordsContributeEventsAndAnnualAverage() {
        LocalDate date = LocalDate.of(2026, 11, 10);
        var older = row(1L, "{\"ef\":45,\"event\":\"2026-01-01\"}");
        var latest = row(2L, "{\"ef\":35,\"death\":0,\"med\":1}");
        when(mapper.recordsForRecompute(301)).thenReturn(List.of(latest, older));
        var schema = mock(FormService.SchemaView.class);
        var categories =
                List.of(
                        category("fu_echo", List.of(field("ef", "fu_ef", "number", "EF（示例）"))),
                        category(
                                "fu_contact",
                                List.of(field("death", "death", "radio", "是否死亡（示例）"))),
                        category("fu_med", List.of(field("med", "postop_acei", "radio", "用药（示例）"))),
                        category(
                                "fu_event",
                                List.of(field("event", "postop_mi_date", "date", "事件日期（示例）"))));
        when(schema.categories()).thenReturn(categories);
        when(forms.schema("A_ANNUAL_FU", 1)).thenReturn(schema);
        when(forms.progress(anyString(), anyInt(), any(), any()))
                .thenReturn(
                        new FormService.ProgressView(
                                "A_ANNUAL_FU",
                                1,
                                List.of(new CategoryProgress("fu_contact", "随访（示例）", 1, 2))),
                        new FormService.ProgressView(
                                "A_ANNUAL_FU",
                                1,
                                List.of(new CategoryProgress("fu_contact", "随访（示例）", 1, 1))));
        service.recompute(301, 7L);
        verify(mapper)
                .updateFollowupSummary(
                        301,
                        date,
                        "ALIVE",
                        "{\"ef\":35,\"medications\":[\"用药（示例）\"]}",
                        "{\"postop_mi\":\"2026-01-01\"}",
                        75,
                        7L);
    }

    @Test
    void deletingLastSubmittedRecordClearsOldSummary() {
        when(mapper.recordsForRecompute(301)).thenReturn(List.of());
        service.recompute(301, 7L);
        verify(mapper).updateFollowupSummary(301, null, null, null, null, null, 7L);
    }

    private static PatientSummaryMapper.SummaryRecord row(long id, String data) {
        return new PatientSummaryMapper.SummaryRecord(
                id,
                id == 2L ? LocalDate.of(2026, 11, 10) : LocalDate.of(2026, 1, 1),
                "A_ANNUAL_FU",
                1,
                data,
                null,
                true);
    }

    private static FormService.FieldView field(String key, String code, String type, String label) {
        var f = mock(FormService.FieldView.class);
        when(f.key()).thenReturn(key);
        when(f.code()).thenReturn(code);
        when(f.type()).thenReturn(type);
        when(f.label()).thenReturn(label);
        return f;
    }

    private static FormService.CategoryView category(
            String key, List<FormService.FieldView> fields) {
        var c = mock(FormService.CategoryView.class);
        when(c.key()).thenReturn(key);
        when(c.fields()).thenReturn(fields);
        return c;
    }
}
