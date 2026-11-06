package com.crdp.followup.report.app;

import com.crdp.followup.patient.app.BaselineLockService;
import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.record.app.FollowupRecordService;
import com.crdp.followup.report.domain.MappingValues;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.MappingItemRow;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.MappingRow;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.ReportHeadRow;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告单 → 结构化字段映射写入（E16-T07 / A-ATT-013；C-SCAN-005 程控报告单同一机制，预置随 E23）： 预览（候选目标、字段、原值 /
 * 新值、冲突提示）与确认（逐项确认覆盖、 写入留痕、审计），接口 docs/api/report.md §3。
 *
 * <p>验收「映射写入留痕，冲突提示」：
 *
 * <ul>
 *   <li><b>留痕</b>：写入经 {@link FieldHistoryService#logChanges} 逐字段落 fe_field_history（source =
 *       REPORT，tech-plan §6 五来源之一），并写 RPT_MAPPING_WRITE
 *       操作审计；值只存映射目标记录，报告单不另存一份（A-ATT-013「参数只保存在映射目标记录中」）。
 *   <li><b>冲突提示</b>：预览每行带 conflict（原值非空且与新值不同，OQ-65 值口径）；确认只写 fieldKeys 里 的键——未确认的冲突字段跳过并在响应里回告
 *       skippedConflicts（A 规格流程第 4 步「原值不同的须逐项确认覆盖」）； 目标记录版本过期 40900（AGENTS §5.10 乐观锁，前端重新预览）。
 *   <li><b>映射目标须是位置之一</b>（A-ATT-013）：基线映射要求位置含目标分类（按映射字段的所属分类推导，A 为「超声检查」）， 随访映射的候选 =
 *       位置中的随访记录（整条或分组，多条时逐条列出由用户选）。
 *   <li><b>时相边界与锁定</b>（V64 remark 由本写入侧校验）：术前最后一次 ≤ 手术日期、基线映射 ≤ 出院日期；基线锁定 40901（A-PAT-006）。
 *   <li><b>值转换</b>（{@link MappingValues}）：指标结果文本按目标字段类型转换（数值 / 日期 / 选项名→编码 / 长×宽）， 转换失败预览逐行提示、确认
 *       40001。
 *   <li><b>附带写入</b>：映射级（随访目标「复查超声 = 是」）与项目级（返流程度写入时「有无…返流 = 有」）按 V64 配置 一并列出，随确认一并写入。
 * </ul>
 *
 * <p>报告单越范围 / 不存在 / 已删 40400（不暴露对象存在）；预览不写任何数据。写入闸门与版本口径复用各目标记录 app 口（ {@code
 * FollowupRecordService.applyMappingWrite} / {@code
 * BaselineSectionService.applyMappingWrite}），本服务只做映射解析与接线。
 */
@Service
public class ReportMappingService {

    static final String TARGET_BASELINE = "BASELINE";

    static final String TARGET_FOLLOWUP_RECORD = "FOLLOWUP_RECORD";

    static final String TARGET_IMPLANT_RECORD = "IMPLANT_RECORD";

    /** 固定来源项目：报告单的报告时间（A 规格映射表首行「超声检查时间 = 报告时间」）。 */
    static final String ITEM_REPORT_DATE = "REPORT_DATE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> PHASES = Set.of("PREOP_LAST", "PREDISCHARGE_LAST");

    private final ReportMapper mapper;
    private final ReportConfigMapper config;
    private final FormService formService;
    private final FollowupRecordService recordService;
    private final BaselineSectionService baselineService;
    private final BaselineLockService lockService;
    private final FieldHistoryService fieldHistory;
    private final AuditLogger audit;

    public ReportMappingService(
            ReportMapper mapper,
            ReportConfigMapper config,
            FormService formService,
            FollowupRecordService recordService,
            BaselineSectionService baselineService,
            BaselineLockService lockService,
            FieldHistoryService fieldHistory,
            AuditLogger audit) {
        this.mapper = mapper;
        this.config = config;
        this.formService = formService;
        this.recordService = recordService;
        this.baselineService = baselineService;
        this.lockService = lockService;
        this.fieldHistory = fieldHistory;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO（report.md §3）

    /** 一条将写入的字段（预览行）：ITEM = 指标项目行（含 REPORT_DATE），EXTRA = 附带写入。 */
    public record WriteView(
            String source,
            String itemCode,
            String result,
            String fieldKey,
            String fieldLabel,
            Object oldValue,
            Object newValue,
            boolean conflict,
            String error) {}

    /** 一个可映射目标（基线分节或一条随访记录）：targetRecordId 为分节 / 记录 id（分节未建为 null、targetVersion 0）。 */
    public record CandidateView(
            String linkRecordId,
            String recordLabel,
            String recordStatus,
            String targetRecordId,
            int targetVersion,
            String categoryKey,
            String categoryLabel,
            List<WriteView> writes,
            int conflictCount) {}

    /** 一条适用映射（blockedReason 非空 = 时相边界 / 锁定 / 配置问题，确认会被拦截）。 */
    public record MappingOptionView(
            String mappingId,
            String code,
            String name,
            String phase,
            String targetType,
            String targetFormCode,
            String targetFormName,
            String blockedReason,
            List<CandidateView> candidates) {}

    /** 预览响应：mappings 为空 = 该类型无映射配置或位置中没有可映射目标（前端隐藏映射步骤）。 */
    public record PreviewView(
            String reportId,
            String typeCode,
            String typeName,
            String name,
            LocalDate reportDate,
            List<MappingOptionView> mappings) {}

    /** 确认请求：mappingId / targetVersion 必填；随访目标带 linkRecordId；fieldKeys = 确认写入的键（含逐项确认的冲突覆盖）。 */
    public record ConfirmCommand(
            String mappingId, String linkRecordId, Integer targetVersion, List<String> fieldKeys) {}

    /** 确认结果：writtenFields 实际写入、skippedConflicts 未确认而跳过的冲突字段、fieldChanges 字段历史条数。 */
    public record ConfirmedView(
            String mappingId,
            String targetType,
            String targetRecordId,
            int targetVersion,
            List<String> writtenFields,
            List<String> skippedConflicts,
            int fieldChanges) {}

    /** 附带写入配置（rpt_mapping / rpt_mapping_item.extra_writes JSON：[{fieldKey,value}]）。 */
    record ExtraWrite(String fieldKey, String value) {}

    /** 装配出的候选（视图 + 目标记录现值），确认路径复用同一装配保证「预览即所得」。 */
    record Candidate(CandidateView view, Map<String, Object> currentData) {}

    // ---------------------------------------------------------------- 预览（冲突提示）

    /** 预览：列出适用映射、候选目标与将写入的字段（原值 / 新值 / conflict / 转换错误），不写任何数据。 */
    public PreviewView preview(long reportId) {
        ReportHeadRow report = requireReport(reportId);
        PreviewContext context = contextOf(report);
        List<MappingOptionView> options = new ArrayList<>();
        for (MappingRow row : context.mappings()) {
            MappingOptionView option = optionOf(row, context);
            if (option != null) {
                options.add(option);
            }
        }
        return new PreviewView(
                String.valueOf(report.id()),
                report.typeCode(),
                report.typeName(),
                report.nameSnapshot(),
                report.reportDate(),
                options);
    }

    // ---------------------------------------------------------------- 确认（写入留痕）

    /**
     * 确认写入：只写 fieldKeys 内的键（含逐项确认的冲突覆盖），未确认的冲突字段跳过并回告； 同一事务写字段历史（source = REPORT）与
     * RPT_MAPPING_WRITE 审计。
     */
    @Transactional
    public ConfirmedView confirm(long reportId, ConfirmCommand command) {
        if (command == null || command.mappingId() == null || command.mappingId().isBlank()) {
            throw bad("请选择要应用的映射");
        }
        if (command.targetVersion() == null || command.targetVersion() < 0) {
            throw bad("请携带目标记录版本（targetVersion，未建分节为 0）");
        }
        if (command.fieldKeys() == null || command.fieldKeys().isEmpty()) {
            throw bad("请至少确认 1 个要写入的字段");
        }
        ReportHeadRow report = requireReport(reportId);
        MappingRow mapping = requireMapping(command.mappingId(), report);
        PreviewContext context = contextOf(report, mapping);
        MappingOptionView option = optionOf(mapping, context);
        if (option == null) {
            throw bad("映射目标必须是该报告单的位置之一（先在报告单上补位置）");
        }
        if (option.blockedReason() != null) {
            if (option.blockedReason().contains("已锁定")) {
                // 基线锁定与保存 / 暂存同口径 40901（A-PAT-006 锁定后只读）；其余拦截原因 40001
                throw new ApiException(409, 40901, option.blockedReason());
            }
            throw bad(option.blockedReason());
        }
        Candidate candidate = chooseCandidate(context, option, command, mapping, report);

        Map<String, Object> merged = new LinkedHashMap<>(candidate.currentData());
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        LinkedHashSet<String> requested = new LinkedHashSet<>(command.fieldKeys());
        for (WriteView write : candidate.view().writes()) {
            boolean chosen = requested.remove(write.fieldKey());
            if (!chosen) {
                if (write.error() == null && write.conflict()) {
                    skipped.add(write.fieldKey());
                }
                continue;
            }
            if (write.error() != null) {
                throw bad(write.error());
            }
            merged.put(write.fieldKey(), write.newValue());
            written.add(write.fieldKey());
        }
        if (!requested.isEmpty()) {
            throw bad("字段不在该映射的写入清单中：" + String.join("、", requested));
        }
        if (written.isEmpty()) {
            throw bad("请至少确认 1 个要写入的字段");
        }

        String reason =
                "报告单映射写入："
                        + report.typeName()
                        + (report.nameSnapshot() == null ? "" : " · " + report.nameSnapshot())
                        + "（报告单 "
                        + report.id()
                        + "）";
        int changes;
        String targetRecordId;
        int newVersion;
        if (TARGET_BASELINE.equals(mapping.targetType())) {
            lockService.requireUnlocked(report.patientId(), "基线已锁定，不能写入映射字段（需先解锁）");
            BaselineSectionService.MappingWriteOutcome outcome =
                    baselineService.applyMappingWrite(
                            report.patientId(),
                            candidate.view().categoryKey(),
                            command.targetVersion(),
                            merged);
            changes =
                    fieldHistory
                            .logChanges(
                                    FieldHistoryService.PT_BASELINE_SECTION,
                                    Long.parseLong(outcome.after().id()),
                                    outcome.formCode(),
                                    outcome.templateVersion(),
                                    outcome.before(),
                                    outcome.after().data(),
                                    ChangeSource.REPORT,
                                    reason)
                            .size();
            targetRecordId = outcome.after().id();
            newVersion = outcome.after().version();
        } else {
            FollowupRecordService.MappingWriteOutcome outcome =
                    recordService.applyMappingWrite(
                            Long.parseLong(candidate.view().linkRecordId()),
                            command.targetVersion(),
                            merged);
            changes =
                    fieldHistory
                            .logChanges(
                                    FieldHistoryService.FU_RECORD,
                                    outcome.after().id(),
                                    outcome.after().formCode(),
                                    outcome.after().templateVersion(),
                                    outcome.before(),
                                    outcome.after().data(),
                                    ChangeSource.REPORT,
                                    reason)
                            .size();
            targetRecordId = String.valueOf(outcome.after().id());
            newVersion = outcome.after().version();
        }
        audit.append(
                AuditEntry.operation("followup", "RPT_MAPPING_WRITE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(report.id()))
                        .description(
                                "映射写入："
                                        + report.typeName()
                                        + " → "
                                        + targetLabel(option, candidate)
                                        + "，写字段 "
                                        + written.size()
                                        + " 个（跳过未确认冲突 "
                                        + skipped.size()
                                        + " 个、字段历史 "
                                        + changes
                                        + " 条），来源报告单 "
                                        + report.id())
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "mappingId",
                                                String.valueOf(mapping.id()),
                                                "targetRecordId",
                                                targetRecordId,
                                                "writtenFields",
                                                written,
                                                "skippedConflicts",
                                                skipped)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new ConfirmedView(
                String.valueOf(mapping.id()),
                mapping.targetType(),
                targetRecordId,
                newVersion,
                List.copyOf(written),
                List.copyOf(skipped),
                changes);
    }

    // ---------------------------------------------------------------- 装配（预览与确认共用：预览即所得）

    /** 一条映射的适用性判断与候选装配；目标不在位置中返回 null（映射不适用，不进清单）。 */
    private MappingOptionView optionOf(MappingRow mapping, PreviewContext context) {
        if (TARGET_IMPLANT_RECORD.equals(mapping.targetType())) {
            // 配置表支持（V64），C 程控记录目标随 E21 开放
            return new MappingOptionView(
                    String.valueOf(mapping.id()),
                    mapping.code(),
                    mapping.name(),
                    mapping.phase(),
                    mapping.targetType(),
                    mapping.targetFormCode(),
                    mapping.targetFormName(),
                    "程控记录映射随医院 C 扩展（E21）开放，当前不可写入",
                    List.of());
        }
        if (TARGET_BASELINE.equals(mapping.targetType())) {
            return baselineOption(mapping, context);
        }
        if (TARGET_FOLLOWUP_RECORD.equals(mapping.targetType())) {
            return followupOption(mapping, context);
        }
        return new MappingOptionView(
                String.valueOf(mapping.id()),
                mapping.code(),
                mapping.name(),
                mapping.phase(),
                mapping.targetType(),
                mapping.targetFormCode(),
                mapping.targetFormName(),
                "映射目标类型不支持：" + mapping.targetType(),
                List.of());
    }

    private MappingOptionView baselineOption(MappingRow mapping, PreviewContext context) {
        if (!BaselineSectionService.BASELINE_FORM_CODE.equals(mapping.targetFormCode())) {
            return blocked(mapping, "映射目标表单不是本院基线表单，暂不能写入");
        }
        List<MappingItemRow> items = config.findMappingItems(mapping.id());
        // 目标分类 = 映射字段所属的基线分类（A 为「超声检查」）；字段不在模板中按配置问题拦截
        String categoryKey = null;
        String categoryLabel = null;
        for (FormService.CategoryView category : context.baselineSchema().categories()) {
            for (FormService.FieldView field : category.fields()) {
                for (MappingItemRow item : items) {
                    if (field.key().equals(item.targetFieldKey())) {
                        categoryKey = category.key();
                        categoryLabel = category.label();
                        break;
                    }
                }
                if (categoryKey != null) {
                    break;
                }
            }
            if (categoryKey != null) {
                break;
            }
        }
        if (categoryKey == null) {
            return blocked(mapping, "映射目标字段不在基线模板中（映射配置需修正）");
        }
        String anchorCategory = categoryKey;
        boolean located =
                context.links().stream()
                        .anyMatch(
                                link ->
                                        ReportService.LINK_BASELINE.equals(link.linkType())
                                                && ReportService.ANCHOR_CATEGORY.equals(
                                                        link.anchorType())
                                                && anchorCategory.equals(link.anchorKey()));
        if (!located) {
            return null; // 位置不含目标分类 → 映射不适用（A-ATT-013 映射目标须是位置之一）
        }
        BaselineSectionService.BaselineView baseline =
                baselineService.baseline(context.patientId());
        BaselineSectionService.SectionView section = null;
        for (BaselineSectionService.SectionView item : baseline.sections()) {
            if (anchorCategory.equals(item.code())) {
                section = item;
                break;
            }
        }
        Map<String, Object> current =
                section == null ? Map.of() : new LinkedHashMap<>(section.data());
        String blockedReason = baselineBlockReason(mapping, context);
        FormSchemaIndex index = context.baselineIndex();
        List<WriteView> writes = writesOf(mapping, items, context, index, current);
        int conflicts = (int) writes.stream().filter(WriteView::conflict).count();
        CandidateView candidate =
                new CandidateView(
                        null,
                        "基线 · " + categoryLabel,
                        null,
                        section == null ? null : section.id(),
                        section == null ? 0 : section.version(),
                        anchorCategory,
                        categoryLabel,
                        writes,
                        conflicts);
        return new MappingOptionView(
                String.valueOf(mapping.id()),
                mapping.code(),
                mapping.name(),
                mapping.phase(),
                mapping.targetType(),
                mapping.targetFormCode(),
                mapping.targetFormName(),
                blockedReason,
                List.of(candidate));
    }

    /** 时相边界（V64 remark）与基线锁定（A-PAT-006）的拦截提示；通过返回 null。 */
    private String baselineBlockReason(MappingRow mapping, PreviewContext context) {
        BaselineSectionService.BaselineView baseline =
                baselineService.baseline(context.patientId());
        if (baseline.lock() != null && baseline.lock().locked()) {
            return "基线已锁定，不能写入映射字段（需先解锁）";
        }
        ReportMapper.PatientDatesRow dates = mapper.patientDates(context.patientId());
        LocalDate reportDate = context.report().reportDate();
        if ("PREOP_LAST".equals(mapping.phase())
                && dates != null
                && dates.surgeryDate() != null
                && reportDate.isAfter(dates.surgeryDate())) {
            return "报告时间晚于手术日期，不能写入基线「术前最后一次」";
        }
        if (PHASES.contains(mapping.phase())
                && dates != null
                && dates.dischargeDate() != null
                && reportDate.isAfter(dates.dischargeDate())) {
            return "报告时间晚于出院日期，不能写入基线（" + phaseLabel(mapping.phase()) + "）";
        }
        return null;
    }

    private MappingOptionView followupOption(MappingRow mapping, PreviewContext context) {
        // 候选 = 位置中的随访记录（整条或分组都指到记录，A-ATT-013「位置含该记录整条或其中分组」）
        LinkedHashSet<Long> recordIds = new LinkedHashSet<>();
        for (LinkRow link : context.links()) {
            if (ReportService.LINK_FOLLOWUP_RECORD.equals(link.linkType())
                    && link.linkRecordId() != null) {
                recordIds.add(link.linkRecordId());
            }
        }
        List<CandidateView> candidates = new ArrayList<>();
        Map<Long, Candidate> assembled = new LinkedHashMap<>();
        for (Long recordId : recordIds) {
            ReportMapper.RecordRow meta = context.records().get(recordId);
            if (meta == null || !meta.formCode().equals(mapping.targetFormCode())) {
                continue; // 记录不可见（范围）或表单与映射目标不一致 → 不作候选
            }
            FollowupRecordService.RecordView record = recordService.record(recordId);
            FormService.SchemaView schema =
                    formService.schema(record.formCode(), record.templateVersion());
            FormSchemaIndex index = new FormSchemaIndex(schema);
            Map<String, Object> current = new LinkedHashMap<>(record.data());
            List<WriteView> writes =
                    writesOf(
                            mapping,
                            config.findMappingItems(mapping.id()),
                            context,
                            index,
                            current);
            int conflicts = (int) writes.stream().filter(WriteView::conflict).count();
            CandidateView view =
                    new CandidateView(
                            String.valueOf(recordId),
                            recordTitle(meta),
                            record.status(),
                            String.valueOf(recordId),
                            record.version(),
                            null,
                            null,
                            writes,
                            conflicts);
            candidates.add(view);
            assembled.put(recordId, new Candidate(view, current));
        }
        if (candidates.isEmpty()) {
            return null; // 位置中没有该映射表单的随访记录 → 不适用
        }
        context.candidates().put(String.valueOf(mapping.id()), assembled);
        return new MappingOptionView(
                String.valueOf(mapping.id()),
                mapping.code(),
                mapping.name(),
                mapping.phase(),
                mapping.targetType(),
                mapping.targetFormCode(),
                mapping.targetFormName(),
                null,
                List.copyOf(candidates));
    }

    /** 预写入清单：映射项目行（REPORT_DATE 取报告时间）+ 项目级附带写入 + 映射级附带写入，按 fieldKey 去重（首个优先）。 */
    private List<WriteView> writesOf(
            MappingRow mapping,
            List<MappingItemRow> items,
            PreviewContext context,
            FormSchemaIndex index,
            Map<String, Object> current) {
        List<WriteView> writes = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (MappingItemRow item : items) {
            String rawResult = resultOf(item.itemCode(), context);
            if (rawResult == null) {
                continue; // 报告单没填该项目 → 没有可写入值
            }
            addWrite(
                    writes,
                    seen,
                    index,
                    current,
                    "ITEM",
                    item.itemCode(),
                    rawResult,
                    item.targetFieldKey(),
                    item.targetFieldLabel());
            for (ExtraWrite extra : parseExtraWrites(item.extraWrites())) {
                addWrite(
                        writes,
                        seen,
                        index,
                        current,
                        "EXTRA",
                        item.itemCode(),
                        extra.value(),
                        extra.fieldKey(),
                        index.labelOf(extra.fieldKey()));
            }
        }
        for (ExtraWrite extra : parseExtraWrites(mapping.extraWrites())) {
            addWrite(
                    writes,
                    seen,
                    index,
                    current,
                    "EXTRA",
                    null,
                    extra.value(),
                    extra.fieldKey(),
                    index.labelOf(extra.fieldKey()));
        }
        return List.copyOf(writes);
    }

    private void addWrite(
            List<WriteView> writes,
            LinkedHashSet<String> seen,
            FormSchemaIndex index,
            Map<String, Object> current,
            String source,
            String itemCode,
            String rawResult,
            String fieldKey,
            String fieldLabel) {
        if (fieldKey == null || !seen.add(fieldKey)) {
            return;
        }
        FormService.FieldView field = index.fieldOf(fieldKey);
        String label = fieldLabel != null ? fieldLabel : fieldKey;
        if (field == null) {
            writes.add(
                    new WriteView(
                            source,
                            itemCode,
                            rawResult,
                            fieldKey,
                            label,
                            current.get(fieldKey),
                            null,
                            false,
                            "目标字段「" + label + "」不在记录的模板版本中，不能写入"));
            return;
        }
        MappingValues.Converted converted =
                MappingValues.convert(field.label(), field.type(), optionsOf(field), rawResult);
        Object newValue = converted.value();
        boolean conflict =
                converted.ok() && MappingValues.conflict(current.get(fieldKey), newValue);
        writes.add(
                new WriteView(
                        source,
                        itemCode,
                        rawResult,
                        fieldKey,
                        field.label(),
                        current.get(fieldKey),
                        newValue,
                        conflict,
                        converted.error()));
    }

    /** 指标取值：REPORT_DATE 固定取报告时间，其余取指标结果行。 */
    private String resultOf(String itemCode, PreviewContext context) {
        if (ITEM_REPORT_DATE.equals(itemCode)) {
            LocalDate reportDate = context.report().reportDate();
            return reportDate == null ? null : reportDate.toString();
        }
        return context.indicators().get(itemCode);
    }

    // ---------------------------------------------------------------- 上下文与校验

    /** 预览装配上下文：报告单 + 位置 + 指标 + 适用映射 + 目标记录元数据；候选装配随填（确认路径复用）。 */
    private final class PreviewContext {

        private final ReportHeadRow report;

        private final List<LinkRow> links;

        private final Map<String, String> indicators;

        private final List<MappingRow> mappings;

        private final Map<Long, ReportMapper.RecordRow> records;

        private final Map<String, Map<Long, Candidate>> candidates = new LinkedHashMap<>();

        private FormService.SchemaView baselineSchema;

        private FormSchemaIndex baselineIndex;

        PreviewContext(
                ReportHeadRow report,
                List<MappingRow> mappings,
                List<LinkRow> links,
                Map<String, String> indicators,
                Map<Long, ReportMapper.RecordRow> records) {
            this.report = report;
            this.mappings = mappings;
            this.links = links;
            this.indicators = indicators;
            this.records = records;
        }

        ReportHeadRow report() {
            return report;
        }

        long patientId() {
            return report.patientId();
        }

        List<LinkRow> links() {
            return links;
        }

        Map<String, String> indicators() {
            return indicators;
        }

        List<MappingRow> mappings() {
            return mappings;
        }

        Map<Long, ReportMapper.RecordRow> records() {
            return records;
        }

        Map<String, Map<Long, Candidate>> candidates() {
            return candidates;
        }

        FormService.SchemaView baselineSchema() {
            if (baselineSchema == null) {
                baselineSchema =
                        formService.schema(
                                BaselineSectionService.BASELINE_FORM_CODE,
                                formService.publishedVersion(
                                        BaselineSectionService.BASELINE_FORM_CODE));
            }
            return baselineSchema;
        }

        FormSchemaIndex baselineIndex() {
            if (baselineIndex == null) {
                baselineIndex = new FormSchemaIndex(baselineSchema());
            }
            return baselineIndex;
        }
    }

    private PreviewContext contextOf(ReportHeadRow report) {
        List<MappingRow> mappings = new ArrayList<>();
        for (MappingRow row : config.findMappings()) {
            if (report.typeCode().equals(row.typeCode()) && "ENABLED".equals(row.status())) {
                mappings.add(row);
            }
        }
        return contextOf(report, mappings);
    }

    private PreviewContext contextOf(ReportHeadRow report, MappingRow single) {
        return contextOf(report, List.of(single));
    }

    private PreviewContext contextOf(ReportHeadRow report, List<MappingRow> mappings) {
        List<LinkRow> links = mappings.isEmpty() ? List.of() : mapper.linksOfReport(report.id());
        Map<String, String> indicators = new LinkedHashMap<>();
        if (!mappings.isEmpty()) {
            for (ReportMapper.IndicatorRow row : mapper.indicatorsOfReport(report.id())) {
                indicators.putIfAbsent(row.itemCode(), row.result());
            }
        }
        Map<Long, ReportMapper.RecordRow> records = new LinkedHashMap<>();
        boolean wantsFollowup =
                mappings.stream()
                        .anyMatch(item -> TARGET_FOLLOWUP_RECORD.equals(item.targetType()));
        if (wantsFollowup) {
            for (ReportMapper.RecordRow row : mapper.recordsOfPatient(report.patientId())) {
                records.put(row.id(), row);
            }
        }
        return new PreviewContext(report, mappings, links, indicators, records);
    }

    private ReportHeadRow requireReport(long reportId) {
        ReportHeadRow report = mapper.findReport(reportId);
        if (report == null) {
            throw new ApiException(404, 40400, "报告单不存在");
        }
        return report;
    }

    private MappingRow requireMapping(String mappingId, ReportHeadRow report) {
        long id;
        try {
            id = Long.parseLong(mappingId.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(404, 40400, "映射不存在");
        }
        MappingRow mapping = config.findMapping(id);
        if (mapping == null) {
            throw new ApiException(404, 40400, "映射不存在");
        }
        if (!"ENABLED".equals(mapping.status())) {
            throw bad("映射已停用，不能写入");
        }
        if (!report.typeCode().equals(mapping.typeCode())) {
            throw bad("映射不属于该报告单的类型：" + report.typeName());
        }
        return mapping;
    }

    /** 确认目标：基线取唯一候选；随访按 linkRecordId 在候选中选（不在候选 = 不是位置中的记录，40001）。 */
    private Candidate chooseCandidate(
            PreviewContext context,
            MappingOptionView option,
            ConfirmCommand command,
            MappingRow mapping,
            ReportHeadRow report) {
        if (TARGET_BASELINE.equals(mapping.targetType())) {
            if (option.candidates().size() != 1) {
                throw bad("基线映射的候选不唯一，映射配置需修正");
            }
            CandidateView view = option.candidates().get(0);
            Map<String, Object> current = baselineCurrent(report.patientId(), view.categoryKey());
            return new Candidate(view, current);
        }
        if (command.linkRecordId() == null || command.linkRecordId().isBlank()) {
            throw bad("随访目标必须选择一条记录（linkRecordId）");
        }
        long recordId;
        try {
            recordId = Long.parseLong(command.linkRecordId().trim());
        } catch (NumberFormatException e) {
            throw bad("随访目标记录不合法");
        }
        Map<Long, Candidate> candidates = context.candidates().get(String.valueOf(mapping.id()));
        Candidate candidate = candidates == null ? null : candidates.get(recordId);
        if (candidate == null) {
            throw bad("映射目标必须是该报告单的位置之一（所选记录不在位置中或表单不符）");
        }
        return candidate;
    }

    // ---------------------------------------------------------------- 辅助

    /** 模板字段索引（键 → 字段 / 显示名；选项集转 MappingValues.Option）。 */
    private static final class FormSchemaIndex {

        private final Map<String, FormService.FieldView> fields = new LinkedHashMap<>();

        FormSchemaIndex(FormService.SchemaView schema) {
            for (FormService.CategoryView category : schema.categories()) {
                for (FormService.FieldView field : category.fields()) {
                    fields.putIfAbsent(field.key(), field);
                }
            }
        }

        FormService.FieldView fieldOf(String fieldKey) {
            return fields.get(fieldKey);
        }

        String labelOf(String fieldKey) {
            FormService.FieldView field = fields.get(fieldKey);
            return field == null ? fieldKey : field.label();
        }
    }

    private static List<MappingValues.Option> optionsOf(FormService.FieldView field) {
        if (field.optionSet() == null) {
            return List.of();
        }
        List<MappingValues.Option> options = new ArrayList<>();
        for (FormService.OptionItemView item : field.optionSet()) {
            options.add(new MappingValues.Option(item.code(), item.label()));
        }
        return List.copyOf(options);
    }

    /** 附带写入 JSON（[{fieldKey,value}]，V64 口径）→ 清单；空或不可解析为空（配置核对页同口径）。 */
    static List<ExtraWrite> parseExtraWrites(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(
                    json,
                    JSON.getTypeFactory().constructCollectionType(List.class, ExtraWrite.class));
        } catch (Exception failure) {
            return List.of();
        }
    }

    private static MappingOptionView blocked(MappingRow mapping, String reason) {
        return new MappingOptionView(
                String.valueOf(mapping.id()),
                mapping.code(),
                mapping.name(),
                mapping.phase(),
                mapping.targetType(),
                mapping.targetFormCode(),
                mapping.targetFormName(),
                reason,
                List.of());
    }

    private static String phaseLabel(String phase) {
        return "PREOP_LAST".equals(phase)
                ? "术前最后一次"
                : "PREDISCHARGE_LAST".equals(phase) ? "出院前最后一次" : phase;
    }

    /** 记录标题（与 ReportService.reportLocations 同口径：节点名 / 来源 + 日期）。 */
    private static String recordTitle(ReportMapper.RecordRow record) {
        String label =
                record.nodeName() != null
                        ? record.nodeName()
                        : Map.of("BACKFILL", "历史补录", "UNPLANNED", "计划外随访")
                                .getOrDefault(
                                        record.source() == null ? "" : record.source(), "随访记录");
        LocalDate date = record.planDate() != null ? record.planDate() : record.visitDate();
        return date == null ? label : label + " " + date;
    }

    private Map<String, Object> baselineCurrent(long patientId, String categoryKey) {
        for (BaselineSectionService.SectionView section :
                baselineService.baseline(patientId).sections()) {
            if (section.code().equals(categoryKey)) {
                return new LinkedHashMap<>(section.data());
            }
        }
        return new LinkedHashMap<>();
    }

    private String targetLabel(MappingOptionView option, Candidate candidate) {
        if (TARGET_BASELINE.equals(option.targetType())) {
            return "基线 · "
                    + candidate.view().categoryLabel()
                    + "（"
                    + phaseLabel(option.phase())
                    + "）";
        }
        return option.targetFormName() + " · " + candidate.view().recordLabel();
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            return null;
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
