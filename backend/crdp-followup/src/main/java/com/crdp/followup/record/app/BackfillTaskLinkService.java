package com.crdp.followup.record.app;

import com.crdp.followup.implant.app.DeviceContextService;
import com.crdp.followup.record.infra.mapper.BackfillTaskLinkMapper;
import com.crdp.followup.record.infra.mapper.RecordFeatureMapper;
import com.crdp.framework.audit.*;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 补录提交编排：患者 / 记录锁、候选重验、双向关联、原提交事务与同请求幂等。 */
@Service
public class BackfillTaskLinkService {
    public static final String FEATURE = "backfill_task_link";
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final BackfillTaskLinkMapper mapper;
    private final RecordFeatureMapper features;
    private final DeviceContextService devices;
    private final RecordEntryService entry;
    private final DigestService digests;
    private final AuditLogger audit;
    private final com.crdp.form.record.app.FormService forms;

    public BackfillTaskLinkService(
            BackfillTaskLinkMapper mapper,
            RecordFeatureMapper features,
            DeviceContextService devices,
            RecordEntryService entry,
            DigestService digests,
            AuditLogger audit,
            com.crdp.form.record.app.FormService forms) {
        this.forms = forms;
        this.mapper = mapper;
        this.features = features;
        this.devices = devices;
        this.entry = entry;
        this.digests = digests;
        this.audit = audit;
    }

    public record CandidateView(
            String taskId,
            String planName,
            String nodeName,
            java.time.LocalDate planDate,
            java.time.LocalDate windowStart,
            java.time.LocalDate windowEnd) {}

    @AuditLog(
            module = "followup",
            action = "BACKFILL_CANDIDATES",
            objectType = "FU_RECORD",
            objectId = "#id",
            description = "读取范围内补录任务候选（不记录字段值）")
    public List<CandidateView> candidates(long id) {
        var row = requireRecord(id, false);
        if (!enabled()) throw new ApiException(403, 40301, "补录任务联动未开通");
        if (!"BACKFILL".equals(row.source()) || !"DRAFT".equals(row.status())) return List.of();
        return views(findCandidates(row, false));
    }

    @AuditLog(
            module = "followup",
            action = "RECORD_SUBMIT_REQUEST",
            objectType = "FU_RECORD",
            objectId = "#id",
            description = "记录提交请求（不记录请求载荷）")
    @Transactional
    public RecordEntryService.SubmitResultView submit(
            long id, RecordEntryService.SubmitCommand command, Long selectedTask) {
        var initial = requireRecord(id, false);
        if (command == null || command.baseVersion() == null)
            throw new ApiException(400, 40001, "提交必须携带 baseVersion（乐观锁）");
        if (!enabled() || !"BACKFILL".equals(initial.source())) {
            if (selectedTask != null) throw new ApiException(403, 40301, "当前记录不支持补录任务联动");
            return entry.submit(id, command);
        }
        if (mapper.lockPatient(initial.patientId()) == null) throw missing();
        var row = requireRecord(id, true);
        String digest = requestDigest(command, selectedTask);
        if ("SUBMITTED".equals(row.status())) {
            if (row.result() != null
                    && Objects.equals(row.digest(), digest)
                    && row.version() == command.baseVersion() + 1) {
                try {
                    return JSON.readValue(row.result(), RecordEntryService.SubmitResultView.class);
                } catch (Exception invalid) {
                    throw new IllegalStateException("补录幂等结果不可读取", invalid);
                }
            }
            throw new ApiException(409, 40901, "记录已提交，请查看结果或通过修改流程更正");
        }
        if (command.data() != null) {
            var schema = forms.schema(row.formCode(), row.templateVersion());
            for (var category : schema.categories())
                for (var field : category.fields()) {
                    Object date = command.data().get(field.key());
                    if ("followup_date".equals(field.code())
                            && date != null
                            && !Objects.equals(String.valueOf(row.visitDate()), date))
                        throw new ApiException(400, 40001, "表单实际随访日期与补录登记日期不一致，请先核对补录日期");
                }
        }
        if (selectedTask != null && mapper.selectedInPatient(selectedTask, row.patientId()) == null)
            throw missing();
        var candidates = findCandidates(row, true);
        BackfillTaskLinkMapper.Candidate selected = null;
        if (selectedTask != null) {
            selected =
                    candidates.stream()
                            .filter(c -> c.taskId() == selectedTask)
                            .findFirst()
                            .orElseThrow(BackfillTaskLinkService::changed);
        } else if (candidates.size() == 1) selected = candidates.getFirst();
        else if (candidates.size() > 1)
            throw new ApiException(
                    409,
                    40901,
                    "存在多个可关联任务，请选择后重新提交",
                    Map.of("backfillCandidates", views(candidates)));
        if (selected != null) {
            try {
                if (mapper.linkTask(selected.taskId(), row.patientId(), id) != 1
                        || mapper.linkRecord(id, selected.taskId(), selected.enrollmentId()) != 1)
                    throw changed();
            } catch (DuplicateKeyException collision) {
                throw changed();
            }
        }
        var result = entry.submitBackfillLinked(id, command);
        if (mapper.saveResult(id, digest, write(result)) != 1) throw changed();
        audit.append(
                AuditEntry.operation("followup", "BACKFILL_TASK_LINK")
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(id))
                        .description(
                                selected == null
                                        ? "补录独立提交（无可关联任务）"
                                        : "补录关联并完成任务 " + selected.taskId())
                        .success()
                        .build());
        return result;
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(features.findEnabled(FEATURE));
    }

    private BackfillTaskLinkMapper.RecordState requireRecord(long id, boolean locking) {
        var row = mapper.record(id, locking);
        if (row == null) throw missing();
        return row;
    }

    private List<BackfillTaskLinkMapper.Candidate> findCandidates(
            BackfillTaskLinkMapper.RecordState row, boolean locking) {
        if (row.taskId() != null || row.visitDate() == null) return List.of();
        var device = devices.resolve(row.patientId(), row.visitDate());
        if (device == null || !device.current()) return List.of();
        return mapper.candidates(
                row.patientId(),
                Long.parseLong(device.implantId()),
                row.formCode(),
                row.visitDate(),
                locking);
    }

    private String requestDigest(RecordEntryService.SubmitCommand command, Long selectedTask) {
        Map<String, Object> request = new TreeMap<>();
        request.put("command", command);
        request.put("selectedTask", selectedTask);
        return digests.hashHex(write(request));
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("补录提交结果编码失败", invalid);
        }
    }

    private static List<CandidateView> views(List<BackfillTaskLinkMapper.Candidate> candidates) {
        return candidates.stream()
                .map(
                        c ->
                                new CandidateView(
                                        String.valueOf(c.taskId()),
                                        c.planName(),
                                        c.nodeName(),
                                        c.planDate(),
                                        c.windowStart(),
                                        c.windowEnd()))
                .toList();
    }

    private static ApiException changed() {
        return new ApiException(409, 40901, "候选任务已变化或已有记录，请重新加载候选后确认");
    }

    private static ApiException missing() {
        return new ApiException(404, 40400, "记录或任务不存在");
    }
}
