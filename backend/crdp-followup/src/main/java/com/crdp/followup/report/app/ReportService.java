package com.crdp.followup.report.app;

import com.crdp.followup.contact.app.ContactLogService;
import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.implant.app.ImplantGateway;
import com.crdp.followup.implant.app.ImplantService;
import com.crdp.followup.implant.infra.mapper.ImplantMapper;
import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.ItemRow;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.NameRow;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.TypeRow;
import com.crdp.followup.report.infra.mapper.ReportFeatureMapper;
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
import java.util.stream.Collectors;
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
 *       的植入记录锚点（implant_record，整条或器械信息 / 电极分组）随 E21-T32 开放（implant 开关关闭 40301）。
 *   <li>锚点校验（E16-T02 口径）：基线分类对当前发布版模板、随访分组对该记录固化版本的模板分类清单； 修改位置（增删、至少保留一个、写 RPT_LOCATION_CHANGE
 *       操作日志）随 E16-T13 落地， 无位置报告单可查询为「未指定位置」并经 add 补指定（A-ATT-020）；模板改版退回随 E16-T15。
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

    /**
     * 植入记录位置（E21-T32 / C-SCAN-010、C-PAT-017）：整条植入记录（anchorType=null）或其中分组 —— C_IMPLANT 表单分类
     * 器械信息（device）/ 电极（lead），不细到字段；随 {@code implant} 功能开关开放（关闭 40301，AGENTS §5.7）。
     */
    static final String LINK_IMPLANT_RECORD = "implant_record";

    static final String ANCHOR_CATEGORY = "category";

    static final String ANCHOR_SECTION = "section";

    /** 电子化状态功能开关（C-SCAN-011 / C-DASH-007；关闭 → 40301，AGENTS §5.7）。 */
    static final String SCAN_PROGRESS_FEATURE = "scan_progress";

    /** 电子化状态取值（C-SCAN-011：PENDING 待录入 / DONE 已电子化 / NO_ENTRY 无需录入；DONE 只由转录或手工产生）。 */
    private static final Map<String, String> DIGITIZE_LABEL =
            Map.of("PENDING", "待录入", "DONE", "已电子化", "NO_ENTRY", "无需录入");

    /** 转录终态（移除随访位置自动回退的判定起点，E21-T27 / C-SCAN-020 §7）。 */
    static final String STATUS_DONE = "DONE";

    /** 回退目标态（待录入）。 */
    static final String STATUS_PENDING = "PENDING";

    /** 电子化状态显示名（同包转录服务复用；未知值原样返回，null 由调用方处理）。 */
    static String digitizeStatusLabel(String status) {
        return DIGITIZE_LABEL.getOrDefault(status, status);
    }

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
    private final ReportFeatureMapper features;
    private final ReportPatientGateway patientScope;
    private final FormService formService;
    private final FilePolicyService filePolicy;
    private final SysFileMapper files;
    private final ContactLogService contactLogs;
    private final AuditLogger audit;
    private final ImplantGateway implants;

    public ReportService(
            ReportMapper mapper,
            ReportConfigMapper config,
            ReportFeatureMapper features,
            ReportPatientGateway patientScope,
            FormService formService,
            FilePolicyService filePolicy,
            SysFileMapper files,
            ContactLogService contactLogs,
            AuditLogger audit,
            ImplantGateway implants) {
        this.mapper = mapper;
        this.config = config;
        this.features = features;
        this.patientScope = patientScope;
        this.formService = formService;
        this.filePolicy = filePolicy;
        this.files = files;
        this.contactLogs = contactLogs;
        this.audit = audit;
        this.implants = implants;
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

    /**
     * 创建请求（report.md §2 POST /reports）。{@code contactLogId} 仅「通话记录」类型（A-ATT-017，E16-T08）：
     * 关联某条沟通记录后其录音即本报告单附件（同一批 sys_file、不重复存储），位置缺省时自动取该沟通关联任务的随访记录（整条）、无则「未指定位置」。
     */
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
            String note,
            String contactLogId) {}

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

    /** 患者内报告单行（含位置标签、附件数与首附件；AttachmentNav 消费，E16-T14）。 */
    public record ReportItemView(
            String id,
            String patientId,
            String typeCode,
            String typeName,
            String name,
            String contactLogId,
            LocalDate reportDate,
            String findings,
            String conclusion,
            String note,
            String digitizeStatus,
            boolean locationAdjusted,
            int version,
            String creatorName,
            String createdAt,
            long fileCount,
            FirstFileView firstFile,
            List<LocationTagView> locations) {}

    /**
     * 首个附件（E16-T14 左栏附件导航 / 各位置内联缩略图数据源）：图片经预览短时链接出缩略图， 非图片出文件类型图标；无附件为 null。口径与全院检索行 firstFile
     * 一致（sort_no 最小一条）。
     */
    public record FirstFileView(String fileId, String format) {}

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

    /** 修改位置请求（E16-T13 / A-ATT-020、C-SCAN-020）：add / remove 至少一项非空。 */
    public record LocationChangeRequest(List<LocationInput> add, List<LocationInput> remove) {}

    /** 修改位置结果（回读位置标签，与患者内清单同形；前端随后刷新）。 */
    public record LocationsChangedView(
            String id, int locationCount, List<LocationTagView> locations) {}

    // ---------------------------------------------------------------- 创建（A-ATT-001 /
    // A-ATT-002、C-SCAN-010）

    /**
     * 创建报告单：位置至少 1 个（40001；关联沟通记录的通话记录例外，见 {@link #linkedLocations}）、 附件上限与归属校验、指标项目归属校验；写
     * RPT_CREATE 审计。
     */
    @Transactional
    public CreatedView create(long patientId, CreateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        requirePatient(patientId);
        TypeRow type = requireType(request.typeCode());
        NameRow name = requireName(type, request.nameId());
        LocalDate reportDate = requireReportDate(request.reportDate());
        ContactLink link = contactLinkOf(patientId, type, request.contactLogId());
        List<NormalizedLocation> locations =
                link == null
                        ? normalizeLocations(patientId, request.locations())
                        : linkedLocations(patientId, link, request.locations());
        List<Long> fileIds = normalizeFileIds(request.fileIds());
        List<Long> linkedAudioIds = linkedAudioIdsOf(link, fileIds);
        Map<String, Object> fields = request.fields() == null ? Map.of() : request.fields();
        String fieldValuesJson = writeFields(type, fields);

        long operator = CurrentSession.requireHospital().userId();
        ReportInsert row = new ReportInsert();
        row.setPatientId(patientId);
        row.setTypeCode(type.code());
        row.setTypeName(type.name());
        row.setNameId(name == null ? null : name.id());
        row.setNameSnapshot(name == null ? null : name.name());
        row.setContactLogId(link == null ? null : link.contactLogId());
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
        // 关联沟通记录的录音：同一批 sys_file（沟通侧已置 ATTACHED），只挂关联行不改台账状态（A-ATT-017 不重复存储）
        for (Long audioId : linkedAudioIds) {
            mapper.insertFile(new ReportMapper.FileInsert(reportId, patientId, audioId, sortNo++));
        }
        int fileCount = sortNo;
        int indicatorCount = insertIndicators(reportId, patientId, name, request.indicators());

        CreatedView view =
                new CreatedView(
                        String.valueOf(reportId),
                        type.code(),
                        type.name(),
                        name == null ? null : name.name(),
                        reportDate,
                        locations.size(),
                        fileCount);
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
                                        + fileCount
                                        + " 个、指标 "
                                        + indicatorCount
                                        + " 项"
                                        + (link == null
                                                ? ""
                                                : "；关联沟通记录 "
                                                        + link.contactLogId()
                                                        + "（录音即附件，A-ATT-017）"))
                        .afterJson(writeJson(view))
                        .build());
        log.info(
                "报告单创建（E16-T02）：reportId={} patientId={} type={} 位置数={} 附件数={} 关联沟通记录={}",
                reportId,
                patientId,
                type.code(),
                locations.size(),
                fileCount,
                link == null ? null : link.contactLogId());
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
     * 传入 RPT_DELETE / RPT_FILE_DELETE / RPT_LOCATION_CHANGE）。
     */
    private ReportMapper.ReportHeadRow requireReportInScope(long reportId, String deniedAction) {
        ReportMapper.ReportHeadRow report = mapper.findReport(reportId);
        if (report == null) {
            audit.append(
                    AuditEntry.operation("followup", deniedAction)
                            .objectType("REPORT")
                            .objectId(String.valueOf(reportId))
                            .description("报告单操作被拒绝：报告单不在操作者数据范围内或已删除")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "报告单不存在或不在您的数据范围内");
        }
        return report;
    }

    // ---------------------------------------------------------------- 修改位置（E16-T13 /
    // A-ATT-020、C-SCAN-020）

    /**
     * 修改位置（A-ATT-020、C-SCAN-020「增加或移除位置，至少保留一个；每次增删写入操作日志」）：
     *
     * <ul>
     *   <li>add 按创建同口径校验：锚点须在当前模板版本内（基线 = 当前发布版分类、随访分组 = 该记录固化版本）， 越患者记录 40400； 与在册位置四元组重复 40001。
     *   <li>remove 只做结构解析后按四元组匹配在册行（模板版本不参与——锚点可能来自旧版本或已退回，E16-T15）， 不在册 40001。
     *   <li>结果至少保留 1 个位置（40001）：全部移除不被允许；无位置报告单（历史迁移 / 模板调整后失去位置）只进 add（「指定位置」补上，A-ATT-020）。
     *   <li>增删同场一次事务：插入新增行、删除移除行、version + 1（V63 列注释「修改位置随 E16-T13」）， 写 RPT_LOCATION_CHANGE
     *       操作日志（谁、何时、增加 / 移除了哪些位置——beforeJson / afterJson 带位置清单）。
     *   <li>已电子化报告单移除其随访记录位置（移除后不再有任何随访位置）→ 状态自动回「待录入」并写 RPT_DIGITIZE_CHANGE（E21-T27 / C-SCAN-020、C
     *       规格 §7，结构化记录不受影响；见 {@link #revertDoneIfFollowupLocationLost}）。
     * </ul>
     *
     * <p>越范围 / 不存在 / 已删报告单 40400（写 DENIED 审计）；越患者数据范围查询「未指定位置」不受影响 （E16-T05
     * locationScope=UNASSIGNED 已有）。
     */
    @Transactional
    public LocationsChangedView changeLocations(long reportId, LocationChangeRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        ReportMapper.ReportHeadRow report = requireReportInScope(reportId, "RPT_LOCATION_CHANGE");
        long patientId = report.patientId();
        List<ReportMapper.LinkRow> current = mapper.linksOfReport(reportId);
        List<NormalizedLocation> adds = normalizeAddedLocations(patientId, current, request.add());
        List<NormalizedLocation> removes = normalizeRemovedLocations(current, request.remove());
        if (adds.isEmpty() && removes.isEmpty()) {
            throw bad("请增加或移除至少 1 个位置");
        }
        // 同一位置同时出现在 add / remove 时：在册行由 add 的「已在报告单上」40001 先行拦截（add 只收不在册位置、
        // remove 只收在册位置，交叠必然落在其一），无需单独的交叠校验分支。
        if (current.size() - removes.size() + adds.size() < 1) {
            // A-ATT-020「至少保留一个」；无位置报告单由历史迁移 / 模板调整产生（E18-T08 / E16-T15），不经本口移除到 0
            throw bad("请至少保留 1 个位置（无位置的报告单由历史迁移或模板调整产生，不在此移除到 0 个）");
        }

        long operator = CurrentSession.requireHospital().userId();
        List<ReportMapper.LinkRow> removedRows =
                current.stream()
                        .filter(
                                link ->
                                        removes.stream()
                                                .anyMatch(
                                                        remove ->
                                                                linkDedupeKey(link)
                                                                        .equals(
                                                                                remove
                                                                                        .dedupeKey())))
                        .toList();
        for (NormalizedLocation add : adds) {
            mapper.insertLink(
                    new ReportMapper.LinkInsert(
                            reportId,
                            patientId,
                            add.linkType(),
                            add.linkRecordId(),
                            add.anchorType(),
                            add.anchorKey(),
                            add.templateVersion()));
        }
        for (NormalizedLocation remove : removes) {
            mapper.deleteLink(
                    reportId,
                    remove.linkType(),
                    remove.linkRecordId(),
                    remove.anchorType(),
                    remove.anchorKey());
        }
        if (!adds.isEmpty()) {
            // E16-T15：用户 add 重新指定位置后清报告单级「原位置已调整」提示（纯移除不清——退回事实仍待处置）
            mapper.clearReportLocationAdjusted(reportId);
        }
        mapper.bumpVersion(reportId, operator);

        List<ReportMapper.LinkRow> after = mapper.linksOfReport(reportId);
        revertDoneIfFollowupLocationLost(reportId, removes, after, operator, report);
        List<LocationTagView> afterTags = buildTags(patientId, after);
        audit.append(
                AuditEntry.operation("followup", "RPT_LOCATION_CHANGE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "修改报告单位置："
                                        + changeSummary(patientId, adds, removedRows)
                                        + "；报告单："
                                        + report.typeName()
                                        + (report.nameSnapshot() == null
                                                ? ""
                                                : " · " + report.nameSnapshot()))
                        .beforeJson(writeJson(locationRefs(current)))
                        .afterJson(writeJson(locationRefs(after)))
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "报告单位置变更（E16-T13）：reportId={} patientId={} 增加={} 移除={} operator={}",
                reportId,
                patientId,
                adds.size(),
                removes.size(),
                operator);
        return new LocationsChangedView(
                String.valueOf(reportId), afterTags.size(), List.copyOf(afterTags));
    }

    /**
     * 已电子化报告单移除其随访记录位置 → 状态自动回「待录入」（E21-T27 / C-SCAN-020、C 规格 §7、评审 CL-10； 结构化记录不受影响）。
     *
     * <p>触发口径：本次移除了随访记录位置（整条或分组），且移除后报告单<b>不再有任何</b>随访记录位置（只剩基线等）。 仍保有另一条随访位置（含换记录同场
     * add+remove）不触发——转录锚点可能指向任一在位记录，不误退（多位置边界见
     * docs/testing/hospital-c-digitize-transcribe-acceptance.md §3）。当前态非 DONE 不动（无变化不留痕）。
     *
     * <p>留痕：RPT_DIGITIZE_CHANGE（自动口径，描述注明触发原因），version 随 updateDigitizeStatus 再 +1。
     */
    private void revertDoneIfFollowupLocationLost(
            long reportId,
            List<NormalizedLocation> removes,
            List<ReportMapper.LinkRow> after,
            long operator,
            ReportMapper.ReportHeadRow report) {
        boolean followupRemoved =
                removes.stream().anyMatch(remove -> LINK_FOLLOWUP_RECORD.equals(remove.linkType()));
        if (!followupRemoved) {
            return;
        }
        boolean followupRemains =
                after.stream().anyMatch(link -> LINK_FOLLOWUP_RECORD.equals(link.linkType()));
        if (followupRemains) {
            return;
        }
        ReportMapper.DigitizeRow digitize = mapper.findDigitizeRow(reportId);
        if (digitize == null || !STATUS_DONE.equals(digitize.digitizeStatus())) {
            return;
        }
        mapper.updateDigitizeStatus(reportId, STATUS_PENDING, operator);
        Map<String, String> before = new LinkedHashMap<>();
        before.put("digitizeStatus", STATUS_DONE);
        audit.append(
                AuditEntry.operation("followup", "RPT_DIGITIZE_CHANGE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "移除随访记录位置，已电子化 → 待录入（自动）：报告单："
                                        + report.typeName()
                                        + (report.nameSnapshot() == null
                                                ? ""
                                                : " · " + report.nameSnapshot()))
                        .beforeJson(writeJson(before))
                        .afterJson(writeJson(Map.of("digitizeStatus", STATUS_PENDING)))
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("已电子化报告单移除随访位置自动回待录入（E21-T27）：reportId={} operator={}", reportId, operator);
    }

    // ---------------------------------------------------------------- 电子化状态手工修改（E21-T15 /
    // C-SCAN-011）

    /** 手工修改请求（report.md §3 POST /reports/{id}/digitize-status）：改为「无需录入」须填原因。 */
    public record DigitizeChangeRequest(String status, String reason) {}

    /** 修改结果：新状态与推进后的乐观锁版本（编辑 / 修改位置同口径）。 */
    public record DigitizeChangedView(String id, String digitizeStatus, int version) {}

    /**
     * 手工修改电子化状态（C-SCAN-011「手工改为无需录入须填原因」，验收「状态可手工修改并留痕」）：
     *
     * <ul>
     *   <li>功能开关 {@code scan_progress} 关闭（含无行）→ 40301（医院 A / B 不启用电子化状态，A-ATT-018）。
     *   <li>取值只收 PENDING / DONE / NO_ENTRY（其他 40001）；目标状态与当前一致 40001（无变化不留痕）。
     *   <li>改为 NO_ENTRY 时 reason 必填（去首尾空白非空、≤ 255 字）；其余状态 reason 可空（填了照记入日志）。
     *   <li>留痕：同事务写 RPT_DIGITIZE_CHANGE 操作日志（谁、何时、前后值、原因），报告单 version + 1 （乐观锁与 E16-T13
     *       修改位置同口径）。当前状态为 NULL（类型 / 名称未配置默认值）也可手工设定。
     *   <li>「转录到结构化记录后自动变已电子化」（C-SCAN-012）随 E21-T17，不经本口。
     *   <li>越范围 / 不存在 / 已删报告单 40400（写 DENIED 审计，不暴露对象存在）；无权限 40300、模块关闭 40301。
     * </ul>
     */
    @Transactional
    public DigitizeChangedView changeDigitizeStatus(long reportId, DigitizeChangeRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        if (!Boolean.TRUE.equals(features.findEnabled(SCAN_PROGRESS_FEATURE))) {
            throw new ApiException(403, 40301, "电子化状态未开通（功能开关 scan_progress 关闭）");
        }
        String target = request.status() == null ? "" : request.status().trim();
        String label = DIGITIZE_LABEL.get(target);
        if (label == null) {
            throw bad("电子化状态只支持：待录入（PENDING）/ 已电子化（DONE）/ 无需录入（NO_ENTRY）");
        }
        ReportMapper.DigitizeRow report = mapper.findDigitizeRow(reportId);
        if (report == null) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_DIGITIZE_CHANGE")
                            .objectType("REPORT")
                            .objectId(String.valueOf(reportId))
                            .description("报告单电子化状态修改被拒绝：报告单不在操作者数据范围内或已删除")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "报告单不存在或不在您的数据范围内");
        }
        if (target.equals(report.digitizeStatus())) {
            throw bad("电子化状态未变化（当前已是「" + label + "」）");
        }
        String reason = trimToNull(request.reason());
        if (reason != null && reason.length() > 255) {
            throw bad("原因不能超过 255 字");
        }
        if ("NO_ENTRY".equals(target) && reason == null) {
            throw bad("改为「无需录入」须填写原因");
        }
        long operator = CurrentSession.requireHospital().userId();
        mapper.updateDigitizeStatus(reportId, target, operator);
        String beforeLabel =
                report.digitizeStatus() == null
                        ? "未设置"
                        : DIGITIZE_LABEL.getOrDefault(
                                report.digitizeStatus(), report.digitizeStatus());
        // before 的 digitizeStatus 可为 NULL（Map.of 不收 null 值），用 LinkedHashMap 承载
        Map<String, String> before = new LinkedHashMap<>();
        before.put("digitizeStatus", report.digitizeStatus());
        audit.append(
                AuditEntry.operation("followup", "RPT_DIGITIZE_CHANGE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "修改报告单电子化状态："
                                        + beforeLabel
                                        + " → "
                                        + label
                                        + (reason == null ? "" : "，原因：" + reason)
                                        + "；报告单："
                                        + report.typeName()
                                        + (report.nameSnapshot() == null
                                                ? ""
                                                : " · " + report.nameSnapshot()))
                        .beforeJson(writeJson(before))
                        .afterJson(writeJson(Map.of("digitizeStatus", target)))
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "报告单电子化状态变更（E21-T15）：reportId={} patientId={} {} → {} operator={}",
                reportId,
                report.patientId(),
                report.digitizeStatus(),
                target,
                operator);
        return new DigitizeChangedView(String.valueOf(reportId), target, report.version() + 1);
    }

    /** 增加清单：创建同口径锚点校验（当前模板版本）+ 请求内去重 + 不与在册位置重复（同一报告单同一位置唯一）。 */
    private List<NormalizedLocation> normalizeAddedLocations(
            long patientId, List<ReportMapper.LinkRow> current, List<LocationInput> add) {
        if (add == null || add.isEmpty()) {
            return List.of();
        }
        Map<String, String> baselineCategories = baselineCategoryMap();
        Map<Long, RecordRow> records = recordMap(patientId);
        Map<Long, ImplantMapper.ImplantRow> implantRows = implantMap(patientId);
        Map<String, NormalizedLocation> distinct = new LinkedHashMap<>();
        for (LocationInput input : add) {
            if (input == null) {
                continue;
            }
            NormalizedLocation normalized =
                    normalizeOne(input, baselineCategories, records, implantRows);
            distinct.putIfAbsent(normalized.dedupeKey(), normalized);
        }
        for (NormalizedLocation normalized : distinct.values()) {
            for (ReportMapper.LinkRow link : current) {
                if (linkDedupeKey(link).equals(normalized.dedupeKey())) {
                    throw bad("该位置已在报告单上，无需重复添加：" + locationRef(normalized));
                }
            }
        }
        return List.copyOf(distinct.values());
    }

    /** 移除清单：只做结构解析（不校验当前模板——锚点可能来自旧版本或已退回，E16-T15），按四元组匹配在册行； 不在册 40001（不区分「从未有 / 已被他人移除」）。 */
    private List<NormalizedLocation> normalizeRemovedLocations(
            List<ReportMapper.LinkRow> current, List<LocationInput> remove) {
        if (remove == null || remove.isEmpty()) {
            return List.of();
        }
        Map<String, NormalizedLocation> matched = new LinkedHashMap<>();
        for (LocationInput input : remove) {
            if (input == null) {
                continue;
            }
            NormalizedLocation parsed = parseRemoveRef(input);
            boolean found = false;
            for (ReportMapper.LinkRow link : current) {
                if (linkDedupeKey(link).equals(parsed.dedupeKey())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw bad("该位置不在报告单上，无法移除：" + locationRef(parsed));
            }
            matched.putIfAbsent(parsed.dedupeKey(), parsed);
        }
        return List.copyOf(matched.values());
    }

    /** 移除位置的结构解析：粒度合法性（基线 = 分类、随访 = 整条或分组）与 add 同口径，模板版本不参与。 */
    private NormalizedLocation parseRemoveRef(LocationInput input) {
        String linkType = input.linkType() == null ? "" : input.linkType().trim();
        String anchorType =
                input.anchorType() == null || input.anchorType().isBlank()
                        ? null
                        : input.anchorType().trim();
        String anchorKey = trimToNull(input.anchorKey());
        if (LINK_IMPLANT_RECORD.equals(linkType)) {
            // 模板版本不参与（与随访位置同口径）；分组锚点只查结构，是否仍在册由四元组匹配判定
            if (input.linkRecordId() == null) {
                throw bad("植入记录位置缺少记录标识");
            }
            if (anchorType != null && !ANCHOR_SECTION.equals(anchorType)) {
                throw bad("植入记录位置只能是整条记录或其中某个分组（器械信息 / 电极）");
            }
            if (ANCHOR_SECTION.equals(anchorType) && anchorKey == null) {
                throw bad("分组位置缺少分组锚点");
            }
            return new NormalizedLocation(
                    LINK_IMPLANT_RECORD, input.linkRecordId(), anchorType, anchorKey, 0);
        }
        if (LINK_BASELINE.equals(linkType)) {
            if (!ANCHOR_CATEGORY.equals(anchorType) || anchorKey == null) {
                throw bad("基线位置必须是某个分类（不能是分组或整条记录）");
            }
            return new NormalizedLocation(LINK_BASELINE, null, ANCHOR_CATEGORY, anchorKey, 0);
        }
        if (LINK_FOLLOWUP_RECORD.equals(linkType)) {
            if (input.linkRecordId() == null) {
                throw bad("随访记录位置缺少记录标识");
            }
            if (anchorType != null && !ANCHOR_SECTION.equals(anchorType)) {
                throw bad("随访记录位置只能是整条记录或其中某个分组");
            }
            if (ANCHOR_SECTION.equals(anchorType) && anchorKey == null) {
                throw bad("分组位置缺少分组锚点");
            }
            return new NormalizedLocation(
                    LINK_FOLLOWUP_RECORD, input.linkRecordId(), anchorType, anchorKey, 0);
        }
        throw bad("位置类型不合法：" + linkType);
    }

    /** 在册位置行的四元组（与 {@link NormalizedLocation#dedupeKey()} 同构）。 */
    private static String linkDedupeKey(ReportMapper.LinkRow link) {
        return link.linkType()
                + '|'
                + (link.linkRecordId() == null ? "" : link.linkRecordId())
                + '|'
                + (link.anchorType() == null ? "" : link.anchorType())
                + '|'
                + (link.anchorKey() == null ? "" : link.anchorKey());
    }

    /** 位置的技术标识（错误文案用，不暴露患者数据）：基线分类 key / 记录 id + 分组 key。 */
    private static String locationRef(NormalizedLocation location) {
        if (LINK_BASELINE.equals(location.linkType())) {
            return "基线分类 " + location.anchorKey();
        }
        if (LINK_IMPLANT_RECORD.equals(location.linkType())) {
            String ref = "植入记录 " + location.linkRecordId();
            return location.anchorKey() == null ? ref : ref + " 分组 " + location.anchorKey();
        }
        String ref = "随访记录 " + location.linkRecordId();
        return location.anchorKey() == null ? ref : ref + " 分组 " + location.anchorKey();
    }

    /** 操作日志摘要：增加 N 个（标签清单）、移除 M 个（标签清单）——A-ATT-020「增加或移除了哪个位置」。 */
    private String changeSummary(
            long patientId, List<NormalizedLocation> adds, List<ReportMapper.LinkRow> removedRows) {
        StringBuilder summary = new StringBuilder();
        if (!adds.isEmpty()) {
            List<ReportMapper.LinkRow> addRows =
                    adds.stream().map(ReportService::toLinkRow).toList();
            String labels =
                    buildTags(patientId, addRows).stream()
                            .map(ReportService::tagText)
                            .collect(Collectors.joining("、"));
            summary.append("增加 ").append(adds.size()).append(" 个（").append(labels).append("）");
        }
        if (!removedRows.isEmpty()) {
            if (!summary.isEmpty()) {
                summary.append("、");
            }
            String labels =
                    buildTags(patientId, removedRows).stream()
                            .map(ReportService::tagText)
                            .collect(Collectors.joining("、"));
            summary.append("移除 ")
                    .append(removedRows.size())
                    .append(" 个（")
                    .append(labels)
                    .append("）");
        }
        return summary.toString();
    }

    /** 归一化位置 → 位置行（标签构建复用；id / adjusted 不参与显示）。 */
    private static ReportMapper.LinkRow toLinkRow(NormalizedLocation location) {
        return new ReportMapper.LinkRow(
                null,
                null,
                location.linkType(),
                location.linkRecordId(),
                location.anchorType(),
                location.anchorKey(),
                location.templateVersion(),
                0);
    }

    /**
     * 操作日志 before / after 的位置清单：四元组 + 模板版本的紧凑形态（不含显示标签与日期——静态 ObjectMapper 不带 jsr310 模块序列化不了
     * LocalDate，且留痕以锚点标识为准）。
     */
    private static List<Map<String, Object>> locationRefs(List<ReportMapper.LinkRow> rows) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (ReportMapper.LinkRow link : rows) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("linkType", link.linkType());
            ref.put("linkRecordId", link.linkRecordId());
            ref.put("anchorType", link.anchorType());
            ref.put("anchorKey", link.anchorKey());
            ref.put("templateVersion", link.templateVersion());
            refs.add(ref);
        }
        return refs;
    }

    /** 位置标签文案（与前端 reportLocationText 同口径）：「基线 · 病例基本信息」「第2年随访 2026-06-10 · 超声复查」。 */
    private static String tagText(LocationTagView tag) {
        String scope =
                LINK_BASELINE.equals(tag.linkType())
                        ? "基线"
                        : LINK_IMPLANT_RECORD.equals(tag.linkType())
                                ? (tag.recordLabel() != null ? tag.recordLabel() : "植入记录")
                                : (tag.recordLabel() != null ? tag.recordLabel() : "随访记录");
        return tag.anchorLabel() == null ? scope : scope + " · " + tag.anchorLabel();
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

    /** 批量创建的公共部分 → 单份创建请求（批量不收指标 / 所见 / 结论 / 备注 / 沟通关联，随批后逐份编辑）。 */
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
                null,
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

    // ---------------------------------------------------------------- 通话记录关联（E16-T08 / A-ATT-017）

    /** 已解析的沟通记录关联（录音 fileId 即附件，不重复上传）。 */
    private record ContactLink(long contactLogId, Long taskId, List<Long> audioFileIds) {}

    /**
     * 解析可选的 {@code contactLogId}：仅「通话记录」类型（40001）；归属校验（不属于该患者 / 越范围 40400）与录音清单 由 {@link
     * ContactLogService#linkableContact}（沟通子域 app 口）承担。
     */
    private ContactLink contactLinkOf(long patientId, TypeRow type, String rawContactLogId) {
        if (rawContactLogId == null || rawContactLogId.isBlank()) {
            return null;
        }
        if (!CallReportService.CALL_TYPE_CODE.equals(type.code())) {
            throw bad("关联沟通记录仅适用于「通话记录」类型报告单");
        }
        long contactLogId;
        try {
            contactLogId = Long.parseLong(rawContactLogId.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(404, 40400, "沟通记录不存在");
        }
        ContactLogService.LinkableContact contact =
                contactLogs.linkableContact(patientId, contactLogId);
        return new ContactLink(contact.id(), contact.taskId(), contact.audioFileIds());
    }

    /**
     * 关联通话记录的位置（A-ATT-017「录音生成的通话记录位置预选为该任务对应的随访记录（整条），没有对应记录时进入未指定位置」）：
     * 显式给了照常校验（必填口径不变）；缺省取该沟通关联任务的随访记录（整条，模板版本取记录固化版本），无对应记录时不落位置行（「未指定位置」组，A-ATT-020）。
     */
    private List<NormalizedLocation> linkedLocations(
            long patientId, ContactLink link, List<LocationInput> locations) {
        if (locations != null && !locations.isEmpty()) {
            return normalizeLocations(patientId, locations);
        }
        RecordRow record = null;
        if (link.taskId() != null) {
            for (RecordRow row : mapper.recordsOfPatient(patientId)) {
                if (link.taskId().equals(row.taskId())) {
                    record = row;
                    break;
                }
            }
        }
        if (record == null) {
            return List.of(); // 未指定位置：无位置报告单由本联动产生（A-ATT-017），E16-T05 UNASSIGNED / E16-T14 既有口径承接
        }
        return List.of(
                new NormalizedLocation(
                        LINK_FOLLOWUP_RECORD, record.id(), null, null, record.templateVersion()));
    }

    /** 关联录音去重（与新上传附件重复时只留一份）并校验合计上限（≤ file.upload.max.files.per.report，超限 40001）。 */
    private List<Long> linkedAudioIdsOf(ContactLink link, List<Long> fileIds) {
        if (link == null || link.audioFileIds().isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>();
        for (Long audioId : link.audioFileIds()) {
            if (audioId != null && !fileIds.contains(audioId) && !distinct.contains(audioId)) {
                distinct.add(audioId);
            }
        }
        if (!distinct.isEmpty()
                && !filePolicy.policy().withinFileCount(fileIds.size() + distinct.size())) {
            throw bad(
                    "附件数量超过上限（每份报告单最多 "
                            + filePolicy.policy().maxFilesPerReport()
                            + " 个，含关联沟通记录的录音）");
        }
        return distinct;
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
        Map<Long, ImplantMapper.ImplantRow> implantRows = implantMap(patientId);
        Map<String, NormalizedLocation> distinct = new LinkedHashMap<>();
        for (LocationInput input : locations) {
            if (input == null) {
                continue;
            }
            NormalizedLocation normalized =
                    normalizeOne(input, baselineCategories, records, implantRows);
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
            Map<Long, RecordRow> records,
            Map<Long, ImplantMapper.ImplantRow> implantRows) {
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
        if (LINK_IMPLANT_RECORD.equals(linkType)) {
            return normalizeImplantLocation(input, anchorType, anchorKey, implantRows);
        }
        throw bad("位置类型不合法：" + linkType);
    }

    /**
     * 植入记录位置（E21-T32 / C-SCAN-010）：整条（anchorType=null）或其中分组（section，锚点对 C_IMPLANT 当前发布版分类清单——器械信息
     * device / 电极 lead）；须 {@code implant} 开关开启（关闭 40301，AGENTS §5.7）；越患者 / 越范围 / 不存在的植入记录统一
     * 40400（不暴露对象存在）。
     */
    private NormalizedLocation normalizeImplantLocation(
            LocationInput input,
            String anchorType,
            String anchorKey,
            Map<Long, ImplantMapper.ImplantRow> implantRows) {
        requireImplantFeature();
        if (input.linkRecordId() == null) {
            throw bad("植入记录位置缺少记录标识");
        }
        ImplantMapper.ImplantRow implant = implantRows.get(input.linkRecordId());
        if (implant == null) {
            // 不区分「不存在 / 不属于该患者 / 越范围」，统一 40400 不暴露对象存在
            throw new ApiException(404, 40400, "植入记录不存在");
        }
        int version = formService.publishedVersion(ImplantService.IMPLANT_FORM_CODE);
        if (anchorType == null) {
            return new NormalizedLocation(LINK_IMPLANT_RECORD, implant.id(), null, null, version);
        }
        if (!ANCHOR_SECTION.equals(anchorType)) {
            throw bad("植入记录位置只能是整条记录或其中某个分组（器械信息 / 电极）");
        }
        if (anchorKey == null) {
            throw bad("分组位置缺少分组锚点");
        }
        if (!implantCategoryMap().containsKey(anchorKey)) {
            throw bad("植入分组不存在：" + anchorKey);
        }
        return new NormalizedLocation(
                LINK_IMPLANT_RECORD, implant.id(), ANCHOR_SECTION, anchorKey, version);
    }

    /** 植入开关闸门（E21-T32）：关闭（含无行）→ 40301，与 scan_progress / implant 子域同口径。 */
    private void requireImplantFeature() {
        if (!Boolean.TRUE.equals(features.findEnabled(ImplantService.FEATURE_IMPLANT))) {
            throw new ApiException(403, 40301, "植入记录未开通（功能开关 implant 关闭）");
        }
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

    /**
     * 植入记录位置分组清单（E21-T32）：C_IMPLANT 表单当前发布版分类（V90 预置 器械信息 device / 电极 lead）——
     * 植入记录无按记录固化版本（结构化列非表单实例），锚点一律对当前发布版校验（与基线分类同口径）。
     */
    private Map<String, String> implantCategoryMap() {
        String code = ImplantService.IMPLANT_FORM_CODE;
        return categoryMap(code, formService.publishedVersion(code));
    }

    /** 患者全部植入记录（当前用户范围内；越范围空清单 → 上层按 40400 处理）。 */
    private Map<Long, ImplantMapper.ImplantRow> implantMap(long patientId) {
        Map<Long, ImplantMapper.ImplantRow> map = new LinkedHashMap<>();
        for (ImplantMapper.ImplantRow row : implants.implantsOf(patientId)) {
            map.put(row.id(), row);
        }
        return map;
    }

    /**
     * 植入记录标题（「植入记录#2 2025-01-10」，与 {@code ImplantService} 植入序号同口径：按植入日期升序 1..N）——
     * 位置树节点与位置标签共用；日期缺失防御性只留序号。
     */
    static String implantTitle(ImplantMapper.ImplantRow row, Map<Long, Integer> implantNoById) {
        Integer no = implantNoById.get(row.id());
        String label = no == null ? "植入记录" : "植入记录#" + no;
        return row.implantDate() == null ? label : label + " " + row.implantDate();
    }

    /** 植入序号表（按植入日期升序、同日 id 小者在前 1..N；与 ImplantService 列表口径一致）。 */
    private static Map<Long, Integer> implantNoMap(List<ImplantMapper.ImplantRow> rows) {
        List<ImplantMapper.ImplantRow> byDateAsc = new ArrayList<>(rows);
        byDateAsc.sort(
                java.util.Comparator.comparing(ImplantMapper.ImplantRow::implantDate)
                        .thenComparing(ImplantMapper.ImplantRow::id));
        Map<Long, Integer> nos = new LinkedHashMap<>();
        for (int i = 0; i < byDateAsc.size(); i++) {
            nos.put(byDateAsc.get(i).id(), i + 1);
        }
        return nos;
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

        // E21-T32 / C-SCAN-010、C-PAT-017：植入记录根（implant 开关开启才有）——每条植入记录整条 +
        // 器械信息 / 电极分组；当前器械置顶，其余按植入日期新在前（与植入列表同序，pages.md §4.4）。
        if (Boolean.TRUE.equals(features.findEnabled(ImplantService.FEATURE_IMPLANT))) {
            String implantCode = ImplantService.IMPLANT_FORM_CODE;
            int implantVersion = formService.publishedVersion(implantCode);
            Map<String, String> implantCategories = categoryMap(implantCode, implantVersion);
            List<ImplantMapper.ImplantRow> implantRows = implants.implantsOf(patientId);
            // 当前器械置顶（在用在前），其余保持 gateway 的日期新在前——与 ImplantService 列表同序
            implantRows =
                    implantRows.stream()
                            .sorted(
                                    (left, right) -> {
                                        boolean leftActive =
                                                com.crdp.followup.implant.domain.ImplantCodes
                                                        .STATUS_ACTIVE
                                                        .equals(left.status());
                                        boolean rightActive =
                                                com.crdp.followup.implant.domain.ImplantCodes
                                                        .STATUS_ACTIVE
                                                        .equals(right.status());
                                        return leftActive == rightActive ? 0 : leftActive ? -1 : 1;
                                    })
                            .toList();
            Map<Long, Integer> implantNos = implantNoMap(implantRows);
            List<LocationNodeView> implantChildren = new ArrayList<>();
            for (ImplantMapper.ImplantRow row : implantRows) {
                List<LocationNodeView> groups = new ArrayList<>();
                for (var group : implantCategories.entrySet()) {
                    groups.add(
                            new LocationNodeView(
                                    "implant:" + row.id() + ":sec:" + group.getKey(),
                                    group.getValue(),
                                    true,
                                    new LocationValueView(
                                            LINK_IMPLANT_RECORD,
                                            String.valueOf(row.id()),
                                            ANCHOR_SECTION,
                                            group.getKey(),
                                            implantVersion),
                                    null));
                }
                implantChildren.add(
                        new LocationNodeView(
                                "implant:" + row.id(),
                                implantTitle(row, implantNos),
                                true,
                                new LocationValueView(
                                        LINK_IMPLANT_RECORD,
                                        String.valueOf(row.id()),
                                        null,
                                        null,
                                        implantVersion),
                                groups));
            }
            roots.add(new LocationNodeView("implant", "植入记录", false, null, implantChildren));
        }

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

    /** 记录标题（「第2年随访 2026-06-10」；退回作业 E16-T15 的日志描述共用，包内可见）。 */
    static String recordTitle(RecordRow record) {
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
        Map<Long, FirstFileView> firstFiles = new LinkedHashMap<>();
        for (ReportMapper.FirstFileRow row : mapper.firstFilesOfPatient(patientId)) {
            firstFiles.putIfAbsent(
                    row.reportId(),
                    new FirstFileView(String.valueOf(row.fileId()), row.formatCode()));
        }
        List<ReportItemView> items = new ArrayList<>();
        for (ReportRow row : mapper.reportsOfPatient(patientId)) {
            List<LocationTagView> locations = tags.getOrDefault(row.id(), List.of());
            if ("baseline".equals(normalized)
                    && locations.stream()
                            .noneMatch(
                                    tag ->
                                            LINK_BASELINE.equals(tag.linkType())
                                                    || LINK_IMPLANT_RECORD.equals(
                                                            tag.linkType()))) {
                // E21-T32 / C-PAT-017：植入记录在基线资料页签，implant 位置随 baseline 侧下发
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
                            row.contactLogId() == null ? null : String.valueOf(row.contactLogId()),
                            row.reportDate(),
                            row.findings(),
                            row.conclusion(),
                            row.note(),
                            row.digitizeStatus(),
                            row.locationAdjusted() != null && row.locationAdjusted() > 0,
                            row.version() == null ? 0 : row.version(),
                            row.creatorName(),
                            row.createdAt(),
                            fileCounts.getOrDefault(row.id(), 0L),
                            firstFiles.get(row.id()),
                            locations));
        }
        return new ReportsView(String.valueOf(patientId), normalized, items);
    }

    /** 位置标签（列表 / 左栏显示用）：recordLabel / anchorLabel 按当前模板解析（模板改版退回随 E16-T15）。 */
    private Map<Long, List<LocationTagView>> locationTags(long patientId) {
        Map<Long, List<LocationTagView>> tags = new LinkedHashMap<>();
        List<LinkRow> links = mapper.linksOfPatient(patientId);
        TagContext context = tagContextOf(patientId, links);
        for (LinkRow link : links) {
            tags.computeIfAbsent(link.reportId(), id -> new ArrayList<>())
                    .add(tagOf(link, context));
        }
        return tags;
    }

    /** 标签拼装上下文（基线 / 随访 / 植入三侧的模板分类与记录行，一趟取齐）。 */
    private record TagContext(
            Map<String, String> baselineCategories,
            Map<Long, RecordRow> records,
            Map<Long, Map<String, String>> sectionCache,
            Map<Long, ImplantMapper.ImplantRow> implantRows,
            Map<Long, Integer> implantNos,
            Map<String, String> implantCategories) {}

    /**
     * 标签拼装上下文（基线 / 随访 / 植入三侧的模板分类与记录行）。植入侧只在清单里确有 implant 位置时才读（A / B 租户与纯基线 / 随访报告单不多读 C_IMPLANT
     * 模板与植入表）。
     */
    private TagContext tagContextOf(long patientId, List<LinkRow> links) {
        boolean hasImplantLinks =
                links.stream().anyMatch(link -> LINK_IMPLANT_RECORD.equals(link.linkType()));
        List<ImplantMapper.ImplantRow> rows =
                hasImplantLinks ? implants.implantsOf(patientId) : List.of();
        Map<String, String> implantCategories = hasImplantLinks ? implantCategoryMap() : Map.of();
        Map<Long, ImplantMapper.ImplantRow> implantRows = new LinkedHashMap<>();
        for (ImplantMapper.ImplantRow row : rows) {
            implantRows.put(row.id(), row);
        }
        return new TagContext(
                baselineCategoryMap(),
                recordMap(patientId),
                new LinkedHashMap<>(),
                implantRows,
                implantNoMap(rows),
                implantCategories);
    }

    /** 位置行 → 标签（修改位置的变更前后 / 操作日志摘要共用一份标签拼装）。 */
    private List<LocationTagView> buildTags(long patientId, List<ReportMapper.LinkRow> rows) {
        TagContext context = tagContextOf(patientId, rows);
        List<LocationTagView> tags = new ArrayList<>();
        for (ReportMapper.LinkRow link : rows) {
            tags.add(tagOf(link, context));
        }
        return tags;
    }

    private LocationTagView tagOf(LinkRow link, TagContext context) {
        Map<String, String> baselineCategories = context.baselineCategories();
        Map<Long, RecordRow> records = context.records();
        Map<Long, ImplantMapper.ImplantRow> implantRows = context.implantRows();
        Map<Long, Integer> implantNos = context.implantNos();
        Map<String, String> implantCategories = context.implantCategories();
        String recordLabel = null;
        LocalDate recordDate = null;
        String anchorLabel = null;
        if (LINK_BASELINE.equals(link.linkType())) {
            anchorLabel = baselineCategories.get(link.anchorKey());
        } else if (LINK_IMPLANT_RECORD.equals(link.linkType())
                && link.linkRecordId() != null
                && implantRows.containsKey(link.linkRecordId())) {
            // E21-T32：植入记录标签「植入记录#2 2025-01-10 · 器械信息」——记录读不出（防御：行已不存在）只留锚点
            ImplantMapper.ImplantRow implant = implantRows.get(link.linkRecordId());
            recordLabel = implantTitle(implant, implantNos);
            recordDate = implant.implantDate();
            if (ANCHOR_SECTION.equals(link.anchorType())) {
                anchorLabel = implantCategories.get(link.anchorKey());
            }
        } else if (LINK_FOLLOWUP_RECORD.equals(link.linkType()) && link.linkRecordId() != null) {
            RecordRow record = records.get(link.linkRecordId());
            if (record != null) {
                recordLabel = recordTitle(record);
                recordDate = record.planDate() != null ? record.planDate() : record.visitDate();
                if (ANCHOR_SECTION.equals(link.anchorType())) {
                    Map<String, String> sections =
                            context.sectionCache()
                                    .computeIfAbsent(
                                            record.id(),
                                            id ->
                                                    categoryMap(
                                                            record.formCode(),
                                                            record.templateVersion()));
                    anchorLabel = sections.get(link.anchorKey());
                }
            }
        }
        return new LocationTagView(
                link.linkType(),
                link.linkRecordId() == null ? null : String.valueOf(link.linkRecordId()),
                recordLabel,
                recordDate,
                link.anchorType(),
                link.anchorKey(),
                anchorLabel,
                link.templateVersion() == null ? 0 : link.templateVersion(),
                link.adjusted() != null && link.adjusted() > 0);
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
