package com.crdp.followup.record.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper.PatientRecordMetaRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 程控记录录入辅助（E21-T11 / C-FU-008、C-FU-024；docs/api/followup.md §3）：重复记录提示与随访序号派生。
 *
 * <p><b>重复记录提示（C-FU-024「同一患者同一随访日期已有记录时提示，确认后可保存」）</b>：按「有效随访日期」 匹配——{@code visit_date ??
 * 表单随访日期字段（编码 followup_date）?? 关联任务计划日期}（任务录入记录的随访日期在表单字段里， fu_record.visit_date 为 NULL，OQ-134①
 * 同源口径）；草稿同样算（同一日期已开草稿再录是重复）；软删行不算。数据范围前置：越范围患者 40400，不暴露存在。
 *
 * <p><b>随访序号（C-FU-008「自动带入随访序号」；完整重排与变更历史随 E21-T09 / C-FU-018）</b>：同一患者已提交记录按有效随访日期升序的序号—— 1 +
 * 早于本次的条数（同日按 id 小者为先）；本次（录入中的 DRAFT）的有效日期 = visit_date ?? 关联任务计划日期。 无可定日期（任务又无计划日期）返回 null，前端不显示。
 */
@Service
public class RecordEntryAssistService {

    /** A 规格字段编码：随访日期（与 RecordEntryService 的 death 定位同法，模板字段编码定位稳定键）。 */
    static final String FIELD_CODE_FOLLOWUP_DATE = "followup_date";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatientContactGateway patientScope;
    private final FollowupRecordMapper recordMapper;
    private final FollowupTaskQueryMapper taskQueryMapper;
    private final FormService formService;

    public RecordEntryAssistService(
            PatientContactGateway patientScope,
            FollowupRecordMapper recordMapper,
            FollowupTaskQueryMapper taskQueryMapper,
            FormService formService) {
        this.patientScope = patientScope;
        this.recordMapper = recordMapper;
        this.taskQueryMapper = taskQueryMapper;
        this.formService = formService;
    }

    // ---------------------------------------------------------------- 重复记录提示（C-FU-024）

    /** 重复提示行：定位 + 来源 / 状态（前端提示「该患者在 {日期} 已有一条随访记录，确定继续？」）。 */
    public record DuplicateItemView(
            String recordId, LocalDate visitDate, String source, String status) {}

    /** 重复提示结果：duplicate = false 时 records 为空（不重复，直接保存）。 */
    public record DuplicateCheckView(boolean duplicate, List<DuplicateItemView> records) {}

    /** 同一患者同一随访日期已有记录？（软删行不算；excludeRecordId 排除自身——修改 / 重新提交场景）。 */
    public DuplicateCheckView checkDuplicate(
            long patientId, LocalDate visitDate, Long excludeRecordId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        List<DuplicateItemView> hits = new ArrayList<>();
        for (Map.Entry<LocalDate, List<PatientRecordMetaRow>> entry :
                effectiveDatesOf(patientId).entrySet()) {
            if (!entry.getKey().equals(visitDate)) {
                continue;
            }
            for (PatientRecordMetaRow row : entry.getValue()) {
                if (excludeRecordId != null && row.id() == excludeRecordId) {
                    continue;
                }
                hits.add(
                        new DuplicateItemView(
                                String.valueOf(row.id()),
                                row.visitDate(),
                                row.source(),
                                row.status()));
            }
        }
        return new DuplicateCheckView(!hits.isEmpty(), hits);
    }

    // ---------------------------------------------------------------- 随访编号 / 随访序号（C-FU-008 派生显示）

    /** 患者随访编号（C-PAT-003 / E21-T10 pt_patient.visit_no；未生成 / 越范围 null）。 */
    public String visitNoOf(long patientId) {
        return recordMapper.findPatientVisitNo(patientId);
    }

    /**
     * 随访序号（同一患者按有效随访日期升序，1 起；同日按 id 小者为先）：1 + 有效日期早于本次的已提交条数（同日 id 更小者也计为先）； 录入中的 DRAFT
     * 用本次有效日期（visit_date ?? 任务计划日期）预演落点，完整重排与变更历史随 E21-T09 / C-FU-018。 无法定本次日期返回 null。
     */
    public Integer sequenceNoOf(long patientId, long recordId, LocalDate currentEffective) {
        if (currentEffective == null) {
            return null;
        }
        int before = 0;
        for (Map.Entry<LocalDate, List<PatientRecordMetaRow>> entry :
                effectiveDatesOf(patientId).entrySet()) {
            if (entry.getKey().isBefore(currentEffective)) {
                before += submittedCount(entry.getValue());
            }
        }
        for (PatientRecordMetaRow row :
                effectiveDatesOf(patientId).getOrDefault(currentEffective, List.of())) {
            if (row.id() < recordId && isSubmitted(row)) {
                before++;
            }
        }
        return before + 1;
    }

    // ---------------------------------------------------------------- 内部（有效随访日期派生）

    /** 患者在册记录按有效随访日期分组（重复提示与序号共用；模板 schema 按 (formCode, version) 记忆化）。 */
    private Map<LocalDate, List<PatientRecordMetaRow>> effectiveDatesOf(long patientId) {
        List<PatientRecordMetaRow> rows = recordMapper.findPatientRecordMeta(patientId);
        Map<Long, LocalDate> planDates = planDatesOf(rows);
        Map<String, String> dateKeysByForm = new HashMap<>();
        Map<LocalDate, List<PatientRecordMetaRow>> byDate = new HashMap<>();
        for (PatientRecordMetaRow row : rows) {
            LocalDate effective = effectiveOf(row, planDates, dateKeysByForm);
            if (effective != null) {
                byDate.computeIfAbsent(effective, key -> new ArrayList<>()).add(row);
            }
        }
        return byDate;
    }

    /** 单行有效随访日期：visit_date ?? 表单随访日期字段 ?? 任务计划日期。 */
    private LocalDate effectiveOf(
            PatientRecordMetaRow row,
            Map<Long, LocalDate> planDates,
            Map<String, String> dateKeysByForm) {
        if (row.visitDate() != null) {
            return row.visitDate();
        }
        LocalDate formDate = formFollowupDateOf(row, dateKeysByForm);
        if (formDate != null) {
            return formDate;
        }
        return row.taskId() == null ? null : planDates.get(row.taskId());
    }

    /** 表单随访日期字段值（模板字段编码 followup_date 定位稳定键，与 death 定位同法；无字段 / 无值 null）。 */
    private LocalDate formFollowupDateOf(
            PatientRecordMetaRow row, Map<String, String> dateKeysByForm) {
        String cacheKey = row.formCode() + "#" + row.templateVersion();
        if (!dateKeysByForm.containsKey(cacheKey)) {
            dateKeysByForm.put(cacheKey, followupDateKey(row.formCode(), row.templateVersion()));
        }
        String key = dateKeysByForm.get(cacheKey);
        if (key == null) {
            return null;
        }
        Object value = readMap(row.data()).get(key);
        return value instanceof String text ? parseDate(text) : null;
    }

    /** 模板里随访日期字段的稳定键（编码 followup_date；无此字段 null——表单无随访日期字段时不回落表单值）。 */
    private String followupDateKey(String formCode, int templateVersion) {
        FormService.SchemaView schema = formService.schema(formCode, templateVersion);
        if (schema == null) {
            return null;
        }
        for (FormService.CategoryView category : schema.categories()) {
            for (FormService.FieldView field : category.fields()) {
                if (FIELD_CODE_FOLLOWUP_DATE.equals(field.code())) {
                    return field.key();
                }
            }
        }
        return null;
    }

    /** 任务计划日期批量补查（visit_date 为空且挂任务的行；越范围任务的行不可见）。 */
    private Map<Long, LocalDate> planDatesOf(List<PatientRecordMetaRow> rows) {
        List<Long> taskIds = new ArrayList<>();
        for (PatientRecordMetaRow row : rows) {
            if (row.visitDate() == null && row.taskId() != null) {
                taskIds.add(row.taskId());
            }
        }
        Map<Long, LocalDate> planDates = new HashMap<>();
        if (taskIds.isEmpty()) {
            return planDates;
        }
        for (FollowupTaskQueryMapper.TaskPlanDateRow row :
                taskQueryMapper.findPlanDatesByIds(taskIds)) {
            planDates.put(row.taskId(), row.planDate());
        }
        return planDates;
    }

    private static boolean isSubmitted(PatientRecordMetaRow row) {
        return "SUBMITTED".equals(row.status());
    }

    private static int submittedCount(List<PatientRecordMetaRow> rows) {
        int count = 0;
        for (PatientRecordMetaRow row : rows) {
            if (isSubmitted(row)) {
                count++;
            }
        }
        return count;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<HashMap<String, Object>>() {});
        } catch (JsonProcessingException invalid) {
            return Map.of();
        }
    }
}
