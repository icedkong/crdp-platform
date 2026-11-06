package com.crdp.followup.report.app;

import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.ItemRow;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.NameRow;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.TypeRow;
import com.crdp.followup.report.infra.mapper.ReportInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.FileCountRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.ReportRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：报告单创建、位置树与患者内清单（docs/api/report.md §2）。
 *
 * <ul>
 *   <li>位置必填、可多选：粒度三种——基线某分类（baseline + category）、某条随访记录整条 （followup_record + anchor
 *       NULL）、某条随访记录中的某分组（followup_record + section）， 不细到字段；位置为空 40001（「请至少选择 1 个位置」）；C
 *       的植入记录锚点（implant_record）随 E21-T32 开放，此前 40001 拒绝。
 *   <li>锚点校验（E16-T02 口径）：基线分类对当前发布版模板、随访分组对该记录固化版本的模板分类清单； 锚点按模板版本校验、修改位置与「未指定位置」随 E16-T13，模板改版退回随
 *       E16-T15。
 *   <li>附件走 E10 上传接口拿 fileId 后挂台账（≤ file.upload.max.files.per.report，超限 40001）； 批量上传（E16-T03 /
 *       A-ATT-014、C-SCAN-007）见 {@link #batchCreate}：每个文件一份或合并为一份，一次 ≤ 50 个文件。
 *   <li>指标结果逐行存显示快照（模板修改不联动已存报告单，E16-T01 口径）；↑ / ↓ 由服务端按参考范围 与数值结果计算，解析不出范围不标。
 *   <li>审计：创建写 RPT_CREATE（module=followup，objectType=REPORT）；类型 / 名称删除守卫（OQ-95②） 在 {@code
 *       ReportConfigService} 引用本域的计数。
 * </ul>
 *
 * <p>越范围 / 不存在的患者 40400（不暴露对象存在，AGENTS.md §5.2）。
 */
@Service
public class ReportService {

    static final String LINK_BASELINE = "baseline";

    static final String LINK_FOLLOWUP_RECORD = "followup_record";

    static final String ANCHOR_CATEGORY = "category";

    static final String ANCHOR_SECTION = "section";

    /** 来源标签（与患者时间轴同口径：历史补录 / 计划外随访，E15-T06 / T07）。 */
    private static final Map<String, String> SOURCE_LABEL =
            Map.of("BACKFILL", "历史补录", "UNPLANNED", "计划外随访", "TASK", "随访", "REMOTE", "远程监测");

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 参考范围解析（A-ATT-012 ↑ / ↓）：区间「3.5–9.5 / 3.5-9.5 / 3.5~9.5」、上限「≤ 3.4 / ＜125 / <125」、 下限「≥ 60 /
     * ＞5.0 / >5.0」；数值结果解析失败或范围不可解析时不标（显示照原文）。
     */
    private static final Pattern RANGE_INTERVAL =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*[-–~～至]\\s*([0-9]+(?:\\.[0-9]+)?)$");

    private static final Pattern RANGE_MAX =
            Pattern.compile("^(?:≤|<=|＜|<)\\s*([0-9]+(?:\\.[0-9]+)?)$");

    private static final Pattern RANGE_MIN =
            Pattern.compile("^(?:≥|>=|＞|>)\\s*([0-9]+(?:\\.[0-9]+)?)$");

    private static final Pattern NUMBER = Pattern.compile("^-?[0-9]+(?:\\.[0-9]+)?$");

    private final ReportMapper mapper;
    private final ReportConfigMapper config;
    private final ReportPatientGateway patientScope;
    private final FormService formService;
    private final FilePolicyService filePolicy;
    private final SysFileMapper files;
    private final AuditLogger audit;

    public ReportService(
            ReportMapper mapper,
            ReportConfigMapper config,
            ReportPatientGateway patientScope,
            FormService formService,
            FilePolicyService filePolicy,
            SysFileMapper files,
            AuditLogger audit) {
        this.mapper = mapper;
        this.config = config;
        this.patientScope = patientScope;
        this.formService = formService;
        this.filePolicy = filePolicy;
        this.files = files;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 位置输入（components.md §8 ReportLocationInput；anchorType=null 表示整条记录）。 */
    public record LocationInput(
            String linkType,
            Long linkRecordId,
            String anchorType,
            String anchorKey,
            Integer anchorTemplateVersion) {}

    /** 指标结果输入（refRange 为前端按患者性别选定后的参考范围显示串）。 */
    public record IndicatorInput(String itemCode, String result, String refRange) {}

    /** 创建请求（report.md §2 POST /reports）。 */
    public record CreateRequest(
            String typeCode,
            String nameId,
            LocalDate reportDate,
            Map<String, Object> fields,
            List<IndicatorInput> indicators,
            String findings,
            String conclusion,
            List<LocationInput> locations,
            List<String> fileIds,
            String note) {}

    /** 创建结果（前端随后刷新患者内清单）。 */
    public record CreatedView(
            String id,
            String typeCode,
            String typeName,
            String name,
            LocalDate reportDate,
            int locationCount,
            int fileCount) {}

    /**
     * 批量上传模式（A-ATT-014、C-SCAN-007）：{@link #BATCH_MODE_ONE_PER_FILE} 每个文件一份、 {@link
     * #BATCH_MODE_MERGE} 合并为一份多页资料。
     */
    public static final String BATCH_MODE_ONE_PER_FILE = "ONE_PER_FILE";

    public static final String BATCH_MODE_MERGE = "MERGE";

    /** 一次批量上传的文件数上限（A-ATT-014「一次最多 50 个文件」；C-SCAN-007 同口径）。 */
    static final int MAX_BATCH_FILES = 50;

    /** 逐份调整（A-ATT-014「可逐份调整」）：null / 空清单表示该项沿用公共值。 */
    public record BatchItemOverride(
            String fileId, String nameId, LocalDate reportDate, List<LocationInput> locations) {}

    /** 批量上传请求（report.md §2 POST /reports/batch-create）：公共类型 / 名称 / 报告时间 / 位置。 */
    public record BatchCreateRequest(
            String mode,
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<LocationInput> locations,
            List<String> fileIds,
            List<BatchItemOverride> items) {}

    /** 批量上传结果：reports 与文件顺序一致（ONE_PER_FILE）或单元素（MERGE）。 */
    public record BatchCreatedView(
            String mode, int reportCount, int fileCount, List<CreatedView> reports) {}

    /** 位置树节点（components.md §8 LocationTreeNode；selectable=false 为分组节点）。 */
    public record LocationNodeView(
            String key,
            String title,
            boolean selectable,
            LocationValueView value,
            List<LocationNodeView> children) {}

    /** 位置树叶子值（提交时原样回传；anchorType=null 表示整条记录；ID 序列化为字符串）。 */
    public record LocationValueView(
            String linkType,
            String linkRecordId,
            String anchorType,
            String anchorKey,
            int anchorTemplateVersion) {}

    /** 患者内报告单行（含位置标签与附件数；AttachmentNav 完整形态随 E16-T14）。 */
    public record ReportItemView(
            String id,
            String patientId,
            String typeCode,
            String typeName,
            String name,
            LocalDate reportDate,
            String findings,
            String conclusion,
            String note,
            String digitizeStatus,
            int version,
            String creatorName,
            String createdAt,
            long fileCount,
            List<LocationTagView> locations) {}

    /** 位置标签（recordLabel / anchorLabel 供列表与左栏直接显示；ID 序列化为字符串）。 */
    public record LocationTagView(
            String linkType,
            String linkRecordId,
            String recordLabel,
            LocalDate recordDate,
            String anchorType,
            String anchorKey,
            String anchorLabel,
            int templateVersion,
            boolean adjusted) {}

    /** 患者内清单响应。 */
    public record ReportsView(String patientId, String scope, List<ReportItemView> items) {}

    // ---------------------------------------------------------------- 创建（A-ATT-001 /
    // A-ATT-002、C-SCAN-010）

    /** 创建报告单：位置至少 1 个（40001）、附件上限与归属校验、指标项目归属校验；写 RPT_CREATE 审计。 */
    @Transactional
    public CreatedView create(long patientId, CreateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        requirePatient(patientId);
        TypeRow type = requireType(request.typeCode());
        NameRow name = requireName(type, request.nameId());
        LocalDate reportDate = requireReportDate(request.reportDate());
        List<NormalizedLocation> locations = normalizeLocations(patientId, request.locations());
        List<Long> fileIds = normalizeFileIds(request.fileIds());
        Map<String, Object> fields = request.fields() == null ? Map.of() : request.fields();
        String fieldValuesJson = writeFields(type, fields);

        long operator = CurrentSession.requireHospital().userId();
        ReportInsert row = new ReportInsert();
        row.setPatientId(patientId);
        row.setTypeCode(type.code());
        row.setTypeName(type.name());
        row.setNameId(name == null ? null : name.id());
        row.setNameSnapshot(name == null ? null : name.name());
        row.setReportDate(reportDate);
        row.setFindings(trimToNull(request.findings()));
        row.setConclusion(trimToNull(request.conclusion()));
        row.setNote(trimToNull(request.note()));
        row.setFieldValues(fieldValuesJson);
        row.setDigitizeStatus(digitizeStatusOf(type, name));
        row.setCreatedBy(operator);
        mapper.insertReport(row);
        long reportId = row.getId();
        for (NormalizedLocation location : locations) {
            mapper.insertLink(
                    new ReportMapper.LinkInsert(
                            reportId,
                            patientId,
                            location.linkType(),
                            location.linkRecordId(),
                            location.anchorType(),
                            location.anchorKey(),
                            location.templateVersion()));
        }
        int sortNo = 0;
        for (Long fileId : fileIds) {
            attachFile(reportId, fileId);
            mapper.insertFile(new ReportMapper.FileInsert(reportId, patientId, fileId, sortNo++));
        }
        int indicatorCount = insertIndicators(reportId, patientId, name, request.indicators());

        CreatedView view =
                new CreatedView(
                        String.valueOf(reportId),
                        type.code(),
                        type.name(),
                        name == null ? null : name.name(),
                        reportDate,
                        locations.size(),
                        fileIds.size());
        audit.append(
                AuditEntry.operation("followup", "RPT_CREATE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "上传报告单："
                                        + type.name()
                                        + (name == null ? "" : " · " + name.name())
                                        + "，报告时间 "
                                        + reportDate
                                        + "，位置 "
                                        + locations.size()
                                        + " 个、附件 "
                                        + fileIds.size()
                                        + " 个、指标 "
                                        + indicatorCount
                                        + " 项")
                        .afterJson(writeJson(view))
                        .build());
        log.info(
                "报告单创建（E16-T02）：reportId={} patientId={} type={} 位置数={} 附件数={}",
                reportId,
                patientId,
                type.code(),
                locations.size(),
                fileIds.size());
        return view;
    }

    // ---------------------------------------------------------------- 删除（E16-T09 /
    // A-ATT-007、C-SCAN-016）

    /** 删除请求：原因必填、≤ 255 字（A-ATT-007「须填写原因」）。 */
    public record DeleteRequest(String reason) {}

    /** 删除结果（报告单与单个文件同形；前端随后刷新患者内清单）。 */
    public record DeletedView(String id, String status) {}

    /**
     * 删除整份报告单（软删除，A-ATT-007 / C-SCAN-016）：原因必填（40001）→ 患者数据范围校验 （越范围 / 不存在 40400 不暴露存在，写 DENIED
     * 审计）→ status=DELETED + 原因 / 删除人 / 删除时间 落列（followup V65），附件台账 sys_file 一并软删（预览 / 下载随即
     * 40400）；数据长期保留、无物理删除。 审计 RPT_DELETE 带原因与附件计数。
     */
    @Transactional
    public DeletedView delete(long reportId, DeleteRequest request) {
        String reason = requireDeleteReason(request == null ? null : request.reason());
        ReportMapper.ReportHeadRow report = requireReportInScope(reportId, "RPT_DELETE");
        List<ReportMapper.ReportFileRow> fileRows = mapper.fileRowsOfReport(reportId);
        long operator = CurrentSession.requireHospital().userId();
        if (mapper.softDeleteReport(reportId, reason, operator) == 0) {
            // 并发兜底：校验与更新之间被他人删除
            throw new ApiException(409, 40901, "报告单已被删除，无需重复操作");
        }
        for (ReportMapper.ReportFileRow file : fileRows) {
            files.markDeleted(file.fileId());
        }
        audit.append(
                AuditEntry.operation("followup", "RPT_DELETE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "删除报告单："
                                        + report.typeName()
                                        + (report.nameSnapshot() == null
                                                ? ""
                                                : " · " + report.nameSnapshot())
                                        + "（报告时间 "
                                        + report.reportDate()
                                        + "），附件 "
                                        + fileRows.size()
                                        + " 个；原因："
                                        + reason)
                        .beforeJson("{\"status\":\"NORMAL\"}")
                        .afterJson(
                                "{\"status\":\"DELETED\",\"reason\":\""
                                        + reason
                                        + "\",\"fileCount\":"
                                        + fileRows.size()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "报告单已删除（E16-T09）：reportId={} patientId={} fileCount={} operator={}",
                reportId,
                report.patientId(),
                fileRows.size(),
                operator);
        return new DeletedView(String.valueOf(reportId), "DELETED");
    }

    /**
     * 删除报告单中的单个文件（软删除，A-ATT-007）：报告单保留、只软删该文件的 sys_file 台账 （关联行保留作归属台账；预览 / 下载随即 40400）；文件不在该报告单 /
     * 已删 40400。审计 RPT_FILE_DELETE 带报告单（类型 · 名称）与文件名、原因。
     */
    @Transactional
    public DeletedView deleteFile(long reportId, long fileId, DeleteRequest request) {
        String reason = requireDeleteReason(request == null ? null : request.reason());
        ReportMapper.ReportHeadRow report = requireReportInScope(reportId, "RPT_FILE_DELETE");
        ReportMapper.ReportFileRow file = mapper.findFileOfReport(reportId, fileId);
        if (file == null) {
            // 不区分「不在该报告单 / 已删除」，统一 40400 不暴露存在
            throw new ApiException(404, 40400, "文件不存在或不在该报告单中");
        }
        files.markDeleted(fileId);
        audit.append(
                AuditEntry.operation("followup", "RPT_FILE_DELETE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "删除报告单附件："
                                        + report.typeName()
                                        + (report.nameSnapshot() == null
                                                ? ""
                                                : " · " + report.nameSnapshot())
                                        + " · "
                                        + file.originalName()
                                        + "；原因："
                                        + reason)
                        .afterJson(
                                "{\"fileId\":"
                                        + fileId
                                        + ",\"status\":\"DELETED\",\"reason\":\""
                                        + reason
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "报告单附件已删除（E16-T09）：reportId={} fileId={} operator={}",
                reportId,
                fileId,
                CurrentSession.requireHospital().userId());
        return new DeletedView(String.valueOf(reportId), "DELETED");
    }

    /** 删除原因必填（去首尾空白后非空；≤ 255 字与列宽一致）。 */
    private static String requireDeleteReason(String raw) {
        String reason = trimToNull(raw);
        if (reason == null) {
            throw bad("请填写删除原因");
        }
        if (reason.length() > 255) {
            throw bad("删除原因不能超过 255 字");
        }
        return reason;
    }

    /**
     * 删除入口的患者数据范围校验（@DataScope 出行的 findReport）：越范围 / 不存在 / 已删一律 40400， 并按删除患者同口径写 DENIED 审计（action
     * 传入 RPT_DELETE / RPT_FILE_DELETE）。
     */
    private ReportMapper.ReportHeadRow requireReportInScope(long reportId, String deniedAction) {
        ReportMapper.ReportHeadRow report = mapper.findReport(reportId);
        if (report == null) {
            audit.append(
                    AuditEntry.operation("followup", deniedAction)
                            .objectType("REPORT")
                            .objectId(String.valueOf(reportId))
                            .description("删除报告单被拒绝：报告单不在操作者数据范围内或已删除")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "报告单不存在或不在您的数据范围内");
        }
        return report;
    }

    // ---------------------------------------------------------------- 批量上传（E16-T03 /
    // A-ATT-014、C-SCAN-007）

    /**
     * 批量上传：先统一选择类型与位置（至少一个），可逐份调整名称与报告时间； {@link #BATCH_MODE_ONE_PER_FILE} 每个文件一份、{@link
     * #BATCH_MODE_MERGE} 合并为一份多页资料 （文件按提交顺序排 sortNo，C-SCAN-006「可调整顺序」）。一次 ≤ {@link
     * #MAX_BATCH_FILES} 个文件； 单份数量上限沿用创建校验（A-ATT-004 超限 40001「超限拦截」）。整批一个事务：任一份失败全部回滚，
     * 由前端整批重试（文件已上传、fileId 仍有效）。
     *
     * <p>审计：每份报告单照常写 RPT_CREATE（复用 {@link #create}），另写一条批量汇总 RPT_BATCH_CREATE。
     */
    @Transactional
    public BatchCreatedView batchCreate(long patientId, BatchCreateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        String mode = normalizeBatchMode(request.mode());
        List<Long> fileIds = normalizeBatchFileIds(request.fileIds());
        Map<Long, BatchItemOverride> overrides = normalizeBatchOverrides(request.items(), fileIds);
        List<CreatedView> created = new ArrayList<>();
        if (BATCH_MODE_MERGE.equals(mode)) {
            created.add(
                    create(
                            patientId,
                            toCreateRequest(
                                    request,
                                    request.nameId(),
                                    request.reportDate(),
                                    request.locations(),
                                    fileIds)));
        } else {
            for (Long fileId : fileIds) {
                BatchItemOverride override = overrides.get(fileId);
                String nameId =
                        override != null && override.nameId() != null
                                ? override.nameId()
                                : request.nameId();
                LocalDate reportDate =
                        override != null && override.reportDate() != null
                                ? override.reportDate()
                                : request.reportDate();
                List<LocationInput> locations =
                        override != null
                                        && override.locations() != null
                                        && !override.locations().isEmpty()
                                ? override.locations()
                                : request.locations();
                created.add(
                        create(
                                patientId,
                                toCreateRequest(
                                        request, nameId, reportDate, locations, List.of(fileId))));
            }
        }
        BatchCreatedView view =
                new BatchCreatedView(mode, created.size(), fileIds.size(), List.copyOf(created));
        audit.append(
                AuditEntry.operation("followup", "RPT_BATCH_CREATE")
                        .objectType("REPORT")
                        .objectId(created.isEmpty() ? null : created.get(0).id())
                        .description(
                                "批量上传报告单（"
                                        + (BATCH_MODE_MERGE.equals(mode) ? "合并为一份" : "每个文件一份")
                                        + "）：报告单 "
                                        + created.size()
                                        + " 份、文件 "
                                        + fileIds.size()
                                        + " 个，类型 "
                                        + request.typeCode())
                        .afterJson(writeJson(view))
                        .build());
        log.info(
                "报告单批量创建（E16-T03）：patientId={} mode={} 报告单={} 份、文件={} 个",
                patientId,
                mode,
                created.size(),
                fileIds.size());
        return view;
    }

    /** 批量创建的公共部分 → 单份创建请求（批量不收指标 / 所见 / 结论 / 备注，随批后逐份编辑）。 */
    private CreateRequest toCreateRequest(
            BatchCreateRequest batch,
            String nameId,
            LocalDate reportDate,
            List<LocationInput> locations,
            List<Long> fileIds) {
        return new CreateRequest(
                batch.typeCode(),
                nameId,
                reportDate,
                Map.of(),
                List.of(),
                null,
                null,
                locations,
                fileIds.stream().map(String::valueOf).toList(),
                null);
    }

    private String normalizeBatchMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw bad("请选择批量模式（每个文件一份 / 合并为一份）");
        }
        String normalized = mode.trim();
        if (!BATCH_MODE_ONE_PER_FILE.equals(normalized) && !BATCH_MODE_MERGE.equals(normalized)) {
            throw bad("批量模式只能是 ONE_PER_FILE 或 MERGE");
        }
        return normalized;
    }

    /** 批量文件清单：与单份同口径解析去重；空清单 40001、超过 {@link #MAX_BATCH_FILES} 个 40001。 */
    private List<Long> normalizeBatchFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw bad("请至少选择 1 个文件");
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (String raw : fileIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                distinct.add(Long.parseLong(raw.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(404, 40400, "附件文件不存在或已删除");
            }
        }
        if (distinct.isEmpty()) {
            throw bad("请至少选择 1 个文件");
        }
        if (distinct.size() > MAX_BATCH_FILES) {
            throw bad("一次最多上传 " + MAX_BATCH_FILES + " 个文件");
        }
        return List.copyOf(distinct);
    }

    /** 逐份调整归一化：按文件 ID 建索引；引用清单外的文件 40001。 */
    private Map<Long, BatchItemOverride> normalizeBatchOverrides(
            List<BatchItemOverride> items, List<Long> fileIds) {
        Map<Long, BatchItemOverride> index = new LinkedHashMap<>();
        if (items == null) {
            return index;
        }
        for (BatchItemOverride item : items) {
            if (item == null || item.fileId() == null || item.fileId().isBlank()) {
                continue;
            }
            long fileId;
            try {
                fileId = Long.parseLong(item.fileId().trim());
            } catch (NumberFormatException e) {
                throw bad("逐份调整的文件不在本次上传清单内");
            }
            if (!fileIds.contains(fileId)) {
                throw bad("逐份调整的文件不在本次上传清单内");
            }
            index.put(fileId, item);
        }
        return index;
    }

    private TypeRow requireType(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw bad("请选择资料类型");
        }
        Long id = config.findTypeIdByCode(typeCode.trim());
        // 未命中（含防御：id 非法）统一 40400，不暴露存在性
        TypeRow type = id == null || id <= 0 ? null : config.findType(id);
        if (type == null) {
            throw new ApiException(404, 40400, "资料类型不存在");
        }
        if (!"ENABLED".equals(type.status())) {
            throw bad("资料类型已停用，不能添加该类型的报告单");
        }
        return type;
    }

    /** 名称按类型行 nameRequired 校验（OQ-95③）：必填缺省 40001；给了必须属于该类型且启用。 */
    private NameRow requireName(TypeRow type, String nameId) {
        if (nameId == null || nameId.isBlank()) {
            if (type.nameRequired()) {
                throw bad("该类型必须选择名称（" + type.name() + "）");
            }
            return null;
        }
        long id;
        try {
            id = Long.parseLong(nameId.trim());
        } catch (NumberFormatException e) {
            throw bad("名称不存在");
        }
        NameRow name = config.findName(id);
        if (name == null
                || !"ENABLED".equals(name.status())
                || !type.code().equals(name.typeCode())) {
            throw bad("名称不存在或不属于该类型");
        }
        return name;
    }

    private LocalDate requireReportDate(LocalDate reportDate) {
        if (reportDate == null) {
            throw bad("请填写报告时间");
        }
        return reportDate;
    }

    /** 类型专属字段值经表单引擎写入闸门校验（键 / 类型 / 硬范围；E15-T07 业务表复用口）。 */
    private String writeFields(TypeRow type, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        int version = formService.publishedVersion(type.formCode());
        Map<String, Object> gated = formService.writeGateData(type.formCode(), version, fields);
        try {
            return JSON.writeValueAsString(gated);
        } catch (Exception e) {
            throw new ApiException(500, 50000, "报告单字段值序列化失败");
        }
    }

    /** 电子化状态（C-SCAN-011）：名称覆盖 → 类型默认；A 两侧均 NULL。 */
    private String digitizeStatusOf(TypeRow type, NameRow name) {
        if (name != null && name.digitizeStatusOverride() != null) {
            return name.digitizeStatusOverride();
        }
        return type.defaultDigitizeStatus();
    }

    // ---------------------------------------------------------------- 位置（A-ATT-002 / C-SCAN-010）

    /** 归一化位置：结构校验 + 锚点对模板分类清单校验 + 去重；空清单 40001。 */
    private List<NormalizedLocation> normalizeLocations(
            long patientId, List<LocationInput> locations) {
        if (locations == null || locations.isEmpty()) {
            throw bad("请至少选择 1 个位置");
        }
        Map<String, String> baselineCategories = baselineCategoryMap();
        Map<Long, RecordRow> records = recordMap(patientId);
        Map<String, NormalizedLocation> distinct = new LinkedHashMap<>();
        for (LocationInput input : locations) {
            if (input == null) {
                continue;
            }
            NormalizedLocation normalized = normalizeOne(input, baselineCategories, records);
            if (distinct.putIfAbsent(normalized.dedupeKey(), normalized) != null
                    && log.isDebugEnabled()) {
                log.debug("报告单位置重复提交，已合并（E16-T02）：{}", normalized.dedupeKey());
            }
        }
        if (distinct.isEmpty()) {
            throw bad("请至少选择 1 个位置");
        }
        return List.copyOf(distinct.values());
    }

    private NormalizedLocation normalizeOne(
            LocationInput input,
            Map<String, String> baselineCategories,
            Map<Long, RecordRow> records) {
        String linkType = input.linkType() == null ? "" : input.linkType().trim();
        String anchorType =
                input.anchorType() == null || input.anchorType().isBlank()
                        ? null
                        : input.anchorType().trim();
        String anchorKey = trimToNull(input.anchorKey());
        if (LINK_BASELINE.equals(linkType)) {
            if (!ANCHOR_CATEGORY.equals(anchorType)) {
                throw bad("基线位置必须是某个分类（不能是分组或整条记录）");
            }
            if (anchorKey == null) {
                throw bad("基线位置缺少分类锚点");
            }
            if (!baselineCategories.containsKey(anchorKey)) {
                throw bad("基线分类不存在：" + anchorKey);
            }
            int version = formService.publishedVersion(BaselineSectionService.BASELINE_FORM_CODE);
            return new NormalizedLocation(LINK_BASELINE, null, ANCHOR_CATEGORY, anchorKey, version);
        }
        if (LINK_FOLLOWUP_RECORD.equals(linkType)) {
            if (input.linkRecordId() == null) {
                throw bad("随访记录位置缺少记录标识");
            }
            RecordRow record = records.get(input.linkRecordId());
            if (record == null) {
                // 不区分「不存在 / 不属于该患者 / 越范围」，统一 40400 不暴露对象存在
                throw new ApiException(404, 40400, "随访记录不存在");
            }
            if (anchorType == null) {
                return new NormalizedLocation(
                        LINK_FOLLOWUP_RECORD, record.id(), null, null, record.templateVersion());
            }
            if (!ANCHOR_SECTION.equals(anchorType)) {
                throw bad("随访记录位置只能是整条记录或其中某个分组");
            }
            if (anchorKey == null) {
                throw bad("分组位置缺少分组锚点");
            }
            Map<String, String> sections = categoryMap(record.formCode(), record.templateVersion());
            if (!sections.containsKey(anchorKey)) {
                throw bad("随访分组不存在：" + anchorKey);
            }
            return new NormalizedLocation(
                    LINK_FOLLOWUP_RECORD,
                    record.id(),
                    ANCHOR_SECTION,
                    anchorKey,
                    record.templateVersion());
        }
        if ("implant_record".equals(linkType)) {
            throw bad("植入记录位置随医院 C 扩展开放（E21-T32），当前不可选");
        }
        throw bad("位置类型不合法：" + linkType);
    }

    /** 归一化后的位置（templateVersion 为锚点校验依据的版本，存储以此为准）。 */
    private record NormalizedLocation(
            String linkType,
            Long linkRecordId,
            String anchorType,
            String anchorKey,
            int templateVersion) {

        String dedupeKey() {
            return linkType
                    + '|'
                    + (linkRecordId() == null ? "" : linkRecordId())
                    + '|'
                    + (anchorType() == null ? "" : anchorType())
                    + '|'
                    + (anchorKey() == null ? "" : anchorKey());
        }
    }

    private Map<String, String> baselineCategoryMap() {
        String code = BaselineSectionService.BASELINE_FORM_CODE;
        return categoryMap(code, formService.publishedVersion(code));
    }

    private Map<String, String> categoryMap(String formCode, int version) {
        Map<String, String> map = new LinkedHashMap<>();
        for (FormService.CategoryView category :
                formService.schema(formCode, version).categories()) {
            map.put(category.key(), category.label());
        }
        return map;
    }

    private Map<Long, RecordRow> recordMap(long patientId) {
        Map<Long, RecordRow> map = new LinkedHashMap<>();
        for (RecordRow row : mapper.recordsOfPatient(patientId)) {
            map.put(row.id(), row);
        }
        return map;
    }

    // ---------------------------------------------------------------- 附件与指标

    /** 附件去重与数量上限（≤ file.upload.max.files.per.report，超限 40001）。 */
    private List<Long> normalizeFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (String raw : fileIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                distinct.add(Long.parseLong(raw.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(404, 40400, "附件文件不存在或已删除");
            }
        }
        List<Long> ids = List.copyOf(distinct);
        if (!ids.isEmpty() && !filePolicy.policy().withinFileCount(ids.size())) {
            throw bad("附件数量超过上限（每份报告单最多 " + filePolicy.policy().maxFilesPerReport() + " 个）");
        }
        return ids;
    }

    /** 校验并关联附件（与沟通录音同口径，E15-T04）：在册且未挂他处；关联后置 ATTACHED。 */
    private void attachFile(long reportId, long fileId) {
        SysFileRow file = files.find(fileId);
        if (file == null || "DELETED".equals(file.getStatus())) {
            throw new ApiException(
                    404, 40400, "附件文件不存在或已删除：" + (file == null ? fileId : file.getOriginalName()));
        }
        if (files.markAttached(fileId) == 0) {
            throw bad("文件已关联其他记录：" + file.getOriginalName());
        }
    }

    /** 指标结果落行：项目必须来自名称绑定模板（启用）；↑ / ↓ 服务端按参考范围计算。 */
    private int insertIndicators(
            long reportId, long patientId, NameRow name, List<IndicatorInput> indicators) {
        if (indicators == null || indicators.isEmpty()) {
            return 0;
        }
        Map<String, ItemRow> items = indicatorItemsOf(name);
        int sortNo = 0;
        int stored = 0;
        for (IndicatorInput input : indicators) {
            if (input == null || input.itemCode() == null || trimToNull(input.result()) == null) {
                continue;
            }
            ItemRow item = items.get(input.itemCode().trim());
            if (item == null) {
                throw bad("指标项目不属于所选名称的模板：" + input.itemCode());
            }
            String result = input.result().trim();
            String refRange = trimToNull(input.refRange());
            if (refRange == null) {
                refRange = item.refRange();
            }
            mapper.insertIndicator(
                    new ReportMapper.IndicatorInsert(
                            reportId,
                            patientId,
                            item.itemCode(),
                            item.itemName(),
                            item.unit(),
                            refRange,
                            result,
                            indicatorFlag(refRange, result),
                            sortNo++));
            stored++;
        }
        return stored;
    }

    private Map<String, ItemRow> indicatorItemsOf(NameRow name) {
        if (name == null || name.indicatorTplId() == null) {
            throw bad("所选名称未绑定指标模板，不能填写指标结果");
        }
        ReportConfigMapper.TplRow tpl = config.findTpl(name.indicatorTplId());
        if (tpl == null || !"ENABLED".equals(tpl.status())) {
            throw bad("所选名称绑定的指标模板已停用，不能填写指标结果");
        }
        Map<String, ItemRow> map = new LinkedHashMap<>();
        for (ItemRow item : config.findItems(tpl.id())) {
            map.put(item.itemCode(), item);
        }
        return map;
    }

    /** ↑ / ↓ 标记（A-ATT-012）：结果与参考范围都可解析为数值时比较，超上限 UP、低于下限 DOWN；否则不标。 */
    static String indicatorFlag(String refRange, String result) {
        if (refRange == null || result == null) {
            return null;
        }
        Double value = parseNumber(result);
        if (value == null) {
            return null;
        }
        String range = refRange.trim();
        Matcher interval = RANGE_INTERVAL.matcher(range);
        if (interval.matches()) {
            double min = Double.parseDouble(interval.group(1));
            double max = Double.parseDouble(interval.group(2));
            if (value < min) {
                return "DOWN";
            }
            return value > max ? "UP" : null;
        }
        Matcher maxOnly = RANGE_MAX.matcher(range);
        if (maxOnly.matches()) {
            return value > Double.parseDouble(maxOnly.group(1)) ? "UP" : null;
        }
        Matcher minOnly = RANGE_MIN.matcher(range);
        if (minOnly.matches()) {
            return value < Double.parseDouble(minOnly.group(1)) ? "DOWN" : null;
        }
        return null;
    }

    private static Double parseNumber(String raw) {
        String text = raw.trim();
        return NUMBER.matcher(text).matches() ? Double.parseDouble(text) : null;
    }

    // ---------------------------------------------------------------- 位置树与患者内清单

    /** 位置树（components.md §8）：基线资料（当前发布版分类）+ 随访记录（整条 + 分组）。 */
    public List<LocationNodeView> reportLocations(long patientId) {
        requirePatient(patientId);
        List<LocationNodeView> roots = new ArrayList<>();
        String baselineCode = BaselineSectionService.BASELINE_FORM_CODE;
        int baselineVersion = formService.publishedVersion(baselineCode);
        List<LocationNodeView> baselineChildren = new ArrayList<>();
        for (var category : categoryMap(baselineCode, baselineVersion).entrySet()) {
            baselineChildren.add(
                    new LocationNodeView(
                            "baseline:" + category.getKey(),
                            category.getValue(),
                            true,
                            new LocationValueView(
                                    LINK_BASELINE,
                                    null,
                                    ANCHOR_CATEGORY,
                                    category.getKey(),
                                    baselineVersion),
                            null));
        }
        roots.add(new LocationNodeView("baseline", "基线资料", false, null, baselineChildren));

        List<LocationNodeView> recordChildren = new ArrayList<>();
        for (RecordRow record : mapper.recordsOfPatient(patientId)) {
            List<LocationNodeView> sections = new ArrayList<>();
            for (var section :
                    categoryMap(record.formCode(), record.templateVersion()).entrySet()) {
                sections.add(
                        new LocationNodeView(
                                "rec:" + record.id() + ":sec:" + section.getKey(),
                                section.getValue(),
                                true,
                                new LocationValueView(
                                        LINK_FOLLOWUP_RECORD,
                                        String.valueOf(record.id()),
                                        ANCHOR_SECTION,
                                        section.getKey(),
                                        record.templateVersion()),
                                null));
            }
            recordChildren.add(
                    new LocationNodeView(
                            "rec:" + record.id(),
                            recordTitle(record),
                            true,
                            new LocationValueView(
                                    LINK_FOLLOWUP_RECORD,
                                    String.valueOf(record.id()),
                                    null,
                                    null,
                                    record.templateVersion()),
                            sections));
        }
        roots.add(new LocationNodeView("followup", "随访记录", false, null, recordChildren));
        return roots;
    }

    private String recordTitle(RecordRow record) {
        String label =
                record.nodeName() != null
                        ? record.nodeName()
                        : SOURCE_LABEL.getOrDefault(
                                record.source() == null ? "" : record.source(), "随访记录");
        LocalDate date = record.planDate() != null ? record.planDate() : record.visitDate();
        return date == null ? label : label + " " + date;
    }

    /** 患者内清单（scope=all 默认；baseline / followup 只保留含该侧位置的报告单）。 */
    public ReportsView reportsOf(long patientId, String scope) {
        requirePatient(patientId);
        String normalized =
                scope == null || scope.isBlank() || "all".equals(scope) ? "all" : scope.trim();
        if (!"all".equals(normalized)
                && !"baseline".equals(normalized)
                && !"followup".equals(normalized)) {
            throw bad("scope 只能是 all / baseline / followup");
        }
        Map<Long, List<LocationTagView>> tags = locationTags(patientId);
        Map<Long, Long> fileCounts = new LinkedHashMap<>();
        for (FileCountRow row : mapper.fileCountsOfPatient(patientId)) {
            fileCounts.put(row.reportId(), row.fileCount());
        }
        List<ReportItemView> items = new ArrayList<>();
        for (ReportRow row : mapper.reportsOfPatient(patientId)) {
            List<LocationTagView> locations = tags.getOrDefault(row.id(), List.of());
            if ("baseline".equals(normalized)
                    && locations.stream().noneMatch(tag -> LINK_BASELINE.equals(tag.linkType()))) {
                continue;
            }
            if ("followup".equals(normalized)
                    && locations.stream()
                            .noneMatch(tag -> LINK_FOLLOWUP_RECORD.equals(tag.linkType()))) {
                continue;
            }
            items.add(
                    new ReportItemView(
                            String.valueOf(row.id()),
                            String.valueOf(row.patientId()),
                            row.typeCode(),
                            row.typeName(),
                            row.nameSnapshot(),
                            row.reportDate(),
                            row.findings(),
                            row.conclusion(),
                            row.note(),
                            row.digitizeStatus(),
                            row.version() == null ? 0 : row.version(),
                            row.creatorName(),
                            row.createdAt(),
                            fileCounts.getOrDefault(row.id(), 0L),
                            locations));
        }
        return new ReportsView(String.valueOf(patientId), normalized, items);
    }

    /** 位置标签（列表 / 左栏显示用）：recordLabel / anchorLabel 按当前模板解析（模板改版退回随 E16-T15）。 */
    private Map<Long, List<LocationTagView>> locationTags(long patientId) {
        Map<String, String> baselineCategories = baselineCategoryMap();
        Map<Long, RecordRow> records = recordMap(patientId);
        Map<Long, Map<String, String>> sectionCache = new LinkedHashMap<>();
        Map<Long, List<LocationTagView>> tags = new LinkedHashMap<>();
        for (LinkRow link : mapper.linksOfPatient(patientId)) {
            String recordLabel = null;
            LocalDate recordDate = null;
            String anchorLabel = null;
            if (LINK_BASELINE.equals(link.linkType())) {
                anchorLabel = baselineCategories.get(link.anchorKey());
            } else if (LINK_FOLLOWUP_RECORD.equals(link.linkType())
                    && link.linkRecordId() != null) {
                RecordRow record = records.get(link.linkRecordId());
                if (record != null) {
                    recordLabel = recordTitle(record);
                    recordDate = record.planDate() != null ? record.planDate() : record.visitDate();
                    if (ANCHOR_SECTION.equals(link.anchorType())) {
                        Map<String, String> sections =
                                sectionCache.computeIfAbsent(
                                        record.id(),
                                        id ->
                                                categoryMap(
                                                        record.formCode(),
                                                        record.templateVersion()));
                        anchorLabel = sections.get(link.anchorKey());
                    }
                }
            }
            tags.computeIfAbsent(link.reportId(), id -> new ArrayList<>())
                    .add(
                            new LocationTagView(
                                    link.linkType(),
                                    link.linkRecordId() == null
                                            ? null
                                            : String.valueOf(link.linkRecordId()),
                                    recordLabel,
                                    recordDate,
                                    link.anchorType(),
                                    link.anchorKey(),
                                    anchorLabel,
                                    link.templateVersion() == null ? 0 : link.templateVersion(),
                                    link.adjusted() != null && link.adjusted() > 0));
        }
        return tags;
    }

    // ---------------------------------------------------------------- 通用

    private void requirePatient(long patientId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在");
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            // 创建视图 / 字段值序列化失败不拦截主流程（审计 after 可缺省），与 E16-T01 writeJson 同口径
            return null;
        }
    }
}
