package com.crdp.followup.report.app;

import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.patient.app.PatientRegistrationService;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.TypeRow;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.Criteria;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.FileCountRow;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.FileRowRaw;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.LinkRowRaw;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.RowRaw;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 全院报告单列表与检索（E16-T05 / A-ATT-016、C-SCAN-014；docs/api/report.md §2 POST /reports/search，页面
 * /followup/report，pages.md §6.4）。
 *
 * <p>验收口径：
 *
 * <ul>
 *   <li>按病区隔离：行查询经 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（rpt_report 在
 *       ControlledTables，E07-T02 改写）——越范围患者的报告单在全院列表不出现，范围为空返回空页；
 *   <li>检索：患者（姓名 / 病案号盲索引精确、随访编号 / 原系统编号等值，C-SCAN-014）、类型、报告时间与上传日期范围、 上传人、位置（{@code UNASSIGNED}
 *       未指定位置 / 含基线 / 含随访记录，A-ATT-016）、电子化状态（C）、病区； 同一关键词还 LIKE 匹配名称 / 所见 / 结论与类型字段文字（field_values
 *       JSON 文本，含「医院」； 不识别图片内文字）；
 *   <li>行内患者只输出脱敏值（姓名 / 病案号解密后 {@link SensitiveValueMasker}，AGENTS §5.3；列表不提供逐行明文）；
 *   <li>位置标签（「基线 · 超声检查」「第 3 年随访 · 超声复查」）逐行返回，超过 2 个折叠 +N 由前端做（悬停显示全部）；
 *       无位置行（历史迁移、模板调整后失去位置——进入「未指定位置」组，A-ATT-020）返回空清单；
 *   <li>缩略图列取第一个附件（图片走预览短时链接，components.md §6 懒加载口径）；
 *   <li>批量查询写 BATCH_QUERY 数据访问审计（AGENTS §5.4）。
 * </ul>
 */
@Service
public class ReportSearchService {

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 100;

    private static final int KEYWORD_MAX_LENGTH = 64;

    /** 位置筛选（A-ATT-016「位置（含『未指定位置』）」）：不传 = 全部。 */
    private static final List<String> LOCATION_SCOPES =
            List.of("UNASSIGNED", "BASELINE", "FOLLOWUP");

    /** 电子化状态（C-SCAN-011：PENDING 待录入 / DONE 已电子化 / NO_ENTRY 无需录入；A 不启用、行值为 null）。 */
    private static final List<String> DIGITIZE_VALUES = List.of("PENDING", "DONE", "NO_ENTRY");

    /** 来源标签（与患者内清单同口径，E16-T02）。 */
    private static final Map<String, String> SOURCE_LABEL =
            Map.of("BACKFILL", "历史补录", "UNPLANNED", "计划外随访", "TASK", "随访", "REMOTE", "远程监测");

    private final ReportSearchMapper mapper;
    private final ReportConfigMapper config;
    private final PatientMapper patients;
    private final FormService formService;
    private final CryptoService crypto;
    private final AuditLogger audit;

    public ReportSearchService(
            ReportSearchMapper mapper,
            ReportConfigMapper config,
            PatientMapper patients,
            FormService formService,
            CryptoService crypto,
            AuditLogger audit) {
        this.mapper = mapper;
        this.config = config;
        this.patients = patients;
        this.formService = formService;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 检索请求（docs/api/report.md §2 reports/search body；字段全部可空）。 */
    public record SearchRequest(
            Integer pageNo,
            Integer pageSize,
            String keyword,
            String typeCode,
            LocalDate reportDateFrom,
            LocalDate reportDateTo,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            Long creatorId,
            String locationScope,
            String digitizeStatus,
            List<Long> wardIds) {}

    /** 缩略图首附件（图片格式由前端走预览短时链接；非图片显示文件类型图标）。 */
    public record FirstFileView(String fileId, String format) {}

    /**
     * 列表行（姓名 / 病案号只含脱敏值；locations 为空 = 未指定位置，前端显示橙色标签）。 位置标签结构与患者内清单一致（{@link
     * ReportService.LocationTagView}）。
     */
    public record RowView(
            String id,
            String patientId,
            String patientName,
            String mrn,
            String visitNo,
            String wardName,
            String typeCode,
            String typeName,
            String name,
            LocalDate reportDate,
            List<ReportService.LocationTagView> locations,
            String digitizeStatus,
            String creatorName,
            String createdAt,
            long fileCount,
            FirstFileView firstFile) {}

    /** 类型候选（只含启用类型）。 */
    public record TypeOptionView(String code, String name) {}

    /** 上传人候选（当前数据范围内出现过的上传人）。 */
    public record UserOptionView(String id, String name) {}

    /** 病区候选（启用项；行级隔离仍按数据范围）。 */
    public record WardOptionView(String id, String name) {}

    /** 分页结果（统一分页口径 + 筛选候选，候选本身不敏感）。 */
    public record ReportPage(
            List<RowView> items,
            long total,
            int pageNo,
            int pageSize,
            List<TypeOptionView> types,
            List<UserOptionView> uploaders,
            List<WardOptionView> wards) {}

    // ---------------------------------------------------------------- 检索（A-ATT-016 / C-SCAN-014）

    /** 条件检索一页（校验 → 盲索引 → 范围内查询 → 脱敏 → 批量查询审计）。 */
    public ReportPage search(SearchRequest request) {
        SearchRequest safe = request == null ? emptyRequest() : request;
        int pageNo = safe.pageNo() == null ? 1 : safe.pageNo();
        int pageSize = safe.pageSize() == null ? PAGE_SIZE_DEFAULT : safe.pageSize();
        if (pageNo < 1) {
            throw invalid("pageNo（从 1 开始）");
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAX) {
            throw invalid("pageSize（1–" + PAGE_SIZE_MAX + "）");
        }
        Criteria criteria = criteriaOf(safe);

        List<RowRaw> rows = mapper.search(criteria, (pageNo - 1) * pageSize, pageSize);
        List<Long> reportIds = rows.stream().map(RowRaw::id).toList();
        Map<Long, List<ReportService.LocationTagView>> locations =
                reportIds.isEmpty() ? Map.of() : locationTags(mapper.linksOfReports(reportIds));
        Map<Long, Long> fileCounts = new LinkedHashMap<>();
        if (!reportIds.isEmpty()) {
            for (FileCountRow row : mapper.fileCountsOfReports(reportIds)) {
                fileCounts.put(row.reportId(), row.fileCount());
            }
        }
        Map<Long, FileRowRaw> firstFiles = new LinkedHashMap<>();
        if (!reportIds.isEmpty()) {
            for (FileRowRaw row : mapper.firstFilesOfReports(reportIds)) {
                firstFiles.putIfAbsent(row.reportId(), row);
            }
        }
        List<RowView> items = new ArrayList<>();
        for (RowRaw row : rows) {
            FileRowRaw first = firstFiles.get(row.id());
            items.add(
                    new RowView(
                            String.valueOf(row.id()),
                            String.valueOf(row.patientId()),
                            maskName(row.nameCipher()),
                            maskMrn(row.mrnCipher()),
                            row.visitNo(),
                            row.wardName(),
                            row.typeCode(),
                            row.typeName(),
                            row.nameSnapshot(),
                            row.reportDate(),
                            locations.getOrDefault(row.id(), List.of()),
                            row.digitizeStatus(),
                            row.creatorName(),
                            row.createdAt(),
                            fileCounts.getOrDefault(row.id(), 0L),
                            first == null
                                    ? null
                                    : new FirstFileView(
                                            String.valueOf(first.fileId()), first.formatCode())));
        }
        long total = mapper.count(criteria);

        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("REPORT")
                        .description("检索全院报告单列表")
                        .recordCount(total)
                        .queryDigest(digest(safe))
                        .build());
        return new ReportPage(
                items, total, pageNo, pageSize, typeOptions(), uploaderOptions(), wardOptions());
    }

    // ---------------------------------------------------------------- 筛选候选

    /** 类型候选（只含启用类型；停用类型不再出现在筛选，但已存报告单照常显示快照）。 */
    private List<TypeOptionView> typeOptions() {
        List<TypeOptionView> options = new ArrayList<>();
        for (TypeRow type : config.findTypes()) {
            if ("ENABLED".equals(type.status())) {
                options.add(new TypeOptionView(type.code(), type.name()));
            }
        }
        return options;
    }

    private List<UserOptionView> uploaderOptions() {
        return mapper.uploaderOptions().stream()
                .map(row -> new UserOptionView(String.valueOf(row.id()), row.name()))
                .toList();
    }

    private List<WardOptionView> wardOptions() {
        return patients.findWards().stream()
                .map(row -> new WardOptionView(String.valueOf(row.id()), row.name()))
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private static SearchRequest emptyRequest() {
        return new SearchRequest(
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 条件装配（检索词盲索引 + 文字 LIKE、枚举白名单、日期区间校验）。 */
    private Criteria criteriaOf(SearchRequest safe) {
        String keyword = normalizeKeyword(safe.keyword());
        String nameBlind = null;
        String mrnBlind = null;
        if (keyword != null) {
            nameBlind = crypto.blindIndex(PatientRegistrationService.PURPOSE_NAME, keyword);
            mrnBlind = crypto.blindIndex(PatientRegistrationService.PURPOSE_MRN, keyword);
        }
        validateRange(safe.reportDateFrom(), safe.reportDateTo(), "reportDateFrom / reportDateTo");
        validateRange(safe.uploadedFrom(), safe.uploadedTo(), "uploadedFrom / uploadedTo");
        return new Criteria(
                normalizeTypeCode(safe.typeCode()),
                safe.reportDateFrom(),
                safe.reportDateTo(),
                safe.uploadedFrom(),
                safe.uploadedTo(),
                positiveOrNull(safe.creatorId(), "creatorId"),
                nameBlind,
                mrnBlind,
                keyword,
                keyword,
                normalizeEnum(safe.locationScope(), "locationScope", LOCATION_SCOPES),
                normalizeEnum(safe.digitizeStatus(), "digitizeStatus", DIGITIZE_VALUES),
                sanitizeWardIds(safe.wardIds()));
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > KEYWORD_MAX_LENGTH) {
            throw invalid("检索词（最多 " + KEYWORD_MAX_LENGTH + " 个字符）");
        }
        return trimmed;
    }

    private static String normalizeTypeCode(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            return null;
        }
        String trimmed = typeCode.trim();
        if (trimmed.length() > 32) {
            throw invalid("typeCode（最多 32 个字符）");
        }
        return trimmed;
    }

    /** 枚举白名单（未知值 40001，不静默吞掉）。 */
    private static String normalizeEnum(String value, String field, List<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw invalid(field + "（" + String.join(" / ", allowed) + "）");
        }
        return normalized;
    }

    private static void validateRange(LocalDate from, LocalDate to, String field) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid(field + "（起止日期颠倒）");
        }
    }

    private static Long positiveOrNull(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw invalid(field + "（须为正整数）");
        }
        return value;
    }

    private static List<Long> sanitizeWardIds(List<Long> wardIds) {
        if (wardIds == null || wardIds.isEmpty()) {
            return null;
        }
        List<Long> cleaned =
                wardIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (cleaned.isEmpty()) {
            throw invalid("wardIds（须为正整数）");
        }
        return cleaned;
    }

    /** 位置行 → 标签（按报告单分组；锚点显示名按当前发布版 / 记录固化版本解析，与患者内清单同口径）。 */
    private Map<Long, List<ReportService.LocationTagView>> locationTags(List<LinkRowRaw> links) {
        Map<String, String> baselineCategories = baselineCategoryMap();
        Map<String, Map<String, String>> sectionCache = new LinkedHashMap<>();
        Map<Long, List<ReportService.LocationTagView>> tags = new LinkedHashMap<>();
        for (LinkRowRaw link : links) {
            String recordLabel = null;
            LocalDate recordDate = null;
            String anchorLabel = null;
            if ("baseline".equals(link.linkType())) {
                anchorLabel = baselineCategories.get(link.anchorKey());
            } else if ("followup_record".equals(link.linkType()) && link.recordId() != null) {
                recordLabel = recordTitle(link);
                recordDate = link.planDate() != null ? link.planDate() : link.visitDate();
                if ("section".equals(link.anchorType())) {
                    int recordVersion =
                            link.recordTemplateVersion() == null ? 0 : link.recordTemplateVersion();
                    Map<String, String> sections =
                            sectionCache.computeIfAbsent(
                                    link.formCode() + "#" + recordVersion,
                                    key -> categoryMap(link.formCode(), recordVersion));
                    anchorLabel = sections.get(link.anchorKey());
                }
            }
            tags.computeIfAbsent(link.reportId(), id -> new ArrayList<>())
                    .add(
                            new ReportService.LocationTagView(
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

    private static String recordTitle(LinkRowRaw link) {
        String label =
                link.nodeName() != null
                        ? link.nodeName()
                        : SOURCE_LABEL.getOrDefault(
                                link.recordSource() == null ? "" : link.recordSource(), "随访记录");
        LocalDate date = link.planDate() != null ? link.planDate() : link.visitDate();
        return date == null ? label : label + " " + date;
    }

    private Map<String, String> baselineCategoryMap() {
        String code = BaselineSectionService.BASELINE_FORM_CODE;
        return categoryMap(code, formService.publishedVersion(code));
    }

    private Map<String, String> categoryMap(String formCode, int version) {
        Map<String, String> map = new LinkedHashMap<>();
        for (var category : formService.schema(formCode, version).categories()) {
            map.put(category.key(), category.label());
        }
        return map;
    }

    /** 行内姓名脱敏（解密失败按未知处理，不抛错——列表口径与任务列表一致）。 */
    private String maskName(String cipher) {
        String name = cipher == null ? null : crypto.decrypt(cipher);
        return name == null ? "" : SensitiveValueMasker.mask("name", name);
    }

    private String maskMrn(String cipher) {
        String mrn = cipher == null ? null : crypto.decrypt(cipher);
        return mrn == null ? "" : SensitiveValueMasker.mask("medicalRecordNo", mrn);
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, 40001, message);
    }

    /** 检索摘要（审计 queryDigest：不含明文检索词，只记条件形态，AGENTS §5.4 敏感值只存摘要）。 */
    private static String digest(SearchRequest request) {
        StringBuilder digest = new StringBuilder("全院报告单");
        if (request.keyword() != null && !request.keyword().isBlank()) {
            digest.append("·关键词");
        }
        if (request.typeCode() != null && !request.typeCode().isBlank()) {
            digest.append("·类型");
        }
        if (request.reportDateFrom() != null || request.reportDateTo() != null) {
            digest.append("·报告时间");
        }
        if (request.uploadedFrom() != null || request.uploadedTo() != null) {
            digest.append("·上传日期");
        }
        if (request.creatorId() != null) {
            digest.append("·上传人");
        }
        if (request.locationScope() != null && !request.locationScope().isBlank()) {
            digest.append("·位置=").append(request.locationScope());
        }
        if (request.digitizeStatus() != null && !request.digitizeStatus().isBlank()) {
            digest.append("·电子化");
        }
        if (request.wardIds() != null && !request.wardIds().isEmpty()) {
            digest.append("·病区");
        }
        return digest.toString();
    }
}
