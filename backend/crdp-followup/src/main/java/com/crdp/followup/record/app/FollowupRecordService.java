package com.crdp.followup.record.app;

import com.crdp.followup.implant.app.DeviceContextService;
import com.crdp.followup.record.infra.mapper.FollowupRecordInsert;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * fu_record 生命周期（E15-T07 / A-FU-014、C-FU-013；OQ-77① / OQ-83① 载体迁移）：随访记录业务表上的 创建 / 读取 / 自动保存草稿 /
 * 提交，写入闸门与版本口径复用 {@link FormService} 引擎口（writeGateData / validateForSubmit /
 * stripHiddenFields），<b>不与 fe_record 双写</b>（OQ-63①）。
 *
 * <p>与 E15-T02 期 fe_record 载体的行为逐条对齐（HTTP 契约不变，followup.md §3）：
 *
 * <ul>
 *   <li>创建：template_version 固化为当前发布版、初始 DRAFT / version 0；
 *   <li>草稿：只过写入闸门（键 / 类型 / 派生 / 硬范围），changes 增量合并（null 清空键）、data_meta 不动； baseVersion 过期 40900
 *       附冲突详情（currentVersion / 更新人显示名 / 更新时间 / diffFields——只列 changes 两边不同的键，OQ-77③）；已提交 40901；
 *   <li>提交：完整校验（42200）→ 隐藏字段清空 → 写入闸门 → SUBMITTED + version + 1（乐观锁）；返回提交后视图、 提交前
 *       data、被清空键与软校验警告（字段历史与任务联动由 {@link RecordEntryService} 接线）。
 * </ul>
 */
@Service
public class FollowupRecordService {

    /** fu_record.source：任务录入 / 历史补录（计划外 UNPLANNED 随 E15-T06、远程监测 REMOTE 随 E21）。 */
    public static final String SOURCE_TASK = "TASK";

    public static final String SOURCE_BACKFILL = "BACKFILL";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FollowupRecordMapper recordMapper;
    private final FormService formService;
    private final DeviceContextService deviceContext;

    public FollowupRecordService(
            FollowupRecordMapper recordMapper,
            FormService formService,
            DeviceContextService deviceContext) {
        this.recordMapper = recordMapper;
        this.formService = formService;
        this.deviceContext = deviceContext;
    }

    // ---------------------------------------------------------------- DTO

    /** fu_record 记录视图（业务列 + 引擎列；followup.md §3 记录形状）。 */
    public record RecordView(
            long id,
            long patientId,
            Long taskId,
            String source,
            LocalDate visitDate,
            String formCode,
            int templateVersion,
            String status,
            int version,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            String createdAt,
            String updatedAt) {}

    /** 创建请求：任务录入（taskId 非空）或历史补录（visitDate 必填、taskId 空）；data / dataMeta 可空。 */
    public record CreateCommand(
            long patientId,
            Long taskId,
            String source,
            LocalDate visitDate,
            String formCode,
            Map<String, Object> data,
            Map<String, Object> dataMeta) {}

    /** 40900 冲突详情（与 fe_record 载体同形，patient.md §4.1 / OQ-77③）。 */
    public record DraftConflictView(
            int currentVersion,
            String updatedBy,
            String updatedAt,
            List<DiffFieldView> diffFields) {}

    /** 两边不同字段：mine = 请求值、server = 服务器当前值、label 取模板版本快照显示名。 */
    public record DiffFieldView(String key, String label, Object mine, Object server) {}

    /** 提交结果：提交后视图 + 提交前 data + 隐藏字段清空的键 + 软校验警告（字段历史 diff 由调用方算）。 */
    public record SubmitOutcome(
            RecordView view,
            Map<String, Object> before,
            List<String> clearedKeys,
            List<com.crdp.form.rule.app.FieldIssue> warnings) {}

    // ---------------------------------------------------------------- 创建 / 读取

    /** 新建 DRAFT 记录（写入闸门校验后落库；表单 / 版本不存在 40400）。 */
    @Transactional
    public RecordView createDraft(CreateCommand command) {
        int templateVersion = formService.publishedVersion(command.formCode());
        Map<String, Object> persisted =
                formService.writeGateData(
                        command.formCode(),
                        templateVersion,
                        command.data() == null ? new LinkedHashMap<>() : command.data());
        Long operator = CurrentSession.current().map(item -> item.userId()).orElse(null);
        FollowupRecordInsert insert =
                new FollowupRecordInsert(
                        command.patientId(),
                        command.taskId(),
                        command.source(),
                        command.visitDate(),
                        command.formCode(),
                        templateVersion,
                        writeJson(persisted),
                        writeJson(command.dataMeta()),
                        operator);
        recordMapper.insertRecord(insert);
        return record(insert.getId());
    }

    /** 读取记录（不存在 / 越范围 40400——不暴露存在）。 */
    public RecordView record(long id) {
        FollowupRecordMapper.RecordRowRaw row = requireRecord(id);
        return toView(row);
    }

    // ---------------------------------------------------------------- 自动保存草稿（E25-T01 契约）

    /** 自动保存草稿：changes 增量合并（null 清空键）后过写入闸门；返回保存后的视图。 */
    @Transactional
    public RecordView saveDraft(long id, Integer baseVersion, Map<String, Object> changes) {
        if (baseVersion == null) {
            throw new ApiException(400, 40001, "自动保存必须携带 baseVersion（乐观锁）");
        }
        FollowupRecordMapper.RecordRowRaw row = requireRecord(id);
        if ("SUBMITTED".equals(row.status())) {
            throw new ApiException(409, 40901, "记录已提交，不再自动保存（修改走已提交记录修改流程）");
        }
        if (row.version() != baseVersion) {
            throw draftConflict(row, changes);
        }
        Map<String, Object> merged = readMap(row.data());
        if (changes != null) {
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<String, Object> persisted =
                formService.writeGateData(row.formCode(), row.templateVersion(), merged);
        int updated =
                recordMapper.updateDraft(
                        id,
                        baseVersion,
                        writeJson(persisted),
                        CurrentSession.requireHospital().userId());
        if (updated != 1) {
            throw draftConflict(requireRecord(id), changes);
        }
        return record(id);
    }

    // ---------------------------------------------------------------- 提交（A-FU-005 / A-FU-014）

    /** 提交：完整校验（42200）→ 隐藏字段清空 → 写入闸门 → SUBMITTED；字段历史 / 任务完成由调用方接线。 */
    @Transactional
    public SubmitOutcome submit(
            long id, Integer baseVersion, Map<String, Object> data, Map<String, Object> dataMeta) {
        if (baseVersion == null) {
            throw new ApiException(400, 40001, "提交必须携带 baseVersion（乐观锁）");
        }
        FollowupRecordMapper.RecordRowRaw row = requireRecord(id);
        if ("SUBMITTED".equals(row.status())) {
            throw new ApiException(409, 40901, "记录已提交，不能重复提交（修改走已提交记录修改流程）");
        }
        if (row.version() != baseVersion) {
            throw draftConflict(requireRecord(id), data);
        }
        Map<String, Object> values = data == null ? new LinkedHashMap<>() : data;
        // 器械上下文（E21-T03 / C-FU-009）：提交校验与隐藏字段清空用同一上下文——按记录随访日期匹配当时在用的
        // 器械（visit_date 为空的任务录入回落当前器械，C-PAT-007）；开关关闭 / 无器械为空 Map（ctx 断链不成立）。
        Map<String, Object> conditionContext =
                deviceContext.conditionContext(row.patientId(), row.visitDate());
        List<com.crdp.form.rule.app.FieldIssue> warnings =
                formService.validateForSubmit(
                        row.formCode(), row.templateVersion(), values, conditionContext);
        FormService.StrippedData stripped =
                formService.stripHiddenFields(
                        row.formCode(), row.templateVersion(), values, conditionContext);
        Map<String, Object> persisted =
                formService.writeGateData(row.formCode(), row.templateVersion(), stripped.data());
        Map<String, Object> meta =
                dataMeta == null ? readMap(row.dataMeta()) : new LinkedHashMap<>(dataMeta);
        int updated =
                recordMapper.submitRecord(
                        id,
                        baseVersion,
                        writeJson(persisted),
                        writeJson(meta),
                        CurrentSession.requireHospital().userId());
        if (updated != 1) {
            throw draftConflict(requireRecord(id), data);
        }
        return new SubmitOutcome(record(id), readMap(row.data()), stripped.clearedKeys(), warnings);
    }

    // ---------------------------------------------------------------- 报告单映射写入（E16-T07 /
    // C-SCAN-005）

    /** 映射写入结果：写入后视图 + 写入前 data（字段历史 before 由调用方接线）。 */
    public record MappingWriteOutcome(RecordView after, Map<String, Object> before) {}

    /**
     * 报告单映射写入（E16-T07 / A-ATT-013「参数写入映射目标记录」）：合并值过写入闸门（键 / 类型 / 派生 / 硬范围）后写回， 状态不变、version + 1（乐观锁
     * 40900）；字段历史（source = REPORT）由 {@code ReportMappingService} 在同一事务接线。 mergedData 为「目标记录现值 +
     * 本次映射写入值」的全量（与提交口径一致，不做字段级合并）。
     */
    @Transactional
    public MappingWriteOutcome applyMappingWrite(
            long id, int baseVersion, Map<String, Object> mergedData) {
        FollowupRecordMapper.RecordRowRaw row = requireRecord(id);
        if (row.version() != baseVersion) {
            throw draftConflict(row, mergedData);
        }
        Map<String, Object> values = mergedData == null ? new LinkedHashMap<>() : mergedData;
        Map<String, Object> persisted =
                formService.writeGateData(row.formCode(), row.templateVersion(), values);
        int updated =
                recordMapper.applyMappingWrite(
                        id,
                        baseVersion,
                        writeJson(persisted),
                        CurrentSession.requireHospital().userId());
        if (updated != 1) {
            throw draftConflict(requireRecord(id), mergedData);
        }
        return new MappingWriteOutcome(record(id), readMap(row.data()));
    }

    // ---------------------------------------------------------------- 内部

    private FollowupRecordMapper.RecordRowRaw requireRecord(long id) {
        FollowupRecordMapper.RecordRowRaw row = recordMapper.findRecord(id);
        if (row == null) {
            throw new ApiException(404, 40400, "随访记录不存在");
        }
        return row;
    }

    /** 草稿冲突详情（E25 / OQ-77③）：diffFields 只列 changes 里两边取值不同的键，label 取模板版本快照显示名。 */
    private ApiException draftConflict(
            FollowupRecordMapper.RecordRowRaw row, Map<String, Object> changes) {
        Map<String, Object> server = readMap(row.data());
        Map<String, String> labels = formService.fieldLabels(row.formCode(), row.templateVersion());
        List<DiffFieldView> diffFields = new ArrayList<>();
        if (changes != null) {
            for (String key : changes.keySet()) {
                Object mine = changes.get(key);
                Object serverValue = server.get(key);
                if (!Objects.equals(mine, serverValue)) {
                    diffFields.add(
                            new DiffFieldView(
                                    key, labels.getOrDefault(key, key), mine, serverValue));
                }
            }
        }
        DraftConflictView detail =
                new DraftConflictView(
                        row.version(),
                        row.updatedBy() == null
                                ? null
                                : recordMapper.findDisplayName(row.updatedBy()),
                        formatTime(row.updatedAt()),
                        diffFields);
        return new ApiException(409, 40900, "该记录已被他人更新", detail);
    }

    private RecordView toView(FollowupRecordMapper.RecordRowRaw row) {
        return new RecordView(
                row.id(),
                row.patientId(),
                row.taskId(),
                row.source(),
                row.visitDate(),
                row.formCode(),
                row.templateVersion(),
                row.status(),
                row.version(),
                readMap(row.data()),
                readMap(row.dataMeta()),
                formatTime(row.createdAt()),
                formatTime(row.updatedAt()));
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("随访记录 JSON 解析失败（id 脏数据）", invalid);
        }
    }

    private static String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("随访记录 JSON 序列化失败", invalid);
        }
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区，与表单引擎一致）。 */
    private static String formatTime(LocalDateTime time) {
        return time == null
                ? null
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }
}
