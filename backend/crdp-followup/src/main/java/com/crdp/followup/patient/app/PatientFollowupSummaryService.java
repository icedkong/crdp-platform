package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientSummaryMapper;
import com.crdp.form.record.app.FormService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/** A-PAT-004/019：按有效随访日期重算最近指标、累计终点与年度记录完整度。 */
@Service
public class PatientFollowupSummaryService {
    private final PatientSummaryMapper mapper;
    private final FormService forms;
    private final ObjectMapper json;

    public PatientFollowupSummaryService(
            PatientSummaryMapper mapper, FormService forms, ObjectMapper json) {
        this.mapper = mapper;
        this.forms = forms;
        this.json = json;
    }

    public void recompute(long patientId, Long operator) {
        Map<Long, java.time.LocalDate> dates = new HashMap<>();
        Map<String, FormService.SchemaView> schemas = new HashMap<>();
        var ordered = new ArrayList<>(mapper.recordsForRecompute(patientId));
        for (var row : ordered) {
            var schema =
                    schemas.computeIfAbsent(
                            row.formCode() + "#" + row.templateVersion(),
                            ignored -> forms.schema(row.formCode(), row.templateVersion()));
            java.time.LocalDate effective = row.visitDate();
            if (effective == null) {
                Map<String, Object> data = read(row.data());
                for (var category : schema.categories())
                    for (var field : category.fields()) {
                        if ("followup_date".equals(field.code())
                                && data.get(field.key()) instanceof String text) {
                            try {
                                effective = java.time.LocalDate.parse(text);
                            } catch (java.time.DateTimeException ignored) {
                                /* 无有效日期时回落任务计划日期 */
                            }
                        }
                    }
            }
            if (effective == null) effective = row.planDate();
            if (effective != null) dates.put(row.id(), effective);
        }
        ordered.removeIf(row -> !dates.containsKey(row.id()));
        ordered.sort(
                Comparator.comparing(
                                (PatientSummaryMapper.SummaryRecord row) -> dates.get(row.id()))
                        .thenComparingLong(PatientSummaryMapper.SummaryRecord::id));
        if (ordered.isEmpty()) {
            mapper.updateFollowupSummary(patientId, null, null, null, null, null, operator);
            return;
        }
        var latest = ordered.getLast();
        var date = dates.get(latest.id());
        Map<String, Object> indicators = new LinkedHashMap<>();
        Map<String, String> events = new LinkedHashMap<>();
        String survival = "UNKNOWN";
        List<String> medications = new ArrayList<>();
        List<Integer> percentages = new ArrayList<>();
        for (var row : ordered) {
            Map<String, Object> data = read(row.data());
            var schema = schemas.get(row.formCode() + "#" + row.templateVersion());
            for (var category : schema.categories()) {
                for (var field : category.fields()) {
                    if (field.sensitive()) continue;
                    Object value = data.get(field.key());
                    if ("fu_event".equals(category.key())
                            && "date".equals(field.type())
                            && field.code().endsWith("_date")
                            && value instanceof String text) {
                        try {
                            String event = field.code().substring(0, field.code().length() - 5);
                            String eventDate = java.time.LocalDate.parse(text).toString();
                            events.merge(event, eventDate, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                        } catch (java.time.DateTimeException ignored) {
                            /* 陈旧记录的无效日期不计终点 */
                        }
                    }
                    if (row.id() != latest.id()) continue;
                    if ("death".equals(field.code())) {
                        if (yes(value)) survival = "DEAD";
                        else if (value instanceof Number n && n.intValue() == 0) survival = "ALIVE";
                    }
                    if ("number".equals(field.type()) && value instanceof Number) {
                        indicators.put("fu_ef".equals(field.code()) ? "ef" : field.code(), value);
                    }
                    if ("fu_med".equals(category.key()) && yes(value))
                        medications.add(field.label());
                }
            }
            if (row.annual()) {
                var progress =
                        forms.progress(row.formCode(), row.templateVersion(), data, Map.of());
                int filled = progress.categories().stream().mapToInt(c -> c.filled()).sum();
                int total = progress.categories().stream().mapToInt(c -> c.total()).sum();
                percentages.add(total == 0 ? 100 : (int) Math.round(filled * 100.0 / total));
            }
        }
        indicators.put("medications", medications);
        Integer completeness =
                percentages.isEmpty()
                        ? null
                        : (int)
                                Math.round(
                                        percentages.stream()
                                                .mapToInt(Integer::intValue)
                                                .average()
                                                .orElse(0));
        mapper.updateFollowupSummary(
                patientId,
                date,
                survival,
                write(indicators),
                write(events),
                completeness,
                operator);
    }

    private static boolean yes(Object value) {
        return value instanceof Number n && n.intValue() == 1;
    }

    private Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("摘要记录数据解析失败", failure);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("摘要序列化失败", failure);
        }
    }
}
