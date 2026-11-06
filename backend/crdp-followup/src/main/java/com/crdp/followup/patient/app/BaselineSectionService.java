package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.BaselineSectionInsert;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper.SectionRowRaw;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FormProgressEngine.CategoryProgress;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基线分节用例（E13-T02 / A-PAT-003「其余分类可暂存草稿、陆续补录」、A-PAT-005 字段级留痕；接口 docs/api/patient.md §4）。
 *
 * <p>分节载体 pt_baseline_section（tech-plan §7「每名患者每个分类一行……页签分别暂存互不冲突」），写入闸门与 版本口径复用 {@link
 * FormService} 引擎口（writeGateData / validateForSubmit / stripHiddenFields），与 fu_record（E15-T07）同款：
 *
 * <ul>
 *   <li>读取：按<b>当前发布版</b>模板分类列出全部分节（无行分类输出 EMPTY / version 0），进度（已填 / 应填，A-PAT-021 口径）
 *       按全部分节合并值计算；锁定状态一并下发（前端「编辑」置灰与解锁入口，pages.md §4.2）；
 *   <li>暂存草稿（PUT …/sections/{code}/draft，规格已定）：只过写入闸门、不执行必填 / 跨字段校验、不写字段历史（tech-plan §6
 *       「草稿语义」）；changes 增量合并（null 清空键）、hasDraft 置 1；baseVersion 过期 40900 附冲突详情（patient.md §4.1）；
 *       首次暂存建行（template_version 固化 = 当时发布版）；
 *   <li>正式保存（PUT …/sections/{code}）：完整校验（42200）→ 隐藏字段清空 → 写入闸门 → version + 1、hasDraft 清 0，
 *       同一事务写字段历史（record_type = PT_BASELINE_SECTION，E11-T06）并重算基线完整度（A-PAT-019）；未填完的分类先暂存
 *       草稿保留（保存拦必填，A-PAT-003「可分次补录」）；
 *   <li>锁定（A-PAT-006「锁定后只读」）：分节保存与暂存一律 40901，解锁后恢复（锁定 / 解锁在 {@link BaselineLockService}）。
 * </ul>
 *
 * <p>越范围 / 不存在的患者 40400（不暴露对象存在，AGENTS.md §5.2）。保存与「保留我的并覆盖」写操作审计（常规自动保存不逐次写审计，与 E25-T01 同口径）。
 */
@Service
public class BaselineSectionService {

    /**
     * 基线表单编码：医院 A 基线档案（followup V38 种子，分类 1–8）。医院 C 的患者登记表单随 E21 接入时改为按租户解析——
     * 分节由模板分类决定、字段来自字典（AGENTS §5.8），不在页面硬编码。
     */
    static final String BASELINE_FORM_CODE = "A_BASELINE";

    /** 分节完整度状态（patient.md §4）：未填写 / 部分 / 完成（口径 = 已填必填 ÷ 应填必填，A-PAT-019）。 */
    public static final String STATUS_EMPTY = "EMPTY";

    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_DONE = "DONE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BaselineGateway gateway;
    private final BaselineSectionMapper mapper;
    private final BaselineLockService lockService;
    private final FormService formService;
    private final FieldHistoryService fieldHistory;
    private final PatientSummaryService summary;
    private final AuditLogger audit;

    public BaselineSectionService(
            BaselineGateway gateway,
            BaselineSectionMapper mapper,
            BaselineLockService lockService,
            FormService formService,
            FieldHistoryService fieldHistory,
            PatientSummaryService summary,
            AuditLogger audit) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.lockService = lockService;
        this.formService = formService;
        this.fieldHistory = fieldHistory;
        this.summary = summary;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 分节视图：无行分类输出 version 0 / EMPTY / 空 data（前端整页只读展示「未填写」，pages.md §4.2）。 */
    public record SectionView(
            String code,
            String id,
            int version,
            String status,
            Map<String, Object> data,
            Map<String, Object> meta,
            String updatedBy,
            String updatedAt,
            boolean hasDraft) {}

    /** GET /patients/{id}/baseline 响应（patient.md §4）：模板版本 + 锁定态 + 全部分节 + 分类进度。 */
    public record BaselineView(
            String formCode,
            int formVersion,
            BaselineLockService.LockView lock,
            List<SectionView> sections,
            List<CategoryProgress> progress) {}

    /** 暂存草稿请求（E25 契约：baseVersion 乐观锁、changes 只带变更字段、overwrite 随冲突流程）。 */
    public record DraftCommand(
            Integer baseVersion, Map<String, Object> changes, Boolean overwrite) {}

    /** 暂存结果（表单顶部「已自动保存 HH:mm」）。 */
    public record DraftSavedView(int version, String savedAt) {}

    /** 正式保存请求：data 全量、baseVersion 乐观锁、meta 缺省保留原值。 */
    public record SaveCommand(
            Integer baseVersion, Map<String, Object> data, Map<String, Object> meta) {}

    /** 保存结果：保存后的分节视图 + 软校验警告（不拦截，A-FLD-003「提示但可保存」）。 */
    public record SaveResultView(SectionView section, List<WarningView> warnings) {}

    /** 软校验警告（与 RecordEntryService.WarningView 同形）。 */
    public record WarningView(String key, String message) {}

    /** 40900 冲突详情（patient.md §4.1：当前版本 / 更新人 / 更新时间 / 两边不同字段）。 */
    public record DraftConflictView(
            int currentVersion,
            String updatedBy,
            String updatedAt,
            List<DiffFieldView> diffFields) {}

    /** 两边不同字段：mine = 请求值、server = 服务器当前值、label 取模板版本快照显示名（OQ-77③）。 */
    public record DiffFieldView(String key, String label, Object mine, Object server) {}

    // ---------------------------------------------------------------- 读取

    /** 基线全量视图：按当前发布版模板分类列全部分节 + 合并进度 + 锁定状态。 */
    public BaselineView baseline(long patientId) {
        requirePatient(patientId);
        int formVersion = formService.publishedVersion(BASELINE_FORM_CODE);
        List<FormService.CategoryView> categories =
                formService.schema(BASELINE_FORM_CODE, formVersion).categories();
        Map<String, SectionRowRaw> rows = new LinkedHashMap<>();
        for (SectionRowRaw row : gateway.sections(patientId)) {
            rows.put(row.categoryKey(), row);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (FormService.CategoryView category : categories) {
            SectionRowRaw row = rows.get(category.key());
            if (row != null) {
                merged.putAll(readMap(row.data()));
            }
        }
        Map<String, CategoryProgress> progressByKey = new LinkedHashMap<>();
        for (CategoryProgress item :
                formService
                        .progress(BASELINE_FORM_CODE, formVersion, merged, Map.of())
                        .categories()) {
            progressByKey.put(item.key(), item);
        }
        List<SectionView> sections = new ArrayList<>();
        for (FormService.CategoryView category : categories) {
            SectionRowRaw row = rows.get(category.key());
            if (row == null) {
                sections.add(emptySection(category.key()));
                continue;
            }
            CategoryProgress progress =
                    progressByKey.getOrDefault(
                            category.key(),
                            new CategoryProgress(category.key(), category.label(), 0, 0));
            sections.add(toView(row, progress));
        }
        return new BaselineView(
                BASELINE_FORM_CODE,
                formVersion,
                lockService.lockView(patientId),
                sections,
                List.copyOf(progressByKey.values()));
    }

    // ---------------------------------------------------------------- 暂存草稿（tech-plan §6 草稿语义）

    /** 自动保存草稿：changes 增量合并（null 清空键）后过写入闸门；首次暂存建行；overwrite 写覆盖审计。 */
    @Transactional
    public DraftSavedView saveDraft(long patientId, String categoryKey, DraftCommand command) {
        if (command == null || command.baseVersion() == null) {
            throw new ApiException(400, 40001, "自动保存必须携带 baseVersion（乐观锁）");
        }
        if (command.changes() == null || command.changes().isEmpty()) {
            throw new ApiException(400, 40001, "自动保存必须携带变更字段（只在有新改动时发送）");
        }
        requirePatient(patientId);
        lockService.requireUnlocked(patientId, "基线已锁定，不能暂存草稿（需先解锁）");
        long operator = CurrentSession.requireHospital().userId();
        SectionRowRaw row = gateway.section(patientId, categoryKey);
        if (row == null) {
            requireBaseVersionZero(command.baseVersion());
            int templateVersion = formService.publishedVersion(BASELINE_FORM_CODE);
            Map<String, Object> persisted =
                    formService.writeGateData(
                            BASELINE_FORM_CODE, templateVersion, command.changes());
            mapper.insertSection(
                    new BaselineSectionInsert(
                            patientId,
                            BASELINE_FORM_CODE,
                            categoryKey,
                            templateVersion,
                            writeJson(persisted),
                            null,
                            1,
                            true,
                            operator));
            refreshCompleteness(patientId);
            SectionRowRaw saved = requireRow(patientId, categoryKey);
            return new DraftSavedView(saved.version(), Time.format(saved.updatedAt()));
        }
        if (row.version() != command.baseVersion()) {
            throw draftConflict(row, command.changes());
        }
        Map<String, Object> merged = readMap(row.data());
        for (Map.Entry<String, Object> entry : command.changes().entrySet()) {
            if (entry.getValue() == null) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> persisted =
                formService.writeGateData(row.formCode(), row.templateVersion(), merged);
        int updated =
                mapper.updateDraft(row.id(), command.baseVersion(), writeJson(persisted), operator);
        if (updated != 1) {
            throw draftConflict(requireRow(patientId, categoryKey), command.changes());
        }
        refreshCompleteness(patientId);
        if (Boolean.TRUE.equals(command.overwrite())) {
            audit.append(
                    AuditEntry.operation("followup", "BASELINE_DRAFT_OVERWRITE")
                            .objectType("PT_BASELINE_SECTION")
                            .objectId(String.valueOf(row.id()))
                            .description(
                                    "自动保存覆盖他人版本：基线分节（患者 "
                                            + patientId
                                            + "，分类 "
                                            + categoryKey
                                            + "）自 baseVersion "
                                            + command.baseVersion()
                                            + " 保存为 v"
                                            + (row.version() + 1)
                                            + "（保留我的并覆盖，字段 "
                                            + String.join("、", command.changes().keySet())
                                            + "）")
                            .result(AuditResult.SUCCESS)
                            .build());
        }
        SectionRowRaw saved = requireRow(patientId, categoryKey);
        return new DraftSavedView(saved.version(), Time.format(saved.updatedAt()));
    }

    // ---------------------------------------------------------------- 正式保存（A-PAT-005 留痕）

    /** 正式保存：完整校验（42200）→ 隐藏字段清空 → 写入闸门 → version + 1、清草稿； 同一事务写字段历史与完整度、写审计。首次保存建行。 */
    @Transactional
    public SaveResultView saveSection(long patientId, String categoryKey, SaveCommand command) {
        if (command == null || command.baseVersion() == null) {
            throw new ApiException(400, 40001, "保存必须携带 baseVersion（乐观锁）");
        }
        requirePatient(patientId);
        lockService.requireUnlocked(patientId, "基线已锁定，不能保存（需先解锁）");
        long operator = CurrentSession.requireHospital().userId();
        SectionRowRaw row = gateway.section(patientId, categoryKey);
        boolean created = row == null;
        if (created) {
            requireBaseVersionZero(command.baseVersion());
        } else if (row.version() != command.baseVersion()) {
            throw draftConflict(row, command.data());
        }
        int templateVersion =
                created ? formService.publishedVersion(BASELINE_FORM_CODE) : row.templateVersion();
        String formCode = created ? BASELINE_FORM_CODE : row.formCode();
        List<com.crdp.form.rule.app.FieldIssue> warnings =
                formService.validateForSubmit(formCode, templateVersion, values(command.data()));
        FormService.StrippedData stripped =
                formService.stripHiddenFields(
                        formCode, templateVersion, values(command.data()), Map.of());
        Map<String, Object> persisted =
                formService.writeGateData(formCode, templateVersion, stripped.data());
        if (created) {
            mapper.insertSection(
                    new BaselineSectionInsert(
                            patientId,
                            formCode,
                            categoryKey,
                            templateVersion,
                            writeJson(persisted),
                            writeJson(command.meta()),
                            1,
                            false,
                            operator));
        } else {
            Map<String, Object> meta =
                    command.meta() == null
                            ? readMap(row.dataMeta())
                            : new LinkedHashMap<>(command.meta());
            int updated =
                    mapper.updateSection(
                            row.id(),
                            command.baseVersion(),
                            writeJson(persisted),
                            writeJson(meta),
                            operator);
            if (updated != 1) {
                throw draftConflict(requireRow(patientId, categoryKey), command.data());
            }
        }
        SectionRowRaw saved = requireRow(patientId, categoryKey);
        List<FieldHistoryService.FieldChange> changes =
                fieldHistory.logChanges(
                        FieldHistoryService.PT_BASELINE_SECTION,
                        saved.id(),
                        saved.formCode(),
                        saved.templateVersion(),
                        row == null ? Map.of() : readMap(row.data()),
                        readMap(saved.data()),
                        ChangeSource.USER,
                        "基线分节保存");
        refreshCompleteness(patientId);
        audit.append(
                AuditEntry.operation("followup", "BASELINE_SECTION_SAVE")
                        .objectType("PT_BASELINE_SECTION")
                        .objectId(String.valueOf(saved.id()))
                        .description(
                                "保存基线分节（患者 "
                                        + patientId
                                        + "，分类 "
                                        + categoryKey
                                        + "，v"
                                        + (created ? 0 : row.version())
                                        + " → v"
                                        + saved.version()
                                        + "，字段变更 "
                                        + changes.size()
                                        + " 处）")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new SaveResultView(
                toView(saved), warnings.stream().map(BaselineSectionService::toWarning).toList());
    }

    // ---------------------------------------------------------------- 字段历史查询（A-PAT-005「可查旧值新值人时间」）

    /** 分节字段历史（谁、何时、旧值 → 新值；分节未保存过 40400——无记录可查）。 */
    public List<FieldHistoryService.HistoryView> sectionHistory(
            long patientId, String categoryKey, String fieldKey) {
        requirePatient(patientId);
        SectionRowRaw row = gateway.section(patientId, categoryKey);
        if (row == null) {
            throw new ApiException(404, 40400, "基线分节尚未保存，没有字段历史");
        }
        return fieldHistory.history(FieldHistoryService.PT_BASELINE_SECTION, row.id(), fieldKey);
    }

    // ---------------------------------------------------------------- 内部

    /** 完整度重算（A-PAT-019：已填基线必填 ÷ 应填，四舍五入 0–100；应填为 0 视为 100——无可填项即完整）。 */
    private void refreshCompleteness(long patientId) {
        int formVersion = formService.publishedVersion(BASELINE_FORM_CODE);
        Map<String, Object> merged = new LinkedHashMap<>();
        for (SectionRowRaw row : gateway.sections(patientId)) {
            merged.putAll(readMap(row.data()));
        }
        int filled = 0;
        int total = 0;
        for (CategoryProgress category :
                formService
                        .progress(BASELINE_FORM_CODE, formVersion, merged, Map.of())
                        .categories()) {
            filled += category.filled();
            total += category.total();
        }
        summary.updateBaselineCompleteness(
                patientId, total == 0 ? 100 : (int) Math.round(filled * 100.0 / total), null);
    }

    private static WarningView toWarning(com.crdp.form.rule.app.FieldIssue issue) {
        return new WarningView(issue.key(), issue.message());
    }

    private SectionView toView(SectionRowRaw row) {
        return toView(row, progressOf(row));
    }

    private SectionView toView(SectionRowRaw row, CategoryProgress progress) {
        return new SectionView(
                row.categoryKey(),
                String.valueOf(row.id()),
                row.version(),
                statusOf(row, progress),
                readMap(row.data()),
                readMap(row.dataMeta()),
                displayName(row.updatedBy()),
                Time.format(row.updatedAt()),
                row.hasDraft());
    }

    /** 分节状态（A-PAT-019 口径）：应填必填全部已填 = DONE；无任何值 = EMPTY；其余 PARTIAL。 */
    private static String statusOf(SectionRowRaw row, CategoryProgress progress) {
        boolean anyValue = readMap(row.data()).values().stream().anyMatch(Objects::nonNull);
        if (!anyValue) {
            return STATUS_EMPTY;
        }
        return progress.filled() >= progress.total() ? STATUS_DONE : STATUS_PARTIAL;
    }

    /** 该分节自己的分类进度（保存返回视图用；进度引擎按整段 data 求值，非本分类键不计入）。 */
    private CategoryProgress progressOf(SectionRowRaw row) {
        int formVersion = formService.publishedVersion(BASELINE_FORM_CODE);
        for (CategoryProgress progress :
                formService
                        .progress(BASELINE_FORM_CODE, formVersion, readMap(row.data()), Map.of())
                        .categories()) {
            if (progress.key().equals(row.categoryKey())) {
                return progress;
            }
        }
        return new CategoryProgress(row.categoryKey(), row.categoryKey(), 0, 0);
    }

    private static SectionView emptySection(String categoryKey) {
        return new SectionView(
                categoryKey,
                null,
                0,
                STATUS_EMPTY,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                null,
                null,
                false);
    }

    private static void requireBaseVersionZero(int baseVersion) {
        if (baseVersion != 0) {
            throw new ApiException(409, 40900, "该分节尚未建立，baseVersion 应为 0");
        }
    }

    private void requirePatient(long patientId) {
        if (gateway.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在");
        }
    }

    private SectionRowRaw requireRow(long patientId, String categoryKey) {
        SectionRowRaw row = gateway.section(patientId, categoryKey);
        if (row == null) {
            throw new ApiException(404, 40400, "基线分节不存在");
        }
        return row;
    }

    private String displayName(Long userId) {
        return userId == null ? null : mapper.findDisplayName(userId);
    }

    /** 草稿冲突详情（patient.md §4.1 / OQ-77③）：diffFields 只列 changes 里两边取值不同的键。 */
    private ApiException draftConflict(SectionRowRaw row, Map<String, Object> changes) {
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
                        displayName(row.updatedBy()),
                        Time.format(row.updatedAt()),
                        diffFields);
        return new ApiException(409, 40900, "该记录已被他人更新", detail);
    }

    private static Map<String, Object> values(Map<String, Object> data) {
        return data == null ? new LinkedHashMap<>() : data;
    }

    static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("基线分节 JSON 解析失败（脏数据）", invalid);
        }
    }

    private static String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("基线分节 JSON 序列化失败", invalid);
        }
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区，与表单引擎一致）。 */
    static final class Time {
        private Time() {}

        static String format(java.time.LocalDateTime time) {
            return time == null
                    ? null
                    : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
    }
}
